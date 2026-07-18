package rs.masumi.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rs.masumi.core.typesetting.TypesettingPolicy

class QualityRepairPlannerTest {
    @Test
    fun `warnings never schedule repair`() {
        val plan = plan(
            QualityFixtures.artifact(QualityPageVerdict.PASS_WITH_WARNINGS),
            TypesettingPolicy(),
        )

        assertTrue(plan.pages.isEmpty())
        assertFalse(plan.exhausted)
    }

    @Test
    fun `blocking typesetting issues schedule only their page`() {
        val pass = QualityFixtures.artifact().copy(
            pageId = "7".repeat(64),
            pageOrder = 0,
            sourceSha256 = "7".repeat(64),
            typesettingPageArtifactKey = "6".repeat(64),
            pageArtifactKey = "5".repeat(64),
        )
        val blocked = QualityFixtures.artifact(QualityPageVerdict.BLOCKED).copy(pageOrder = 1)

        val plan = QualityRepairPlanner.plan(
            "3".repeat(64),
            "4".repeat(64),
            TypesettingPolicy(),
            listOf(pass, blocked),
        )

        assertEquals(setOf(1), plan.repairPageOrders())
        assertEquals(QualityRepairStage.TYPESETTING, plan.pages.single().stage)
        assertEquals(1, plan.attempt)
    }

    @Test
    fun `repair policy is conservative and identity visible`() {
        val policy = QualityRepairPlanner.repairTypesettingPolicy(TypesettingPolicy(), 1)

        assertTrue(policy.revision.contains("quality-directed-typesetting-repair-v1-1"))
        assertEquals(700, policy.fontWeight)
        assertEquals(0.04, policy.freeTextExpansionFraction)
    }

    @Test
    fun `lineage stops after the bounded attempt`() {
        val repaired = QualityRepairPlanner.repairTypesettingPolicy(TypesettingPolicy(), 1)
        val plan = plan(QualityFixtures.artifact(QualityPageVerdict.BLOCKED), repaired)

        assertTrue(plan.exhausted)
        assertTrue(plan.pages.isEmpty())
        assertEquals(2, plan.attempt)
    }

    private fun plan(page: PageQualityArtifact, policy: TypesettingPolicy): QualityRepairPlan =
        QualityRepairPlanner.plan(
            "3".repeat(64),
            "4".repeat(64),
            policy,
            listOf(page),
        )
}
