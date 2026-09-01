package rs.masumi.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("https://api.deepseek.com/chat/completions", reference.endpoint)
        assertFalse(reference.toString().contains(secret))
    }

    @Test
    fun artifactReferenceSupportsPrivateLanHttpWithDefaultSettings() {
        val secret = "private-lan-test-secret"
        val settings = TranslationProviderSettings(
            apiUrl = "http://192.168.50.2:8317/v1",
            apiKey = secret,
            model = "gpt-test",
            profileId = "cpa-lan",
            providerName = "CPA LAN",
        )

        val reference = settings.artifactReference()

        assertEquals("cpa-lan", reference.profileId)
        assertEquals("CPA LAN", reference.displayName)
        assertEquals("http://192.168.50.2:8317/v1/chat/completions", reference.endpoint)
        assertFalse(reference.toString().contains(secret))
    }
}
