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
    val sha256: String,
    val downloadUrl: String,
    val installedSha256: String = sha256,
    val normalization: OcrModelFileNormalization = OcrModelFileNormalization.NONE,
) {
    fun toRef(): OcrModelFileRef = OcrModelFileRef(fileName, byteLength, installedSha256)
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
    val repository: String,
    val revision: String,
    val model: OcrModelFileDescriptor,
    val projector: OcrModelFileDescriptor,
    val packageSha256: String,
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
        packageSha256 = packageSha256,
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
    HASH_MISMATCH("OCR model file digest did not match the pinned package"),
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
        repository = "PaddlePaddle/PaddleOCR-VL-1.6-GGUF",
        revision = REVISION,
        model = OcrModelFileDescriptor(
            fileName = "PaddleOCR-VL-1.6-GGUF.gguf",
            byteLength = 935_769_056L,
            sha256 = "f3ae46ec885050acf4b3d31944431e1fd90d50664fb09126af4a3c050ba14ee8",
            downloadUrl = "$BASE_URL/PaddleOCR-VL-1.6-GGUF.gguf",
            installedSha256 = "8d13cb7c4f685e891f411584263db03b038701c110023e6cc6557901f83ab97e",
            normalization = OcrModelFileNormalization.GGUF_BF16_TO_F16,
        ),
        projector = OcrModelFileDescriptor(
            fileName = "PaddleOCR-VL-1.6-GGUF-mmproj.gguf",
            byteLength = 881_770_560L,
            sha256 = "204d757d7610d9b3faab10d506d69e5b244e32bf765e2bab2d0167e65e0a058a",
            downloadUrl = "$BASE_URL/PaddleOCR-VL-1.6-GGUF-mmproj.gguf",
            installedSha256 = "d7e99b91293e706525a2ea68c84507705bc9352d630d5cfb5f6e7b0be25a72a8",
            normalization = OcrModelFileNormalization.GGUF_BF16_TO_F16,
        ),
        packageSha256 = "7f09a652e09641b5f594218eca04017ca38da6dbe84d0cbf26e309313480b40e",
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
