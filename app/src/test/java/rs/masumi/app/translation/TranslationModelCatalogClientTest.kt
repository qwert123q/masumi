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
