package rs.masumi.app.ocr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.core.ocr.OcrExecutionBackend

class NativePaddleOcrCancellationTest {
    @Test
    fun `new invocation is armed before cancellation monitor can reach native dispatch`() {
        val bridge = BeginBarrierBridge()
        val engine = NativePaddleOcrEngine(1L, bridge, OcrExecutionBackend.CPU)
        val cancelled = AtomicBoolean(false)
        val failure = AtomicReference<OcrEngineException?>()
        val worker = thread(start = true) {
            try {
                engine.recognize(
                    OcrEngineRequest(rgb = byteArrayOf(1, 2, 3), width = 1, height = 1),
                    cancelled::get,
                )
            } catch (error: OcrEngineException) {
                failure.set(error)
            }
        }

        assertTrue(bridge.beginStarted.await(1, TimeUnit.SECONDS))
        cancelled.set(true)
        bridge.allowBeginReturn.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(bridge.recognizeStarted.await(0, TimeUnit.MILLISECONDS))
        assertTrue(bridge.cancelCalled.await(0, TimeUnit.MILLISECONDS))
        assertEquals(OcrEngineErrorCode.CANCELLED, failure.get()?.code)
        engine.close()
    }

    private class BeginBarrierBridge : NativeOcrBridge {
        val beginStarted = CountDownLatch(1)
        val allowBeginReturn = CountDownLatch(1)
        val recognizeStarted = CountDownLatch(1)
        val cancelCalled = CountDownLatch(1)

        override fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long = 1L

        override fun executionBackend(handle: Long): String = OcrExecutionBackend.CPU.name

        override fun beginRecognition(handle: Long) {
            beginStarted.countDown()
            check(allowBeginReturn.await(1, TimeUnit.SECONDS))
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
            recognizeStarted.countDown()
            check(cancelCalled.await(1, TimeUnit.SECONDS))
            throw OcrEngineException(OcrEngineErrorCode.CANCELLED)
        }

        override fun cancel(handle: Long) {
            cancelCalled.countDown()
        }

        override fun destroy(handle: Long) = Unit
    }
}
