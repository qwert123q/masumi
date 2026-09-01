package rs.masumi.app.ocr

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject
import rs.masumi.core.modelpackage.OcrModelCapabilities
import rs.masumi.core.modelpackage.OcrModelCapabilityValidator
import rs.masumi.core.ocr.OcrExecutionBackend
import rs.masumi.core.ocr.OcrVisualDetailProfile

internal interface NativeOcrBridge {
    fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long

    /** Test bridges ignore the thread count unless they opt in. */
    fun create(modelPath: String, projectorPath: String, preferGpu: Boolean, threadCount: Int): Long =
        create(modelPath, projectorPath, preferGpu)

    fun executionBackend(handle: Long): String

    /** Arms a new recognition before its cancellation monitor is started. */
    fun beginRecognition(handle: Long) = Unit

    fun recognize(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maximumGeneratedTokens: Int,
        repetitionPenalty: Double,
    ): String

    fun recognize(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maximumGeneratedTokens: Int,
        repetitionPenalty: Double,
        highDetail: Boolean,
        maximumVisualTokens: Int,
        maximumSourcePixels: Int,
    ): String = recognize(handle, rgb, width, height, prompt, maximumGeneratedTokens, repetitionPenalty)

    fun cancel(handle: Long)

    fun destroy(handle: Long)
}

private object JniNativeOcrBridge : NativeOcrBridge {
    init {
        System.loadLibrary("masumi_ocr")
    }

    override fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long =
        nativeCreate(modelPath, projectorPath, preferGpu, DEFAULT_THREAD_COUNT)

    override fun create(
        modelPath: String,
        projectorPath: String,
        preferGpu: Boolean,
        threadCount: Int,
    ): Long = nativeCreate(modelPath, projectorPath, preferGpu, threadCount)

    private external fun nativeCreate(
        modelPath: String,
        projectorPath: String,
        preferGpu: Boolean,
        threadCount: Int,
    ): Long

    override external fun executionBackend(handle: Long): String

    override external fun beginRecognition(handle: Long)

    override fun recognize(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maximumGeneratedTokens: Int,
        repetitionPenalty: Double,
    ): String = nativeRecognize(
        handle, rgb, width, height, prompt, maximumGeneratedTokens, repetitionPenalty, false, 0, 0,
    )

    override fun recognize(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maximumGeneratedTokens: Int,
        repetitionPenalty: Double,
        highDetail: Boolean,
        maximumVisualTokens: Int,
        maximumSourcePixels: Int,
    ): String = nativeRecognize(
        handle, rgb, width, height, prompt, maximumGeneratedTokens, repetitionPenalty, highDetail, maximumVisualTokens, maximumSourcePixels,
    )

    private external fun nativeRecognize(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maximumGeneratedTokens: Int,
        repetitionPenalty: Double,
        highDetail: Boolean,
        maximumVisualTokens: Int,
        maximumSourcePixels: Int,
    ): String

    override external fun cancel(handle: Long)

    override external fun destroy(handle: Long)

    private const val DEFAULT_THREAD_COUNT = 6
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
        // Reset the native cancellation latch before starting the monitor. If
        // nativeRecognize reset it on entry instead, a cancellation delivered
        // in the narrow monitor-to-JNI handoff window would be lost.
        bridge.beginRecognition(handle)
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
                highDetail = request.visualDetailProfile == OcrVisualDetailProfile.HIGH_DETAIL,
                maximumVisualTokens = request.maximumVisualTokens,
                maximumSourcePixels = request.maximumSourcePixels,
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
        if (
            request.visualDetailProfile == OcrVisualDetailProfile.HIGH_DETAIL &&
                (
                    request.maximumVisualTokens !in MINIMUM_HIGH_DETAIL_VISUAL_TOKENS..MAXIMUM_HIGH_DETAIL_VISUAL_TOKENS ||
                        request.maximumSourcePixels !in 1..MAXIMUM_HIGH_DETAIL_SOURCE_PIXELS ||
                        request.width.toLong() * request.height > request.maximumSourcePixels
                    )
        ) {
            throw OcrEngineException(OcrEngineErrorCode.IMAGE_INVALID)
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
            threadCount: Int = DEFAULT_ENGINE_THREADS,
        ): NativePaddleOcrEngine = openWithBridge(model, projector, JniNativeOcrBridge, threadCount)

