package rs.masumi.core.cleanup

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object CleanupIdentity {
    fun pageArtifactKey(
        pageOrder: Int,
        sourceSha256: String,
        translationPageArtifactKey: String,
        dependencies: CleanupDependencies,
    ): String {
        require(pageOrder >= 0)
        requireSha256(sourceSha256)
        requireSha256(translationPageArtifactKey)
        requireSha256(dependencies.translationRunArtifactKey)
        return hash(
            listOf(
                "pageOrder" to pageOrder.toString(),
                "sourceSha256" to sourceSha256,
                "translationPageArtifactKey" to translationPageArtifactKey,
            ) + dependencyFields(dependencies),
        )
    }

    fun runArtifactKey(entries: List<Pair<Int, String>>, dependencies: CleanupDependencies): String {
        val ordered = entries.sortedBy(Pair<Int, String>::first)
        require(ordered.map(Pair<Int, String>::first) == ordered.indices.toList())
        ordered.forEach { requireSha256(it.second) }
        return hash(
            dependencyFields(dependencies) + ordered.flatMap { (order, key) ->
                listOf("pageOrder" to order.toString(), "pageArtifactKey" to key)
            },
        )
    }

    private fun dependencyFields(value: CleanupDependencies): List<Pair<String, String>> = listOf(
        "schemaVersion" to value.schemaVersion.toString(),
        "translationRunArtifactKey" to value.translationRunArtifactKey,
        "policyRevision" to value.policy.revision,
        "boxPaddingFraction" to value.policy.boxPaddingFraction.toString(),
        "minimumPaddingPixels" to value.policy.minimumPaddingPixels.toString(),
        "colorDistanceThreshold" to value.policy.colorDistanceThreshold.toString(),
        "dilationRadiusPixels" to value.policy.dilationRadiusPixels.toString(),
        "minimumMaskCoverage" to value.policy.minimumMaskCoverage.toString(),
        "maximumMaskCoverage" to value.policy.maximumMaskCoverage.toString(),
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
