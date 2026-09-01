package rs.masumi.app.translation

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import rs.masumi.core.translation.TranslationPromptMessages

class OpenAiCompatibleTranslationProviderFailFastTest {
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
    fun `transient http failure stops after the first request`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))

        val failure = captureFailure {
            OpenAiCompatibleTranslationProvider()
                .newCall(settings(), messages())
                .execute()
        }

        assertEquals(TranslationProviderErrorCode.HTTP_TRANSIENT, failure.code)
        assertEquals(1, failure.attemptCount)
        assertEquals(1, server.requestCount)
        assertEquals("/v1/chat/completions", server.takeRequest(2, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun `malformed successful response stops after the first request`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope("not-json")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))

        val failure = captureFailure {
            OpenAiCompatibleTranslationProvider()
                .newCall(settings(), messages())
                .execute()
        }

        assertEquals(TranslationProviderErrorCode.MALFORMED_RESPONSE, failure.code)
        assertEquals(1, failure.attemptCount)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `low level connection failure is not retried by okhttp`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))
        val networkAttempts = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                if (networkAttempts.incrementAndGet() == 1) throw IOException("simulated disconnect")
                chain.proceed(chain.request())
            }
            .build()

        val failure = captureFailure {
            OpenAiCompatibleTranslationProvider(baseClient = client)
                .newCall(settings(), messages())
                .execute()
        }

        assertEquals(TranslationProviderErrorCode.NETWORK, failure.code)
        assertEquals(1, networkAttempts.get())
    }

    @Test
    fun `redirect is reported instead of issuing a second provider request`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", server.url("/redirected")),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))

        val failure = captureFailure {
            OpenAiCompatibleTranslationProvider()
                .newCall(settings(), messages())
                .execute()
        }

        assertEquals(TranslationProviderErrorCode.HTTP_CLIENT, failure.code)
        assertEquals(307, failure.httpStatus)
        assertEquals(1, server.requestCount)
    }

    private fun settings() = TranslationProviderSettings(
        apiUrl = server.url("/v1/").toString(),
        apiKey = "test-secret",
        model = "test-model",
        allowInsecureLocalhost = true,
    )

    private fun messages() = TranslationPromptMessages(
        system = "system-instruction",
        user = "{\"items\":[]}",
    )

    private fun successBody(): String = envelope(
        """{"items":[{"id":"item-1","role":"DIALOGUE","translation":"译文"}]}""",
    )

    private fun envelope(content: String): String = buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject { put("content", content) })
                })
            },
        )
    }.toString()

    private fun captureFailure(block: () -> Unit): TranslationProviderException = try {
        block()
        fail("Expected translation provider failure")
        throw AssertionError("unreachable")
    } catch (error: TranslationProviderException) {
        error
    }
}
