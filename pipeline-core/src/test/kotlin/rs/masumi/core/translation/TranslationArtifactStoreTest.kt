package rs.masumi.core.translation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranslationArtifactStoreTest {
    private lateinit var project: Path
    private lateinit var store: TranslationArtifactStore

    @BeforeTest
    fun setUp() {
        project = Files.createTempDirectory("masumi-translation-store")
        store = TranslationArtifactStore(project)
    }

    @AfterTest
    fun tearDown() {
        project.toFile().deleteRecursively()
    }

    @Test
    fun `window is durable before journal and complete run publishes atomically`() {
        var job = TranslationJobReducer.startRunning(TranslationArtifactFixtures.job(), 2L)
        job = TranslationJobReducer.startWindow(job, 0, 3L)
        store.prepareRun(job)
        store.writeJob(job)

        val glossary = listOf(TranslationGlossaryEntry("名前", "名字"))
        val windowArtifact = TranslationWindowArtifact(
            windowIndex = 0,
            windowArtifactKey = job.windows.single().windowArtifactKey,
            inputGlossarySha256 = job.dependencies.initialGlossarySha256,
            outputGlossarySha256 = TranslationArtifactIdentity.glossarySha256(glossary),
            outputGlossary = glossary,
            items = listOf(TranslationArtifactFixtures.outcome()),
            ignoredResponseIds = emptyList(),
            usage = TranslationUsage(10, 5, 15),
            providerModelId = "model-safe",
            attemptCount = 1,
            durationMillis = 20L,
        )
        val windowPath = store.commitWindow(job, windowArtifact)
        assertTrue(checkpoint(job).resolve(windowPath).exists())
        assertEquals(TranslationWindowState.RUNNING, store.readJob(job.jobId)?.windows?.single()?.state)

        job = TranslationJobReducer.commitWindow(job, 0, windowPath, 1, 0, windowArtifact.usage, null, 4L)
        store.writeJob(job)
        val pageArtifact = PageTranslationArtifact(
            pageId = job.pages.single().pageId,
            pageOrder = 0,
            ocrPageArtifactKey = job.pages.single().ocrPageArtifactKey,
            pageArtifactKey = job.pages.single().pageArtifactKey,
            dependencies = job.dependencies,
            items = windowArtifact.items,
            protectedOcrRegions = emptyList(),
        )
        val pagePath = store.commitPage(job, pageArtifact)
        job = TranslationJobReducer.commitPage(job, pageArtifact.pageOrder, pagePath, 5L)
        job = TranslationJobReducer.finishSuccess(job, 6L)

        val run = TranslationRunArtifact(
            runArtifactKey = job.runArtifactKey,
            projectId = job.projectId,
            createdAtEpochMillis = 6L,
            dependencies = job.dependencies,
            entries = listOf(
                TranslationRunEntry(
                    pageId = pageArtifact.pageId,
                    pageOrder = 0,
                    ocrPageArtifactKey = pageArtifact.ocrPageArtifactKey,
                    pageArtifactKey = pageArtifact.pageArtifactKey,
                    artifactPath = pagePath,
                ),
            ),
            glossaryPath = "glossary.json",
        )
        val report = TranslationReport(
            jobId = job.jobId,
            projectId = job.projectId,
            runArtifactKey = job.runArtifactKey,
            startedAtEpochMillis = 1L,
            finishedAtEpochMillis = 6L,
            status = job.status,
            totalPageCount = 1,
            committedPageCount = 1,
            totalWindowCount = 1,
            committedWindowCount = 1,
            translatedItemCount = 1,
            preservedItemCount = 0,
            protectedOcrRegionCount = 0,
            promptTokens = 10,
            completionTokens = 5,
            totalTokens = 15,
            retryCount = 0,
            provider = job.dependencies.provider.reference,
        )
        val published = store.publishRun(
            job,
            run,
            TranslationGlossaryArtifact(sha256 = windowArtifact.outputGlossarySha256, entries = glossary),
            report,
        )

        assertTrue(published.resolve("artifact.json").exists())
        assertTrue(published.resolve("report.json").exists())
        assertTrue(published.resolve("glossary.json").exists())
        assertFalse(checkpoint(job).exists())
        assertEquals(run, assertNotNull(store.readPublishedRun(job.runArtifactKey)))
        assertEquals(report, assertNotNull(store.readPublishedReport(job.runArtifactKey)))
        assertEquals(glossary, assertNotNull(store.readPublishedGlossary(job.runArtifactKey)).entries)
    }

    @Test
    fun `latest resumable translation job ignores other job formats`() {
        val older = TranslationArtifactFixtures.job().copy(updatedAtEpochMillis = 2L)
        val newer = older.copy(jobId = "translation-job-2", updatedAtEpochMillis = 3L)
        store.writeJob(older)
        store.writeJob(newer)
        Files.writeString(project.resolve("jobs/not-translation.json"), "{}")

        assertEquals(newer.jobId, store.findResumableJob()?.jobId)
    }

    @Test
    fun `discarding retryable suffix removes its window and dependent page checkpoints only`() {
        val base = TranslationArtifactFixtures.job()
        val prefixRegionId = "1".repeat(64)
        val failedRegionId = "2".repeat(64)
        val dependentRegionId = "3".repeat(64)
        val prefix = base.windows.single().copy(
            translationRegionIds = listOf(prefixRegionId),
            state = TranslationWindowState.COMMITTED,
            checkpointPath = "windows/0000-${base.windows.single().windowArtifactKey}.json",
        )
        val failed = base.windows.single().copy(
            windowIndex = 1,
            windowArtifactKey = "d".repeat(64),
            translationRegionIds = listOf(failedRegionId),
            state = TranslationWindowState.PRESERVED_SOURCE,
            checkpointPath = "windows/0001-${"d".repeat(64)}.json",
            error = TranslationError("NETWORK"),
        )
        val dependent = base.windows.single().copy(
            windowIndex = 2,
            windowArtifactKey = "e".repeat(64),
            translationRegionIds = listOf(dependentRegionId),
            state = TranslationWindowState.COMMITTED,
            checkpointPath = null,
        )
        val prefixPage = base.pages.single().copy(
            translationRegionIds = listOf(prefixRegionId),
            state = TranslationPageState.COMMITTED,
            artifactPath = "pages/0000-${base.pages.single().pageId}/translation.json",
        )
        val affectedPage = base.pages.single().copy(
            pageId = "4".repeat(64),
            pageOrder = 1,
            pageArtifactKey = "5".repeat(64),
            translationRegionIds = listOf(failedRegionId, dependentRegionId),
            state = TranslationPageState.COMMITTED,
            artifactPath = "pages/0001-${"4".repeat(64)}/translation.json",
        )
        val job = base.copy(
            status = TranslationJobStatus.CANCELLED,
            windows = listOf(prefix, failed, dependent),
            pages = listOf(prefixPage, affectedPage),
        )
        val prefixWindowPath = checkpoint(job).resolve(requireNotNull(prefix.checkpointPath))
        val failedWindowPath = checkpoint(job).resolve(requireNotNull(failed.checkpointPath))
        val dependentWindowPath = checkpoint(job).resolve(
            "windows/0002-${dependent.windowArtifactKey}.json",
        )
        val prefixPagePath = checkpoint(job).resolve(requireNotNull(prefixPage.artifactPath))
        val affectedPagePath = checkpoint(job).resolve(requireNotNull(affectedPage.artifactPath))
        listOf(
            prefixWindowPath,
            failedWindowPath,
            dependentWindowPath,
            prefixPagePath,
            affectedPagePath,
        ).forEach { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "checkpoint")
        }

        store.discardWindowSuffix(job, firstWindowIndex = 1)

        assertTrue(prefixWindowPath.exists())
        assertTrue(prefixPagePath.exists())
        assertFalse(failedWindowPath.exists())
        assertFalse(dependentWindowPath.exists())
        assertFalse(affectedPagePath.exists())
    }

    @Test
    fun `discard rejects an escaping job checkpoint root before deleting anything`() {
        val base = TranslationArtifactFixtures.job()
        val escaping = base.copy(
            jobId = "../../artifacts/translation",
            status = TranslationJobStatus.CANCELLED,
            windows = listOf(
                base.windows.single().copy(
                    state = TranslationWindowState.PENDING,
                    checkpointPath = "sentinel.txt",
                ),
            ),
        )
        val escapedRoot = project
            .resolve("staging/translation/${escaping.jobId}/${escaping.runArtifactKey}")
            .normalize()
        val sentinel = escapedRoot.resolve("sentinel.txt")
        Files.createDirectories(sentinel.parent)
        Files.writeString(sentinel, "must survive")

        val failure = runCatching {
            store.discardWindowSuffix(escaping, firstWindowIndex = 0)
        }.exceptionOrNull()

        assertTrue(sentinel.exists(), "an invalid job root must be rejected before any deletion")
        assertTrue(failure is IllegalArgumentException, "an escaping job root must be rejected")
    }

    @Test
    fun `discard rejects an escaping window artifact key before deleting anything`() {
        val base = TranslationArtifactFixtures.job()
        val maliciousWindow = base.windows.single().copy(
            windowArtifactKey = "../../../../escape-window",
            state = TranslationWindowState.PENDING,
            checkpointPath = null,
        )
        val job = base.copy(
            status = TranslationJobStatus.CANCELLED,
            windows = listOf(maliciousWindow),
        )
        val sentinel = checkpoint(job)
            .resolve("windows/0000-${maliciousWindow.windowArtifactKey}.json")
            .normalize()
        Files.createDirectories(sentinel.parent)
        Files.writeString(sentinel, "must survive")

        val failure = runCatching {
            store.discardWindowSuffix(job, firstWindowIndex = 0)
        }.exceptionOrNull()

        assertTrue(sentinel.exists(), "an invalid window key must be rejected before any deletion")
        assertTrue(failure is IllegalArgumentException, "an escaping window key must be rejected")
    }

    @Test
    fun `discard rejects an escaping page id before deleting anything`() {
        val base = TranslationArtifactFixtures.job()
        val maliciousPage = base.pages.single().copy(
            pageId = "../../../../escape-page",
            artifactPath = null,
        )
        val job = base.copy(
            status = TranslationJobStatus.CANCELLED,
            pages = listOf(maliciousPage),
        )
        val sentinel = checkpoint(job)
            .resolve(
                "pages/${maliciousPage.pageOrder.toString().padStart(4, '0')}-${maliciousPage.pageId}/translation.json",
            )
            .normalize()
        val earlierWindowCheckpoint = checkpoint(job).resolve(
            "windows/0000-${job.windows.single().windowArtifactKey}.json",
        )
        listOf(sentinel, earlierWindowCheckpoint).forEach { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "must survive")
        }

        val failure = runCatching {
            store.discardWindowSuffix(job, firstWindowIndex = 0)
        }.exceptionOrNull()

        assertTrue(sentinel.exists(), "an invalid page id must be rejected before any deletion")
        assertTrue(
            earlierWindowCheckpoint.exists(),
            "all derived paths must be validated before an earlier checkpoint is deleted",
        )
        assertTrue(failure is IllegalArgumentException, "an escaping page id must be rejected")
    }

    @Test
    fun `discard rejects a suffix checkpoint path that aliases the trusted prefix`() {
        val base = TranslationArtifactFixtures.job()
        val prefix = base.windows.single().copy(
            state = TranslationWindowState.COMMITTED,
            checkpointPath = "windows/0000-${base.windows.single().windowArtifactKey}.json",
        )
        val suffix = base.windows.single().copy(
            windowIndex = 1,
            windowArtifactKey = "d".repeat(64),
            state = TranslationWindowState.PENDING,
            checkpointPath = prefix.checkpointPath,
        )
        val job = base.copy(
            status = TranslationJobStatus.CANCELLED,
            windows = listOf(prefix, suffix),
        )
        val prefixCheckpoint = checkpoint(job).resolve(requireNotNull(prefix.checkpointPath))
        Files.createDirectories(prefixCheckpoint.parent)
        Files.writeString(prefixCheckpoint, "trusted prefix")

        val failure = runCatching {
            store.discardWindowSuffix(job, firstWindowIndex = 1)
        }.exceptionOrNull()

        assertTrue(prefixCheckpoint.exists(), "an aliased prefix checkpoint must never be deleted")
        assertTrue(failure is IllegalArgumentException, "a suffix path aliasing the prefix must be rejected")
    }

    private fun checkpoint(job: TranslationJobRecord): Path = project
        .resolve("staging/translation/${job.jobId}/${job.runArtifactKey}")
}
