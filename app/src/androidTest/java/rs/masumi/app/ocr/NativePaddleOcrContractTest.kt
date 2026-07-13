package rs.masumi.app.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

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
        val engine = NativePaddleOcrEngine(1L, bridge)

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
        val engine = NativePaddleOcrEngine(1L, bridge)
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

    private class RecordingBridge : NativeOcrBridge {
        var recognizeCalls = 0

        override fun create(modelPath: String, projectorPath: String): Long = 1L

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

        override fun create(modelPath: String, projectorPath: String): Long = 1L

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
}
