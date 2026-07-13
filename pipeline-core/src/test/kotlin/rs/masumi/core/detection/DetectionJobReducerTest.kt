package rs.masumi.core.detection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetectionJobReducerTest {
    @Test
    fun `first failure retries and second failure preserves source`() {
        val downloading = DetectionJobReducer.startModelDownload(job(), 2)
        val running = DetectionJobReducer.startRunning(downloading, 3)
        val firstAttempt = DetectionJobReducer.startPage(running, PAGE_ID, 4)
        val retry = DetectionJobReducer.recordRetry(firstAttempt, PAGE_ID, error(), 5)
        val secondAttempt = DetectionJobReducer.startPage(retry, PAGE_ID, 6)
        val preserved = DetectionJobReducer.preservePage(secondAttempt, PAGE_ID, error(), 7)
        val finished = DetectionJobReducer.finish(preserved, 8)

        assertEquals(DetectionPageState.PRESERVED_SOURCE, preserved.pages.single().state)
        assertEquals(2, preserved.pages.single().attemptCount)
        assertEquals(DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES, finished.status)
    }

    @Test
    fun `commits a page with relative artifact paths`() {
        val running = DetectionJobReducer.startRunning(
            DetectionJobReducer.startModelDownload(job(), 2),
            3,
        )
        val started = DetectionJobReducer.startPage(running, PAGE_ID, 4)
        val committed = DetectionJobReducer.commitPage(
            started,
            pageId = PAGE_ID,
            regionsPath = "pages/$PAGE_ID/regions.json",
            previewPaths = mapOf(0 to "previews/0000.png"),
            nowEpochMillis = 5,
        )
        val finished = DetectionJobReducer.finish(committed, 6)

        assertEquals(DetectionPageState.COMMITTED, committed.pages.single().state)
        assertEquals("previews/0000.png", committed.pages.single().previewPath)
        assertEquals(DetectionJobStatus.SUCCEEDED, finished.status)
    }

    @Test
    fun `recovery resets running pages and retains committed pages`() {
        val twoPages = job().copy(
            pages = listOf(
                page(order = 0, pageId = PAGE_ID, state = DetectionPageState.RUNNING, attempts = 1),
                page(
                    order = 1,
                    pageId = OTHER_PAGE_ID,
                    state = DetectionPageState.COMMITTED,
                    attempts = 1,
                    regionsPath = "pages/$OTHER_PAGE_ID/regions.json",
                    previewPath = "previews/0001.png",
                ),
            ),
            status = DetectionJobStatus.RUNNING,
        )

        val recovered = DetectionJobReducer.recoverInterrupted(twoPages, 10)

        assertEquals(DetectionJobStatus.QUEUED, recovered.status)
        assertEquals(DetectionPageState.PENDING, recovered.pages[0].state)
        assertEquals(1, recovered.pages[0].attemptCount)
        assertEquals(DetectionPageState.COMMITTED, recovered.pages[1].state)
    }

    @Test
    fun `cancel request prevents another page from starting at boundary`() {
        val running = DetectionJobReducer.startRunning(
            DetectionJobReducer.startModelDownload(job(), 2),
            3,
        )
        val cancelled = DetectionJobReducer.finishCancellation(
            DetectionJobReducer.requestCancel(running, 4),
            5,
        )

        assertTrue(cancelled.cancelRequested)
        assertEquals(DetectionJobStatus.CANCELLED, cancelled.status)
        assertFailsWith<IllegalArgumentException> {
            DetectionJobReducer.startPage(cancelled, PAGE_ID, 6)
        }
    }

    @Test
    fun `cancelled job resumes with committed page states retained`() {
        val running = DetectionJobReducer.startRunning(
            DetectionJobReducer.startModelDownload(job(), 2),
            3,
        )
        val committed = DetectionJobReducer.commitPage(
            DetectionJobReducer.startPage(running, PAGE_ID, 4),
            PAGE_ID,
            "pages/$PAGE_ID/regions.json",
            mapOf(0 to "previews/0000.png"),
            5,
        )
        val cancelled = DetectionJobReducer.finishCancellation(
            DetectionJobReducer.requestCancel(committed, 6),
            7,
        )

        val resumed = DetectionJobReducer.resumeCancelled(cancelled, 8)

        assertEquals(DetectionJobStatus.QUEUED, resumed.status)
        assertFalse(resumed.cancelRequested)
        assertEquals(DetectionPageState.COMMITTED, resumed.pages.single().state)
    }

    @Test
    fun `illegal transitions do not mutate job`() {
        val queued = job()

        assertFailsWith<IllegalArgumentException> {
            DetectionJobReducer.startPage(queued, PAGE_ID, 2)
        }
        assertFalse(queued.cancelRequested)
        assertEquals(DetectionJobStatus.QUEUED, queued.status)
    }

    private fun job(): DetectionJobRecord = DetectionJobRecord(
        jobId = "job-1",
        projectId = "project-1",
        runArtifactKey = "f".repeat(64),
        startedAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        model = model(),
        preprocessing = DetectionPreprocessingConfig(),
        thresholds = DetectionThresholdConfig(),
        pages = listOf(page(0, PAGE_ID)),
    )

    private fun page(
        order: Int,
        pageId: String,
        state: DetectionPageState = DetectionPageState.PENDING,
        attempts: Int = 0,
        regionsPath: String? = null,
        previewPath: String? = null,
    ): DetectionJobPage = DetectionJobPage(
        order = order,
        pageId = pageId,
        pageArtifactKey = if (order == 0) "b".repeat(64) else "d".repeat(64),
        state = state,
        attemptCount = attempts,
        regionsPath = regionsPath,
        previewPath = previewPath,
    )

    private fun model(): DetectorModelRef = DetectorModelRef(
        modelId = "public/model",
        repository = "public/model",
        revision = "revision-1",
        fileName = "model.onnx",
        sha256 = "c".repeat(64),
        byteLength = 100,
        license = "Apache-2.0",
        opset = 18,
        runtimeRevision = "runtime:1",
    )

    private fun error() = DetectionError("PAGE_INFERENCE_FAILED", "Page inference failed")

    private companion object {
        val PAGE_ID = "a".repeat(64)
        val OTHER_PAGE_ID = "e".repeat(64)
    }
}
