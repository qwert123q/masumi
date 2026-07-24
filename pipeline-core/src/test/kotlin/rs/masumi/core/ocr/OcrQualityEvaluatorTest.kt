package rs.masumi.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import rs.masumi.core.detection.DetectorClass

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
            sourceClass = DetectorClass.TEXT_IN_BUBBLE,
            attempts = listOf(attempt("今日は", 0.50), attempt("今日は", 0.48)),
        )

        assertEquals(OcrRegionState.RECOGNIZED, decision.state)
        assertEquals(0, decision.selectedAttemptIndex)
    }

    @Test
    fun `primary token probability at threshold is recognized`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.4,
            sourceClass = DetectorClass.TEXT_IN_BUBBLE,
            attempts = listOf(attempt("今日は", 0.55)),
        )

        assertEquals(OcrRegionState.RECOGNIZED, decision.state)
        assertEquals(0, decision.selectedAttemptIndex)
    }

    @Test
    fun `forced truncation is never recognized`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.9,
            sourceClass = DetectorClass.TEXT_IN_BUBBLE,
            attempts = listOf(attempt("長い文", 0.99, truncated = true)),
        )

        assertEquals(OcrRegionState.NEEDS_FALLBACK, decision.state)
    }

    @Test
    fun `pathological repetition is never recognized`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.9,
            sourceClass = DetectorClass.TEXT_IN_BUBBLE,
            attempts = listOf(attempt("あ".repeat(20), 0.99, repetitionStopped = true)),
        )

        assertEquals(OcrRegionState.NEEDS_FALLBACK, decision.state)
        assertEquals(true, decision.quality.repeatedUnit)
    }

    @Test
    fun `two clean empty attempts confirm no text`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.9,
            sourceClass = DetectorClass.TEXT_IN_BUBBLE,
            attempts = listOf(attempt("", 0.99), attempt("", 0.99)),
        )

        assertEquals(OcrRegionState.NO_TEXT_CONFIRMED, decision.state)
        assertEquals(null, decision.selectedAttemptIndex)
    }

    @Test
    fun `one empty attempt remains uncertain`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.9,
            sourceClass = DetectorClass.TEXT_IN_BUBBLE,
            attempts = listOf(attempt("", 0.99)),
        )

        assertEquals(OcrRegionState.NEEDS_FALLBACK, decision.state)
    }

    @Test
    fun `agreeing common-only hallucinations from low confidence free text are preserved`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.46,
            sourceClass = DetectorClass.TEXT_FREE,
            attempts = listOf(attempt("012", 0.50), attempt("012", 0.48)),
        )

        assertEquals(OcrRegionState.NEEDS_FALLBACK, decision.state)
        assertEquals("LOW_CONFIDENCE_FREE_TEXT_SCRIPT_MISMATCH", decision.quality.decisionReason)
    }

    @Test
    fun `low confidence free text with Japanese script can still pass agreement`() {
        val decision = OcrQualityEvaluator().evaluate(
            detectorConfidence = 0.46,
            sourceClass = DetectorClass.TEXT_FREE,
            attempts = listOf(attempt("なぜなら", 0.50), attempt("なぜなら", 0.48)),
        )

        assertEquals(OcrRegionState.RECOGNIZED, decision.state)
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
