package rs.masumi.app.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationSettingsStoreTest {
    @Test
    fun credentialsPersistOnlyInApplicationPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "translation_provider_test_${System.nanoTime()}"
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
            Files.deleteIfExists(projectRoot)
        }
    }
}
