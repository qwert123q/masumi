package rs.masumi.app.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineProcessDeathTrackerTest {
    @Test
    fun `retries one process death then pauses`() {
        val tracker = PipelineProcessDeathTracker(retriesBeforePause = 1)

        assertFalse(tracker.record("project"))
        assertTrue(tracker.record("project"))
    }

    @Test
    fun `successful progress resets the death count`() {
        val tracker = PipelineProcessDeathTracker(retriesBeforePause = 1)

        assertFalse(tracker.record("project"))
        tracker.clear("project")
        assertFalse(tracker.record("project"))
    }
}
