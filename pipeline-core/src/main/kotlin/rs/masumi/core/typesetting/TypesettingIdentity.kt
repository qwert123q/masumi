package rs.masumi.core.typesetting

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object TypesettingIdentity {
    fun pageArtifactKey(
        pageOrder: Int,
        sourceSha256: String,
        cleanupPageArtifactKey: String,
        dependencies: TypesettingDependencies,
    ): String {
        require(pageOrder >= 0)
        requireSha256(sourceSha256)
        requireSha256(cleanupPageArtifactKey)
        requireSha256(dependencies.cleanupRunArtifactKey)
        return hash(
            listOf(
                "pageOrder" to pageOrder.toString(),
                "sourceSha256" to sourceSha256,
                "cleanupPageArtifactKey" to cleanupPageArtifactKey,
            ) + dependencyFields(dependencies),
        )
    }

    fun runArtifactKey(entries: List<Pair<Int, String>>, dependencies: TypesettingDependencies): String {
        val ordered = entries.sortedBy(Pair<Int, String>::first)
        require(ordered.map(Pair<Int, String>::first) == ordered.indices.toList())
        ordered.forEach { requireSha256(it.second) }
        return hash(
            dependencyFields(dependencies) + ordered.flatMap { (order, key) ->
                listOf("pageOrder" to order.toString(), "pageArtifactKey" to key)
            },
        )
    }

    private fun dependencyFields(value: TypesettingDependencies): List<Pair<String, String>> = listOf(
        "schemaVersion" to value.schemaVersion.toString(),
        "cleanupRunArtifactKey" to value.cleanupRunArtifactKey,
        "policyRevision" to value.policy.revision,
        "fontFamily" to value.policy.fontFamily,
        "fontWeight" to value.policy.fontWeight.toString(),
        "minimumFontSizePixels" to value.policy.minimumFontSizePixels.toString(),
        "minimumFontSizePageFraction" to value.policy.minimumFontSizePageFraction.toString(),
        "maximumFontSizePageFraction" to value.policy.maximumFontSizePageFraction.toString(),
        "bubbleInsetFraction" to value.policy.bubbleInsetFraction.toString(),
        "minimumBubbleInsetFraction" to value.policy.minimumBubbleInsetFraction.toString(),
        "freeTextExpansionFraction" to value.policy.freeTextExpansionFraction.toString(),
        "verticalAspectThreshold" to value.policy.verticalAspectThreshold.toString(),
        "lineSpacingEm" to value.policy.lineSpacingEm.toString(),
        "letterSpacingEm" to value.policy.letterSpacingEm.toString(),
        "freeTextStrokeEm" to value.policy.freeTextStrokeEm.toString(),
        "verticalPunctuationRevision" to value.policy.verticalPunctuationRevision,
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
