package rs.masumi.app.translation

internal enum class TranslationConnectivityFailureKind {
    INVALID_CONFIGURATION,
    INVALID_ENDPOINT,
    AUTHENTICATION,
    ENDPOINT_NOT_FOUND,
    REQUEST_REJECTED,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    HTTP_FAILURE,
    INVALID_RESPONSE,
    RESPONSE_TOO_LARGE,
    TIMEOUT,
    NETWORK,
    CANCELLED,
    UNKNOWN,
}

internal data class TranslationConnectivityFailurePresentation(
    val kind: TranslationConnectivityFailureKind,
    val httpStatus: Int?,
)

internal data class TranslationSettingsControlAvailability(
    val saveEnabled: Boolean,
    val remoteRequestInProgress: Boolean,
) {
    val saveControlEnabled: Boolean
        get() = saveEnabled

    val remoteRequestEnabled: Boolean
        get() = !remoteRequestInProgress
}

internal fun Throwable.forUserPresentation(): TranslationConnectivityFailurePresentation {
    val failure = this as? TranslationConnectivityException
        ?: return TranslationConnectivityFailurePresentation(
            kind = TranslationConnectivityFailureKind.UNKNOWN,
            httpStatus = null,
        )
    val kind = when (failure.code) {
        TranslationConnectivityError.INVALID_CONFIGURATION ->
            TranslationConnectivityFailureKind.INVALID_CONFIGURATION
        TranslationConnectivityError.INVALID_ENDPOINT ->
            TranslationConnectivityFailureKind.INVALID_ENDPOINT
        TranslationConnectivityError.AUTHENTICATION ->
            TranslationConnectivityFailureKind.AUTHENTICATION
        TranslationConnectivityError.ENDPOINT_NOT_FOUND ->
            TranslationConnectivityFailureKind.ENDPOINT_NOT_FOUND
        TranslationConnectivityError.HTTP -> when (failure.httpStatus) {
            429 -> TranslationConnectivityFailureKind.RATE_LIMITED
            400 -> TranslationConnectivityFailureKind.REQUEST_REJECTED
            in 500..599 -> TranslationConnectivityFailureKind.SERVICE_UNAVAILABLE
            else -> TranslationConnectivityFailureKind.HTTP_FAILURE
        }
        TranslationConnectivityError.MALFORMED_RESPONSE ->
            TranslationConnectivityFailureKind.INVALID_RESPONSE
        TranslationConnectivityError.RESPONSE_TOO_LARGE ->
            TranslationConnectivityFailureKind.RESPONSE_TOO_LARGE
        TranslationConnectivityError.TIMEOUT -> TranslationConnectivityFailureKind.TIMEOUT
        TranslationConnectivityError.NETWORK -> TranslationConnectivityFailureKind.NETWORK
        TranslationConnectivityError.CANCELLED -> TranslationConnectivityFailureKind.CANCELLED
    }
    return TranslationConnectivityFailurePresentation(kind, failure.httpStatus)
}
