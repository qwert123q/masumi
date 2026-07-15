package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.ocr.OcrCandidate
import rs.masumi.core.ocr.OcrFixtures
import rs.masumi.core.ocr.OcrProtectionPolicy
import rs.masumi.core.ocr.OcrRegionArtifact
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.ocr.OcrSemanticStatus

class TranslationInputBuilderTest {
    @Test
    fun `recognized text becomes ordered input while uncertain OCR stays protected`() {
        val base = OcrFixtures.pageArtifact()
        val free = base.regions.single().copy(
            candidate = candidate("free", 0, OcrSemanticStatus.UNRESOLVED_FREE_TEXT),
            attempts = listOf(OcrFixtures.attempt(normalizedText = "ナレーション")),
        )
        val dialogue = base.regions.single().copy(
            candidate = candidate("dialogue", 1, OcrSemanticStatus.REQUIRED_TEXT),
            attempts = listOf(OcrFixtures.attempt(normalizedText = "台詞")),
        )
        val protected = OcrRegionArtifact(
            candidate = candidate("uncertain", 2, OcrSemanticStatus.UNRESOLVED_FREE_TEXT),
            attempts = listOf(OcrFixtures.attempt(normalizedText = "候補")),
            selectedAttemptIndex = 0,
            quality = null,
            state = OcrRegionState.NEEDS_FALLBACK,
        )
        val empty = protected.copy(
            candidate = candidate("empty", 3, OcrSemanticStatus.UNRESOLVED_FREE_TEXT),
            attempts = emptyList(),
            selectedAttemptIndex = null,
            state = OcrRegionState.NO_TEXT_CONFIRMED,
        )

        val input = TranslationInputBuilder().build(
            pageOrder = 4,
            page = base.copy(regions = listOf(empty, protected, dialogue, free)),
        )

        assertEquals(listOf("free", "dialogue"), input.items.map { it.ocrRegionId })
        assertEquals(
            listOf(TranslationRoleHint.CLASSIFY_FREE_TEXT, TranslationRoleHint.DIALOGUE),
            input.items.map { it.roleHint },
        )
        assertEquals(listOf("ナレーション", "台詞"), input.items.map { it.sourceText })
        assertEquals(listOf("uncertain"), input.protectedRegions.map { it.ocrRegionId })
        assertEquals(4, input.pageOrder)
    }

    @Test
    fun `active OCR cannot become translation input`() {
        val base = OcrFixtures.pageArtifact()

        assertFailsWith<IllegalArgumentException> {
            TranslationInputBuilder().build(
                pageOrder = 0,
                page = base.copy(regions = listOf(base.regions.single().copy(state = OcrRegionState.RUNNING))),
            )
        }
    }

    private fun candidate(id: String, rank: Int, semantic: OcrSemanticStatus): OcrCandidate =
        OcrFixtures.candidate().copy(
            ocrRegionId = id,
            sourceClass = if (semantic == OcrSemanticStatus.REQUIRED_TEXT) {
                DetectorClass.TEXT_IN_BUBBLE
            } else {
                DetectorClass.TEXT_FREE
            },
            semanticStatus = semantic,
            protectionPolicy = if (semantic == OcrSemanticStatus.REQUIRED_TEXT) {
                OcrProtectionPolicy.NONE
            } else {
                OcrProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED
            },
            readingOrderRank = rank,
        )
}
