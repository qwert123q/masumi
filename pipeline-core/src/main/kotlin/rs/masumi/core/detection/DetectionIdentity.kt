package rs.masumi.core.detection

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object DetectionIdentity {
    fun pageArtifactKey(
        sourceSha256: String,
        schemaVersion: Int,
        model: DetectorModelRef,
        preprocessing: DetectionPreprocessingConfig,
        thresholds: DetectionThresholdConfig,
    ): String {
        requireSha256(sourceSha256, "sourceSha256")
        requireSha256(model.sha256, "model.sha256")
        require(schemaVersion > 0) { "schemaVersion must be positive" }

        return hashFields(
            listOf(
                "sourceSha256" to sourceSha256,
                "schemaVersion" to schemaVersion.toString(),
                "modelId" to model.modelId,
                "modelRepository" to model.repository,
                "modelRevision" to model.revision,
                "modelFileName" to model.fileName,
                "modelSha256" to model.sha256,
                "modelByteLength" to model.byteLength.toString(),
                "modelLicense" to model.license,
                "modelOpset" to model.opset.toString(),
                "runtimeRevision" to model.runtimeRevision,
                "inputWidth" to preprocessing.inputWidth.toString(),
                "inputHeight" to preprocessing.inputHeight.toString(),
                "colorOrder" to preprocessing.colorOrder,
                "interpolation" to preprocessing.interpolation,
                "rescaleDivisor" to preprocessing.rescaleDivisor.toString(),
                "normalize" to preprocessing.normalize.toString(),
                "pad" to preprocessing.pad.toString(),
                "thresholdBubble" to thresholds.bubble.toString(),
                "thresholdTextInBubble" to thresholds.textInBubble.toString(),
                "thresholdTextFree" to thresholds.textFree.toString(),
            ),
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
                entries.forEach { (order, pageArtifactKey) ->
                    add("pageOrder" to order.toString())
                    add("pageArtifactKey" to pageArtifactKey)
                }
            },
        )
    }

    fun regionId(
        pageId: String,
        pageArtifactKey: String,
        queryIndex: Int,
        detectorClass: DetectorClass,
    ): String {
        requireSha256(pageId, "pageId")
        requireSha256(pageArtifactKey, "pageArtifactKey")
        require(queryIndex >= 0) { "queryIndex must not be negative" }

        return hashFields(
            listOf(
                "pageId" to pageId,
                "pageArtifactKey" to pageArtifactKey,
                "queryIndex" to queryIndex.toString(),
                "detectorClass" to detectorClass.name,
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
        return digest.digest().toHex()
    }

    private fun requireSha256(value: String, field: String) {
        require(SHA256.matches(value)) { "$field must be a lowercase SHA-256 digest" }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
