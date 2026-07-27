package rs.masumi.app.translation

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object OpenAiCompatibleEndpointResolver {
    private const val CHAT_COMPLETIONS_PATH = "/chat/completions"
    private const val MODELS_PATH = "/models"
    private val LOCALHOSTS = setOf("localhost", "127.0.0.1", "::1")
    private val VERSION_SEGMENT = Regex("v\\d+")

    fun completionUrl(apiUrl: String, allowInsecureLocalhost: Boolean): HttpUrl {
        val parsed = parse(apiUrl, allowInsecureLocalhost)
        return if (parsed.encodedPath.trimEnd('/').endsWith(CHAT_COMPLETIONS_PATH)) {
            parsed
        } else {
            parsed.newBuilder().addPathSegments("chat/completions").build()
        }
    }

    fun modelUrls(apiUrl: String, allowInsecureLocalhost: Boolean): List<HttpUrl> {
        val parsed = parse(apiUrl, allowInsecureLocalhost)
        val path = parsed.encodedPath.trimEnd('/')
        if (path.endsWith(MODELS_PATH)) return listOf(parsed)

        val basePath = path
            .removeSuffix(CHAT_COMPLETIONS_PATH)
            .ifBlank { "/" }
        val base = parsed.newBuilder().encodedPath(basePath).build()
        val candidates = mutableListOf(
            base.newBuilder().addPathSegment("models").build(),
        )
        val finalSegment = base.pathSegments.lastOrNull().orEmpty()
        if (!VERSION_SEGMENT.matches(finalSegment)) {
            candidates += base.newBuilder().addPathSegments("v1/models").build()
        }
        return candidates.distinct()
    }

    private fun parse(apiUrl: String, allowInsecureLocalhost: Boolean): HttpUrl {
        val parsed = apiUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("apiUrl is invalid")
        require(parsed.query == null && parsed.fragment == null) {
            "apiUrl must not contain query or fragment"
        }
        if (parsed.scheme != "https") {
            require(allowInsecureLocalhost && parsed.host in LOCALHOSTS) {
                "apiUrl must use HTTPS"
            }
        }
        return parsed
    }
}
