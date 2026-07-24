package rs.masumi.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import rs.masumi.core.typesetting.TypesettingFixtures

class QualityEvaluatorTest {
    @Test
    fun `verified pixels inside every region pass`() {
        val typesetting = TypesettingFixtures.artifact("flattened-png".toByteArray())
        val result = evaluate(
            typesetting,
            QualityPixelAudit(100, 200, 50, 0, mapOf("1".repeat(64) to 50)),
        )

        assertEquals(QualityPageVerdict.PASS, result.verdict)
        assertEquals(1, result.verifiedTypesetRegionCount)
    }

    @Test
    fun `missing region pixels block the page`() {
        val typesetting = TypesettingFixtures.artifact("flattened-png".toByteArray())
        val result = evaluate(typesetting, QualityPixelAudit(100, 200, 0, 0, emptyMap()))

        assertEquals(QualityPageVerdict.BLOCKED, result.verdict)
        assertEquals(listOf(QualityIssueCode.TYPESET_PIXELS_MISSING), result.issues.map { it.code })
    }

    @Test
    fun `changes outside layout above tolerance block the page`() {
        val typesetting = TypesettingFixtures.artifact("flattened-png".toByteArray())
        val result = evaluate(
            typesetting,
            QualityPixelAudit(100, 200, 90, 33, mapOf("1".repeat(64) to 57)),
        )

        assertEquals(QualityPageVerdict.BLOCKED, result.verdict)
        assertEquals(QualityIssueCode.CHANGED_PIXELS_OUTSIDE_LAYOUT, result.issues.single().code)
    }

    @Test
    fun `preserved page is warning only`() {
        val result = QualityEvaluator.evaluate(
            pageId = "c".repeat(64),
            pageOrder = 0,
            sourceSha256 = "c".repeat(64),
            typesettingPageArtifactKey = "d".repeat(64),
            pageArtifactKey = "e".repeat(64),
            dependencies = QualityFixtures.dependencies,
            typesetting = null,
            pixelAudit = null,
        )

        assertEquals(QualityPageVerdict.PASS_WITH_WARNINGS, result.verdict)
        assertEquals(QualityIssueCode.PAGE_PRESERVED_CLEANED, result.issues.single().code)
    }

    private fun evaluate(
        typesetting: rs.masumi.core.typesetting.PageTypesettingArtifact,
        audit: QualityPixelAudit,
    ): PageQualityArtifact = QualityEvaluator.evaluate(
        pageId = "c".repeat(64),
        pageOrder = 0,
        sourceSha256 = "c".repeat(64),
        typesettingPageArtifactKey = "d".repeat(64),
        pageArtifactKey = "e".repeat(64),
        dependencies = QualityFixtures.dependencies,
        typesetting = typesetting,
        pixelAudit = audit,
    )
}
