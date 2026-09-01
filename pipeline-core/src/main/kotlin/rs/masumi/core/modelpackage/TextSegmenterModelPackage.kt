package rs.masumi.core.modelpackage

import kotlinx.serialization.Serializable
import rs.masumi.core.cleanup.CleanupMaskModelRef
import java.nio.file.Path

@Serializable
data class TextSegmenterModelDescriptor(
    val modelId: String,
    val storageKey: String,
    val storageRevision: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val assetPath: String,
    val byteLength: Long,
    val license: String,
    val opset: Int,
    val runtimeRevision: String,
) {
    fun toModelRef(): CleanupMaskModelRef = CleanupMaskModelRef(
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
data class TextSegmenterModelSignature(
    val inputName: String,
    val inputShape: List<Int>,
    val outputName: String,
    val outputShape: List<Int>,
)

@Serializable
data class TextSegmenterModelPackageMetadata(
    val schemaVersion: Int = 1,
    val storageRevision: String = "",
    val model: CleanupMaskModelRef,
    val signature: TextSegmenterModelSignature,
    val acquiredAtEpochMillis: Long,
)

fun interface TextSegmenterSignatureValidator {
    fun validate(modelFile: Path): TextSegmenterModelSignature
}

enum class TextSegmenterModelPackageErrorCode(val safeMessage: String) {
    LENGTH_MISMATCH("Text segmentation model byte length did not match the pinned package"),
    SIGNATURE_MISMATCH("Text segmentation model tensor signature did not match the pinned package"),
    INSTALL_IO("Text segmentation model package could not be installed"),
}

class TextSegmenterModelPackageException(
    val code: TextSegmenterModelPackageErrorCode,
    cause: Throwable? = null,
) : RuntimeException(code.safeMessage, cause)

object PinnedComicTextSegmenter {
    val descriptor = TextSegmenterModelDescriptor(
        modelId = "dmMaze/comic-text-detector-segmentation",
        storageKey = "dmMaze--comic-text-detector-segmentation",
        storageRevision = "beta-0.3-seg-only-512-v1",
        repository = "zyddnys/manga-image-translator",
        revision = "beta-0.3-seg-only-512-v1",
        fileName = "comic-text-segmenter-512.onnx",
        assetPath = "models/comic-text-segmenter-512.onnx",
        byteLength = 65_568_382,
        license = "GPL-3.0-only",
        opset = 11,
        runtimeRevision = "onnxruntime-android:1.27.0",
    )
}
