package rs.masumi.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectLaunchModeTest {
    @Test
    fun `opening management details never resumes a paused pipeline`() {
        assertTrue(ProjectLaunchMode.DETAILS.showDetails)
        assertFalse(ProjectLaunchMode.DETAILS.autoContinue)
    }

    @Test
    fun `the dedicated continue action still resumes processing`() {
        assertFalse(ProjectLaunchMode.CONTINUE.showDetails)
        assertTrue(ProjectLaunchMode.CONTINUE.autoContinue)
    }
}