        internal fun openCpuOnly(
            model: Path,
            projector: Path,
            threadCount: Int = DEFAULT_ENGINE_THREADS,
        ): NativePaddleOcrEngine = openSingle(
            model,
            projector,
            JniNativeOcrBridge,
            OcrExecutionBackend.CPU,
            threadCount,
        )

        internal fun openWithBridge(
            model: Path,
            projector: Path,
            bridge: NativeOcrBridge,
            threadCount: Int = DEFAULT_ENGINE_THREADS,
        ): NativePaddleOcrEngine {
            if (!Files.isRegularFile(model)) throw OcrEngineException(OcrEngineErrorCode.MODEL_LOAD)
            if (!Files.isRegularFile(projector)) {
                throw OcrEngineException(OcrEngineErrorCode.PROJECTOR_LOAD)
            }
            return openSingle(model, projector, bridge, OcrExecutionBackend.VULKAN, threadCount)
        }

        internal fun openCpuOnlyWithBridge(
            model: Path,
            projector: Path,
            bridge: NativeOcrBridge,
            threadCount: Int = DEFAULT_ENGINE_THREADS,
        ): NativePaddleOcrEngine {
            if (!Files.isRegularFile(model)) throw OcrEngineException(OcrEngineErrorCode.MODEL_LOAD)
            if (!Files.isRegularFile(projector)) {
                throw OcrEngineException(OcrEngineErrorCode.PROJECTOR_LOAD)
            }
            return openSingle(model, projector, bridge, OcrExecutionBackend.CPU, threadCount)
        }

        private fun openSingle(
            model: Path,
            projector: Path,
            bridge: NativeOcrBridge,
            requestedBackend: OcrExecutionBackend,
            threadCount: Int,
        ): NativePaddleOcrEngine {
            val handle = try {
                bridge.create(
                    model.toString(),
                    projector.toString(),
                    requestedBackend == OcrExecutionBackend.VULKAN,
                    threadCount,
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
        internal const val DEFAULT_ENGINE_THREADS = 6
        private const val MINIMUM_HIGH_DETAIL_VISUAL_TOKENS = 129
        private const val MAXIMUM_HIGH_DETAIL_VISUAL_TOKENS = 256
        private const val MAXIMUM_HIGH_DETAIL_SOURCE_PIXELS = 4_000_000
    }
}

internal class ResilientPaddleOcrEngine private constructor(
    private val model: Path,
    private val projector: Path,
    private val bridge: NativeOcrBridge,
    private val threadCount: Int,
    initialEngine: NativePaddleOcrEngine,
) : OcrEngine {
    private val closed = AtomicBoolean(false)
    private val fallbackLock = ReentrantLock()
    private var acceleratorRestartUsed = false

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
                if (acceleratorRestartUsed) throw failure
                if (activeEngine === selected) {
                    acceleratorRestartUsed = true
                    selected.close()
                    activeEngine = NativePaddleOcrEngine.openWithBridge(model, projector, bridge, threadCount)
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
            threadCount: Int = NativePaddleOcrEngine.DEFAULT_ENGINE_THREADS,
        ): OcrEngine = openWithBridge(
            model,
            projector,
            JniNativeOcrBridge,
            threadCount,
        )

        internal fun openWithBridge(
            model: Path,
            projector: Path,
            bridge: NativeOcrBridge,
            threadCount: Int = NativePaddleOcrEngine.DEFAULT_ENGINE_THREADS,
        ): OcrEngine = ResilientPaddleOcrEngine(
            model = model,
            projector = projector,
            bridge = bridge,
            threadCount = threadCount,
            initialEngine = NativePaddleOcrEngine.openWithBridge(model, projector, bridge, threadCount),
        )
    }
}

internal object DevicePaddleOcrEngine {
    fun open(
        model: Path,
        projector: Path,
        backendHealth: OcrBackendHealthStore,
        threadCount: Int = NativePaddleOcrEngine.DEFAULT_ENGINE_THREADS,
    ): OcrEngine = openWithBridge(model, projector, JniNativeOcrBridge, backendHealth, threadCount)

