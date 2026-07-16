package rs.masumi.app.ocr

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject
import rs.masumi.core.modelpackage.OcrModelCapabilities
import rs.masumi.core.modelpackage.OcrModelCapabilityValidator
import rs.masumi.core.ocr.OcrExecutionBackend

internal interface NativeOcrBridge {
    fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long

    fun executionBackend(handle: Long): String

    fun recognize(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maximumGeneratedTokens: Int,
        repetitionPenalty: Double,
    ): String

    fun cancel(handle: Long)

    fun destroy(handle: Long)
}

private object JniNativeOcrBridge : NativeOcrBridge {
    init {
        System.loadLibrary("masumi_ocr")
    }

    override external fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long

    override external fun executionBackend(handle: Long): String

    override external fun recognize(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maximumGeneratedTokens: Int,
        repetitionPenalty: Double,
    ): String

    override external fun cancel(handle: Long)

    override external fun destroy(handle: Long)
}

class NativePaddleOcrEngine internal constructor(
    private val handle: Long,
    private val bridge: NativeOcrBridge,
    override val executionBackend: OcrExecutionBackend,
) : OcrEngine {
    private val closed = AtomicBoolean(false)
    private val inferenceLock = ReentrantLock()

    override fun recognize(
        request: OcrEngineRequest,
        cancellation: () -> Boolean,
    ): OcrEngineResult = inferenceLock.withLock {
        ensureOpen()
        validateRequest(request)
        if (cancellation()) {
            cancel()
            throw OcrEngineException(OcrEngineErrorCode.CANCELLED)
        }
        val monitoring = AtomicBoolean(true)
        val cancellationMonitor = Thread {
            while (monitoring.get()) {
                if (cancellation()) {
                    cancel()
                    return@Thread
                }
                try {
                    Thread.sleep(CANCELLATION_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            name = "masumi-ocr-cancellation"
            isDaemon = true
            start()
        }
        val response = try {
            bridge.recognize(
                handle = handle,
                rgb = request.rgb,
                width = request.width,
                height = request.height,
                prompt = request.prompt,
                maximumGeneratedTokens = request.maximumGeneratedTokens,
                repetitionPenalty = request.repetitionPenalty,
            )
        } catch (failure: OcrEngineException) {
            throw failure
        } catch (failure: Throwable) {
            throw OcrEngineException(OcrEngineErrorCode.DECODE, failure)
        } finally {
            monitoring.set(false)
            cancellationMonitor.interrupt()
        }
        parseResult(response)
    }

    override fun cancel() {
        if (!closed.get()) bridge.cancel(handle)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            bridge.cancel(handle)
            inferenceLock.withLock { bridge.destroy(handle) }
        }
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

        internal fun openCpuOnly(
            model: Path,
            projector: Path,
        ): NativePaddleOcrEngine = openSingle(
            model,
            projector,
            JniNativeOcrBridge,
            OcrExecutionBackend.CPU,
        )

        internal fun openWithBridge(
            model: Path,
            projector: Path,
            bridge: NativeOcrBridge,
        ): NativePaddleOcrEngine {
            if (!Files.isRegularFile(model)) throw OcrEngineException(OcrEngineErrorCode.MODEL_LOAD)
            if (!Files.isRegularFile(projector)) {
                throw OcrEngineException(OcrEngineErrorCode.PROJECTOR_LOAD)
            }
            return try {
                openSingle(model, projector, bridge, OcrExecutionBackend.VULKAN)
            } catch (_: OcrEngineException) {
                openSingle(model, projector, bridge, OcrExecutionBackend.CPU)
            }
        }

        internal fun openCpuOnlyWithBridge(
            model: Path,
            projector: Path,
            bridge: NativeOcrBridge,
        ): NativePaddleOcrEngine {
            if (!Files.isRegularFile(model)) throw OcrEngineException(OcrEngineErrorCode.MODEL_LOAD)
            if (!Files.isRegularFile(projector)) {
                throw OcrEngineException(OcrEngineErrorCode.PROJECTOR_LOAD)
            }
            return openSingle(model, projector, bridge, OcrExecutionBackend.CPU)
        }

        private fun openSingle(
            model: Path,
            projector: Path,
            bridge: NativeOcrBridge,
            requestedBackend: OcrExecutionBackend,
        ): NativePaddleOcrEngine {
            val handle = try {
                bridge.create(
                    model.toString(),
                    projector.toString(),
                    requestedBackend == OcrExecutionBackend.VULKAN,
                )
            } catch (failure: Throwable) {
                throw OcrEngineException(OcrEngineErrorCode.MODEL_LOAD, failure)
            }
            when (handle) {
                MODEL_LOAD_FAILURE -> throw OcrEngineException(OcrEngineErrorCode.MODEL_LOAD)
                PROJECTOR_LOAD_FAILURE -> throw OcrEngineException(OcrEngineErrorCode.PROJECTOR_LOAD)
                VISION_UNSUPPORTED_FAILURE -> {
                    throw OcrEngineException(OcrEngineErrorCode.VISION_UNSUPPORTED)
                }
                TEMPLATE_FAILURE -> throw OcrEngineException(OcrEngineErrorCode.TOKENIZE)
                ACCELERATOR_UNAVAILABLE_FAILURE -> {
                    throw OcrEngineException(OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE)
                }
            }
            if (handle == 0L) throw OcrEngineException(OcrEngineErrorCode.CONTEXT)
            val actualBackend = try {
                OcrExecutionBackend.valueOf(bridge.executionBackend(handle))
            } catch (failure: Throwable) {
                bridge.destroy(handle)
                throw OcrEngineException(OcrEngineErrorCode.CONTEXT, failure)
            }
            if (actualBackend != requestedBackend) {
                bridge.destroy(handle)
                throw OcrEngineException(OcrEngineErrorCode.CONTEXT)
            }
            return NativePaddleOcrEngine(handle, bridge, actualBackend)
        }

        private const val RGB_CHANNEL_COUNT = 3L
        private const val MODEL_LOAD_FAILURE = -1L
        private const val PROJECTOR_LOAD_FAILURE = -2L
        private const val VISION_UNSUPPORTED_FAILURE = -3L
        private const val TEMPLATE_FAILURE = -4L
        private const val ACCELERATOR_UNAVAILABLE_FAILURE = -5L
        private const val CANCELLATION_POLL_MILLIS = 10L
    }
}

internal class ResilientPaddleOcrEngine private constructor(
    private val model: Path,
    private val projector: Path,
    private val bridge: NativeOcrBridge,
    private val backendHealth: OcrBackendHealthStore?,
    initialEngine: NativePaddleOcrEngine,
) : OcrEngine {
    private val closed = AtomicBoolean(false)
    private val fallbackLock = ReentrantLock()

    @Volatile
    private var activeEngine = initialEngine

    override val executionBackend: OcrExecutionBackend
        get() = activeEngine.executionBackend

    override fun recognize(
        request: OcrEngineRequest,
        cancellation: () -> Boolean,
    ): OcrEngineResult {
        val selected = activeEngine
        return try {
            selected.recognize(request, cancellation)
        } catch (failure: OcrEngineException) {
            if (failure.code != OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE ||
                selected.executionBackend != OcrExecutionBackend.VULKAN
            ) {
                throw failure
            }
            fallbackLock.withLock {
                if (closed.get() || cancellation()) throw OcrEngineException(OcrEngineErrorCode.CANCELLED)
                if (activeEngine === selected) {
                    backendHealth?.markVulkanUnavailable()
                    selected.close()
                    activeEngine = NativePaddleOcrEngine.openCpuOnlyWithBridge(model, projector, bridge)
                }
                activeEngine.recognize(request, cancellation)
            }
        }
    }

    override fun cancel() {
        activeEngine.cancel()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            fallbackLock.withLock { activeEngine.close() }
        }
    }

    companion object {
        fun open(
            model: Path,
            projector: Path,
            backendHealth: OcrBackendHealthStore? = null,
        ): OcrEngine = openWithBridge(
            model,
            projector,
            JniNativeOcrBridge,
            backendHealth,
        )

        internal fun openWithBridge(
            model: Path,
            projector: Path,
            bridge: NativeOcrBridge,
            backendHealth: OcrBackendHealthStore? = null,
        ): OcrEngine = ResilientPaddleOcrEngine(
            model = model,
            projector = projector,
            bridge = bridge,
            backendHealth = backendHealth,
            initialEngine = if (backendHealth?.shouldPreferVulkan() != false) {
                NativePaddleOcrEngine.openWithBridge(model, projector, bridge)
            } else {
                NativePaddleOcrEngine.openCpuOnlyWithBridge(model, projector, bridge)
            },
        )
    }
}

internal class OcrBackendHealthStore(
    private val unavailableMarker: Path,
) {
    fun shouldPreferVulkan(): Boolean = !Files.isRegularFile(unavailableMarker)

    fun markVulkanUnavailable() {
        runCatching {
            Files.createDirectories(requireNotNull(unavailableMarker.parent))
            if (!Files.exists(unavailableMarker)) Files.createFile(unavailableMarker)
        }
    }
}

class NativePaddleOcrCapabilityValidator : OcrModelCapabilityValidator {
    override fun validate(model: Path, projector: Path): OcrModelCapabilities {
        NativePaddleOcrEngine.openCpuOnly(model, projector).use { }
        return OcrModelCapabilities(
            vision = true,
            embeddedChatTemplate = true,
            projectorType = "paddleocr",
        )
    }
}
