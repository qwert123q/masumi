package rs.masumi.core.typesetting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypesettingRepairPlannerTest {
    @Test
    fun `only selected page renders while terminal siblings are reused`() {
        assertEquals(
            TypesettingRepairPageAction.RENDER,
            TypesettingRepairPlanner.pageAction(TypesettingPageState.COMMITTED, true),
        )
        assertEquals(
            TypesettingRepairPageAction.REUSE_COMMITTED,
            TypesettingRepairPlanner.pageAction(TypesettingPageState.COMMITTED, false),
        )
        assertEquals(
            TypesettingRepairPageAction.CARRY_PRESERVED,
            TypesettingRepairPlanner.pageAction(TypesettingPageState.PRESERVED_CLEANED_PAGE, false),
        )
    }

    @Test
    fun `non terminal reuse page is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TypesettingRepairPlanner.pageAction(TypesettingPageState.RUNNING, false)
        }
    }
}