    internal fun openWithBridge(
        model: Path,
        projector: Path,
        bridge: NativeOcrBridge,
        backendHealth: OcrBackendHealthStore,
        threadCount: Int = NativePaddleOcrEngine.DEFAULT_ENGINE_THREADS,
    ): OcrEngine {
        val initialEngine = if (backendHealth.shouldPreferVulkan()) {
            try {
                ResilientPaddleOcrEngine.openWithBridge(model, projector, bridge, threadCount)
            } catch (failure: OcrEngineException) {
                if (failure.code != OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE) throw failure
                backendHealth.markVulkanUnavailable()
                NativePaddleOcrEngine.openCpuOnlyWithBridge(model, projector, bridge, threadCount)
            }
        } else {
            NativePaddleOcrEngine.openCpuOnlyWithBridge(model, projector, bridge, threadCount)
        }
        return BackendHealthRecordingEngine(
            model = model,
            projector = projector,
            bridge = bridge,
            backendHealth = backendHealth,
            threadCount = threadCount,
            initialEngine = initialEngine,
        )
    }
}

private class BackendHealthRecordingEngine(
    private val model: Path,
    private val projector: Path,
    private val bridge: NativeOcrBridge,
    private val backendHealth: OcrBackendHealthStore,
    private val threadCount: Int,
    initialEngine: OcrEngine,
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
        promoteCpuAfterCooldown(cancellation)
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
                    val replacement = try {
                        ResilientPaddleOcrEngine.openWithBridge(model, projector, bridge, threadCount)
                    } catch (_: OcrEngineException) {
                        throw failure
                    }
                    activeEngine = replacement
                    selected.close()
                }
                throw failure
            }
        }
    }

    private fun promoteCpuAfterCooldown(cancellation: () -> Boolean) {
        if (activeEngine.executionBackend != OcrExecutionBackend.CPU ||
            !backendHealth.shouldPreferVulkan()
        ) {
            return
        }
        fallbackLock.withLock {
            if (closed.get() || cancellation()) throw OcrEngineException(OcrEngineErrorCode.CANCELLED)
            val selected = activeEngine
            if (selected.executionBackend != OcrExecutionBackend.CPU) return@withLock
            val promoted = try {
                ResilientPaddleOcrEngine.openWithBridge(model, projector, bridge, threadCount)
            } catch (failure: OcrEngineException) {
                if (failure.code != OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE) throw failure
                backendHealth.markVulkanUnavailable()
                return@withLock
            }
            activeEngine = promoted
            selected.close()
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
}

internal class OcrBackendHealthStore(
    private val unavailableMarker: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) {
    init {
        require(retryDelayMillis >= 0L)
    }

    fun shouldPreferVulkan(): Boolean {
        if (!Files.isRegularFile(unavailableMarker)) return true
        val unavailableAt = runCatching {
            Files.getLastModifiedTime(unavailableMarker).toMillis()
        }.getOrNull() ?: return false
        val elapsed = (clock.millis() - unavailableAt).coerceAtLeast(0L)
        if (elapsed < retryDelayMillis) return false
        return runCatching {
            Files.deleteIfExists(unavailableMarker)
            true
        }.getOrDefault(false)
    }

    fun markVulkanUnavailable() {
        runCatching {
            Files.createDirectories(requireNotNull(unavailableMarker.parent))
            if (!Files.exists(unavailableMarker)) Files.createFile(unavailableMarker)
            Files.setLastModifiedTime(unavailableMarker, FileTime.fromMillis(clock.millis()))
        }
    }

    private companion object {
        const val DEFAULT_RETRY_DELAY_MILLIS = 5L * 60L * 1_000L
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
