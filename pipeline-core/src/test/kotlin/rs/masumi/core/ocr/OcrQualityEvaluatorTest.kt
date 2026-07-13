package rs.masumi.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals

class OcrQualityEvaluatorTest {
    @Test
    fun `normalization removes one fence converts newlines and applies NFC`() {
        val normalized = OcrQualityEvaluator().normalize("```text\r\n e\u0301\r\n```")

        assertEquals("é", normalized)
    }

    @Test
    fun `two agreeing valid attempts are recognized`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.4,
            attempts = listOf(attempt("今日は", 0.50), attempt("今日は", 0.48)),
        )

        assertEquals(OcrRegionState.RECOGNIZED, decision.state)
        assertEquals(0, decision.selectedAttemptIndex)
    }

    @Test
    fun `primary token probability at threshold is recognized`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.4,
            attempts = listOf(attempt("今日は", 0.55)),
        )

        assertEquals(OcrRegionState.RECOGNIZED, decision.state)
        assertEquals(0, decision.selectedAttemptIndex)
    }

    @Test
    fun `forced truncation is never recognized`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.9,
            attempts = listOf(attempt("長い文", 0.99, truncated = true)),
        )

        assertEquals(OcrRegionState.NEEDS_FALLBACK, decision.state)
    }

    @Test
    fun `pathological repetition is never recognized`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.9,
            attempts = listOf(attempt("あ".repeat(20), 0.99, repetitionStopped = true)),
        )

        assertEquals(OcrRegionState.NEEDS_FALLBACK, decision.state)
        assertEquals(true, decision.quality.repeatedUnit)
    }

    @Test
    fun `two clean empty attempts confirm no text`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.9,
            attempts = listOf(attempt("", 0.99), attempt("", 0.99)),
        )

        assertEquals(OcrRegionState.NO_TEXT_CONFIRMED, decision.state)
        assertEquals(null, decision.selectedAttemptIndex)
    }

    @Test
    fun `one empty attempt remains uncertain`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.9,
            attempts = listOf(attempt("", 0.99)),
        )

        assertEquals(OcrRegionState.NEEDS_FALLBACK, decision.state)
    }

    private fun attempt(
        text: String,
        probability: Double,
        truncated: Boolean = false,
        repetitionStopped: Boolean = false,
    ) = OcrFixtures.attempt(rawText = text, normalizedText = text).copy(
        tokenIds = if (text.isEmpty()) emptyList() else listOf(1),
        tokenProbabilities = if (text.isEmpty()) emptyList() else listOf(probability),
        generatedTokenCount = if (text.isEmpty()) 0 else 1,
        truncated = truncated,
        repetitionStopped = repetitionStopped,
    )
}
