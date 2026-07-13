package rs.masumi.app.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private class RecordingBridge : NativeOcrBridge {
        var recognizeCalls = 0

        override fun create(modelPath: String, projectorPath: String): Long = 1L

        override fun recognize(handle: Long, requestJson: String, rgb: ByteArray): String {
            recognizeCalls += 1
            return "{}"
        }

        override fun cancel(handle: Long) = Unit

        override fun destroy(handle: Long) = Unit
    }
}
