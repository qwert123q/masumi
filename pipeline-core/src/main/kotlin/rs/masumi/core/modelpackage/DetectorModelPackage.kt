package rs.masumi.core.modelpackage

import kotlinx.serialization.Serializable
import rs.masumi.core.detection.DetectorModelRef
import java.nio.file.Path

@Serializable
data class DetectorModelDescriptor(
    val modelId: String,
    val storageKey: String,
    val storageRevision: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val byteLength: Long,
    val license: String,
    val opset: Int,
    val runtimeRevision: String,
    val downloadUrl: String,
) {
    fun toModelRef(): DetectorModelRef = DetectorModelRef(
        modelId = modelId,
        repository = repository,
        revision = revision,
        fileName = fileName,
        byteLength = byteLength,
        license = license,
        opset = opset,
        runtimeRevision = runtimeRevision,
    )
}

@Serializable
data class DetectorModelSignature(
    val imageInputName: String,
    val imageInputShape: List<Int?>,
    val sizeInputName: String,
    val sizeInputShape: List<Int?>,
    val outputNames: List<String>,
    val queryCount: Int,
)

@Serializable
data class DetectorModelPackageMetadata(
    val schemaVersion: Int = 1,
    val storageRevision: String = "",
    val model: DetectorModelRef,
    val signature: DetectorModelSignature,
    val acquiredAtEpochMillis: Long,
)

fun interface ModelSignatureValidator {
    fun validate(modelFile: Path): DetectorModelSignature
}

enum class ModelPackageErrorCode(val safeMessage: String) {
    LENGTH_MISMATCH("Model byte length did not match the pinned package"),
    SIGNATURE_MISMATCH("Model tensor signature did not match the pinned package"),
    INSTALL_IO("Model package could not be installed"),
}

class ModelPackageException(
    val code: ModelPackageErrorCode,
    cause: Throwable? = null,
) : RuntimeException(code.safeMessage, cause)

object PinnedComicDetector {
    val descriptor = DetectorModelDescriptor(
        modelId = "ogkalu/comic-text-and-bubble-detector",
        storageKey = "ogkalu--comic-text-and-bubble-detector",
        storageRevision = "detector-v4-s-int8-r1",
        repository = "ogkalu/comic-text-and-bubble-detector",
        revision = "16e8a622f91fabc6b5b65c96d32d1183f8843546",
        fileName = "detector-v4-s_int8.onnx",
        byteLength = 11_120_765,
        license = "Apache-2.0",
        opset = 18,
        runtimeRevision = "onnxruntime-android:1.27.0",
        downloadUrl = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/" +
            "16e8a622f91fabc6b5b65c96d32d1183f8843546/detector-v4-s_int8.onnx",
    )
}
