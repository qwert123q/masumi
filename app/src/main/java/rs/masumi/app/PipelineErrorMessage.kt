package rs.masumi.app

import android.content.Context
import java.util.Locale

internal enum class PipelineErrorAdvice {
    MODEL,
    RESPONSE,
    NETWORK,
    CREDENTIALS,
    STORAGE,
    SOURCE,
    DEVICE,
    UNKNOWN,
}

internal fun classifyPipelineError(errorCode: String?): PipelineErrorAdvice {
    val code = errorCode?.trim()?.uppercase(Locale.ROOT).orEmpty()
    if (code.isEmpty()) return PipelineErrorAdvice.UNKNOWN
    return when {
        code.contains("MODEL") || code.contains("TOKENIZE") -> PipelineErrorAdvice.MODEL
        code.contains("INCOMPLETE_TRANSLATION_RESPONSE") || code.contains("MALFORMED_RESPONSE") ->
            PipelineErrorAdvice.RESPONSE
        code.contains("HTTP_CLIENT") || code.contains("UNAUTHORIZED") ||
            code.contains("FORBIDDEN") || code.contains("API_KEY") ||
            code.contains("CREDENTIAL") -> PipelineErrorAdvice.CREDENTIALS
        code.contains("NETWORK") || code.contains("TIMEOUT") ||
            code.contains("CONNECTION") || code.contains("HTTP_SERVER") ||
            code.contains("HTTP_TRANSIENT") -> PipelineErrorAdvice.NETWORK
        code.contains("WRITE") || code.contains("READ") || code.contains("STORAGE") ||
            code.contains("DESTINATION") || code.contains("ARTIFACT") || code.endsWith("_IO") ->
            PipelineErrorAdvice.STORAGE
        code.contains("SOURCE") || code.contains("HASH") || code.contains("DEPENDENCY") ||
            code.contains("STALE") || code.contains("DECODE") -> PipelineErrorAdvice.SOURCE
        code.contains("ACCELERATOR") || code.contains("SESSION_CREATE") ||
            code.contains("CONTEXT") || code.contains("PROCESS_DIED") -> PipelineErrorAdvice.DEVICE
        else -> PipelineErrorAdvice.UNKNOWN
    }
}

internal fun Context.describePipelineError(errorCode: String?): String {
    val code = errorCode?.takeIf(String::isNotBlank)
    if (code == null) return getString(R.string.pipeline_error_without_code)
    val message = when (classifyPipelineError(code)) {
        PipelineErrorAdvice.MODEL -> R.string.pipeline_error_model
        PipelineErrorAdvice.RESPONSE -> R.string.pipeline_error_response
        PipelineErrorAdvice.NETWORK -> R.string.pipeline_error_network
        PipelineErrorAdvice.CREDENTIALS -> R.string.pipeline_error_credentials
        PipelineErrorAdvice.STORAGE -> R.string.pipeline_error_storage
        PipelineErrorAdvice.SOURCE -> R.string.pipeline_error_source
        PipelineErrorAdvice.DEVICE -> R.string.pipeline_error_device
        PipelineErrorAdvice.UNKNOWN -> R.string.pipeline_error_unknown
    }
    return getString(message, code)
}

internal fun Context.describePipelineErrorBrief(errorCode: String?): String {
    val code = errorCode?.takeIf(String::isNotBlank)
        ?: return getString(R.string.pipeline_error_brief_unknown)
    val message = when (classifyPipelineError(code)) {
        PipelineErrorAdvice.MODEL -> R.string.pipeline_error_brief_model
        PipelineErrorAdvice.RESPONSE -> R.string.pipeline_error_brief_response
        PipelineErrorAdvice.NETWORK -> R.string.pipeline_error_brief_network
        PipelineErrorAdvice.CREDENTIALS -> R.string.pipeline_error_brief_credentials
        PipelineErrorAdvice.STORAGE -> R.string.pipeline_error_brief_storage
        PipelineErrorAdvice.SOURCE -> R.string.pipeline_error_brief_source
        PipelineErrorAdvice.DEVICE -> R.string.pipeline_error_brief_device
        PipelineErrorAdvice.UNKNOWN -> return getString(R.string.pipeline_error_brief_code, code)
    }
    return getString(message)
}
