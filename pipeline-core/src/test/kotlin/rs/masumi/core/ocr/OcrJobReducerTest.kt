package rs.masumi.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrJobReducerTest {
    @Test
    fun `job follows model load region commit page commit and success graph`() {
        val downloading = OcrJobReducer.startModelDownload(job(), 2L)
        val loading = OcrJobReducer.startModelLoad(downloading, 3L)
        val running = OcrJobReducer.startRunning(loading, 4L)
        val started = OcrJobReducer.startRegion(running, PAGE_ID, REGION_ONE, 5L)
        val terminal = OcrJobReducer.commitTerminalRegion(
            started,
            PAGE_ID,
            REGION_ONE,
            OcrRegionState.RECOGNIZED,
            "pages/$PAGE_ID/regions/$REGION_ONE.json",
            null,
            6L,
        )
        val secondStarted = OcrJobReducer.startRegion(terminal, PAGE_ID, REGION_TWO, 7L)
        val preserved = OcrJobReducer.commitTerminalRegion(
            secondStarted,
            PAGE_ID,
            REGION_TWO,
            OcrRegionState.NEEDS_FALLBACK,
            "pages/$PAGE_ID/regions/$REGION_TWO.json",
            null,
            8L,
        )
        val committed = OcrJobReducer.commitPage(
            preserved,
            PAGE_ID,
            "pages/$PAGE_ID/ocr.json",
            mapOf(0 to "previews/0000.png"),
            9L,
        )
        val finished = OcrJobReducer.finishSuccess(committed, 10L)

        assertEquals(OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS, finished.status)
        assertEquals(OcrPageState.COMMITTED, finished.pages.single().state)
    }

    @Test
    fun `interruption repeats only the running region`() {
        val running = runningJob(
            OcrRegionCheckpoint(REGION_ONE, OcrRegionState.RECOGNIZED, 1, "pages/$PAGE_ID/regions/$REGION_ONE.json"),
            OcrRegionCheckpoint(REGION_TWO, OcrRegionState.RUNNING, 1),
            OcrRegionCheckpoint(REGION_THREE, OcrRegionState.PENDING),
        )

        val recovered = OcrJobReducer.recoverInterrupted(running, 200L)

        assertEquals(OcrJobStatus.QUEUED, recovered.status)
        assertEquals(OcrPageState.PENDING, recovered.pages.single().state)
        assertEquals(
            listOf(OcrRegionState.RECOGNIZED, OcrRegionState.PENDING, OcrRegionState.PENDING),
            recovered.pages.single().regions.map { it.state },
        )
        assertEquals(1, recovered.pages.single().regions[1].attemptCount)
    }

    @Test
    fun `cancellation resets active region but retains terminal checkpoints`() {
        val requested = OcrJobReducer.requestCancel(
            runningJob(
                OcrRegionCheckpoint(REGION_ONE, OcrRegionState.RECOGNIZED, 1, "pages/$PAGE_ID/regions/$REGION_ONE.json"),
                OcrRegionCheckpoint(REGION_TWO, OcrRegionState.RUNNING, 1),
            ),
            10L,
        )

        val cancelled = OcrJobReducer.finishCancellation(requested, 11L)
        val resumed = OcrJobReducer.resumeCancelled(cancelled, 12L)

        assertEquals(OcrJobStatus.CANCELLED, cancelled.status)
        assertEquals(OcrRegionState.RECOGNIZED, cancelled.pages.single().regions[0].state)
        assertEquals(OcrRegionState.PENDING, cancelled.pages.single().regions[1].state)
        assertEquals(OcrJobStatus.QUEUED, resumed.status)
        assertFalse(resumed.cancelRequested)
    }

    @Test
    fun `illegal transition leaves original job unchanged`() {
        val queued = job()

        assertFailsWith<IllegalArgumentException> {
            OcrJobReducer.startRegion(queued, PAGE_ID, REGION_ONE, 2L)
        }
        assertEquals(OcrJobStatus.QUEUED, queued.status)
        assertTrue(queued.pages.single().regions.all { it.state == OcrRegionState.PENDING })
    }

    @Test
    fun `empty candidate page can enter running state before atomic page commit`() {
        val running = runningJob().copy(
            pages = listOf(page(regions = emptyList())),
        )

        val started = OcrJobReducer.startEmptyPage(running, PAGE_ID, 5L)

        assertEquals(OcrPageState.RUNNING, started.pages.single().state)
        assertTrue(started.pages.single().regions.isEmpty())
    }

    private fun job(): OcrJobRecord = OcrJobRecord(
        jobId = "ocr-job-1",
        projectId = "project-1",
        detectionRunArtifactKey = "9".repeat(64),
        runArtifactKey = RUN_KEY,
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        dependencies = OcrFixtures.dependencies(),
        pages = listOf(page()),
    )

    private fun runningJob(vararg regions: OcrRegionCheckpoint): OcrJobRecord = job().copy(
        status = OcrJobStatus.RUNNING,
        pages = listOf(page(state = OcrPageState.RUNNING, regions = regions.toList())),
    )

    private fun page(
        state: OcrPageState = OcrPageState.PENDING,
        regions: List<OcrRegionCheckpoint> = listOf(
            OcrRegionCheckpoint(REGION_ONE),
            OcrRegionCheckpoint(REGION_TWO),
        ),
    ) = OcrJobPage(
        order = 0,
        pageId = PAGE_ID,
        sourceSha256 = PAGE_ID,
        detectionPageArtifactKey = DETECTION_KEY,
        pageArtifactKey = PAGE_KEY,
        state = state,
        regions = regions,
    )

    private companion object {
        val PAGE_ID = "a".repeat(64)
        val DETECTION_KEY = "b".repeat(64)
        val PAGE_KEY = "c".repeat(64)
        val RUN_KEY = "d".repeat(64)
        val REGION_ONE = "e".repeat(64)
        val REGION_TWO = "f".repeat(64)
        val REGION_THREE = "1".repeat(64)
    }
}
