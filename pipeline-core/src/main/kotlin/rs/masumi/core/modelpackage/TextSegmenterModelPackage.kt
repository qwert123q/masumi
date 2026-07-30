package rs.masumi.core.modelpackage

import kotlinx.serialization.Serializable
import rs.masumi.core.cleanup.CleanupMaskModelRef
import java.nio.file.Path

@Serializable
data class TextSegmenterModelDescriptor(
    val modelId: String,
    val storageKey: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val assetPath: String,
    val byteLength: Long,
    val sha256: String,
    val license: String,
    val opset: Int,
    val runtimeRevision: String,
) {
    fun toModelRef(): CleanupMaskModelRef = CleanupMaskModelRef(
        modelId = modelId,
        repository = repository,
        revision = revision,
        fileName = fileName,
        sha256 = sha256,
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
    val model: CleanupMaskModelRef,
    val signature: TextSegmenterModelSignature,
    val acquiredAtEpochMillis: Long,
)

fun interface TextSegmenterSignatureValidator {
    fun validate(modelFile: Path): TextSegmenterModelSignature
}

enum class TextSegmenterModelPackageErrorCode(val safeMessage: String) {
    LENGTH_MISMATCH("Text segmentation model byte length did not match the pinned package"),
    HASH_MISMATCH("Text segmentation model digest did not match the pinned package"),
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
        repository = "zyddnys/manga-image-translator",
        revision = "beta-0.3-seg-only-512-v1",
        fileName = "comic-text-segmenter-512.onnx",
        assetPath = "models/comic-text-segmenter-512.onnx",
        byteLength = 65_568_382,
        sha256 = "688cb2b55bc14e29957bb4dad768e7420a4b1f740b84ffadc83ecaac63846485",
        license = "GPL-3.0-only",
        opset = 11,
        runtimeRevision = "onnxruntime-android:1.27.0",
    )
}
