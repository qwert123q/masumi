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

    private fun checkpoint(job: TranslationJobRecord): Path = project
        .resolve("staging/translation/${job.jobId}/${job.runArtifactKey}")
}
