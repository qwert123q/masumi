package rs.masumi.core.cleanup

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CleanupArtifactStoreTest {
    private lateinit var project: java.nio.file.Path
    private lateinit var store: CleanupArtifactStore

    @BeforeTest
    fun setUp() {
        project = Files.createTempDirectory("masumi-cleanup-store")
        store = CleanupArtifactStore(project)
    }

    @AfterTest
    fun tearDown() {
        project.toFile().deleteRecursively()
    }

    @Test
    fun `page pixels and JSON commit before one atomic run publication`() {
        val png = byteArrayOf(1, 2, 3, 4)
        var job = CleanupJobReducer.start(CleanupFixtures.job(), 2L)
        job = CleanupJobReducer.startPage(job, 0, 3L)
        store.prepareRun(job)
        store.writeJob(job)
        val artifact = CleanupFixtures.artifact(png)
        val (artifactPath, imagePath) = store.commitPage(job, artifact, png, "webp")
        assertTrue(imagePath.endsWith("cleaned.webp"))
        job = CleanupJobReducer.commitPage(job, 0, artifactPath, imagePath, 1, 0, 4L)
        job = CleanupJobReducer.finishSuccess(job, 5L)
        val run = CleanupRunArtifact(
            runArtifactKey = job.runArtifactKey,
            projectId = job.projectId,
            createdAtEpochMillis = 5L,
            dependencies = job.dependencies,
            entries = listOf(
                CleanupRunEntry(
                    pageId = job.pages.single().pageId,
                    pageOrder = 0,
                    sourceSha256 = job.pages.single().sourceSha256,
                    translationPageArtifactKey = job.pages.single().translationPageArtifactKey,
                    pageArtifactKey = job.pages.single().pageArtifactKey,
                    state = CleanupPageState.COMMITTED,
                    artifactPath = artifactPath,
                    imagePath = imagePath,
                ),
            ),
        )
        val report = CleanupReport(
            jobId = job.jobId,
            projectId = job.projectId,
            runArtifactKey = job.runArtifactKey,
            startedAtEpochMillis = 1L,
            finishedAtEpochMillis = 5L,
            status = job.status,
            totalPageCount = 1,
            committedPageCount = 1,
            preservedPageCount = 0,
            cleanedRegionCount = 1,
            preservedRegionCount = 0,
            changedPixelCount = 20,
            retryCount = 0,
        )

        val published = store.publishRun(job, run, report)

        assertTrue(published.resolve("artifact.json").toFile().isFile)
        assertFalse(project.resolve("staging/cleanup/${job.jobId}/${job.runArtifactKey}").toFile().exists())
        assertEquals(run, assertNotNull(store.readPublishedRun(job.runArtifactKey)))
        assertEquals(report, assertNotNull(store.readPublishedReport(job.runArtifactKey)))
        assertEquals(artifact, assertNotNull(store.readPublishedPage(job.runArtifactKey, run.entries.single())))

        // Ordinary catalog/page reads trust the app-private atomic publish.
        // Changing image bytes after publication must not trigger another
        // whole-file digest pass at every downstream lookup.
        Files.write(published.resolve(imagePath), byteArrayOf(9, 8, 7, 6))
        assertEquals(run, assertNotNull(store.readPublishedRun(job.runArtifactKey)))
        assertEquals(artifact, assertNotNull(store.readPublishedPage(job.runArtifactKey, run.entries.single())))
    }

    @Test
    fun `cleaned page rejects a visually significant absolute residual count`() {
        val png = byteArrayOf(1, 2, 3, 4)
        var job = CleanupJobReducer.start(CleanupFixtures.job(), 2L)
        job = CleanupJobReducer.startPage(job, 0, 3L)
        store.prepareRun(job)
        val artifact = CleanupFixtures.artifact(png).let { page ->
            page.copy(
                regions = page.regions.map { region ->
                    region.copy(auditPixelCount = 1_000, residualPixelCount = 9)
                },
            )
        }

        assertFailsWith<IllegalArgumentException> {
            store.commitPage(job, artifact, png, "webp")
        }
    }

    @Test
    fun `cleaned page accepts audited residual only when explicitly marked best effort`() {
        val png = byteArrayOf(1, 2, 3, 4)
        var job = CleanupJobReducer.start(CleanupFixtures.job(), 2L)
        job = CleanupJobReducer.startPage(job, 0, 3L)
        store.prepareRun(job)
        val artifact = CleanupFixtures.artifact(png).let { page ->
            page.copy(
                regions = page.regions.map { region ->
                    region.copy(
                        completionMode = CleanupCompletionMode.BEST_EFFORT_RESIDUAL,
                        auditPixelCount = 1_000,
                        initialResidualPixelCount = 24,
                        residualRetryPixelCount = 18,
                        residualPixelCount = 9,
                        cleanupAttemptCount = 3,
                    )
                },
            )
        }

        val committed = store.commitPage(job, artifact, png, "webp")

        assertTrue(committed.first.endsWith("cleanup.json"))
    }

    @Test
    fun `best effort residual rejects missing local retry diagnostics`() {
        val png = byteArrayOf(1, 2, 3, 4)
        var job = CleanupJobReducer.start(CleanupFixtures.job(), 2L)
        job = CleanupJobReducer.startPage(job, 0, 3L)
        store.prepareRun(job)
        val artifact = CleanupFixtures.artifact(png).let { page ->
            page.copy(
                regions = page.regions.map { region ->
                    region.copy(
                        completionMode = CleanupCompletionMode.BEST_EFFORT_RESIDUAL,
                        auditPixelCount = 1_000,
                        initialResidualPixelCount = 0,
                        residualRetryPixelCount = 0,
                        residualPixelCount = 9,
                        cleanupAttemptCount = 3,
                    )
                },
            )
        }

        assertFailsWith<IllegalArgumentException> {
            store.commitPage(job, artifact, png, "webp")
        }
    }

    @Test
    fun `neural outcome rejects an inconsistent attempt count`() {
        val png = byteArrayOf(1, 2, 3, 4)
        var job = CleanupJobReducer.start(CleanupFixtures.job(), 2L)
        job = CleanupJobReducer.startPage(job, 0, 3L)
        store.prepareRun(job)
        val artifact = CleanupFixtures.artifact(png).let { page ->
            page.copy(
                regions = page.regions.map { region ->
                    region.copy(
                        maskSource = CleanupMaskSource.COMIC_TEXT_SEGMENTATION_NEURAL_RETRY,
                        cleanupAttemptCount = 3,
                        neuralFallbackOutcome = NeuralFallbackOutcome.SUCCEEDED,
                        neuralFallbackMillis = 12,
                    )
                },
            )
        }

        assertFailsWith<IllegalArgumentException> {
            store.commitPage(job, artifact, png, "webp")
        }
    }

    @Test
    fun `successful neural retry records its independent acceptance result`() {
        val png = byteArrayOf(1, 2, 3, 4)
        var job = CleanupJobReducer.start(CleanupFixtures.job(), 2L)
        job = CleanupJobReducer.startPage(job, 0, 3L)
        store.prepareRun(job)
        val artifact = CleanupFixtures.artifact(png).let { page ->
            page.copy(
                regions = page.regions.map { region ->
                    region.copy(
                        maskSource = CleanupMaskSource.COMIC_TEXT_SEGMENTATION_NEURAL_RETRY,
                        auditPixelCount = 1_000,
                        initialResidualPixelCount = 24,
                        residualRetryPixelCount = 18,
                        residualPixelCount = 0,
                        cleanupAttemptCount = 4,
                        neuralFallbackOutcome = NeuralFallbackOutcome.SUCCEEDED,
                        neuralFallbackMillis = 12,
                    )
                },
            )
        }

        val committed = store.commitPage(job, artifact, png, "webp")

        assertTrue(committed.first.endsWith("cleanup.json"))
        assertTrue(committed.second.endsWith("cleaned.webp"))
    }
}
