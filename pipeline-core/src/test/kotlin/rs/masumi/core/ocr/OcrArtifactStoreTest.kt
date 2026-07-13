package rs.masumi.core.ocr

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OcrArtifactStoreTest {
    private lateinit var project: Path
    private lateinit var store: OcrArtifactStore

    @BeforeTest
    fun setUp() {
        project = Files.createTempDirectory("masumi-ocr-store")
        store = OcrArtifactStore(project)
    }

    @AfterTest
    fun tearDown() {
        project.toFile().deleteRecursively()
    }

    @Test
    fun `terminal checkpoint is durable before job state then run publishes atomically`() {
        var job = runningJob()
        store.writeJob(job)

        val firstArtifact = regionArtifact(REGION_ONE, OcrRegionState.RECOGNIZED)
        store.commitRegion(job, job.pages.single(), firstArtifact)
        assertTrue(checkpoint(job).resolve("pages/$PAGE_ID/regions/$REGION_ONE.json").exists())
        assertEquals(OcrRegionState.RUNNING, store.readJob(job.jobId)?.pages?.single()?.regions?.first()?.state)
        job = OcrJobReducer.commitTerminalRegion(
            job,
            PAGE_ID,
            REGION_ONE,
            firstArtifact.state,
            "pages/$PAGE_ID/regions/$REGION_ONE.json",
            null,
            5L,
        )
        store.writeJob(job)

        job = OcrJobReducer.startRegion(job, PAGE_ID, REGION_TWO, 6L)
        val secondArtifact = regionArtifact(REGION_TWO, OcrRegionState.NO_TEXT_CONFIRMED)
        store.commitRegion(job, job.pages.single(), secondArtifact)
        job = OcrJobReducer.commitTerminalRegion(
            job,
            PAGE_ID,
            REGION_TWO,
            secondArtifact.state,
            "pages/$PAGE_ID/regions/$REGION_TWO.json",
            null,
            7L,
        )
        store.writeJob(job)

        store.commitPage(job, pageArtifact(firstArtifact, secondArtifact), PREVIEW_BYTES, listOf(0))
        assertTrue(checkpoint(job).resolve("pages/$PAGE_ID/ocr.json").exists())
        assertTrue(checkpoint(job).resolve("previews/0000.png").exists())
        assertFalse(project.resolve("artifacts/ocr/${job.runArtifactKey}").exists())

        job = OcrJobReducer.commitPage(
            job,
            PAGE_ID,
            "pages/$PAGE_ID/ocr.json",
            mapOf(0 to "previews/0000.png"),
            8L,
        )
        val finished = OcrJobReducer.finishSuccess(job, 9L)
        val published = store.publishRun(finished, runArtifact(finished), report(finished))

        assertTrue(published.resolve("artifact.json").exists())
        assertTrue(published.resolve("report.json").exists())
        assertTrue(published.resolve("pages/$PAGE_ID/ocr.json").exists())
        assertFalse(checkpoint(job).exists())
        assertEquals(finished.runArtifactKey, store.readPublishedRun(finished.runArtifactKey)?.runArtifactKey)
    }

    @Test
    fun `invalid terminal checkpoint is rejected`() {
        val job = runningJob()
        val foreign = regionArtifact("1".repeat(64), OcrRegionState.RECOGNIZED)

        assertFailsWith<IllegalArgumentException> {
            store.commitRegion(job, job.pages.single(), foreign)
        }
        assertFalse(checkpoint(job).resolve("pages/$PAGE_ID/regions/${foreign.candidate.ocrRegionId}.json").exists())
    }

    @Test
    fun `duplicate page orders share OCR artifact and keep distinct previews`() {
        val base = runningJob()
        var job = base.copy(
            pages = listOf(base.pages.single(), base.pages.single().copy(order = 1)),
        )
        val first = regionArtifact(REGION_ONE, OcrRegionState.RECOGNIZED)
        store.commitRegion(job, job.pages.first(), first)
        job = OcrJobReducer.commitTerminalRegion(
            job,
            PAGE_ID,
            REGION_ONE,
            first.state,
            "pages/$PAGE_ID/regions/$REGION_ONE.json",
            null,
            5L,
        )
        job = OcrJobReducer.startRegion(job, PAGE_ID, REGION_TWO, 6L)
        val second = regionArtifact(REGION_TWO, OcrRegionState.NO_TEXT_CONFIRMED)
        store.commitRegion(job, job.pages.first(), second)
        job = OcrJobReducer.commitTerminalRegion(
            job,
            PAGE_ID,
            REGION_TWO,
            second.state,
            "pages/$PAGE_ID/regions/$REGION_TWO.json",
            null,
            7L,
        )

        store.commitPage(job, pageArtifact(first, second), PREVIEW_BYTES, listOf(0, 1))

        assertTrue(checkpoint(job).resolve("pages/$PAGE_ID/ocr.json").exists())
        assertTrue(checkpoint(job).resolve("previews/0000.png").exists())
        assertTrue(checkpoint(job).resolve("previews/0001.png").exists())
    }

    @Test
    fun `finds latest resumable OCR job`() {
        val older = runningJob().copy(jobId = "ocr-job-old", updatedAtEpochMillis = 10L)
        val newer = runningJob().copy(
            jobId = "ocr-job-new",
            updatedAtEpochMillis = 20L,
            status = OcrJobStatus.CANCELLED,
            cancelRequested = true,
        )
        store.writeJob(older)
        store.writeJob(newer)

        assertEquals("ocr-job-new", assertNotNull(store.findResumableJob()).jobId)
    }

    private fun runningJob(): OcrJobRecord = OcrJobRecord(
        jobId = "ocr-job-1",
        projectId = "project-1",
        detectionRunArtifactKey = "9".repeat(64),
        runArtifactKey = RUN_KEY,
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 4L,
        status = OcrJobStatus.RUNNING,
        dependencies = OcrFixtures.dependencies(),
        pages = listOf(
            OcrJobPage(
                order = 0,
                pageId = PAGE_ID,
                sourceSha256 = PAGE_ID,
                detectionPageArtifactKey = DETECTION_KEY,
                pageArtifactKey = PAGE_KEY,
                state = OcrPageState.RUNNING,
                regions = listOf(
                    OcrRegionCheckpoint(REGION_ONE, OcrRegionState.RUNNING, 1),
                    OcrRegionCheckpoint(REGION_TWO),
                ),
            ),
        ),
    )

    private fun regionArtifact(id: String, state: OcrRegionState): OcrRegionArtifact {
        val base = OcrFixtures.pageArtifact().regions.single()
        val hasText = state != OcrRegionState.NO_TEXT_CONFIRMED
        return base.copy(
            candidate = base.candidate.copy(ocrRegionId = id),
            attempts = if (hasText) base.attempts else listOf(
                OcrFixtures.attempt(rawText = "", normalizedText = ""),
                OcrFixtures.attempt(rawText = "", normalizedText = "").copy(strategy = OcrCropStrategy.TIGHT_TEXT),
            ),
            selectedAttemptIndex = if (hasText) 0 else null,
            state = state,
        )
    }

    private fun pageArtifact(vararg regions: OcrRegionArtifact): PageOcrArtifact =
        OcrFixtures.pageArtifact().copy(
            pageId = PAGE_ID,
            sourceSha256 = PAGE_ID,
            detectionPageArtifactKey = DETECTION_KEY,
            pageArtifactKey = PAGE_KEY,
            dependencies = OcrFixtures.dependencies(),
            regions = regions.toList(),
        )

    private fun runArtifact(job: OcrJobRecord): OcrRunArtifact = OcrRunArtifact(
        runArtifactKey = job.runArtifactKey,
        projectId = job.projectId,
        detectionRunArtifactKey = job.detectionRunArtifactKey,
        createdAtEpochMillis = 9L,
        dependencies = job.dependencies,
        entries = job.pages.map { page ->
            OcrRunEntry(
                order = page.order,
                pageId = page.pageId,
                sourceSha256 = page.sourceSha256,
                detectionPageArtifactKey = page.detectionPageArtifactKey,
                pageArtifactKey = page.pageArtifactKey,
                state = page.state,
                artifactPath = page.artifactPath,
                previewPath = page.previewPath,
                error = page.error,
            )
        },
    )

    private fun report(job: OcrJobRecord) = OcrReport(
        jobId = job.jobId,
        projectId = job.projectId,
        runArtifactKey = job.runArtifactKey,
        startedAtEpochMillis = job.startedAtEpochMillis,
        finishedAtEpochMillis = 9L,
        status = job.status,
        totalPageCount = 1,
        committedPageCount = 1,
        totalRegionCount = 2,
        recognizedRegionCount = 1,
        needsFallbackRegionCount = 0,
        noTextRegionCount = 1,
        preservedRegionCount = 0,
        retryCount = 0,
    )

    private fun checkpoint(job: OcrJobRecord): Path = project
        .resolve("staging/ocr")
        .resolve(job.jobId)
        .resolve(job.runArtifactKey)

    private companion object {
        val PAGE_ID = "a".repeat(64)
        val DETECTION_KEY = "b".repeat(64)
        val PAGE_KEY = "c".repeat(64)
        val RUN_KEY = "d".repeat(64)
        val REGION_ONE = "e".repeat(64)
        val REGION_TWO = "f".repeat(64)
        val PREVIEW_BYTES = byteArrayOf(1, 2, 3)
    }
}
