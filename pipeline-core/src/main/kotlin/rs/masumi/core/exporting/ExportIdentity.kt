package rs.masumi.core.exporting

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ExportIdentity {
    fun destinationKey(destinationUri: String): String {
        require(destinationUri.isNotBlank())
        return hash(listOf("destinationUri" to destinationUri))
    }

    fun exportKey(destinationKey: String, dependencies: ExportDependencies): String {
        requireSha256(destinationKey)
        requireSha256(dependencies.typesettingRunArtifactKey)
        requireSha256(dependencies.qualityRunArtifactKey)
        return hash(
            listOf(
                "schemaVersion" to dependencies.schemaVersion.toString(),
                "destinationKey" to destinationKey,
                "typesettingRunArtifactKey" to dependencies.typesettingRunArtifactKey,
                "qualityRunArtifactKey" to dependencies.qualityRunArtifactKey,
                "policyRevision" to dependencies.policy.revision,
                "minimumPageNumberDigits" to dependencies.policy.minimumPageNumberDigits.toString(),
                "imageMediaType" to dependencies.policy.imageMediaType,
            ),
        )
    }

    fun outputName(pageOrder: Int, totalPageCount: Int, policy: ExportPolicy): String {
        require(pageOrder >= 0 && totalPageCount > pageOrder)
        val digits = maxOf(policy.minimumPageNumberDigits, totalPageCount.toString().length)
        return "${(pageOrder + 1).toString().padStart(digits, '0')}.png"
    }

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
