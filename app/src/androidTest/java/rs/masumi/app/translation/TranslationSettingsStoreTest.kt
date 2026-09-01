package rs.masumi.app.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationSettingsStoreTest {
    @Test
    fun credentialsStayInsideApplicationPrivateData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "translation_provider_test_${System.nanoTime()}"
        val backup = context.noBackupFilesDir.resolve("$preferencesName.providers.json")
        val projectRoot = Files.createTempDirectory(
            context.cacheDir.toPath(),
            "translation-settings-test-",
        )
        val secret = "test-secret-value"
        val store = TranslationSettingsStore(context, preferencesName)

        try {
            store.save(SavedTranslationSettings("https://example.invalid/v1", secret, "model-safe"))

            val saved = store.loadSaved()
            assertNotNull(saved)
            assertEquals(secret, saved!!.apiKey)
            val leaked = Files.walk(projectRoot).use { paths ->
                paths.filter(Files::isRegularFile).anyMatch { path ->
                    runCatching {
                        Files.newBufferedReader(path, Charsets.UTF_8).use { it.readText() }.contains(secret)
                    }.getOrDefault(false)
                }
            }
            assertFalse(leaked)
        } finally {
            context.deleteSharedPreferences(preferencesName)
            backup.delete()
            Files.deleteIfExists(projectRoot)
        }
    }

    @Test
    fun legacySettingsBecomeTheActiveProviderWithoutBeingCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "translation_provider_legacy_test_${System.nanoTime()}"
        val backup = context.noBackupFilesDir.resolve("$preferencesName.providers.json")
        val preferences = context.getSharedPreferences(preferencesName, 0)
        val secret = "legacy-secret"
        preferences.edit()
            .putString("api_url", "https://api.deepseek.com")
            .putString("api_key", secret)
            .putString("model", "deepseek-chat")
            .commit()
        val store = TranslationSettingsStore(context, preferencesName)

        try {
            val active = requireNotNull(store.loadActiveProvider())
            assertEquals("legacy", active.id)
            assertEquals("DeepSeek", active.name)
            assertEquals(secret, active.apiKey)
            assertEquals("deepseek-chat", store.loadProviderSettings()?.model)
            assertNull(preferences.getString("provider_profiles_v1", null))
        } finally {
            context.deleteSharedPreferences(preferencesName)
            backup.delete()
        }
    }

    @Test
    fun multipleProvidersCanBeSavedAndSelectedIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "translation_provider_profiles_test_${System.nanoTime()}"
        val backup = context.noBackupFilesDir.resolve("$preferencesName.providers.json")
        val store = TranslationSettingsStore(context, preferencesName)
        val first = SavedTranslationProvider(
            id = "deepseek",
            name = "DeepSeek",
            apiUrl = "https://api.deepseek.com",
            apiKey = "first-secret",
            model = "deepseek-chat",
        )
        val second = SavedTranslationProvider(
            id = "openrouter",
            name = "OpenRouter",
            apiUrl = "https://openrouter.ai/api/v1",
            apiKey = "second-secret",
            model = "anthropic/claude-sonnet-4",
        )

        try {
            store.saveProvider(first)
            store.saveProvider(second)
            assertEquals(second.id, store.loadActiveProvider()?.id)
            assertEquals(2, store.loadProviders().size)

            store.selectActiveProvider(first.id)

            assertEquals(first, store.loadActiveProvider())
            assertEquals(first.apiKey, store.loadProviderSettings()?.apiKey)
        } finally {
            context.deleteSharedPreferences(preferencesName)
            backup.delete()
        }
    }

    @Test
    fun privateLanHttpProviderRoundTripsThroughPrivateStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "translation_provider_lan_test_${System.nanoTime()}"
        val backup = context.noBackupFilesDir.resolve("$preferencesName.providers.json")
        val store = TranslationSettingsStore(context, preferencesName)
        val provider = SavedTranslationProvider(
            id = "cpa-lan",
            name = "CPA LAN",
            apiUrl = "http://192.168.50.2:8317/v1",
            apiKey = "private-lan-test-secret",
            model = "gpt-test",
        )

        try {
            store.saveProvider(provider)

            assertEquals(provider, store.loadActiveProvider())
            assertEquals(provider.apiUrl, store.loadProviderSettings()?.apiUrl)
        } finally {
            context.deleteSharedPreferences(preferencesName)
            backup.delete()
        }
    }

    @Test
    fun publicCleartextProviderIsRejectedBeforePersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "translation_provider_public_http_test_${System.nanoTime()}"
        val backup = context.noBackupFilesDir.resolve("$preferencesName.providers.json")
        val store = TranslationSettingsStore(context, preferencesName)

        try {
            assertThrows(IllegalArgumentException::class.java) {
                store.saveProvider(
                    SavedTranslationProvider(
                        id = "public-http",
                        name = "Public HTTP",
                        apiUrl = "http://203.0.113.8:8317/v1",
                        apiKey = "public-http-test-secret",
                        model = "gpt-test",
                    ),
                )
            }
            assertNull(store.loadActiveProvider())
        } finally {
            context.deleteSharedPreferences(preferencesName)
            backup.delete()
        }
    }

    @Test
    fun privateSnapshotRecoversProfilesWhenPreferencesAreUnexpectedlyCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "translation_provider_backup_test_${System.nanoTime()}"
        val preferences = context.getSharedPreferences(preferencesName, 0)
        val backup = context.noBackupFilesDir.resolve("$preferencesName.providers.json")
        val store = TranslationSettingsStore(context, preferencesName)
        val first = SavedTranslationProvider(
            id = "deepseek",
            name = "DeepSeek",
            apiUrl = "https://api.deepseek.com",
            apiKey = "first-secret",
            model = "deepseek-chat",
        )
        val second = SavedTranslationProvider(
            id = "openrouter",
            name = "OpenRouter",
            apiUrl = "https://openrouter.ai/api/v1",
            apiKey = "second-secret",
            model = "anthropic/claude-sonnet-4",
        )

        try {
            store.saveProvider(first)
            store.saveProvider(second)
            store.selectActiveProvider(first.id)
            preferences.edit().clear().commit()

            val recovered = TranslationSettingsStore(context, preferencesName)

            assertEquals(listOf(first, second), recovered.loadProviders())
            assertEquals(first, recovered.loadActiveProvider())
        } finally {
            context.deleteSharedPreferences(preferencesName)
            backup.delete()
        }
    }
}
