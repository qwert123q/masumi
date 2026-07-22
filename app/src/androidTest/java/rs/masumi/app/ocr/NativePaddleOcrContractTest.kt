package rs.masumi.app.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
    fun unavailableVulkanFailsFastWithoutCpuFallback() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = -5L, cpuHandle = 12L)

        val failure = try {
            NativePaddleOcrEngine.openWithBridge(model, projector, bridge)
            fail("Expected accelerator failure")
            throw AssertionError("unreachable")
        } catch (error: OcrEngineException) {
            error
        }

        assertEquals(OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE, failure.code)
        assertEquals(listOf(true), bridge.createPreferences)
    }

    @Test
    fun failedVulkanInitializationFailsFastWithoutCpuFallback() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = -2L, cpuHandle = 12L)

        val failure = try {
            NativePaddleOcrEngine.openWithBridge(model, projector, bridge)
            fail("Expected projector failure")
            throw AssertionError("unreachable")
        } catch (error: OcrEngineException) {
            error
        }

        assertEquals(OcrEngineErrorCode.PROJECTOR_LOAD, failure.code)
        assertEquals(listOf(true), bridge.createPreferences)
    }

    @Test
    fun deviceSelectionUsesCpuWhenVulkanWasPreviouslyUnavailable() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = 11L, cpuHandle = 12L)
        val backendHealth = OcrBackendHealthStore(model.parent.resolve("vulkan-unavailable"))
        backendHealth.markVulkanUnavailable()

        DevicePaddleOcrEngine.openWithBridge(model, projector, bridge, backendHealth).use { engine ->
            assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
        }

        assertEquals(listOf(false), bridge.createPreferences)
    }

    @Test
    fun deviceSelectionRecordsUnavailableVulkanAndReopensOnCpu() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = -5L, cpuHandle = 12L)
        val backendHealth = OcrBackendHealthStore(model.parent.resolve("vulkan-unavailable"))

        DevicePaddleOcrEngine.openWithBridge(model, projector, bridge, backendHealth).use { engine ->
            assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
        }

        assertFalse(backendHealth.shouldPreferVulkan())
        assertEquals(listOf(true, false), bridge.createPreferences)
    }

    @Test
    fun deviceSelectionDoesNotHideProjectorFailure() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = -2L, cpuHandle = 12L)
        val backendHealth = OcrBackendHealthStore(model.parent.resolve("vulkan-unavailable"))

        val failure = try {
            DevicePaddleOcrEngine.openWithBridge(model, projector, bridge, backendHealth)
            fail("Expected projector failure")
            throw AssertionError("unreachable")
        } catch (error: OcrEngineException) {
            error
        }

        assertEquals(OcrEngineErrorCode.PROJECTOR_LOAD, failure.code)
        assertTrue(backendHealth.shouldPreferVulkan())
        assertEquals(listOf(true), bridge.createPreferences)
    }

    @Test
    fun deviceSelectionFallsBackToCpuAfterRepeatedVulkanDeviceLoss() = withModelFiles { model, projector ->
        val bridge = DeviceLossBridge(failEveryRecognition = true)
        val backendHealth = OcrBackendHealthStore(model.parent.resolve("vulkan-unavailable"))

        DevicePaddleOcrEngine.openWithBridge(model, projector, bridge, backendHealth).use { engine ->
            val result = engine.recognize(
                OcrEngineRequest(rgb = byteArrayOf(1, 2, 3), width = 1, height = 1),
                cancellation = { false },
            )

            assertEquals("成功", result.rawText)
            assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
        }

        assertFalse(backendHealth.shouldPreferVulkan())
        assertEquals(listOf(true, true, false), bridge.createPreferences)
        assertEquals(listOf(11L, 13L, 12L), bridge.recognizedHandles)
    }

    @Test
    fun deviceSelectionPromotesCpuBackToVulkanAfterCooldown() = withModelFiles { model, projector ->
        val clock = MutableClock(1_000L)
        val backendHealth = OcrBackendHealthStore(
            unavailableMarker = model.parent.resolve("vulkan-unavailable"),
            clock = clock,
            retryDelayMillis = 300_000L,
        )
        backendHealth.markVulkanUnavailable()
        val bridge = DeviceLossBridge()

        DevicePaddleOcrEngine.openWithBridge(model, projector, bridge, backendHealth).use { engine ->
            assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
            assertEquals("成功", engine.recognize(TEST_REQUEST) { false }.rawText)

            clock.nowEpochMillis += 300_000L
            assertEquals("成功", engine.recognize(TEST_REQUEST) { false }.rawText)
            assertEquals(OcrExecutionBackend.VULKAN, engine.executionBackend)
        }

        assertEquals(listOf(false, true, true), bridge.createPreferences)
        assertEquals(listOf(12L, 11L, 13L), bridge.recognizedHandles)
    }

    @Test
    fun cpuOnlyValidationNeverRequestsVulkan() = withModelFiles { model, projector ->
        val bridge = PolicyBridge(gpuHandle = 11L, cpuHandle = 12L)

        NativePaddleOcrEngine.openCpuOnlyWithBridge(model, projector, bridge).use { engine ->
            assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
        }

        assertEquals(listOf(false), bridge.createPreferences)
    }

    @Test
    fun deviceLossReopensVulkanOnceAndRetriesTheSameCrop() = withModelFiles { model, projector ->
        val bridge = DeviceLossBridge()

        ResilientPaddleOcrEngine.openWithBridge(model, projector, bridge).use { engine ->
            assertEquals(OcrExecutionBackend.VULKAN, engine.executionBackend)
            val result = engine.recognize(
                OcrEngineRequest(rgb = byteArrayOf(1, 2, 3), width = 1, height = 1),
                cancellation = { false },
            )

            assertEquals("成功", result.rawText)
            assertEquals(OcrExecutionBackend.VULKAN, engine.executionBackend)
        }

        assertEquals(listOf(true, true), bridge.createPreferences)
        assertEquals(listOf(11L, 13L), bridge.destroyedHandles)
        assertEquals(listOf(11L, 13L), bridge.recognizedHandles)
    }

    @Test
    fun repeatedDeviceLossFailsAfterOneVulkanRestart() = withModelFiles { model, projector ->
        val bridge = DeviceLossBridge(failEveryRecognition = true)

        val failure = ResilientPaddleOcrEngine.openWithBridge(model, projector, bridge).use { engine ->
            try {
                engine.recognize(
                    OcrEngineRequest(rgb = byteArrayOf(1, 2, 3), width = 1, height = 1),
                    cancellation = { false },
                )
                fail("Expected repeated accelerator failure")
                throw AssertionError("unreachable")
            } catch (error: OcrEngineException) {
                error
            }
        }

        assertEquals(OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE, failure.code)
        assertEquals(listOf(true, true), bridge.createPreferences)
        assertEquals(listOf(11L, 13L), bridge.recognizedHandles)
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

    private class DeviceLossBridge(
        private val failEveryRecognition: Boolean = false,
    ) : NativeOcrBridge {
        val createPreferences = mutableListOf<Boolean>()
        val destroyedHandles = mutableListOf<Long>()
        val recognizedHandles = mutableListOf<Long>()

        override fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long {
            createPreferences += preferGpu
            return if (preferGpu) 11L + (createPreferences.count { it } - 1) * 2L else 12L
        }

        override fun executionBackend(handle: Long): String = if (handle == 12L) {
            OcrExecutionBackend.CPU.name
        } else {
            OcrExecutionBackend.VULKAN.name
        }

        override fun recognize(
            handle: Long,
            rgb: ByteArray,
            width: Int,
            height: Int,
            prompt: String,
            maximumGeneratedTokens: Int,
            repetitionPenalty: Double,
        ): String {
            recognizedHandles += handle
            if (handle == 11L || (failEveryRecognition && handle != 12L)) {
                return "{\"errorCode\":\"ACCELERATOR_UNAVAILABLE\"}"
            }
            return """{"rawText":"成功","tokenIds":[1],"tokenProbabilities":[0.99],"sourceWidth":1,"sourceHeight":1,"processedWidth":1,"processedHeight":1,"visualTokenCount":1,"generatedTokenCount":1,"reachedEos":true,"truncated":false,"repetitionStopped":false,"promptEvaluationMillis":1,"generationMillis":1}"""
        }

        override fun cancel(handle: Long) = Unit

        override fun destroy(handle: Long) {
            destroyedHandles += handle
        }
    }

    private class MutableClock(
        var nowEpochMillis: Long,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(nowEpochMillis)
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

    private companion object {
        val TEST_REQUEST = OcrEngineRequest(rgb = byteArrayOf(1, 2, 3), width = 1, height = 1)
    }
}
