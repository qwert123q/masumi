package rs.masumi.core.ocr

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object OcrIdentity {
    fun regionId(
        pageId: String,
        detectionPageArtifactKey: String,
        sourceRegionIds: List<String>,
        semantic: OcrSemanticStatus,
    ): String {
        require(pageId.isNotBlank()) { "pageId must not be blank" }
        require(detectionPageArtifactKey.isNotBlank()) { "detectionPageArtifactKey must not be blank" }
        require(sourceRegionIds.isNotEmpty()) { "sourceRegionIds must not be empty" }
        require(sourceRegionIds.none(String::isBlank)) { "sourceRegionIds must not contain blanks" }
        val orderedSources = sourceRegionIds.distinct().sorted()
        require(orderedSources.size == sourceRegionIds.size) { "sourceRegionIds must be unique" }
        return hashFields(
            buildList {
                add("pageId" to pageId)
                add("detectionPageArtifactKey" to detectionPageArtifactKey)
                add("consolidationSchemaVersion" to OcrConsolidationConfig().revision)
                add("semantic" to semantic.name)
                add("sourceRegionCount" to orderedSources.size.toString())
                orderedSources.forEach { add("sourceRegionId" to it) }
            },
        )
    }

    fun pageArtifactKey(
        sourceSha256: String,
        detectionPageArtifactKey: String,
        dependencies: OcrDependencies,
    ): String {
        requireSha256(sourceSha256, "sourceSha256")
        requireSha256(detectionPageArtifactKey, "detectionPageArtifactKey")
        requireSha256(dependencies.modelPackage.model.sha256, "model.sha256")
        requireSha256(dependencies.modelPackage.projector.sha256, "projector.sha256")
        requireSha256(dependencies.modelPackage.packageSha256, "packageSha256")
        return hashFields(
            buildList {
                add("sourceSha256" to sourceSha256)
                add("detectionPageArtifactKey" to detectionPageArtifactKey)
                add("schemaVersion" to dependencies.schemaVersion.toString())
                with(dependencies.modelPackage) {
                    add("packageId" to packageId)
                    add("repository" to repository)
                    add("revision" to revision)
                    add("modelFileName" to model.fileName)
                    add("modelByteLength" to model.byteLength.toString())
                    add("modelSha256" to model.sha256)
                    add("projectorFileName" to projector.fileName)
                    add("projectorByteLength" to projector.byteLength.toString())
                    add("projectorSha256" to projector.sha256)
                    add("packageSha256" to packageSha256)
                    add("license" to license)
                }
                with(dependencies.runtime) {
                    add("llamaTag" to llamaTag)
                    add("llamaCommit" to llamaCommit)
                    add("abi" to abi)
                    add("backend" to backend)
                    add("buildContract" to buildContract)
                }
                with(dependencies.consolidation) {
                    add("consolidationRevision" to revision)
                    add("sameClassIouThreshold" to sameClassIouThreshold.toString())
                    add("sameClassContainmentThreshold" to sameClassContainmentThreshold.toString())
                    add("crossClassSmallerCoverageThreshold" to crossClassSmallerCoverageThreshold.toString())
                    add("bubbleAssociationCoverageThreshold" to bubbleAssociationCoverageThreshold.toString())
                }
                add("readingOrderRevision" to dependencies.readingOrder.revision)
                add("verticalOverlapThreshold" to dependencies.readingOrder.verticalOverlapThreshold.toString())
                with(dependencies.crop) {
                    add("cropRevision" to revision)
                    add("paddedTextFraction" to paddedTextFraction.toString())
                    add("tightTextFraction" to tightTextFraction.toString())
                    add("contextTextFraction" to contextTextFraction.toString())
                    add("minimumPaddingPixels" to minimumPaddingPixels.toString())
                }
                add("normalizationRevision" to dependencies.normalization.revision)
                with(dependencies.quality) {
                    add("qualityRevision" to revision)
                    add("agreementSimilarityThreshold" to agreementSimilarityThreshold.toString())
                    add("primaryTokenProbabilityThreshold" to primaryTokenProbabilityThreshold.toString())
                    add("emptyConfirmationAttemptCount" to emptyConfirmationAttemptCount.toString())
                    add("lowConfidenceFreeTextThreshold" to lowConfidenceFreeTextThreshold.toString())
                }
                with(dependencies.generation) {
                    add("prompt" to prompt)
                    add("maximumGeneratedTokens" to maximumGeneratedTokens.toString())
                    add("temperature" to temperature.toString())
                    add("repetitionPenalty" to repetitionPenalty.toString())
                }
            },
        )
    }

    fun runArtifactKey(entries: List<Pair<Int, String>>): String {
        require(entries.isNotEmpty()) { "run entries must not be empty" }
        entries.forEachIndexed { expectedOrder, (order, pageArtifactKey) ->
            require(order == expectedOrder) { "run entry order must be contiguous from zero" }
            requireSha256(pageArtifactKey, "pageArtifactKey")
        }
        return hashFields(
            buildList {
                add("entryCount" to entries.size.toString())
                entries.forEach { (order, key) ->
                    add("pageOrder" to order.toString())
                    add("pageArtifactKey" to key)
                }
            },
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

    private fun requireSha256(value: String, field: String) {
        require(SHA256.matches(value)) { "$field must be a lowercase SHA-256 digest" }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
