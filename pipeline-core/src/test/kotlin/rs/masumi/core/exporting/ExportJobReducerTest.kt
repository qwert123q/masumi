package rs.masumi.core.exporting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExportJobReducerTest {
    @Test
    fun `cancellation and failure recovery keep committed pages and reset only active page`() {
        var job = ExportFixtures.job().copy(
            pages = ExportFixtures.job().pages + ExportFixtures.job().pages.single().copy(
                pageId = "7".repeat(64),
                pageOrder = 1,
                typesettingPageArtifactKey = "9".repeat(64),
                outputName = "0002.png",
            ),
        )
        job = ExportJobReducer.start(job, 2L)
        job = ExportJobReducer.startPage(job, 0, 3L)
        job = ExportJobReducer.commitPage(job, 0, 12L, false, 4L)
        job = ExportJobReducer.startPage(job, 1, 5L)
        job = ExportJobReducer.fail(job, ExportError("DESTINATION_WRITE_FAILED"), 6L)

        val recovered = ExportJobReducer.recover(job, 7L)

        assertEquals(ExportPageState.COMMITTED, recovered.pages[0].state)
        assertEquals(ExportPageState.PENDING, recovered.pages[1].state)
        assertEquals(ExportJobStatus.QUEUED, recovered.status)
        assertFalse(recovered.cancelRequested)
    }

    @Test
    fun `successful report boundary requires every page committed`() {
        var job = ExportJobReducer.start(ExportFixtures.job(), 2L)
        job = ExportJobReducer.startPage(job, 0, 3L)
        job = ExportJobReducer.commitPage(job, 0, 12L, true, 4L)

        val finished = ExportJobReducer.finishSuccess(job, 5L)

        assertEquals(ExportJobStatus.SUCCEEDED, finished.status)
        assertEquals(true, finished.pages.single().reusedExisting)
    }
}
