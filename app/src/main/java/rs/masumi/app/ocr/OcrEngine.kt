package rs.masumi.app.ocr

import java.nio.file.Path
import rs.masumi.core.ocr.OcrExecutionBackend

data class OcrEngineRequest(
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
    val prompt: String = "OCR:",
    val maximumGeneratedTokens: Int = 256,
    val repetitionPenalty: Double = 1.2,
)

data class OcrEngineResult(
    val rawText: String,
    val tokenIds: List<Int>,
    val tokenProbabilities: List<Double>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val processedWidth: Int,
    val processedHeight: Int,
    val visualTokenCount: Int,
    val generatedTokenCount: Int,
    val reachedEos: Boolean,
    val truncated: Boolean,
    val repetitionStopped: Boolean,
    val promptEvaluationMillis: Long,
    val generationMillis: Long,
)

interface OcrEngine : AutoCloseable {
    val executionBackend: OcrExecutionBackend

    fun recognize(request: OcrEngineRequest, cancellation: () -> Boolean): OcrEngineResult

    fun cancel()
}

fun interface OcrEngineFactory {
    fun open(model: Path, projector: Path): OcrEngine
}

enum class OcrEngineErrorCode(val safeMessage: String) {
    MODEL_LOAD("OCR language model could not be loaded"),
    PROJECTOR_LOAD("OCR vision projector could not be loaded"),
    VISION_UNSUPPORTED("OCR model package does not support vision"),
    IMAGE_INVALID("OCR crop pixels were invalid"),
    TOKENIZE("OCR prompt could not be tokenized"),
    CONTEXT("OCR inference context could not be created"),
    ACCELERATOR_UNAVAILABLE("OCR accelerator was unavailable"),
    DECODE("OCR inference could not decode the crop"),
    UTF8("OCR output was not valid UTF-8"),
    CANCELLED("OCR inference was cancelled"),
    CLOSED("OCR engine is already closed"),
}

class OcrEngineException(
    val code: OcrEngineErrorCode,
    cause: Throwable? = null,
) : RuntimeException(code.safeMessage, cause)
