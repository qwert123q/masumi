package rs.masumi.app.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.app.pipeline.PipelineQueueStatus

class LibraryProjectPresentationTest {
    @Test
    fun `old output does not make an actively reprocessed project readable`() {
        assertFalse(isFinishedLibraryProject(102, PipelineQueueStatus.ACTIVE))
    }

    @Test
    fun `paused reprocessing still belongs to the processing shelf`() {
        assertFalse(isFinishedLibraryProject(102, PipelineQueueStatus.PAUSED))
    }

    @Test
    fun `completed output returns to the finished shelf after queue removal`() {
        assertTrue(isFinishedLibraryProject(102, null))
    }
}
