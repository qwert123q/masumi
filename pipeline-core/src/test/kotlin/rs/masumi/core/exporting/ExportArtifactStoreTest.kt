package rs.masumi.core.exporting

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExportArtifactStoreTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("export-store")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `job and privacy-safe report replace atomically`() {
        val store = ExportArtifactStore(root)
        var job = ExportJobReducer.start(ExportFixtures.job(), 2L)
        job = ExportJobReducer.startPage(job, 0, 3L)
        job = ExportJobReducer.commitPage(job, 0, 12L, true, 4L)
        store.writeJob(job)
        job = ExportJobReducer.finishSuccess(job, 5L)
        val report = ExportReport(
            jobId = job.jobId,
            projectId = job.projectId,
            exportKey = job.exportKey,
            destinationKey = job.destinationKey,
            typesettingRunArtifactKey = job.dependencies.typesettingRunArtifactKey,
            qualityRunArtifactKey = job.dependencies.qualityRunArtifactKey,
            startedAtEpochMillis = job.startedAtEpochMillis,
            finishedAtEpochMillis = 5L,
            status = job.status,
            totalPageCount = 1,
            exportedPageCount = 1,
            flattenedPageCount = 1,
            cleanedFallbackPageCount = 0,
            sourceFallbackPageCount = 0,
            reusedPageCount = 1,
            totalByteCount = 12L,
            retryCount = 0,
        )

        assertNull(store.readReport(job.jobId))
        store.commitSuccessfulExport(job, report)

        assertEquals(job, store.readJob(job.jobId))
        assertEquals(report, store.readReport(job.jobId))
        assertEquals(job, store.findLatestJob())
    }
}
