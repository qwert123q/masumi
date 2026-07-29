package rs.masumi.app.pipeline

internal object PipelineRetryPolicy {
    fun isRetryable(stage: PipelineStage, errorCode: String?): Boolean {
        if (errorCode in PROCESS_RECOVERY_ERRORS) return true
        return stage == PipelineStage.TRANSLATION && errorCode in TRANSLATION_RECOVERY_ERRORS
    }

    fun delayMillis(consecutiveFailureCount: Int): Long {
        require(consecutiveFailureCount > 0)
        val exponent = (consecutiveFailureCount - 1).coerceAtMost(MAXIMUM_BACKOFF_EXPONENT)
        return (INITIAL_DELAY_MILLIS shl exponent).coerceAtMost(MAXIMUM_DELAY_MILLIS)
    }

    private val PROCESS_RECOVERY_ERRORS = setOf(
        "OCR_PROCESS_DIED",
        "STAGE_START_FAILED",
    )
    private val TRANSLATION_RECOVERY_ERRORS = setOf(
        "NETWORK",
        "TIMEOUT",
        "HTTP_TRANSIENT",
        "MALFORMED_RESPONSE",
        "INCOMPLETE_TRANSLATION_RESPONSE",
        "BLANK_TRANSLATION_RESPONSE",
    )
    private const val INITIAL_DELAY_MILLIS = 30_000L
    private const val MAXIMUM_DELAY_MILLIS = 5L * 60L * 1_000L
    private const val MAXIMUM_BACKOFF_EXPONENT = 6
}
