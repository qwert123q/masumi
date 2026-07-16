package rs.masumi.core.cleanup

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val (artifactPath, imagePath) = store.commitPage(job, artifact, png)
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
    }
}
