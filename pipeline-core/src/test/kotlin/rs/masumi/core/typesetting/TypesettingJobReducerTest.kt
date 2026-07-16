package rs.masumi.core.typesetting

import kotlin.test.Test
import kotlin.test.assertEquals

class TypesettingJobReducerTest {
    @Test
    fun `recovery retains committed pages and resets only active page`() {
        var job = TypesettingFixtures.job().copy(
            pages = TypesettingFixtures.job().pages + TypesettingFixtures.job().pages.single().copy(
                pageId = "9".repeat(64),
                pageOrder = 1,
                sourceSha256 = "9".repeat(64),
                cleanupPageArtifactKey = "8".repeat(64),
                pageArtifactKey = "7".repeat(64),
            ),
        )
        job = TypesettingJobReducer.start(job, 2L)
        job = TypesettingJobReducer.startPage(job, 0, 3L)
        job = TypesettingJobReducer.commitPage(job, 0, "page.json", "page.png", 1, 0, 4L)
        job = TypesettingJobReducer.startPage(job, 1, 5L)

        val recovered = TypesettingJobReducer.recoverInterrupted(job, 6L)

        assertEquals(TypesettingPageState.COMMITTED, recovered.pages[0].state)
        assertEquals(TypesettingPageState.PENDING, recovered.pages[1].state)
        assertEquals(TypesettingJobStatus.QUEUED, recovered.status)
    }

    @Test
    fun `unfittable region still produces a successful protected run`() {
        var job = TypesettingJobReducer.start(TypesettingFixtures.job(), 2L)
        job = TypesettingJobReducer.startPage(job, 0, 3L)
        job = TypesettingJobReducer.commitPage(job, 0, "page.json", "page.png", 0, 1, 4L)

        val terminal = TypesettingJobReducer.finishSuccess(job, 5L)

        assertEquals(TypesettingJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS, terminal.status)
    }
}
