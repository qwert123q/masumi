package rs.masumi.core.quality

import rs.masumi.core.typesetting.PageTypesettingArtifact
import rs.masumi.core.typesetting.TypesettingRegionState

data class QualityPixelAudit(
    val renderedWidth: Int,
    val renderedHeight: Int,
    val actualChangedPixelCount: Int,
    val changedPixelsOutsideLayout: Int,
    val changedPixelsByOcrRegionId: Map<String, Int>,
) {
    init {
        require(renderedWidth > 0 && renderedHeight > 0)
        require(actualChangedPixelCount >= 0 && changedPixelsOutsideLayout >= 0)
        require(changedPixelsByOcrRegionId.values.all { it >= 0 })
    }
}

object QualityEvaluator {
    fun evaluate(
        pageId: String,
        pageOrder: Int,
        sourceSha256: String,
        typesettingPageArtifactKey: String,
        pageArtifactKey: String,
        dependencies: QualityDependencies,
        typesetting: PageTypesettingArtifact?,
        pixelAudit: QualityPixelAudit?,
    ): PageQualityArtifact {
        val issues = mutableListOf<QualityIssue>()
        if (typesetting == null) {
            issues += QualityIssue(
                code = QualityIssueCode.PAGE_PRESERVED_CLEANED,
                severity = QualitySeverity.WARNING,
            )
        } else {
            val audit = requireNotNull(pixelAudit)
            if (
                audit.renderedWidth != typesetting.visibleWidth ||
                audit.renderedHeight != typesetting.visibleHeight
            ) {
                issues += QualityIssue(
                    code = QualityIssueCode.RENDERED_DIMENSION_MISMATCH,
                    severity = QualitySeverity.BLOCKING,
                )
            }
            typesetting.regions.forEach { region ->
                if (region.state == TypesettingRegionState.PRESERVED_CLEANED_PAGE) {
                    issues += QualityIssue(
                        code = QualityIssueCode.REGION_PRESERVED,
                        severity = QualitySeverity.WARNING,
                        ocrRegionId = region.ocrRegionId,
                    )
                } else {
                    val box = requireNotNull(region.layoutBox)
                    if (
                        box.left < 0 || box.top < 0 || box.right > typesetting.visibleWidth ||
                        box.bottom > typesetting.visibleHeight || box.right <= box.left || box.bottom <= box.top
                    ) {
                        issues += QualityIssue(
                            code = QualityIssueCode.LAYOUT_OUT_OF_BOUNDS,
                            severity = QualitySeverity.BLOCKING,
                            ocrRegionId = region.ocrRegionId,
                        )
                    }
                    val changed = audit.changedPixelsByOcrRegionId[region.ocrRegionId] ?: 0
                    if (changed == 0) {
                        issues += QualityIssue(
                            code = QualityIssueCode.TYPESET_PIXELS_MISSING,
                            severity = QualitySeverity.BLOCKING,
                            ocrRegionId = region.ocrRegionId,
                            observedCount = 0,
                        )
                    }
                }
            }
            if (audit.changedPixelsOutsideLayout > dependencies.policy.maximumChangedPixelsOutsideLayout) {
                issues += QualityIssue(
                    code = QualityIssueCode.CHANGED_PIXELS_OUTSIDE_LAYOUT,
                    severity = QualitySeverity.BLOCKING,
                    observedCount = audit.changedPixelsOutsideLayout,
                )
            }
        }
        val blockingCount = issues.count { it.severity == QualitySeverity.BLOCKING }
        val warningCount = issues.count { it.severity == QualitySeverity.WARNING }
        val verdict = when {
            blockingCount > 0 -> QualityPageVerdict.BLOCKED
            warningCount > 0 -> QualityPageVerdict.PASS_WITH_WARNINGS
            else -> QualityPageVerdict.PASS
        }
        return PageQualityArtifact(
            pageId = pageId,
            pageOrder = pageOrder,
            sourceSha256 = sourceSha256,
            typesettingPageArtifactKey = typesettingPageArtifactKey,
            pageArtifactKey = pageArtifactKey,
            renderedImageSha256 = typesetting?.renderedImageSha256,
            dependencies = dependencies,
            actualChangedPixelCount = pixelAudit?.actualChangedPixelCount ?: 0,
            changedPixelsOutsideLayout = pixelAudit?.changedPixelsOutsideLayout ?: 0,
            verifiedTypesetRegionCount = typesetting?.regions?.count {
                it.state == TypesettingRegionState.TYPESET &&
                    (pixelAudit?.changedPixelsByOcrRegionId?.get(it.ocrRegionId) ?: 0) > 0
            } ?: 0,
            preservedRegionCount = typesetting?.regions?.count {
                it.state == TypesettingRegionState.PRESERVED_CLEANED_PAGE
            } ?: 0,
            issues = issues,
            verdict = verdict,
        )
    }
}
