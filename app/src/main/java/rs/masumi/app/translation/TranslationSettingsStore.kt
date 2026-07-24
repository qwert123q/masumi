package rs.masumi.app.translation

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SavedTranslationSettings(
    val apiUrl: String,
    val apiKey: String,
    val model: String,
)

class TranslationSettingsStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun loadSaved(): SavedTranslationSettings? {
        val apiUrl = preferences.getString(KEY_API_URL, null)?.trim().orEmpty()
        val apiKey = preferences.getString(KEY_API_KEY, null)?.trim().orEmpty()
        val model = preferences.getString(KEY_MODEL, null)?.trim().orEmpty()
        if (apiUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        return runCatching { SavedTranslationSettings(apiUrl, apiKey, model).validated() }.getOrNull()
    }

    fun save(value: SavedTranslationSettings) {
        val validated = value.validated()
        check(
            preferences.edit()
                .putString(KEY_API_URL, validated.apiUrl)
                .putString(KEY_API_KEY, validated.apiKey)
                .putString(KEY_MODEL, validated.model)
                .commit(),
        ) { "translation settings could not be persisted" }
    }

    fun loadProviderSettings(): TranslationProviderSettings? = loadSaved()?.let {
        TranslationProviderSettings(apiUrl = it.apiUrl, apiKey = it.apiKey, model = it.model)
    }

    private fun SavedTranslationSettings.validated(): SavedTranslationSettings {
        val normalized = copy(apiUrl = apiUrl.trim(), apiKey = apiKey.trim(), model = model.trim())
        TranslationProviderSettings(
            apiUrl = normalized.apiUrl,
            apiKey = normalized.apiKey,
            model = normalized.model,
        )
        val parsed = normalized.apiUrl.trimEnd('/').toHttpUrlOrNull()
        require(parsed != null && parsed.scheme == "https" && parsed.query == null && parsed.fragment == null) {
            "translation API URL must be HTTPS without a query or fragment"
        }
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "translation_provider"
        const val KEY_API_URL = "api_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
    }
}
