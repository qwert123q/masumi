package rs.masumi.core.modelpackage

import java.io.Closeable
import java.io.InputStream
import java.nio.file.Path
import kotlinx.serialization.Serializable
import rs.masumi.core.ocr.OcrModelFileRef
import rs.masumi.core.ocr.OcrModelPackageRef
import rs.masumi.core.ocr.OcrRuntimeRef

@Serializable
enum class OcrModelFileNormalization {
    NONE,
    GGUF_BF16_TO_F16,
}

@Serializable
data class OcrModelFileDescriptor(
    val fileName: String,
    val byteLength: Long,
    val downloadUrl: String,
    val normalization: OcrModelFileNormalization = OcrModelFileNormalization.NONE,
) {
    fun toRef(): OcrModelFileRef = OcrModelFileRef(fileName, byteLength)
}

@Serializable
data class OcrNativeRuntimeDescriptor(
    val llamaTag: String,
    val llamaCommit: String,
    val abi: String,
    val backend: String,
    val buildContract: String,
) {
    fun toRef(): OcrRuntimeRef = OcrRuntimeRef(
        llamaTag = llamaTag,
        llamaCommit = llamaCommit,
        abi = abi,
        backend = backend,
        buildContract = buildContract,
    )
}

@Serializable
data class OcrModelPackageDescriptor(
    val packageId: String,
    val storageKey: String,
    val storageRevision: String,
    val repository: String,
    val revision: String,
    val model: OcrModelFileDescriptor,
    val projector: OcrModelFileDescriptor,
    val license: String,
    val prompt: String,
    val runtime: OcrNativeRuntimeDescriptor,
) {
    fun toRef(): OcrModelPackageRef = OcrModelPackageRef(
        packageId = packageId,
        repository = repository,
        revision = revision,
        model = model.toRef(),
        projector = projector.toRef(),
        license = license,
    )
}

@Serializable
data class OcrModelCapabilities(
    val vision: Boolean,
    val embeddedChatTemplate: Boolean,
    val projectorType: String,
)

@Serializable
data class OcrModelPackageMetadata(
    val schemaVersion: Int = 1,
    val storageRevision: String = "",
    val modelPackage: OcrModelPackageRef,
    val runtime: OcrRuntimeRef,
    val prompt: String,
    val capabilities: OcrModelCapabilities,
    val acquiredAtEpochMillis: Long,
)

data class InstalledOcrModelPackage(
    val model: Path,
    val projector: Path,
    val metadata: OcrModelPackageMetadata,
)

fun interface OcrModelCapabilityValidator {
    fun validate(model: Path, projector: Path): OcrModelCapabilities
}

fun interface OcrRangeSource {
    fun open(file: OcrModelFileDescriptor, offset: Long): OcrRangeResponse
}

class OcrRangeResponse(
    val statusCode: Int,
    val totalLength: Long?,
    val contentRangeStart: Long?,
    val input: InputStream,
    private val closeAction: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { input.close() }
        closeAction()
    }
}

enum class OcrModelPackageErrorCode(val safeMessage: String) {
    LENGTH_MISMATCH("OCR model file length did not match the pinned package"),
    RANGE_MISMATCH("OCR model server returned an invalid range response"),
    CAPABILITY_MISMATCH("OCR model and projector capabilities did not match"),
    INSTALL_IO("OCR model package could not be installed"),
}

class OcrModelPackageException(
    val code: OcrModelPackageErrorCode,
    cause: Throwable? = null,
) : RuntimeException(code.safeMessage, cause)

object PinnedPaddleOcrVl {
    private const val REVISION = "511b09642bb324401f15f97cc23bc67e8f0a291d"
    private const val BASE_URL = "https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6-GGUF/resolve/$REVISION"

    val descriptor = OcrModelPackageDescriptor(
        packageId = "PaddlePaddle/PaddleOCR-VL-1.6-GGUF",
        storageKey = "PaddlePaddle--PaddleOCR-VL-1.6-GGUF",
        storageRevision = "paddleocr-vl-1.6-f16-v1",
        repository = "PaddlePaddle/PaddleOCR-VL-1.6-GGUF",
        revision = REVISION,
        model = OcrModelFileDescriptor(
            fileName = "PaddleOCR-VL-1.6-GGUF.gguf",
            byteLength = 935_769_056L,
            downloadUrl = "$BASE_URL/PaddleOCR-VL-1.6-GGUF.gguf",
            normalization = OcrModelFileNormalization.GGUF_BF16_TO_F16,
        ),
        projector = OcrModelFileDescriptor(
            fileName = "PaddleOCR-VL-1.6-GGUF-mmproj.gguf",
            byteLength = 881_770_560L,
            downloadUrl = "$BASE_URL/PaddleOCR-VL-1.6-GGUF-mmproj.gguf",
            normalization = OcrModelFileNormalization.GGUF_BF16_TO_F16,
        ),
        license = "Apache-2.0",
        prompt = "OCR:",
        runtime = OcrNativeRuntimeDescriptor(
            llamaTag = "b8935",
            llamaCommit = "f454bd7eb8944629aabca163ea1c6e67e53fd77e",
            abi = "arm64-v8a",
            backend = "vulkan-preferred-cpu-fallback",
            buildContract = "mtmd-vulkan-safe-f16-t6-image-adaptive-v1",
        ),
    )
}
