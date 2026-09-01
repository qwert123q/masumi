package rs.masumi.app.translation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationPromptMessages
import rs.masumi.core.translation.TranslationProviderReference

interface TranslationProvider {
    fun newCall(
        settings: TranslationProviderSettings,
        messages: TranslationPromptMessages,
    ): TranslationProviderCall
}

interface TranslationProviderCall {
    @Throws(TranslationProviderException::class)
    fun execute(): TranslationProviderResult

    fun cancel()
}

class TranslationProviderSettings(
    val apiUrl: String,
    val apiKey: String,
    val model: String,
    val connectTimeoutMillis: Long = 15_000L,
    val readTimeoutMillis: Long = 90_000L,
    val writeTimeoutMillis: Long = 30_000L,
    val temperature: Double = 0.2,
    val maximumOutputTokens: Int = 4_096,
    val requestJsonObjectFormat: Boolean = true,
    val allowInsecureLocalhost: Boolean = false,
    val profileId: String = "",
    val providerName: String = "",
) {
    init {
        require(apiUrl.isNotBlank() && apiUrl.length <= 2_048 && apiUrl.none(Char::isISOControl)) {
            "apiUrl must be a bounded printable value"
        }
        require(apiKey.isNotBlank() && apiKey.length <= 4_096 && apiKey.none(Char::isISOControl)) {
            "apiKey must be a bounded printable value"
        }
        require(model.isNotBlank() && model.length <= 256 && model.none(Char::isISOControl)) {
            "model must be a bounded printable value"
        }
        require(connectTimeoutMillis > 0L) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0L) { "readTimeoutMillis must be positive" }
        require(writeTimeoutMillis > 0L) { "writeTimeoutMillis must be positive" }
        require(temperature in 0.0..2.0) { "temperature must be between 0 and 2" }
        require(maximumOutputTokens > 0) { "maximumOutputTokens must be positive" }
        require(profileId.length <= 64 && profileId.none(Char::isISOControl)) {
            "profileId must be a bounded printable value"
        }
        require(providerName.length <= 80 && providerName.none(Char::isISOControl)) {
            "providerName must be a bounded printable value"
        }
    }

    fun artifactReference(): TranslationProviderReference {
        val normalizedEndpoint = OpenAiCompatibleEndpointResolver
            .completionUrl(apiUrl, allowInsecureLocalhost)
            .newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
        return TranslationProviderReference(
            profileId = profileId,
            displayName = providerName.ifBlank {
                TranslationProviderCatalog.suggestedName(apiUrl)
            },
            endpointHost = requireNotNull(normalizedEndpoint.toHttpUrlOrNull()).host,
            endpointSha256 = MessageDigest.getInstance("SHA-256")
                .digest(normalizedEndpoint.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }

    override fun toString(): String =
        "TranslationProviderSettings(apiUrl=<redacted>, apiKey=<redacted>, model=<redacted>)"
}

data class TranslationProviderUsage(
    val promptTokens: Long?,
    val completionTokens: Long?,
    val totalTokens: Long?,
)

data class TranslationProviderResult(
    val response: TranslationModelResponse,
    val usage: TranslationProviderUsage?,
    val modelId: String?,
    val attemptCount: Int,
    val durationMillis: Long,
)

enum class TranslationProviderErrorCode {
    NETWORK,
    TIMEOUT,
    HTTP_TRANSIENT,
    HTTP_CLIENT,
    MALFORMED_RESPONSE,
    CANCELLED,
}

class TranslationProviderException(
    val code: TranslationProviderErrorCode,
    val httpStatus: Int? = null,
    val attemptCount: Int,
) : Exception(code.name)
