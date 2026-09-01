package rs.masumi.app.translation

import android.content.Context
import android.util.AtomicFile
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

data class SavedTranslationSettings(
    val apiUrl: String,
    val apiKey: String,
    val model: String,
)

data class SavedTranslationProvider(
    val id: String,
    val name: String,
    val apiUrl: String,
    val apiKey: String,
    val model: String,
)

class TranslationSettingsStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val backupFile = AtomicFile(
        context.noBackupFilesDir.resolve("$preferencesName.providers.json"),
    )

    fun loadProviders(): List<SavedTranslationProvider> = loadSnapshot()?.providers.orEmpty()

    fun loadActiveProvider(): SavedTranslationProvider? {
        val snapshot = loadSnapshot() ?: return null
        return snapshot.providers.firstOrNull { it.id == snapshot.activeId }
            ?: snapshot.providers.firstOrNull()
    }

    private fun loadSnapshot(): ProviderSnapshot? {
        val encoded = preferences.getString(KEY_PROFILES_JSON, null)
        val decoded = encoded?.let { runCatching { decodeProfiles(it) }.getOrNull() }.orEmpty()
        if (decoded.isNotEmpty()) {
            return ProviderSnapshot(
                providers = decoded,
                activeId = preferences.getString(KEY_ACTIVE_PROFILE_ID, null)
                    ?.takeIf { active -> decoded.any { it.id == active } }
                    ?: decoded.first().id,
            ).also(::ensureBackup)
        }
        loadLegacyProvider()?.let { legacy ->
            return ProviderSnapshot(listOf(legacy), legacy.id).also(::ensureBackup)
        }
        return readBackup()?.also(::restorePreferences)
    }

    fun saveProvider(value: SavedTranslationProvider) {
        val validated = value.validated()
        val providers = loadProviders()
            .filterNot { it.id == validated.id }
            .plus(validated)
        persistProviders(providers, validated.id)
    }

    fun selectActiveProvider(providerId: String): SavedTranslationProvider {
        val selected = requireNotNull(loadProviders().firstOrNull { it.id == providerId }) {
            "translation provider was not found"
        }
        persistProviders(loadProviders(), selected.id)
        return selected
    }

    fun loadSaved(): SavedTranslationSettings? = loadActiveProvider()?.let {
        SavedTranslationSettings(apiUrl = it.apiUrl, apiKey = it.apiKey, model = it.model)
    }

    fun save(value: SavedTranslationSettings) {
        val active = loadActiveProvider()
        saveProvider(
            SavedTranslationProvider(
                id = active?.id ?: LEGACY_PROFILE_ID,
                name = active?.name ?: TranslationProviderCatalog.suggestedName(value.apiUrl),
                apiUrl = value.apiUrl,
                apiKey = value.apiKey,
                model = value.model,
            ),
        )
    }

    fun loadProviderSettings(): TranslationProviderSettings? = loadActiveProvider()?.let {
        TranslationProviderSettings(
            apiUrl = it.apiUrl,
            apiKey = it.apiKey,
            model = it.model,
            profileId = it.id,
            providerName = it.name,
        )
    }

    private fun loadLegacyProvider(): SavedTranslationProvider? {
        val apiUrl = preferences.getString(KEY_API_URL, null)?.trim().orEmpty()
        val apiKey = preferences.getString(KEY_API_KEY, null)?.trim().orEmpty()
        val model = preferences.getString(KEY_MODEL, null)?.trim().orEmpty()
        if (apiUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        return runCatching {
            SavedTranslationProvider(
                id = LEGACY_PROFILE_ID,
                name = TranslationProviderCatalog.suggestedName(apiUrl),
                apiUrl = apiUrl,
                apiKey = apiKey,
                model = model,
            ).validated()
        }.getOrNull()
    }

    private fun persistProviders(providers: List<SavedTranslationProvider>, activeId: String) {
        require(providers.size in 1..MAXIMUM_PROVIDER_COUNT) {
            "translation provider count is invalid"
        }
        val validated = providers.map { it.validated() }
        require(validated.map(SavedTranslationProvider::id).distinct().size == validated.size) {
            "translation provider ids must be unique"
        }
        val active = requireNotNull(validated.firstOrNull { it.id == activeId })
        check(
            preferences.edit()
                .putString(KEY_PROFILES_JSON, encodeProfiles(validated))
                .putString(KEY_ACTIVE_PROFILE_ID, active.id)
                // Keep the legacy keys synchronized so an older app build can
                // still use the currently selected provider without losing it.
                .putString(KEY_API_URL, active.apiUrl)
                .putString(KEY_API_KEY, active.apiKey)
                .putString(KEY_MODEL, active.model)
                .commit(),
        ) { "translation settings could not be persisted" }
        writeBackup(ProviderSnapshot(validated, active.id))
    }

    private fun encodeProfiles(providers: List<SavedTranslationProvider>): String = JSONArray().apply {
        providers.forEach { provider ->
            put(
                JSONObject()
                    .put(JSON_ID, provider.id)
                    .put(JSON_NAME, provider.name)
                    .put(JSON_API_URL, provider.apiUrl)
                    .put(JSON_API_KEY, provider.apiKey)
                    .put(JSON_MODEL, provider.model),
            )
        }
    }.toString()

    private fun decodeProfiles(encoded: String): List<SavedTranslationProvider> {
        val array = JSONArray(encoded)
        require(array.length() in 1..MAXIMUM_PROVIDER_COUNT) {
            "translation provider count is invalid"
        }
        return buildList(array.length()) {
            repeat(array.length()) { index ->
                val value = array.getJSONObject(index)
                add(
                    SavedTranslationProvider(
                        id = value.getString(JSON_ID),
                        name = value.getString(JSON_NAME),
                        apiUrl = value.getString(JSON_API_URL),
                        apiKey = value.getString(JSON_API_KEY),
                        model = value.getString(JSON_MODEL),
                    ).validated(),
                )
            }
        }.also { providers ->
            require(providers.map(SavedTranslationProvider::id).distinct().size == providers.size) {
                "translation provider ids must be unique"
            }
        }
    }

    private fun writeBackup(snapshot: ProviderSnapshot) {
        val encoded = JSONObject()
            .put(JSON_BACKUP_VERSION, BACKUP_VERSION)
            .put(JSON_ACTIVE_ID, snapshot.activeId)
            .put(JSON_PROVIDERS, JSONArray(encodeProfiles(snapshot.providers)))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val output = backupFile.startWrite()
        try {
            output.write(encoded)
            backupFile.finishWrite(output)
        } catch (failure: Throwable) {
            backupFile.failWrite(output)
            throw failure
        }
    }

    private fun readBackup(): ProviderSnapshot? = runCatching {
        val root = backupFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
            JSONObject(reader.readText())
        }
        require(root.getInt(JSON_BACKUP_VERSION) == BACKUP_VERSION)
        val providers = decodeProfiles(root.getJSONArray(JSON_PROVIDERS).toString())
        val activeId = root.getString(JSON_ACTIVE_ID)
        require(providers.any { it.id == activeId })
        ProviderSnapshot(providers, activeId)
    }.getOrNull()

    private fun ensureBackup(snapshot: ProviderSnapshot) {
        if (readBackup() == snapshot) return
        runCatching { writeBackup(snapshot) }
    }

    private fun restorePreferences(snapshot: ProviderSnapshot) {
        val active = snapshot.providers.single { it.id == snapshot.activeId }
        preferences.edit()
            .putString(KEY_PROFILES_JSON, encodeProfiles(snapshot.providers))
            .putString(KEY_ACTIVE_PROFILE_ID, active.id)
            .putString(KEY_API_URL, active.apiUrl)
            .putString(KEY_API_KEY, active.apiKey)
            .putString(KEY_MODEL, active.model)
            .commit()
    }

    private fun SavedTranslationProvider.validated(): SavedTranslationProvider {
        val normalized = copy(
            id = id.trim(),
            name = name.trim(),
            apiUrl = apiUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim(),
        )
        require(PROFILE_ID.matches(normalized.id)) { "translation provider id is invalid" }
        require(
            normalized.name.isNotBlank() &&
                normalized.name.length <= 80 &&
                normalized.name.none(Char::isISOControl),
        ) { "translation provider name is invalid" }
        TranslationProviderSettings(
            apiUrl = normalized.apiUrl,
            apiKey = normalized.apiKey,
            model = normalized.model,
            profileId = normalized.id,
            providerName = normalized.name,
        )
        OpenAiCompatibleEndpointResolver.completionUrl(
            normalized.apiUrl,
            allowInsecureLocalhost = false,
        )
        return normalized
    }

    private companion object {
        data class ProviderSnapshot(
            val providers: List<SavedTranslationProvider>,
            val activeId: String,
        )

        const val PREFERENCES_NAME = "translation_provider"
        const val BACKUP_VERSION = 1
        const val KEY_API_URL = "api_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_PROFILES_JSON = "provider_profiles_v1"
        const val KEY_ACTIVE_PROFILE_ID = "active_provider_id"
        const val LEGACY_PROFILE_ID = "legacy"
        const val MAXIMUM_PROVIDER_COUNT = 32
        const val JSON_ID = "id"
        const val JSON_NAME = "name"
        const val JSON_API_URL = "api_url"
        const val JSON_API_KEY = "api_key"
        const val JSON_MODEL = "model"
        const val JSON_BACKUP_VERSION = "version"
        const val JSON_ACTIVE_ID = "active_id"
        const val JSON_PROVIDERS = "providers"
        val PROFILE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
