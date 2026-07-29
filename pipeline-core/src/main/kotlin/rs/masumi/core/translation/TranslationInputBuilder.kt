package rs.masumi.core.translation

import rs.masumi.core.ocr.OcrRegionArtifact
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.ocr.OcrSemanticStatus
import rs.masumi.core.ocr.PageOcrArtifact

class TranslationInputBuilder(
    private val policy: TranslationPolicy = TranslationPolicy(),
    private val prompt: TranslationPromptRef = TranslationPromptRef(),
) {
    fun build(pageOrder: Int, page: PageOcrArtifact): PageTranslationInput {
        require(pageOrder >= 0) { "pageOrder must not be negative" }
        val terminalRegions = page.regions.sortedBy { it.candidate.readingOrderRank }
        require(terminalRegions.none { it.state == OcrRegionState.PENDING || it.state == OcrRegionState.RUNNING }) {
            "translation input requires terminal OCR regions"
        }
        return PageTranslationInput(
            pageId = page.pageId,
            pageOrder = pageOrder,
            ocrPageArtifactKey = page.pageArtifactKey,
            policy = policy,
            prompt = prompt,
            items = terminalRegions.mapNotNull { region -> recognizedItem(page, region) },
            protectedRegions = terminalRegions.mapNotNull(::protectedRegion),
        )
    }

    private fun recognizedItem(page: PageOcrArtifact, region: OcrRegionArtifact): TranslationInputItem? {
        if (region.state != OcrRegionState.RECOGNIZED) return null
        val selectedIndex = requireNotNull(region.selectedAttemptIndex) {
            "recognized OCR region requires a selected attempt"
        }
        val selected = region.attempts.getOrNull(selectedIndex)
            ?: throw IllegalArgumentException("selected OCR attempt is out of range")
        require(selected.normalizedText.isNotBlank()) { "recognized OCR region requires non-blank text" }
        if (!TranslationSourceText.isTranslationCandidate(selected.normalizedText)) return null
        return TranslationInputItem(
            translationRegionId = TranslationIdentity.regionId(
                ocrPageArtifactKey = page.pageArtifactKey,
                ocrRegionId = region.candidate.ocrRegionId,
                policy = policy,
                prompt = prompt,
            ),
            ocrRegionId = region.candidate.ocrRegionId,
            readingOrderRank = region.candidate.readingOrderRank,
            sourceText = selected.normalizedText,
            roleHint = if (region.candidate.semanticStatus == OcrSemanticStatus.REQUIRED_TEXT) {
                TranslationRoleHint.DIALOGUE
            } else {
                TranslationRoleHint.CLASSIFY_FREE_TEXT
            },
        )
    }

    private fun protectedRegion(region: OcrRegionArtifact): ProtectedTranslationRegion? = when (region.state) {
        OcrRegionState.NEEDS_FALLBACK,
        OcrRegionState.PRESERVED_SOURCE,
        -> ProtectedTranslationRegion(
            ocrRegionId = region.candidate.ocrRegionId,
            readingOrderRank = region.candidate.readingOrderRank,
            reason = TranslationProtectionReason.OCR_NOT_TRUSTED,
        )
        OcrRegionState.RECOGNIZED,
        OcrRegionState.NO_TEXT_CONFIRMED,
        -> null
        OcrRegionState.PENDING,
        OcrRegionState.RUNNING,
        -> error("active OCR state passed terminal-state validation")
    }
}
