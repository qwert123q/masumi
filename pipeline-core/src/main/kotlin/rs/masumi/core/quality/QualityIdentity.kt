package rs.masumi.core.quality

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object QualityIdentity {
    fun pageArtifactKey(
        pageOrder: Int,
        sourceSha256: String,
        typesettingPageArtifactKey: String,
        renderedImageSha256: String?,
        dependencies: QualityDependencies,
    ): String {
        require(pageOrder >= 0)
        requireSha256(sourceSha256)
        requireSha256(typesettingPageArtifactKey)
        renderedImageSha256?.let(::requireSha256)
        return hash(
            listOf(
                "pageOrder" to pageOrder.toString(),
                "sourceSha256" to sourceSha256,
                "typesettingPageArtifactKey" to typesettingPageArtifactKey,
                "renderedImageSha256" to (renderedImageSha256 ?: "preserved-cleaned-page"),
            ) + dependencyFields(dependencies),
        )
    }

    fun runArtifactKey(entries: List<Pair<Int, String>>, dependencies: QualityDependencies): String {
        val ordered = entries.sortedBy(Pair<Int, String>::first)
        require(ordered.map(Pair<Int, String>::first) == ordered.indices.toList())
        ordered.forEach { requireSha256(it.second) }
        return hash(
            dependencyFields(dependencies) + ordered.flatMap { (order, key) ->
                listOf("pageOrder" to order.toString(), "pageArtifactKey" to key)
            },
        )
    }

    private fun dependencyFields(value: QualityDependencies): List<Pair<String, String>> = listOf(
        "schemaVersion" to value.schemaVersion.toString(),
        "typesettingRunArtifactKey" to value.typesettingRunArtifactKey,
        "policyRevision" to value.policy.revision,
        "maximumChangedPixelsOutsideLayout" to value.policy.maximumChangedPixelsOutsideLayout.toString(),
    )

    private fun hash(fields: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { (name, value) ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(name.toByteArray(StandardCharsets.UTF_8))
            digest.update('='.code.toByte())
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireSha256(value: String) = require(SHA256.matches(value))
    private val SHA256 = Regex("[0-9a-f]{64}")
}
