package rs.masumi.core.translation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object TranslationIdentity {
    fun regionId(
        ocrPageArtifactKey: String,
        ocrRegionId: String,
        policy: TranslationPolicy,
        prompt: TranslationPromptRef,
    ): String {
        require(ocrPageArtifactKey.isNotBlank()) { "ocrPageArtifactKey must not be blank" }
        require(ocrRegionId.isNotBlank()) { "ocrRegionId must not be blank" }
        return hashFields(
            listOf(
                "ocrPageArtifactKey" to ocrPageArtifactKey,
                "ocrRegionId" to ocrRegionId,
                "policyRevision" to policy.revision,
                "sourceLanguage" to policy.sourceLanguage.name,
                "targetLanguage" to policy.targetLanguage.name,
                "translateDialogue" to policy.translateDialogue.toString(),
                "translateNarration" to policy.translateNarration.toString(),
                "translateSoundEffects" to policy.translateSoundEffects.toString(),
                "automaticApproval" to policy.automaticApproval.toString(),
                "promptRevision" to prompt.revision,
                "responseSchemaRevision" to prompt.responseSchemaRevision,
            ),
        )
    }

    private fun hashFields(fields: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { (name, value) ->
            val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(name.toByteArray(StandardCharsets.UTF_8))
            digest.update('='.code.toByte())
            digest.update(valueBytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(valueBytes)
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
