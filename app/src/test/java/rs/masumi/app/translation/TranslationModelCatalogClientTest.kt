package rs.masumi.app.translation

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TranslationModelCatalogClientTest {
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
    fun endpointResolverKeepsCompletionAndModelsAsSiblings() {
        assertEquals(
            "https://example.com/api/v3/chat/completions",
            OpenAiCompatibleEndpointResolver
                .completionUrl("https://example.com/api/v3", false)
                .toString(),
        )
        assertEquals(
            listOf("https://example.com/api/v3/models"),
            OpenAiCompatibleEndpointResolver
                .modelUrls("https://example.com/api/v3/chat/completions", false)
                .map { it.toString() },
        )
        assertEquals(
            listOf("https://example.com/v1/models"),
            OpenAiCompatibleEndpointResolver
                .modelUrls("https://example.com/v1", false)
                .map { it.toString() },
        )
    }

    @Test
    fun endpointResolverAllowsCleartextForLocalNetworkLiterals() {
        val expectedCompletionUrls = mapOf(
            "http://localhost:8317/v1" to "http://localhost:8317/v1/chat/completions",
            "http://127.0.0.1:8317/v1" to "http://127.0.0.1:8317/v1/chat/completions",
            "http://10.42.0.12:8317/v1" to "http://10.42.0.12:8317/v1/chat/completions",
            "http://172.16.0.1:8317/v1" to "http://172.16.0.1:8317/v1/chat/completions",
            "http://172.31.255.254:8317/v1" to "http://172.31.255.254:8317/v1/chat/completions",
            "http://192.168.50.2:8317/v1" to "http://192.168.50.2:8317/v1/chat/completions",
            "http://[fd12:3456::1]:8317/v1" to "http://[fd12:3456::1]:8317/v1/chat/completions",
        )

        expectedCompletionUrls.forEach { (input, expected) ->
            assertEquals(
                expected,
                OpenAiCompatibleEndpointResolver.completionUrl(input, false).toString(),
            )
        }
    }

    @Test
    fun endpointResolverRejectsCleartextOutsidePrivateAddressRanges() {
        listOf(
            "http://example.com/v1",
            "http://8.8.8.8/v1",
            "http://172.15.255.255/v1",
            "http://172.32.0.0/v1",
            "http://169.254.1.1/v1",
            "http://[fe80::1]/v1",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                OpenAiCompatibleEndpointResolver.completionUrl(input, true)
            }
        }
    }

    @Test
    fun fetchesSortsAndDeduplicatesOpenAiCompatibleModels() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "object": "list",
                  "data": [
                    {"id": "zeta", "owned_by": "vendor"},
                    {"id": "Alpha", "owned_by": "vendor"},
                    {"id": "zeta", "owned_by": "duplicate"},
                    {"id": ""}
                  ]
                }
                """.trimIndent(),
            ),
        )

        val models = clientCall(server.url("/v1").toString()).execute()
        val request = server.takeRequest(2, TimeUnit.SECONDS)

        assertNotNull(request)
        assertEquals("/v1/models", request?.path)
        assertEquals("Bearer test-secret", request?.getHeader("Authorization"))
        assertEquals(listOf("Alpha", "zeta"), models.map(AvailableTranslationModel::id))
        assertEquals("vendor", models.last().ownedBy)
    }

    @Test
    fun missingRootModelsEndpointFallsBackToV1LikeCcSwitch() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":[{"id":"fallback-model","owned_by":"vendor"}]}""",
            ),
        )

        val models = clientCall(server.url("/").toString()).execute()

        assertEquals("fallback-model", models.single().id)
        assertEquals("/models", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertEquals("/v1/models", server.takeRequest(2, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun authenticationAndMalformedResponsesUseSafeErrorCodes() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("test-secret should stay private"))
        val authentication = assertThrows(TranslationModelCatalogException::class.java) {
            clientCall(server.url("/v1").toString()).execute()
        }
        assertEquals(TranslationModelCatalogError.AUTHENTICATION, authentication.code)
        assertEquals(401, authentication.httpStatus)
        assertFalse(authentication.stackTraceToString().contains("test-secret"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
        val malformed = assertThrows(TranslationModelCatalogException::class.java) {
            clientCall(server.url("/v1").toString()).execute()
        }
        assertEquals(TranslationModelCatalogError.MALFORMED_RESPONSE, malformed.code)
    }

    private fun clientCall(apiUrl: String): TranslationModelCatalogCall =
        TranslationModelCatalogClient().newCall(
            apiUrl = apiUrl,
            apiKey = "test-secret",
            allowInsecureLocalhost = true,
        )
}
