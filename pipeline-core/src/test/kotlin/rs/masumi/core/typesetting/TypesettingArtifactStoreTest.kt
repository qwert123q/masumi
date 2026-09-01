package rs.masumi.core.typesetting

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TypesettingArtifactStoreTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("typesetting-store")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `page image and JSON commit before atomic run publication`() {
        val store = TypesettingArtifactStore(root)
        val png = "flattened-png".toByteArray()
        var job = TypesettingJobReducer.start(TypesettingFixtures.job(), 2L)
        store.prepareRun(job)
        job = TypesettingJobReducer.startPage(job, 0, 3L)
        store.writeJob(job)
        val artifact = TypesettingFixtures.artifact(png)
        val paths = store.commitPage(job, artifact, png)
        job = TypesettingJobReducer.commitPage(job, 0, paths.first, paths.second, png.size.toLong(), 1, 0, 4L)
        job = TypesettingJobReducer.finishSuccess(job, 5L)
        store.writeJob(job)
        val run = TypesettingRunArtifact(
            runArtifactKey = job.runArtifactKey,
            projectId = job.projectId,
            createdAtEpochMillis = 5L,
            dependencies = job.dependencies,
            entries = job.pages.map {
                TypesettingRunEntry(
                    pageId = it.pageId,
                    pageOrder = it.pageOrder,
                    cleanupPageArtifactKey = it.cleanupPageArtifactKey,
                    pageArtifactKey = it.pageArtifactKey,
                    state = it.state,
                    artifactPath = it.artifactPath,
                    imagePath = it.imagePath,
                    imageByteLength = it.imageByteLength,
                )
            },
        )
        val report = TypesettingReport(
            jobId = job.jobId,
            projectId = job.projectId,
            runArtifactKey = job.runArtifactKey,
            startedAtEpochMillis = 1L,
            finishedAtEpochMillis = 5L,
            status = job.status,
            totalPageCount = 1,
            committedPageCount = 1,
            preservedPageCount = 0,
            typesetRegionCount = 1,
            preservedRegionCount = 0,
            changedPixelCount = 50,
            retryCount = 0,
        )

        assertNull(store.readPublishedRun(job.runArtifactKey))
        val published = store.publishRun(job, run, report)

        assertNotNull(published)
        assertEquals(run, store.readPublishedRun(job.runArtifactKey))
        assertEquals(report, store.readPublishedReport(job.runArtifactKey))
        assertEquals(artifact, store.readPublishedPage(job.runArtifactKey, run.entries.single()))

        // Local immutable artifacts are not deep-hashed again on every
        // catalog/page read after their atomic publication.
        Files.write(published.resolve(paths.second), "changed-png!!".toByteArray())
        assertEquals(run, store.readPublishedRun(job.runArtifactKey))
        assertEquals(artifact, store.readPublishedPage(job.runArtifactKey, run.entries.single()))

        // Old published JSON had only digest metadata. Decode it with the new
        // field absent and validate by the ordinary non-zero file length.
        val legacyDigest = "a".repeat(64)
        Files.writeString(
            published.resolve(paths.first),
            rs.masumi.core.serialization.TypesettingJson().encodePageArtifact(artifact)
                .replace(
                    "\"renderedImageByteLength\": ${png.size}",
                    "\"renderedImageSha256\": \"$legacyDigest\"",
                ),
        )
        Files.writeString(
            published.resolve("artifact.json"),
            rs.masumi.core.serialization.TypesettingJson().encodeRun(run)
                .replace(
                    "\"imageByteLength\": ${png.size}",
                    "\"renderedImageSha256\": \"$legacyDigest\"",
                ),
        )
        val legacyRun = assertNotNull(store.readPublishedRun(job.runArtifactKey))
        assertEquals(0L, legacyRun.entries.single().imageByteLength)
        assertEquals(
            0L,
            assertNotNull(store.readPublishedPage(job.runArtifactKey, legacyRun.entries.single()))
                .renderedImageByteLength,
        )
    }
}
