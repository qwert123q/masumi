package rs.masumi.core.quality

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QualityArtifactStoreTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("quality-store")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `page JSON commits before atomic report publication`() {
        val store = QualityArtifactStore(root)
        var job = QualityJobReducer.start(QualityFixtures.job(), 2L)
        store.prepareRun(job)
        job = QualityJobReducer.startPage(job, 0, 3L)
        store.writeJob(job)
        val artifact = QualityFixtures.artifact()
        val path = store.commitPage(job, artifact)
        job = QualityJobReducer.commitPage(job, 0, path, artifact.verdict, 0, 0, 4L)
        job = QualityJobReducer.finish(job, 5L)
        store.writeJob(job)
        val run = QualityRunArtifact(
            runArtifactKey = job.runArtifactKey,
            projectId = job.projectId,
            createdAtEpochMillis = 5L,
            dependencies = job.dependencies,
            entries = job.pages.map {
                QualityRunEntry(
                    pageId = it.pageId,
                    pageOrder = it.pageOrder,
                    sourceSha256 = it.sourceSha256,
                    typesettingPageArtifactKey = it.typesettingPageArtifactKey,
                    pageArtifactKey = it.pageArtifactKey,
                    verdict = requireNotNull(it.verdict),
                    artifactPath = requireNotNull(it.artifactPath),
                )
            },
        )
        val report = QualityReport(
            jobId = job.jobId,
            projectId = job.projectId,
            runArtifactKey = job.runArtifactKey,
            typesettingRunArtifactKey = job.dependencies.typesettingRunArtifactKey,
            startedAtEpochMillis = 1L,
            finishedAtEpochMillis = 5L,
            status = job.status,
            totalPageCount = 1,
            passedPageCount = 1,
            warningPageCount = 0,
            blockedPageCount = 0,
            warningCount = 0,
            blockingCount = 0,
            verifiedTypesetRegionCount = 1,
            preservedRegionCount = 0,
            retryCount = 0,
        )

        assertNull(store.readPublishedRun(job.runArtifactKey))
        assertNotNull(store.publishRun(job, run, report))
        assertEquals(run, store.readPublishedRun(job.runArtifactKey))
        assertEquals(report, store.readPublishedReport(job.runArtifactKey))
        assertEquals(artifact, store.readPublishedPage(job.runArtifactKey, run.entries.single()))
    }
}
