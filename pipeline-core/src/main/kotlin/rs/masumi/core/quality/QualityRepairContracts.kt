package rs.masumi.core.quality

import kotlinx.serialization.Serializable
import rs.masumi.core.typesetting.TypesettingPolicy

@Serializable
enum class QualityRepairStage { TYPESETTING }

@Serializable
data class QualityRepairPolicy(
    val revision: String = "quality-directed-typesetting-repair-v1",
    val maximumAttempts: Int = 1,
    val fontWeight: Int = 700,
    val bubbleInsetFraction: Double = 0.22,
    val minimumBubbleInsetFraction: Double = 0.08,
    val freeTextExpansionFraction: Double = 0.04,
    val freeTextStrokeEm: Double = 0.07,
) {
    init {
        require(revision.isNotBlank())
        require(maximumAttempts in 1..3)
        require(fontWeight in 100..900)
        require(bubbleInsetFraction in 0.0..0.4)
        require(minimumBubbleInsetFraction in 0.0..bubbleInsetFraction)
        require(freeTextExpansionFraction in 0.0..0.5)
        require(freeTextStrokeEm in 0.0..0.5)
    }
}

@Serializable
data class QualityRepairPage(
    val pageOrder: Int,
    val stage: QualityRepairStage,
    val issueCodes: List<QualityIssueCode>,
) {
    init {
        require(pageOrder >= 0)
        require(issueCodes.isNotEmpty())
        require(issueCodes == issueCodes.distinct().sortedBy(QualityIssueCode::name))
    }
}

@Serializable
data class QualityRepairPlan(
    val policyRevision: String,
    val sourceQualityRunArtifactKey: String,
    val sourceTypesettingRunArtifactKey: String,
    val attempt: Int,
    val pages: List<QualityRepairPage>,
    val exhausted: Boolean,
) {
    init {
        require(policyRevision.isNotBlank())
        require(SHA256.matches(sourceQualityRunArtifactKey))
        require(SHA256.matches(sourceTypesettingRunArtifactKey))
        require(attempt >= 1)
        require(pages.map(QualityRepairPage::pageOrder) == pages.map(QualityRepairPage::pageOrder).distinct().sorted())
        require(!exhausted || pages.isEmpty())
    }

    fun repairPageOrders(): Set<Int> = pages.mapTo(linkedSetOf(), QualityRepairPage::pageOrder)

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

object QualityRepairPlanner {
    private val repairableTypesettingIssues = setOf(
        QualityIssueCode.RENDERED_DIMENSION_MISMATCH,
        QualityIssueCode.LAYOUT_OUT_OF_BOUNDS,
        QualityIssueCode.TYPESET_PIXELS_MISSING,
        QualityIssueCode.CHANGED_PIXELS_OUTSIDE_LAYOUT,
    )

    fun plan(
        qualityRunArtifactKey: String,
        typesettingRunArtifactKey: String,
        typesettingPolicy: TypesettingPolicy,
        pageArtifacts: List<PageQualityArtifact>,
        policy: QualityRepairPolicy = QualityRepairPolicy(),
    ): QualityRepairPlan {
        val previousAttempts = repairAttempt(typesettingPolicy.revision, policy.revision)
        val nextAttempt = previousAttempts + 1
        if (nextAttempt > policy.maximumAttempts) {
            return QualityRepairPlan(
                policy.revision,
                qualityRunArtifactKey,
                typesettingRunArtifactKey,
                nextAttempt,
                emptyList(),
                exhausted = true,
            )
        }
        val pages = pageArtifacts
            .filter { it.verdict == QualityPageVerdict.BLOCKED }
            .mapNotNull { page ->
                val blocking = page.issues.filter { it.severity == QualitySeverity.BLOCKING }.map(QualityIssue::code)
                if (blocking.isEmpty() || blocking.any { it !in repairableTypesettingIssues }) return@mapNotNull null
                QualityRepairPage(
                    pageOrder = page.pageOrder,
                    stage = QualityRepairStage.TYPESETTING,
                    issueCodes = blocking.distinct().sortedBy(QualityIssueCode::name),
                )
            }
            .sortedBy(QualityRepairPage::pageOrder)
        return QualityRepairPlan(
            policy.revision,
            qualityRunArtifactKey,
            typesettingRunArtifactKey,
            nextAttempt,
            pages,
            exhausted = false,
        )
    }

    fun repairTypesettingPolicy(
        base: TypesettingPolicy,
        attempt: Int,
        policy: QualityRepairPolicy = QualityRepairPolicy(),
    ): TypesettingPolicy {
        require(attempt in 1..policy.maximumAttempts)
        return base.copy(
            revision = "${base.revision}|${policy.revision}-$attempt",
            fontWeight = policy.fontWeight,
            bubbleInsetFraction = policy.bubbleInsetFraction,
            minimumBubbleInsetFraction = policy.minimumBubbleInsetFraction,
            freeTextExpansionFraction = policy.freeTextExpansionFraction,
            freeTextStrokeEm = policy.freeTextStrokeEm,
        )
    }

    private fun repairAttempt(revision: String, policyRevision: String): Int {
        val marker = "|$policyRevision-"
        val suffix = revision.substringAfterLast(marker, missingDelimiterValue = "")
        return suffix.toIntOrNull() ?: 0
    }
}
