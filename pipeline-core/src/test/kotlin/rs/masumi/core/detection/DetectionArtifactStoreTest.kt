package rs.masumi.core.detection

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DetectionArtifactStoreTest {
    private lateinit var project: Path
    private lateinit var store: DetectionArtifactStore

    @BeforeTest
    fun setUp() {
        project = Files.createTempDirectory("masumi-detection-store")
        store = DetectionArtifactStore(project)
    }

    @AfterTest
    fun tearDown() {
        project.toFile().deleteRecursively()
    }

    @Test
    fun `checkpoints a page then atomically publishes a complete run`() {
        var job = runningJob()
        store.writeJob(job)
        store.writeJob(job.copy(updatedAtEpochMillis = 4))
        assertEquals(4, store.readJob(job.jobId)?.updatedAtEpochMillis)

        store.commitPage(job, pageArtifact(), PREVIEW_BYTES, listOf(0))
        val checkpoint = checkpoint(job)
        assertTrue(checkpoint.resolve("pages/$PAGE_ID/regions.json").exists())
        assertTrue(checkpoint.resolve("previews/0000.webp").exists())
        assertFalse(project.resolve("artifacts/detection/${job.runArtifactKey}").exists())

        job = DetectionJobReducer.commitPage(
            job,
            PAGE_ID,
            "pages/$PAGE_ID/regions.json",
            mapOf(0 to "previews/0000.webp"),
            5,
        )
        store.writeJob(job)
        assertTrue(store.validateCommittedPage(job, job.pages.single()))
        assertEquals(PAGE_ID, store.readCommittedPageArtifact(job, job.pages.single())?.pageId)
        val finished = DetectionJobReducer.finish(job, 6)
        val published = store.publishRun(finished, runArtifact(finished), report(finished))

        assertTrue(published.resolve("artifact.json").exists())
        assertTrue(published.resolve("report.json").exists())
        assertTrue(published.resolve("pages/$PAGE_ID/regions.json").exists())
        assertTrue(published.resolve("previews/0000.webp").exists())
        assertFalse(checkpoint.exists())
        assertEquals(finished.runArtifactKey, store.readPublishedRun(finished.runArtifactKey)?.runArtifactKey)
        assertEquals(finished.jobId, store.readPublishedReport(finished.runArtifactKey)?.jobId)
    }

    @Test
    fun `recovery cleans only interrupted page data owned by the job`() {
        val job = runningJob()
        val foreign = project.resolve("staging/detection/other-job/${job.runArtifactKey}")
        Files.createDirectories(foreign)
        Files.write(foreign.resolve("owner.marker"), byteArrayOf(1))
        store.commitPage(job, pageArtifact(), PREVIEW_BYTES, listOf(0))

        store.cleanInterruptedPage(job, job.pages.single())

        assertFalse(checkpoint(job).resolve("pages/$PAGE_ID").exists())
        assertFalse(checkpoint(job).resolve("previews/0000.webp").exists())
        assertTrue(foreign.resolve("owner.marker").exists())
    }

    @Test
    fun `finds the most recently updated resumable job`() {
        val older = runningJob().copy(jobId = "job-older", updatedAtEpochMillis = 10)
        val newer = runningJob().copy(
            jobId = "job-newer",
            updatedAtEpochMillis = 20,
            status = DetectionJobStatus.CANCELLED,
            cancelRequested = true,
        )
        store.writeJob(older)
        store.writeJob(newer)

        assertEquals("job-newer", assertNotNull(store.findResumableJob()).jobId)
    }

    @Test
    fun `finds the latest terminal job for durable status display`() {
        val older = runningJob().copy(jobId = "job-older", updatedAtEpochMillis = 10)
        val failed = runningJob().copy(
            jobId = "job-failed",
            updatedAtEpochMillis = 20,
            status = DetectionJobStatus.FAILED,
            error = DetectionError("MODEL_UNAVAILABLE", "Detector model was unavailable"),
        )
        store.writeJob(older)
        store.writeJob(failed)

        assertEquals("job-failed", assertNotNull(store.findLatestJob()).jobId)
        assertEquals("job-older", assertNotNull(store.findResumableJob()).jobId)
    }

    @Test
    fun `rejects a page artifact without all model queries`() {
        val job = runningJob()

        assertFailsWith<IllegalArgumentException> {
            store.commitPage(
                job,
                pageArtifact().copy(rawQueries = rawQueries().dropLast(1)),
                PREVIEW_BYTES,
                listOf(0),
            )
        }
    }

    @Test
    fun `does not accept a published run with a missing report`() {
        var job = runningJob()
        store.commitPage(job, pageArtifact(), PREVIEW_BYTES, listOf(0))
        job = DetectionJobReducer.commitPage(
            job,
            PAGE_ID,
            "pages/$PAGE_ID/regions.json",
            mapOf(0 to "previews/0000.webp"),
            5,
        )
        val finished = DetectionJobReducer.finish(job, 6)
        val artifact = runArtifact(finished)
        val report = report(finished)
        val published = store.publishRun(finished, artifact, report)
        Files.delete(published.resolve("report.json"))

        assertFailsWith<IllegalArgumentException> {
            store.publishRun(finished, artifact, report)
        }
    }

    private fun runningJob(): DetectionJobRecord = DetectionJobRecord(
        jobId = "job-1",
        projectId = "project-1",
        runArtifactKey = RUN_KEY,
        startedAtEpochMillis = 1,
        updatedAtEpochMillis = 3,
        status = DetectionJobStatus.RUNNING,
        model = model(),
        preprocessing = DetectionPreprocessingConfig(),
        thresholds = DetectionThresholdConfig(),
        pages = listOf(
            DetectionJobPage(
                order = 0,
                pageId = PAGE_ID,
                pageArtifactKey = PAGE_KEY,
                state = DetectionPageState.RUNNING,
                attemptCount = 1,
            ),
        ),
    )

    private fun pageArtifact(): PageDetectionArtifact = PageDetectionArtifact(
        pageId = PAGE_ID,
        sourceSha256 = PAGE_ID,
        pageArtifactKey = PAGE_KEY,
        visibleWidth = 100,
        visibleHeight = 200,
        orientation = VisibleOrientation.NORMAL,
        model = model(),
        preprocessing = DetectionPreprocessingConfig(),
        thresholds = DetectionThresholdConfig(),
        rawQueries = rawQueries(),
        bubbleCandidates = emptyList(),
        textRegions = emptyList(),
    )

    private fun rawQueries(): List<RawQueryRecord> = List(300) { index ->
        RawQueryRecord(
            queryIndex = index,
            label = 0,
            score = 0.0,
            rawBox = listOf(1.0, 2.0, 3.0, 4.0),
            validation = RawQueryValidation.BELOW_THRESHOLD,
        )
    }

    private fun runArtifact(job: DetectionJobRecord): DetectionRunArtifact = DetectionRunArtifact(
        runArtifactKey = job.runArtifactKey,
        projectId = job.projectId,
        createdAtEpochMillis = 6,
        model = job.model,
        preprocessing = job.preprocessing,
        thresholds = job.thresholds,
        entries = job.pages.map { page ->
            DetectionRunEntry(
                order = page.order,
                pageId = page.pageId,
                pageArtifactKey = page.pageArtifactKey,
                state = page.state,
                regionsPath = page.regionsPath,
                previewPath = page.previewPath,
                error = page.error,
            )
        },
    )

    private fun report(job: DetectionJobRecord): DetectionReport = DetectionReport(
        jobId = job.jobId,
        projectId = job.projectId,
        runArtifactKey = job.runArtifactKey,
        startedAtEpochMillis = job.startedAtEpochMillis,
        finishedAtEpochMillis = 6,
        status = job.status,
        totalPageCount = 1,
        committedPageCount = 1,
        preservedPageCount = 0,
        retryCount = 0,
    )

    private fun checkpoint(job: DetectionJobRecord): Path = project
        .resolve("staging/detection")
        .resolve(job.jobId)
        .resolve(job.runArtifactKey)

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

    private companion object {
        val PAGE_ID = "a".repeat(64)
        val PAGE_KEY = "b".repeat(64)
        val RUN_KEY = "f".repeat(64)
        val PREVIEW_BYTES = byteArrayOf(1, 2, 3)
    }
}
