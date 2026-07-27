package rs.masumi.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProviderSettingsTest {
    @Test
    fun artifactReferenceRecordsProviderWithoutApiKey() {
        val secret = "never-write-this-secret"
        val settings = TranslationProviderSettings(
            apiUrl = "https://api.deepseek.com",
            apiKey = secret,
            model = "deepseek-chat",
            profileId = "deepseek",
            providerName = "DeepSeek",
        )

        val reference = settings.artifactReference()

        assertEquals("deepseek", reference.profileId)
        assertEquals("DeepSeek", reference.displayName)
        assertEquals("api.deepseek.com", reference.endpointHost)
        assertTrue(reference.endpointSha256.matches(Regex("[0-9a-f]{64}")))
        assertFalse(reference.toString().contains(secret))
    }
}
