package rs.masumi.app.ocr

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test
import rs.masumi.core.ocr.OcrExecutionBackend

class OcrEngineSessionCacheTest {
    @Test
    fun `reuses a healthy engine for the same model package`() {
        val opened = mutableListOf<FakeEngine>()
        val cache = OcrEngineSessionCache(
            OcrEngineFactory { _, _ -> FakeEngine().also(opened::add) },
        )

        val first = cache.open(MODEL, PROJECTOR)
        first.close()
        val second = cache.open(MODEL, PROJECTOR)

        assertEquals(1, opened.size)
        second.recognize(REQUEST) { false }
        assertEquals(1, opened.single().recognitionCount)
        second.close()
        cache.close()
        assertEquals(1, opened.single().closeCount)
    }

    @Test
    fun `discards a cancelled engine`() {
        val opened = mutableListOf<FakeEngine>()
        val cache = OcrEngineSessionCache(
            OcrEngineFactory { _, _ -> FakeEngine().also(opened::add) },
        )

        cache.open(MODEL, PROJECTOR).use { it.cancel() }
        val replacement = cache.open(MODEL, PROJECTOR)

        assertEquals(2, opened.size)
        replacement.recognize(REQUEST) { false }
        assertEquals(0, opened.first().recognitionCount)
        assertEquals(1, opened.last().recognitionCount)
        assertEquals(1, opened.first().closeCount)
        replacement.close()
        cache.close()
    }

    @Test
    fun `closing while leased closes the engine when returned`() {
        val engine = FakeEngine()
        val cache = OcrEngineSessionCache(OcrEngineFactory { _, _ -> engine })
        val lease = cache.open(MODEL, PROJECTOR)

        cache.close()
        assertEquals(0, engine.closeCount)
        lease.close()
        assertEquals(1, engine.closeCount)
    }

    private class FakeEngine : OcrEngine {
        var closeCount = 0
        var recognitionCount = 0

        override val executionBackend = OcrExecutionBackend.CPU

        override fun recognize(
            request: OcrEngineRequest,
            cancellation: () -> Boolean,
        ): OcrEngineResult {
            recognitionCount += 1
            return RESULT
        }

        override fun cancel() = Unit

        override fun close() {
            closeCount += 1
        }
    }

    companion object {
        private val MODEL = Path.of("/models/ocr.gguf")
        private val PROJECTOR = Path.of("/models/mmproj.gguf")
        private val REQUEST = OcrEngineRequest(
            rgb = byteArrayOf(0, 0, 0),
            width = 1,
            height = 1,
            prompt = "ocr",
            maximumGeneratedTokens = 1,
            repetitionPenalty = 1.0,
        )
        private val RESULT = OcrEngineResult(
            rawText = "",
            tokenIds = emptyList(),
            tokenProbabilities = emptyList(),
            sourceWidth = 1,
            sourceHeight = 1,
            processedWidth = 1,
            processedHeight = 1,
            visualTokenCount = 1,
            generatedTokenCount = 0,
            reachedEos = true,
            truncated = false,
            repetitionStopped = false,
            promptEvaluationMillis = 0,
            generationMillis = 0,
        )
    }
}
