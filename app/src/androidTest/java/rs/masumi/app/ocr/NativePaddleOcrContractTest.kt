package rs.masumi.app.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.ocr.OcrExecutionBackend

@RunWith(AndroidJUnit4::class)
class NativePaddleOcrContractTest {
    @Test
    fun invalidModelPathMapsToSafeCodeWithoutLeakingPath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missingModel = context.cacheDir.toPath().resolve("sensitive-model-location.gguf")
        val missingProjector = context.cacheDir.toPath().resolve("sensitive-projector-location.gguf")

        val failure = try {
            NativePaddleOcrEngine.open(missingModel, missingProjector)
            fail("Expected model load failure")
            throw AssertionError("unreachable")
        } catch (error: OcrEngineException) {
            error
        }

        assertEquals(OcrEngineErrorCode.MODEL_LOAD, failure.code)
        assertFalse(failure.message.orEmpty().contains("sensitive-model-location"))
    }

    @Test
    fun malformedRgbLengthIsRejectedBeforeNativeDispatch() {
        val bridge = RecordingBridge()
        val engine = NativePaddleOcrEngine(1L, bridge, OcrExecutionBackend.CPU)

        val failure = try {
            engine.recognize(
                OcrEngineRequest(rgb = byteArrayOf(1, 2), width = 1, height = 1),
                cancellation = { false },
            )
            fail("Expected image validation failure")
            throw AssertionError("unreachable")
        } catch (error: OcrEngineException) {
            error
        }

        assertEquals(OcrEngineErrorCode.IMAGE_INVALID, failure.code)
        assertEquals(0, bridge.recognizeCalls)
        engine.close()
    }

    @Test
    fun cancellationRequestedDuringRecognitionReachesNativeBridge() {
        val bridge = BlockingBridge()
        val engine = NativePaddleOcrEngine(1L, bridge, OcrExecutionBackend.CPU)
        val cancellation = AtomicBoolean(false)
        val failure = AtomicReference<OcrEngineException?>()
        val worker = thread(start = true) {
            try {
                engine.recognize(
                    OcrEngineRequest(rgb = byteArrayOf(1, 2, 3), width = 1, height = 1),
                    cancellation = cancellation::get,
                )
            } catch (error: OcrEngineException) {
                failure.set(error)
            }
        }

        assertTrue(bridge.recognitionStarted.await(1, TimeUnit.SECONDS))
        cancellation.set(true)
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(bridge.cancelCalled.await(0, TimeUnit.MILLISECONDS))
        assertEquals(OcrEngineErrorCode.CANCELLED, failure.get()?.code)
        engine.close()
    }

    @Test
    fun VulkanSuccessKeepsOneBackendForEngineLifetime() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = 11L, cpuHandle = 12L)

        NativePaddleOcrEngine.openWithBridge(model, projector, bridge).use { engine ->
            assertEquals(OcrExecutionBackend.VULKAN, engine.executionBackend)
        }

        assertEquals(listOf(true), bridge.createPreferences)
        assertEquals(listOf(11L), bridge.destroyedHandles)
    }

    @Test
    fun unavailableVulkanFallsBackOnceToCpu() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = -5L, cpuHandle = 12L)

        NativePaddleOcrEngine.openWithBridge(model, projector, bridge).use { engine ->
            assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
        }

        assertEquals(listOf(true, false), bridge.createPreferences)
    }

    @Test
    fun failedVulkanInitializationFallsBackToCpu() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = -2L, cpuHandle = 12L)

        NativePaddleOcrEngine.openWithBridge(model, projector, bridge).use { engine ->
            assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
        }

        assertEquals(listOf(true, false), bridge.createPreferences)
    }

    @Test
    fun cpuOnlyValidationNeverRequestsVulkan() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = 11L, cpuHandle = 12L)

        NativePaddleOcrEngine.openCpuOnlyWithBridge(model, projector, bridge).use { engine ->
            assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
        }

        assertEquals(listOf(false), bridge.createPreferences)
    }

    private class RecordingBridge : NativeOcrBridge {
        var recognizeCalls = 0

        override fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long = 1L

        override fun executionBackend(handle: Long): String = OcrExecutionBackend.CPU.name

        override fun recognize(
            handle: Long,
            rgb: ByteArray,
            width: Int,
            height: Int,
            prompt: String,
            maximumGeneratedTokens: Int,
            repetitionPenalty: Double,
        ): String {
            recognizeCalls += 1
            return "{}"
        }

        override fun cancel(handle: Long) = Unit

        override fun destroy(handle: Long) = Unit
    }

    private class BlockingBridge : NativeOcrBridge {
        val recognitionStarted = CountDownLatch(1)
        val cancelCalled = CountDownLatch(1)

        override fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long = 1L

        override fun executionBackend(handle: Long): String = OcrExecutionBackend.CPU.name

        override fun recognize(
            handle: Long,
            rgb: ByteArray,
            width: Int,
            height: Int,
            prompt: String,
            maximumGeneratedTokens: Int,
            repetitionPenalty: Double,
        ): String {
            recognitionStarted.countDown()
            cancelCalled.await(500, TimeUnit.MILLISECONDS)
            return "{\"errorCode\":\"CANCELLED\"}"
        }

        override fun cancel(handle: Long) {
            cancelCalled.countDown()
        }

        override fun destroy(handle: Long) = Unit
    }

    private class PolicyBridge(
        private val gpuHandle: Long,
        private val cpuHandle: Long,
    ) : NativeOcrBridge {
        val createPreferences = mutableListOf<Boolean>()
        val destroyedHandles = mutableListOf<Long>()

        override fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long {
            createPreferences += preferGpu
            return if (preferGpu) gpuHandle else cpuHandle
        }

        override fun executionBackend(handle: Long): String = when (handle) {
            gpuHandle -> OcrExecutionBackend.VULKAN.name
            else -> OcrExecutionBackend.CPU.name
        }

        override fun recognize(
            handle: Long,
            rgb: ByteArray,
            width: Int,
            height: Int,
            prompt: String,
            maximumGeneratedTokens: Int,
            repetitionPenalty: Double,
        ): String = error("not used")

        override fun cancel(handle: Long) = Unit

        override fun destroy(handle: Long) {
            destroyedHandles += handle
        }
    }

    private fun withModelFiles(block: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),
            "native-policy-",
        )
        try {
            val model = root.resolve("model.gguf").also { Files.write(it, byteArrayOf(1)) }
            val projector = root.resolve("projector.gguf").also { Files.write(it, byteArrayOf(2)) }
            block(model, projector)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
