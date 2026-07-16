package rs.masumi.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals

class QualityJobReducerTest {
    @Test
    fun `recovery preserves committed pages and resets only active page`() {
        val second = QualityFixtures.job().pages.single().copy(
            pageId = "9".repeat(64),
            pageOrder = 1,
            sourceSha256 = "9".repeat(64),
            typesettingPageArtifactKey = "8".repeat(64),
            pageArtifactKey = "7".repeat(64),
        )
        var job = QualityFixtures.job().copy(pages = QualityFixtures.job().pages + second)
        job = QualityJobReducer.start(job, 2L)
        job = QualityJobReducer.startPage(job, 0, 3L)
        job = QualityJobReducer.commitPage(job, 0, "page.json", QualityPageVerdict.PASS, 0, 0, 4L)
        job = QualityJobReducer.startPage(job, 1, 5L)

        val recovered = QualityJobReducer.recoverInterrupted(job, 6L)

        assertEquals(QualityPageState.COMMITTED, recovered.pages[0].state)
        assertEquals(QualityPageState.PENDING, recovered.pages[1].state)
        assertEquals(QualityJobStatus.QUEUED, recovered.status)
    }

    @Test
    fun `blocking page publishes a blocked terminal result`() {
        var job = QualityJobReducer.start(QualityFixtures.job(), 2L)
        job = QualityJobReducer.startPage(job, 0, 3L)
        job = QualityJobReducer.commitPage(job, 0, "page.json", QualityPageVerdict.BLOCKED, 0, 1, 4L)

        assertEquals(QualityJobStatus.BLOCKED, QualityJobReducer.finish(job, 5L).status)
    }
}
