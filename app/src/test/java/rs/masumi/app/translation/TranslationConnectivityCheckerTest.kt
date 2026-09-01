package rs.masumi.app.translation

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TranslationConnectivityCheckerTest {
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
    fun returnsTranslationFromOpenAiChatCompletion() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 123,
                  "model": "gpt-5.6-luna",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "早上好。今天天气怎么样？要不要一起去天守阁公园散步？"
                    },
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": 40,
                    "completion_tokens": 20,
                    "total_tokens": 60
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = checkerCall().execute()
        val request = server.takeRequest(2, TimeUnit.SECONDS)

        assertEquals("早上好。今天天气怎么样？要不要一起去天守阁公园散步？", result.translation)
        assertNotNull(request)
        assertEquals("/v1/chat/completions", request?.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sendsFixedBoundedTranslationProbe() {
        server.enqueue(successfulResponse())

        checkerCall().execute()
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val messages = payload.getValue("messages").jsonArray

        assertEquals("Bearer test-secret", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("gpt-5.6-luna", payload.getValue("model").jsonPrimitive.content)
        assertEquals(false, payload.getValue("stream").jsonPrimitive.boolean)
        assertEquals(128, payload.getValue("max_tokens").jsonPrimitive.int)
        assertEquals(0.0, payload.getValue("temperature").jsonPrimitive.double, 0.0)
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(
            "你是日译中翻译器。请将用户提供的日文翻译成简体中文，只返回中文译文，不要解释。",
            messages[0].jsonObject.getValue("content").jsonPrimitive.content,
        )
        assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(
            "おはよう。今日の天気はどう？一緒に天守閣公園を散歩しない？",
            messages[1].jsonObject.getValue("content").jsonPrimitive.content,
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun reportsAuthenticationFailureWithoutLeakingDiagnostics() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                "server-response-marker containing test-secret",
            ),
        )

        val failure = assertThrows(TranslationConnectivityException::class.java) {
            checkerCall().execute()
        }
        val diagnostics = failure.toString() + failure.stackTraceToString()

        assertEquals(TranslationConnectivityError.AUTHENTICATION, failure.code)
        assertEquals(401, failure.httpStatus)
        assertFalse(diagnostics.contains("test-secret"))
        assertFalse(diagnostics.contains("server-response-marker"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun reportsMalformedResponseWithoutLeakingResponseBody() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "server-response-marker containing test-secret",
            ),
        )

        val failure = assertThrows(TranslationConnectivityException::class.java) {
            checkerCall().execute()
        }
        val diagnostics = failure.toString() + failure.stackTraceToString()

        assertEquals(TranslationConnectivityError.MALFORMED_RESPONSE, failure.code)
        assertEquals(null, failure.httpStatus)
        assertFalse(diagnostics.contains("test-secret"))
        assertFalse(diagnostics.contains("server-response-marker"))
    }

    @Test
    fun timesOutWithoutRetrying() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val failure = assertThrows(TranslationConnectivityException::class.java) {
            TranslationConnectivityChecker(requestTimeoutMillis = 100L)
                .newCall(
                    apiUrl = server.url("/v1").toString(),
                    apiKey = "test-secret",
                    model = "gpt-5.6-luna",
                    allowInsecureLocalhost = true,
                )
                .execute()
        }

        assertEquals(TranslationConnectivityError.TIMEOUT, failure.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancelsInFlightRequest() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = checkerCall()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val outcome = executor.submit<TranslationConnectivityException?> {
                try {
                    call.execute()
                    null
                } catch (failure: TranslationConnectivityException) {
                    failure
                }
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            call.cancel()
            val failure = requireNotNull(outcome.get(2, TimeUnit.SECONDS))

            assertEquals(TranslationConnectivityError.CANCELLED, failure.code)
            assertEquals(1, server.requestCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun callExecutesAtMostOneRequest() {
        server.enqueue(successfulResponse())
        val call = TranslationConnectivityChecker(requestTimeoutMillis = 100L)
            .newCall(
                apiUrl = server.url("/v1").toString(),
                apiKey = "test-secret",
                model = "gpt-5.6-luna",
                allowInsecureLocalhost = true,
            )

        call.execute()
        assertThrows(IllegalStateException::class.java) { call.execute() }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectIsReportedWithoutASecondProbeRequest() {
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", server.url("/redirected")),
        )
        server.enqueue(successfulResponse())

        val failure = assertThrows(TranslationConnectivityException::class.java) {
            checkerCall().execute()
        }

        assertEquals(TranslationConnectivityError.HTTP, failure.code)
        assertEquals(307, failure.httpStatus)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun reportsInvalidEndpointWithoutExposingConfiguration() {
        val failure = assertThrows(TranslationConnectivityException::class.java) {
            TranslationConnectivityChecker().newCall(
                apiUrl = "not-a-url-marker",
                apiKey = "test-secret",
                model = "gpt-5.6-luna",
            )
        }
        val diagnostics = failure.toString() + failure.stackTraceToString()

        assertEquals(TranslationConnectivityError.INVALID_ENDPOINT, failure.code)
        assertFalse(diagnostics.contains("not-a-url-marker"))
        assertFalse(diagnostics.contains("test-secret"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsUnsafeRequestConfigurationBeforeNetwork() {
        val invalidConfigurations = listOf(
            "" to "gpt-5.6-luna",
            "test-secret" to "",
            "test-secret" to "unsafe\u0000model",
        )

        invalidConfigurations.forEach { (apiKey, model) ->
            val failure = assertThrows(TranslationConnectivityException::class.java) {
                TranslationConnectivityChecker().newCall(
                    apiUrl = server.url("/v1").toString(),
                    apiKey = apiKey,
                    model = model,
                    allowInsecureLocalhost = true,
                )
            }
            assertEquals(TranslationConnectivityError.INVALID_CONFIGURATION, failure.code)
            assertFalse(failure.stackTraceToString().contains("test-secret"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsOversizedResponseWithoutRetainingItsBody() {
        val marker = "oversized-response-marker"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                marker + "x".repeat(256 * 1_024),
            ),
        )

        val failure = assertThrows(TranslationConnectivityException::class.java) {
            checkerCall().execute()
        }
        val diagnostics = failure.toString() + failure.stackTraceToString()

        assertEquals(TranslationConnectivityError.RESPONSE_TOO_LARGE, failure.code)
        assertFalse(diagnostics.contains(marker))
    }

    @Test
    fun classifiesHttpFailuresByStatus() {
        val cases = listOf(
            404 to TranslationConnectivityError.ENDPOINT_NOT_FOUND,
            429 to TranslationConnectivityError.HTTP,
        )

        cases.forEach { (status, expected) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("private body"))

            val failure = assertThrows(TranslationConnectivityException::class.java) {
                checkerCall().execute()
            }

            assertEquals(expected, failure.code)
            assertEquals(status, failure.httpStatus)
        }
        assertEquals(cases.size, server.requestCount)
    }

    private fun successfulResponse(): MockResponse = MockResponse().setResponseCode(200).setBody(
        """
        {
          "id": "chatcmpl-test",
          "object": "chat.completion",
          "created": 123,
          "model": "gpt-5.6-luna",
          "choices": [{
            "index": 0,
            "message": {
              "role": "assistant",
              "content": "早上好。今天天气怎么样？要不要一起去天守阁公园散步？"
            },
            "finish_reason": "stop"
          }],
          "usage": {
            "prompt_tokens": 40,
            "completion_tokens": 20,
            "total_tokens": 60
          }
        }
        """.trimIndent(),
    )

    private fun checkerCall(): TranslationConnectivityCall =
        TranslationConnectivityChecker().newCall(
            apiUrl = server.url("/v1").toString(),
            apiKey = "test-secret",
            model = "gpt-5.6-luna",
            allowInsecureLocalhost = true,
        )
}
