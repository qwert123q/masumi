package rs.masumi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageNavigationTest {
    @Test
    fun restoredDetailsPageIsPreserved() {
        assertEquals(PageNavigation.DETAILS, PageNavigation.normalize(PageNavigation.DETAILS))
    }

    @Test
    fun invalidOrMissingPageFallsBackToWorkspace() {
        assertEquals(PageNavigation.WORKSPACE, PageNavigation.normalize(null))
        assertEquals(PageNavigation.WORKSPACE, PageNavigation.normalize(-1))
        assertEquals(PageNavigation.WORKSPACE, PageNavigation.normalize(99))
    }

    @Test
    fun backReturnsFromDetailsBeforeLeavingWorkspace() {
        assertEquals(
            PageNavigation.WORKSPACE,
            PageNavigation.backDestination(PageNavigation.DETAILS),
        )
        assertNull(PageNavigation.backDestination(PageNavigation.WORKSPACE))
    }
}
