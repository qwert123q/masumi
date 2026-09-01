package rs.masumi.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationConnectivityPresentationTest {
    @Test
    fun `manual remote requests remain available while saving settings is locked`() {
        assertTrue(
            TranslationSettingsControlAvailability(
                saveEnabled = false,
                remoteRequestInProgress = false,
            ).remoteRequestEnabled,
        )
        assertFalse(
            TranslationSettingsControlAvailability(
                saveEnabled = true,
                remoteRequestInProgress = true,
            ).remoteRequestEnabled,
        )
    }

    @Test
    fun `typed connectivity failures map to safe user-facing categories`() {
        val cases = listOf(
            TranslationConnectivityError.INVALID_CONFIGURATION to
                TranslationConnectivityFailureKind.INVALID_CONFIGURATION,
            TranslationConnectivityError.INVALID_ENDPOINT to
                TranslationConnectivityFailureKind.INVALID_ENDPOINT,
            TranslationConnectivityError.AUTHENTICATION to
                TranslationConnectivityFailureKind.AUTHENTICATION,
            TranslationConnectivityError.ENDPOINT_NOT_FOUND to
                TranslationConnectivityFailureKind.ENDPOINT_NOT_FOUND,
            TranslationConnectivityError.MALFORMED_RESPONSE to
                TranslationConnectivityFailureKind.INVALID_RESPONSE,
            TranslationConnectivityError.RESPONSE_TOO_LARGE to
                TranslationConnectivityFailureKind.RESPONSE_TOO_LARGE,
            TranslationConnectivityError.TIMEOUT to TranslationConnectivityFailureKind.TIMEOUT,
            TranslationConnectivityError.NETWORK to TranslationConnectivityFailureKind.NETWORK,
            TranslationConnectivityError.CANCELLED to TranslationConnectivityFailureKind.CANCELLED,
        )

        cases.forEach { (error, expectedKind) ->
            val presentation = TranslationConnectivityException(error).forUserPresentation()

            assertEquals(expectedKind, presentation.kind)
            assertNull(presentation.httpStatus)
        }
    }

    @Test
    fun `http failures distinguish actionable status classes without response content`() {
        val cases = listOf(
            400 to TranslationConnectivityFailureKind.REQUEST_REJECTED,
            429 to TranslationConnectivityFailureKind.RATE_LIMITED,
            503 to TranslationConnectivityFailureKind.SERVICE_UNAVAILABLE,
            418 to TranslationConnectivityFailureKind.HTTP_FAILURE,
        )

        cases.forEach { (status, expectedKind) ->
            assertEquals(
                TranslationConnectivityFailurePresentation(expectedKind, status),
                TranslationConnectivityException(
                    TranslationConnectivityError.HTTP,
                    httpStatus = status,
                ).forUserPresentation(),
            )
        }
    }

    @Test
    fun `untyped failures use a fixed generic category`() {
        assertEquals(
            TranslationConnectivityFailurePresentation(
                kind = TranslationConnectivityFailureKind.UNKNOWN,
                httpStatus = null,
            ),
            IllegalStateException("sensitive response body").forUserPresentation(),
        )
    }
}
