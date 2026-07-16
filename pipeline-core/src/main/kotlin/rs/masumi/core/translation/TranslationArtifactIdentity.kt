package rs.masumi.core.translation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object TranslationArtifactIdentity {
    fun glossarySha256(entries: List<TranslationGlossaryEntry>): String = hashFields(
        entries.sortedWith(compareBy(TranslationGlossaryEntry::source, TranslationGlossaryEntry::translation))
            .flatMap { listOf("source" to it.source, "translation" to it.translation) },
    )

    fun pageArtifactKey(ocrPageArtifactKey: String, dependencies: TranslationDependencies): String = hashFields(
        dependencyFields(dependencies) + ("ocrPageArtifactKey" to ocrPageArtifactKey.also(::requireSha256)),
    ).also { requireSha256(dependencies.ocrRunArtifactKey); requireSha256(dependencies.initialGlossarySha256) }

    fun windowArtifactKey(
        window: TranslationBatchWindow,
        inputGlossarySha256: String,
        dependencies: TranslationDependencies,
    ): String = hashFields(
        dependencyFields(dependencies) + buildList {
            add("windowIndex" to window.windowIndex.toString())
            add("inputGlossarySha256" to inputGlossarySha256)
            window.contextItems.forEach {
                add("contextId" to it.input.translationRegionId)
                add("contextSource" to it.input.sourceText)
                add("contextRoleHint" to it.input.roleHint.name)
            }
            window.items.forEach {
                add("itemId" to it.input.translationRegionId)
                add("itemSource" to it.input.sourceText)
                add("itemRoleHint" to it.input.roleHint.name)
            }
        },
    ).also { requireSha256(inputGlossarySha256) }

    fun runArtifactKey(entries: List<Pair<Int, String>>, dependencies: TranslationDependencies): String {
        val ordered = entries.sortedBy(Pair<Int, String>::first)
        require(ordered.map(Pair<Int, String>::first) == ordered.indices.toList()) {
            "page orders must be contiguous from zero"
        }
        ordered.forEach { requireSha256(it.second) }
        return hashFields(
            dependencyFields(dependencies) + buildList {
            ordered.forEach { (order, key) ->
                add("pageOrder" to order.toString())
                add("pageArtifactKey" to key)
            }
        },
        )
    }

    private fun dependencyFields(value: TranslationDependencies): List<Pair<String, String>> = buildList {
        add("schemaVersion" to value.schemaVersion.toString())
        add("ocrRunArtifactKey" to value.ocrRunArtifactKey)
        add("policyRevision" to value.policy.revision)
        add("sourceLanguage" to value.policy.sourceLanguage.name)
        add("targetLanguage" to value.policy.targetLanguage.name)
        add("translateDialogue" to value.policy.translateDialogue.toString())
        add("translateNarration" to value.policy.translateNarration.toString())
        add("translateSoundEffects" to value.policy.translateSoundEffects.toString())
        add("translateOtherText" to value.policy.translateOtherText.toString())
        add("automaticApproval" to value.policy.automaticApproval.toString())
        add("promptRevision" to value.prompt.revision)
        add("responseSchemaRevision" to value.prompt.responseSchemaRevision)
        add("batchingRevision" to value.batching.revision)
        add("maximumEstimatedInputTokens" to value.batching.maximumEstimatedInputTokens.toString())
        add("maximumContextItems" to value.batching.maximumContextItems.toString())
        add("providerProtocol" to value.provider.protocolRevision)
        add("modelId" to value.provider.modelId)
        add("temperature" to value.provider.temperature.toString())
        add("maximumOutputTokens" to value.provider.maximumOutputTokens.toString())
        add("requestJsonObjectFormat" to value.provider.requestJsonObjectFormat.toString())
        add("initialGlossarySha256" to value.initialGlossarySha256)
    }

    private fun hashFields(fields: List<Pair<String, String>>): String {
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

    private fun requireSha256(value: String) {
        require(SHA256.matches(value)) { "value must be a lowercase SHA-256 digest" }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
