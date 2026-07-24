package rs.masumi.core.cleanup

import kotlin.test.Test
import kotlin.test.assertEquals

class CleanupJobReducerTest {
    @Test
    fun `recovery keeps committed pages and resets only active page`() {
        val base = CleanupFixtures.job()
        val second = base.pages.single().copy(pageOrder = 1, pageArtifactKey = "f".repeat(64), state = CleanupPageState.RUNNING)
        val interrupted = base.copy(
            status = CleanupJobStatus.RUNNING,
            pages = listOf(
                base.pages.single().copy(
                    state = CleanupPageState.COMMITTED,
                    artifactPath = "pages/first/cleanup.json",
                    imagePath = "pages/first/cleaned.png",
                ),
                second,
            ),
        )

        val recovered = CleanupJobReducer.recoverInterrupted(interrupted, 2L)

        assertEquals(CleanupPageState.COMMITTED, recovered.pages[0].state)
        assertEquals(CleanupPageState.PENDING, recovered.pages[1].state)
        assertEquals(CleanupJobStatus.QUEUED, recovered.status)
    }

    @Test
    fun `unsafe region still finishes the run successfully with preservation`() {
        var job = CleanupJobReducer.start(CleanupFixtures.job(), 2L)
        job = CleanupJobReducer.startPage(job, 0, 3L)
        job = CleanupJobReducer.commitPage(job, 0, "cleanup.json", "cleaned.png", 0, 1, 4L)
        job = CleanupJobReducer.finishSuccess(job, 5L)

        assertEquals(CleanupJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS, job.status)
    }
}
