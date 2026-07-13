package rs.masumi.app.ocr

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal interface NativeOcrBridge {
    fun create(modelPath: String, projectorPath: String): Long

    fun recognize(handle: Long, requestJson: String, rgb: ByteArray): String

    fun cancel(handle: Long)

    fun destroy(handle: Long)
}

private object JniNativeOcrBridge : NativeOcrBridge {
    init {
        System.loadLibrary("masumi_ocr")
    }

    override external fun create(modelPath: String, projectorPath: String): Long

    override external fun recognize(handle: Long, requestJson: String, rgb: ByteArray): String

    override external fun cancel(handle: Long)

    override external fun destroy(handle: Long)
}

class NativePaddleOcrEngine internal constructor(
    private val handle: Long,
    private val bridge: NativeOcrBridge,
) : OcrEngine {
    private val closed = AtomicBoolean(false)

    override fun recognize(
        request: OcrEngineRequest,
        cancellation: () -> Boolean,
    ): OcrEngineResult {
        ensureOpen()
        validateRequest(request)
        if (cancellation()) {
            cancel()
            throw OcrEngineException(OcrEngineErrorCode.CANCELLED)
        }
        val response = try {
            bridge.recognize(handle, request.toJson().toString(), request.rgb)
        } catch (failure: OcrEngineException) {
            throw failure
        } catch (failure: Throwable) {
            throw OcrEngineException(OcrEngineErrorCode.DECODE, failure)
        }
        return parseResult(response)
    }

    override fun cancel() {
        if (!closed.get()) bridge.cancel(handle)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) bridge.destroy(handle)
    }

    private fun ensureOpen() {
        if (closed.get()) throw OcrEngineException(OcrEngineErrorCode.CLOSED)
    }

    private fun validateRequest(request: OcrEngineRequest) {
        if (request.width <= 0 || request.height <= 0 || request.maximumGeneratedTokens <= 0) {
            throw OcrEngineException(OcrEngineErrorCode.IMAGE_INVALID)
        }
        val expectedLength = request.width.toLong() * request.height.toLong() * RGB_CHANNEL_COUNT
        if (expectedLength > Int.MAX_VALUE || request.rgb.size.toLong() != expectedLength) {
            throw OcrEngineException(OcrEngineErrorCode.IMAGE_INVALID)
        }
        if (request.prompt.isBlank() || !request.repetitionPenalty.isFinite() || request.repetitionPenalty <= 0.0) {
            throw OcrEngineException(OcrEngineErrorCode.TOKENIZE)
        }
    }

    private fun OcrEngineRequest.toJson(): JSONObject = JSONObject()
        .put("width", width)
        .put("height", height)
        .put("prompt", prompt)
        .put("maximumGeneratedTokens", maximumGeneratedTokens)
        .put("repetitionPenalty", repetitionPenalty)

    private fun parseResult(content: String): OcrEngineResult {
        val json = try {
            JSONObject(content)
        } catch (failure: Throwable) {
            throw OcrEngineException(OcrEngineErrorCode.DECODE, failure)
        }
        json.optString("errorCode").takeIf(String::isNotBlank)?.let { rawCode ->
            val code = OcrEngineErrorCode.entries.firstOrNull { it.name == rawCode }
                ?: OcrEngineErrorCode.DECODE
            throw OcrEngineException(code)
        }
        return try {
            val tokenIdsJson = json.getJSONArray("tokenIds")
            val tokenProbabilitiesJson = json.getJSONArray("tokenProbabilities")
            val tokenIds = List(tokenIdsJson.length(), tokenIdsJson::getInt)
            val tokenProbabilities = List(tokenProbabilitiesJson.length(), tokenProbabilitiesJson::getDouble)
            if (tokenIds.size != tokenProbabilities.size) throw IllegalArgumentException("token mismatch")
            OcrEngineResult(
                rawText = json.getString("rawText"),
                tokenIds = tokenIds,
                tokenProbabilities = tokenProbabilities,
                sourceWidth = json.getInt("sourceWidth"),
                sourceHeight = json.getInt("sourceHeight"),
                processedWidth = json.getInt("processedWidth"),
                processedHeight = json.getInt("processedHeight"),
                visualTokenCount = json.getInt("visualTokenCount"),
                generatedTokenCount = json.getInt("generatedTokenCount"),
                reachedEos = json.getBoolean("reachedEos"),
                truncated = json.getBoolean("truncated"),
                repetitionStopped = json.getBoolean("repetitionStopped"),
                promptEvaluationMillis = json.getLong("promptEvaluationMillis"),
                generationMillis = json.getLong("generationMillis"),
            )
        } catch (failure: OcrEngineException) {
            throw failure
        } catch (failure: Throwable) {
            throw OcrEngineException(OcrEngineErrorCode.DECODE, failure)
        }
    }

    companion object {
        fun open(
            model: Path,
            projector: Path,
        ): NativePaddleOcrEngine = openWithBridge(model, projector, JniNativeOcrBridge)

        internal fun openWithBridge(
            model: Path,
            projector: Path,
            bridge: NativeOcrBridge,
        ): NativePaddleOcrEngine {
            if (!Files.isRegularFile(model)) throw OcrEngineException(OcrEngineErrorCode.MODEL_LOAD)
            if (!Files.isRegularFile(projector)) {
                throw OcrEngineException(OcrEngineErrorCode.PROJECTOR_LOAD)
            }
            val handle = try {
                bridge.create(model.toString(), projector.toString())
            } catch (failure: Throwable) {
                throw OcrEngineException(OcrEngineErrorCode.MODEL_LOAD, failure)
            }
            when (handle) {
                MODEL_LOAD_FAILURE -> throw OcrEngineException(OcrEngineErrorCode.MODEL_LOAD)
                PROJECTOR_LOAD_FAILURE -> throw OcrEngineException(OcrEngineErrorCode.PROJECTOR_LOAD)
            }
            if (handle <= 0L) throw OcrEngineException(OcrEngineErrorCode.CONTEXT)
            return NativePaddleOcrEngine(handle, bridge)
        }

        private const val RGB_CHANNEL_COUNT = 3L
        private const val MODEL_LOAD_FAILURE = -1L
        private const val PROJECTOR_LOAD_FAILURE = -2L
    }
}
