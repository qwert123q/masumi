package rs.masumi.app.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.serialization.TranslationJson
import rs.masumi.core.translation.TranslationModelItem
import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationPromptMessages
import rs.masumi.core.translation.TranslationRole

@RunWith(AndroidJUnit4::class)
class OpenAiCompatibleTranslationProviderTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun successSendsCompatibleRequestAndCapturesStructuredUsage() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))
        val result = provider().newCall(settings(), messages()).execute()
        val request = server.takeRequest(2, TimeUnit.SECONDS)

        assertNotNull(request)
        assertEquals("/v1/chat/completions", request?.path)
        assertEquals("Bearer test-secret", request?.getHeader("Authorization"))
        val body = request?.body?.readUtf8().orEmpty()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertTrue(body.contains("\"role\":\"system\""))
        assertEquals("译文", result.response.items.single().translation)
        assertEquals(11L, result.usage?.promptTokens)
        assertEquals(7L, result.usage?.completionTokens)
        assertEquals(18L, result.usage?.totalTokens)
        assertEquals("test-model", result.modelId)
        assertEquals(1, result.attemptCount)
    }

    @Test
    fun fullCompletionEndpointIsNotAppendedTwiceAndCleartextIsLocalOnly() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))
        provider().newCall(
            settings(apiUrl = server.url("/api/v3/chat/completions").toString()),
            messages(),
        ).execute()

        assertEquals("/api/v3/chat/completions", server.takeRequest(2, TimeUnit.SECONDS)?.path)

        val rejected = try {
            provider().newCall(
                TranslationProviderSettings(
                    apiUrl = "http://example.com/v1",
                    apiKey = "test-secret",
                    model = "test-model",
                    allowInsecureLocalhost = true,
                ),
                messages(),
            ).execute()
            fail("Expected non-local cleartext endpoint rejection")
            throw AssertionError("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals("apiUrl must use HTTPS", rejected.message)
    }

    @Test
    fun transientHttpFailuresRetryButClientFailureDoesNotAndLeaksNothing() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("secret-rate-body"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("secret-service-body"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))

        val retried = provider().newCall(settings(maximumAttempts = 3), messages()).execute()

        assertEquals(3, retried.attemptCount)
        assertEquals(3, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(401).setBody("test-secret private-error"))
        val failure = captureFailure {
            provider().newCall(settings(maximumAttempts = 3), messages()).execute()
        }

        assertEquals(TranslationProviderErrorCode.HTTP_CLIENT, failure.code)
        assertEquals(401, failure.httpStatus)
        assertFalse(failure.retryable)
        assertEquals(1, failure.attemptCount)
        assertEquals(4, server.requestCount)
        assertEquals("HTTP_CLIENT", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.stackTraceToString().contains("test-secret"))
        assertFalse(settings().toString().contains("test-secret"))
        assertFalse(settings().toString().contains(server.hostName))
    }

    @Test
    fun malformedSuccessfulResponseIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope("not-json")))

        val failure = captureFailure {
            provider().newCall(settings(maximumAttempts = 3), messages()).execute()
        }

        assertEquals(TranslationProviderErrorCode.MALFORMED_RESPONSE, failure.code)
        assertEquals(1, failure.attemptCount)
        assertEquals(1, server.requestCount)
        assertNull(failure.cause)
    }

    @Test
    fun readTimeoutMapsToSafeTimeoutCode() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val failure = captureFailure {
            provider().newCall(
                settings(maximumAttempts = 1, readTimeoutMillis = 100L),
                messages(),
            ).execute()
        }

        assertEquals(TranslationProviderErrorCode.TIMEOUT, failure.code)
        assertEquals(1, failure.attemptCount)
        assertNull(failure.cause)
    }

    @Test
    fun cancellationStopsTheActiveHttpCall() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = provider().newCall(
            settings(maximumAttempts = 1, readTimeoutMillis = 10_000L),
            messages(),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<TranslationProviderException?> {
                try {
                    call.execute()
                    null
                } catch (error: TranslationProviderException) {
                    error
                }
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            call.cancel()
            val failure = future.get(2, TimeUnit.SECONDS)

            assertNotNull(failure)
            assertEquals(TranslationProviderErrorCode.CANCELLED, failure?.code)
            assertNull(failure?.cause)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun provider() = OpenAiCompatibleTranslationProvider(
        retryWaiter = TranslationRetryWaiter { _, isCancelled -> !isCancelled() },
    )

    private fun settings(
        maximumAttempts: Int = 1,
        readTimeoutMillis: Long = 2_000L,
        apiUrl: String = server.url("/v1/").toString(),
    ) = TranslationProviderSettings(
        apiUrl = apiUrl,
        apiKey = "test-secret",
        model = "test-model",
        maximumAttempts = maximumAttempts,
        retryDelayMillis = 0L,
        readTimeoutMillis = readTimeoutMillis,
        allowInsecureLocalhost = true,
    )

    private fun messages() = TranslationPromptMessages(
        system = "system-instruction",
        user = "{\"items\":[]}",
    )

    private fun successBody(): String = envelope(
        TranslationJson().encodeModelResponse(
            TranslationModelResponse(
                items = listOf(TranslationModelItem("item-1", TranslationRole.DIALOGUE, "译文")),
            ),
        ),
        includeUsage = true,
    )

    private fun envelope(content: String, includeUsage: Boolean = false): String = buildJsonObject {
        put("model", "test-model")
        put(
            "choices",
            buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject { put("content", content) })
                })
            },
        )
        if (includeUsage) {
            put(
                "usage",
                buildJsonObject {
                    put("prompt_tokens", 11)
                    put("completion_tokens", 7)
                    put("total_tokens", 18)
                },
            )
        }
    }.toString()

    private fun captureFailure(block: () -> Unit): TranslationProviderException = try {
        block()
        fail("Expected translation provider failure")
        throw AssertionError("unreachable")
    } catch (error: TranslationProviderException) {
        error
    }
}
