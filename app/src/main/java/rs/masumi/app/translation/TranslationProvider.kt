package rs.masumi.app.translation

import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationPromptMessages

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
    val maximumAttempts: Int = 3,
    val retryDelayMillis: Long = 1_000L,
    val temperature: Double = 0.2,
    val maximumOutputTokens: Int = 4_096,
    val requestJsonObjectFormat: Boolean = true,
    val allowInsecureLocalhost: Boolean = false,
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
        require(maximumAttempts in 1..10) { "maximumAttempts must be between 1 and 10" }
        require(retryDelayMillis >= 0L) { "retryDelayMillis must not be negative" }
        require(temperature in 0.0..2.0) { "temperature must be between 0 and 2" }
        require(maximumOutputTokens > 0) { "maximumOutputTokens must be positive" }
    }

    override fun toString(): String =
        "TranslationProviderSettings(apiUrl=<redacted>, apiKey=<redacted>, model=<redacted>, " +
            "maximumAttempts=$maximumAttempts)"
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
    val retryable: Boolean,
    val attemptCount: Int,
    val retryAfterMillis: Long? = null,
) : Exception(code.name)

fun interface TranslationRetryWaiter {
    fun wait(delayMillis: Long, isCancelled: () -> Boolean): Boolean
}
