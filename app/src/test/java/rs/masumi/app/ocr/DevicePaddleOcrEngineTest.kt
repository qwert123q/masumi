package rs.masumi.app.ocr

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.core.ocr.OcrExecutionBackend

class DevicePaddleOcrEngineTest {
    @Test
    fun `initial unavailable Vulkan records marker and opens CPU`() = withModelFiles { model, projector ->
        val bridge = RecordingBridge(initialVulkanUnavailable = true)
        val health = OcrBackendHealthStore(model.parent.resolve("vulkan-unavailable"))

        DevicePaddleOcrEngine.openWithBridge(model, projector, bridge, health).use { engine ->
            assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
        }

        assertFalse(health.shouldPreferVulkan())
        assertEquals(listOf(true, false), bridge.createPreferences)
    }

    @Test
    fun `repeated runtime Vulkan loss refreshes Vulkan for the next region without using CPU`() =
        withModelFiles { model, projector ->
            val bridge = RecordingBridge(initialVulkanUnavailable = false)
            val health = OcrBackendHealthStore(model.parent.resolve("vulkan-unavailable"))

            DevicePaddleOcrEngine.openWithBridge(model, projector, bridge, health).use { engine ->
                val failure = runCatching {
                    engine.recognize(
                        OcrEngineRequest(rgb = byteArrayOf(1, 2, 3), width = 1, height = 1),
                        cancellation = { false },
                    )
                }.exceptionOrNull() as OcrEngineException

                assertEquals(OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE, failure.code)
                assertEquals(OcrExecutionBackend.VULKAN, engine.executionBackend)
            }

            assertTrue(health.shouldPreferVulkan())
            assertEquals(listOf(true, true, true), bridge.createPreferences)
            assertEquals(listOf(11L, 13L), bridge.recognizedHandles)
        }

    @Test
    fun `expired backend marker allows Vulkan to be probed again`() = withModelFiles { model, _ ->
        val marker = Files.createFile(model.parent.resolve("vulkan-unavailable"))
        Files.setLastModifiedTime(marker, FileTime.fromMillis(1_000L))
        val health = OcrBackendHealthStore(
            unavailableMarker = marker,
            clock = Clock.fixed(Instant.ofEpochMilli(301_000L), ZoneOffset.UTC),
            retryDelayMillis = 300_000L,
        )

        assertTrue(health.shouldPreferVulkan())
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `CPU engine promotes back to Vulkan after health cooldown`() =
        withModelFiles { model, projector ->
            val clock = MutableClock(1_000L)
            val marker = model.parent.resolve("vulkan-unavailable")
            val health = OcrBackendHealthStore(
                unavailableMarker = marker,
                clock = clock,
                retryDelayMillis = 300_000L,
            )
            health.markVulkanUnavailable()
            val bridge = RecoveryBridge()

            DevicePaddleOcrEngine.openWithBridge(model, projector, bridge, health).use { engine ->
                assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
                runCatching { engine.recognize(TEST_REQUEST) { false } }

                clock.nowEpochMillis += 300_000L
                runCatching { engine.recognize(TEST_REQUEST) { false } }

                assertEquals(OcrExecutionBackend.VULKAN, engine.executionBackend)
            }

            assertEquals(listOf(false, true), bridge.createPreferences)
            assertEquals(listOf(12L, 11L), bridge.recognizedHandles)
            assertFalse(Files.exists(marker))
        }

    private fun withModelFiles(block: (java.nio.file.Path, java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("device-ocr-policy-")
        try {
            val model = Files.write(root.resolve("model.gguf"), "model".toByteArray())
            val projector = Files.write(root.resolve("projector.gguf"), "projector".toByteArray())
            block(model, projector)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class RecordingBridge(
        private val initialVulkanUnavailable: Boolean,
    ) : NativeOcrBridge {
        val createPreferences = mutableListOf<Boolean>()
        val recognizedHandles = mutableListOf<Long>()
        private var vulkanOpenCount = 0

        override fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long {
            createPreferences += preferGpu
            if (!preferGpu) return 12L
            vulkanOpenCount += 1
            if (initialVulkanUnavailable && vulkanOpenCount == 1) return -5L
            return if (vulkanOpenCount == 1) 11L else 13L
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
            if (handle == 12L) throw OcrEngineException(OcrEngineErrorCode.TOKENIZE)
            throw OcrEngineException(OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE)
        }

        override fun cancel(handle: Long) = Unit

        override fun destroy(handle: Long) = Unit
    }

    private class RecoveryBridge : NativeOcrBridge {
        val createPreferences = mutableListOf<Boolean>()
        val recognizedHandles = mutableListOf<Long>()

        override fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long {
            createPreferences += preferGpu
            return if (preferGpu) 11L else 12L
        }

        override fun executionBackend(handle: Long): String = if (handle == 11L) {
            OcrExecutionBackend.VULKAN.name
        } else {
            OcrExecutionBackend.CPU.name
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
            return SUCCESS_RESPONSE
        }

        override fun cancel(handle: Long) = Unit

        override fun destroy(handle: Long) = Unit
    }

    private class MutableClock(
        var nowEpochMillis: Long,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(nowEpochMillis)
    }

    private companion object {
        val TEST_REQUEST = OcrEngineRequest(rgb = byteArrayOf(1, 2, 3), width = 1, height = 1)
        const val SUCCESS_RESPONSE = """{"rawText":"ok","tokenIds":[],"tokenProbabilities":[],"sourceWidth":1,"sourceHeight":1,"processedWidth":1,"processedHeight":1,"visualTokenCount":1,"generatedTokenCount":1,"reachedEos":true,"truncated":false,"repetitionStopped":false,"promptEvaluationMillis":1,"generationMillis":1}"""
    }
}
