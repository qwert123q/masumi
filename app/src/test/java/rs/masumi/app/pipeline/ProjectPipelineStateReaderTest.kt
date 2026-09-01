package rs.masumi.app.pipeline

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.app.ocr.currentOcrDependencies
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.cleanup.CleanupReport
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.cleanup.CleanupRunEntry
import rs.masumi.core.cleanup.PageCleanupArtifact
import rs.masumi.core.detection.DetectionError
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionPreprocessingConfig
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.DetectionRunEntry
import rs.masumi.core.detection.DetectionThresholdConfig
import rs.masumi.core.detection.VisibleOrientation
import rs.masumi.core.exporting.ExportArtifactStore
import rs.masumi.core.exporting.ExportDependencies
import rs.masumi.core.exporting.ExportJobPage
import rs.masumi.core.exporting.ExportJobRecord
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.exporting.ExportPageSource
import rs.masumi.core.exporting.ExportPageState
import rs.masumi.core.exporting.ExportPolicy
import rs.masumi.core.exporting.ExportReport
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.modelpackage.PinnedComicDetector
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrReport
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.ocr.OcrRunEntry
import rs.masumi.core.ocr.PageOcrArtifact
import rs.masumi.core.serialization.CleanupJson
import rs.masumi.core.serialization.DetectionJson
import rs.masumi.core.serialization.OcrJson
import rs.masumi.core.serialization.ProjectJson
import rs.masumi.core.serialization.TranslationJson
import rs.masumi.core.serialization.TypesettingJson
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationDependencies
import rs.masumi.core.translation.TranslationGlossaryArtifact
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.translation.TranslationOutputValidationConfig
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.TranslationProviderDependency
import rs.masumi.core.translation.TranslationReport
import rs.masumi.core.translation.TranslationRunArtifact
import rs.masumi.core.translation.TranslationRunEntry
import rs.masumi.core.translation.PageTranslationArtifact
import rs.masumi.core.typesetting.TypesettingDependencies
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.typesetting.TypesettingPageState
import rs.masumi.core.typesetting.TypesettingPolicy
import rs.masumi.core.typesetting.TypesettingReport
import rs.masumi.core.typesetting.TypesettingRunArtifact
import rs.masumi.core.typesetting.TypesettingRunEntry
import rs.masumi.core.typesetting.PageTypesettingArtifact

class ProjectPipelineStateReaderTest {
    @Test
    fun `typesetting flows directly to export before export can complete`() {
        val workspace = Files.createTempDirectory("masumi-state-reader")
        try {
            val chain = publishThroughTypesetting(workspace)
            val reader = ProjectPipelineStateReader(workspace)

            reader.read(PROJECT_ID).requireState().also { state ->
                assertEquals(PipelineStage.EXPORT, state.nextStage)
                assertFalse(state.blocked)
                assertFalse(state.complete)
            }

            publishExport(chain.project, chain.typesetting)
            reader.read(PROJECT_ID).requireState().also { state ->
                assertNull(state.nextStage)
                assertTrue(state.complete)
                assertFalse(state.blocked)
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `current chain ignores newer typesetting from stale policy and cleanup lineage`() {
        val workspace = Files.createTempDirectory("masumi-current-chain")
        try {
            val chain = publishThroughTypesetting(workspace)
            val stalePolicy = publishTypesetting(
                project = chain.project,
                cleanupRunId = chain.cleanupRunId,
                policy = TypesettingPolicy(revision = "legacy-typesetting-policy"),
                createdAt = 7L,
                runId = "typesetting-run-stale-policy",
                pageId = "typesetting-page-stale-policy",
            )
            publishTypesetting(
                project = chain.project,
                cleanupRunId = "cleanup-run-stale",
                cleanupPageId = "cleanup-page-stale",
                policy = TypesettingPolicy(),
                createdAt = 8L,
                runId = "typesetting-run-stale-cleanup",
                pageId = "typesetting-page-stale-cleanup",
            )

            val current = CurrentPipelineArtifactsReader(workspace).read(PROJECT_ID)

            assertEquals(chain.typesetting.runId, current?.typesetting?.artifact?.runArtifactKey)
            publishExport(chain.project, stalePolicy)
            assertEquals(
                PipelineStage.EXPORT,
                ProjectPipelineStateReader(workspace).read(PROJECT_ID).requireState().nextStage,
            )
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun publishThroughTypesetting(workspace: Path): CurrentChain {
        val project = Files.createDirectories(workspace.resolve("projects/$PROJECT_ID"))
        val source = project.resolve("source/page.png")
        Files.createDirectories(source.parent)
        Files.write(source, byteArrayOf(1))
        Files.write(
            project.resolve("manifest.json"),
            ProjectJson().encodeManifest(
                ProjectManifest(
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = 1L,
                    pages = listOf(
                        PageRecord(
                            order = 0,
                            pageId = PAGE_ID,
                            originalName = "page.png",
                            mediaType = "image/png",
                            byteLength = 1L,
                            storedPath = "source/page.png",
                        ),
                    ),
                ),
            ).toByteArray(),
        )

        val detectionRunId = DETECTION_RUN_ID
        val detectionPageId = DETECTION_PAGE_ID
        val detectionModel = PinnedComicDetector.descriptor.toModelRef()
        val detectionPreprocessing = DetectionPreprocessingConfig()
        val detectionThresholds = DetectionThresholdConfig()
        val detectionJson = DetectionJson()
        writePublished(
            project,
            "detection",
            detectionRunId,
            detectionJson.encodeRun(
                DetectionRunArtifact(
                    runArtifactKey = detectionRunId,
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = 2L,
                    model = detectionModel,
                    preprocessing = detectionPreprocessing,
                    thresholds = detectionThresholds,
                    entries = listOf(
                        DetectionRunEntry(
                            order = 0,
                            pageId = PAGE_ID,
                            pageArtifactKey = detectionPageId,
                            state = DetectionPageState.PRESERVED_SOURCE,
                            error = DetectionError("TEST_PRESERVED", "fixture has no detector output"),
                        ),
                    ),
                ),
            ),
            detectionJson.encodeReport(
                DetectionReport(
                    jobId = "detection-job",
                    projectId = PROJECT_ID,
                    runArtifactKey = detectionRunId,
                    startedAtEpochMillis = 1L,
                    finishedAtEpochMillis = 2L,
                    status = DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES,
                    totalPageCount = 1,
                    committedPageCount = 0,
                    preservedPageCount = 1,
                    retryCount = 0,
                ),
            ),
        )

        val ocrDependencies = currentOcrDependencies()
        val ocrPageId = OCR_PAGE_ID
        val ocrRunId = OCR_RUN_ID
        val ocrJson = OcrJson()
        val ocrDirectory = writePublished(
            project,
            "ocr",
            ocrRunId,
            ocrJson.encodeRun(
                OcrRunArtifact(
                    runArtifactKey = ocrRunId,
                    projectId = PROJECT_ID,
                    detectionRunArtifactKey = detectionRunId,
                    createdAtEpochMillis = 3L,
                    dependencies = ocrDependencies,
                    entries = listOf(
                        OcrRunEntry(
                            order = 0,
                            pageId = PAGE_ID,
                            detectionPageArtifactKey = detectionPageId,
                            pageArtifactKey = ocrPageId,
                            state = OcrPageState.COMMITTED,
                            artifactPath = "pages/0000/ocr.json",
                            previewPath = "pages/0000/preview.png",
                        ),
                    ),
                ),
            ),
            ocrJson.encodeReport(
                OcrReport(
                    jobId = "ocr-job",
                    projectId = PROJECT_ID,
                    runArtifactKey = ocrRunId,
                    startedAtEpochMillis = 2L,
                    finishedAtEpochMillis = 3L,
                    status = OcrJobStatus.SUCCEEDED,
                    totalPageCount = 1,
                    committedPageCount = 1,
                    totalRegionCount = 0,
                    recognizedRegionCount = 0,
                    needsFallbackRegionCount = 0,
                    noTextRegionCount = 0,
                    preservedRegionCount = 0,
                    retryCount = 0,
                ),
            ),
        )
        Files.createDirectories(ocrDirectory.resolve("pages/0000"))
        Files.write(
            ocrDirectory.resolve("pages/0000/ocr.json"),
            ocrJson.encodePageArtifact(
                PageOcrArtifact(
                    pageId = PAGE_ID,
                    detectionPageArtifactKey = detectionPageId,
                    pageArtifactKey = ocrPageId,
                    visibleWidth = 1,
                    visibleHeight = 1,
                    orientation = VisibleOrientation.NORMAL,
                    dependencies = ocrDependencies,
                    regions = emptyList(),
                ),
            ).toByteArray(),
        )
        Files.write(ocrDirectory.resolve("pages/0000/preview.png"), byteArrayOf(1))

        val translationDependencies = TranslationDependencies(
            ocrRunArtifactKey = ocrRunId,
            policy = TranslationPolicy(),
            prompt = TranslationPromptRef(),
            batching = TranslationBatchingConfig(),
            outputValidation = TranslationOutputValidationConfig(),
            provider = TranslationProviderDependency(
                modelId = "model",
                temperature = 0.0,
                maximumOutputTokens = 1,
                requestJsonObjectFormat = true,
            ),
            initialGlossary = emptyList(),
        )
        val translationRunId = TRANSLATION_RUN_ID
        val translationPageId = TRANSLATION_PAGE_ID
        val translationJson = TranslationJson()
        val translationDirectory = writePublished(
            project,
            "translation",
            translationRunId,
            translationJson.encodeRun(
                TranslationRunArtifact(
                    runArtifactKey = translationRunId,
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = 4L,
                    dependencies = translationDependencies,
                    entries = listOf(
                        TranslationRunEntry(
                            pageId = PAGE_ID,
                            pageOrder = 0,
                            ocrPageArtifactKey = ocrPageId,
                            pageArtifactKey = translationPageId,
                            artifactPath = "pages/0000/translation.json",
                        ),
                    ),
                    glossaryPath = "glossary.json",
                ),
            ),
            translationJson.encodeReport(
                TranslationReport(
                    jobId = "translation-job",
                    projectId = PROJECT_ID,
                    runArtifactKey = translationRunId,
                    startedAtEpochMillis = 3L,
                    finishedAtEpochMillis = 4L,
                    status = TranslationJobStatus.SUCCEEDED,
                    totalPageCount = 1,
                    committedPageCount = 1,
                    totalWindowCount = 0,
                    committedWindowCount = 0,
                    translatedItemCount = 0,
                    preservedItemCount = 0,
                    protectedOcrRegionCount = 0,
                    promptTokens = 0,
                    completionTokens = 0,
                    totalTokens = 0,
                    retryCount = 0,
                ),
            ),
        )
        Files.createDirectories(translationDirectory.resolve("pages/0000"))
        Files.write(
            translationDirectory.resolve("pages/0000/translation.json"),
            translationJson.encodePageArtifact(
                PageTranslationArtifact(
                    pageId = PAGE_ID,
                    pageOrder = 0,
                    ocrPageArtifactKey = ocrPageId,
                    pageArtifactKey = translationPageId,
                    dependencies = translationDependencies,
                    items = emptyList(),
                    protectedOcrRegions = emptyList(),
                ),
            ).toByteArray(),
        )
        Files.write(
            translationDirectory.resolve("glossary.json"),
            translationJson.encodeGlossary(
                TranslationGlossaryArtifact(entries = emptyList()),
            ).toByteArray(),
        )

        val cleanupDependencies = currentCleanupDependencies(translationRunId)
        val cleanupRunId = CLEANUP_RUN_ID
        val cleanupPageId = CLEANUP_PAGE_ID
        val cleanupJson = CleanupJson()
        val cleanupDirectory = writePublished(
            project,
            "cleanup",
            cleanupRunId,
            cleanupJson.encodeRun(
                CleanupRunArtifact(
                    runArtifactKey = cleanupRunId,
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = 5L,
                    dependencies = cleanupDependencies,
                    entries = listOf(
                        CleanupRunEntry(
                            pageId = PAGE_ID,
                            pageOrder = 0,
                            translationPageArtifactKey = translationPageId,
                            pageArtifactKey = cleanupPageId,
                            state = CleanupPageState.COMMITTED,
                            artifactPath = "pages/0000/cleanup.json",
                            imagePath = "pages/0000/cleaned.png",
                            imageByteLength = 1L,
                        ),
                    ),
                ),
            ),
            cleanupJson.encodeReport(
                CleanupReport(
                    jobId = "cleanup-job",
                    projectId = PROJECT_ID,
                    runArtifactKey = cleanupRunId,
                    startedAtEpochMillis = 4L,
                    finishedAtEpochMillis = 5L,
                    status = CleanupJobStatus.SUCCEEDED,
                    totalPageCount = 1,
                    committedPageCount = 1,
                    preservedPageCount = 0,
                    cleanedRegionCount = 0,
                    preservedRegionCount = 0,
                    changedPixelCount = 0,
                    retryCount = 0,
                ),
            ),
        )
        Files.createDirectories(cleanupDirectory.resolve("pages/0000"))
        Files.write(
            cleanupDirectory.resolve("pages/0000/cleanup.json"),
            cleanupJson.encodePageArtifact(
                PageCleanupArtifact(
                    pageId = PAGE_ID,
                    pageOrder = 0,
                    translationPageArtifactKey = translationPageId,
                    pageArtifactKey = cleanupPageId,
                    visibleWidth = 1,
                    visibleHeight = 1,
                    cleanedImageByteLength = 1L,
                    dependencies = cleanupDependencies,
                    regions = emptyList(),
                ),
            ).toByteArray(),
        )
        Files.write(cleanupDirectory.resolve("pages/0000/cleaned.png"), byteArrayOf(1))

        return CurrentChain(
            project = project,
            cleanupRunId = cleanupRunId,
            typesetting = publishTypesetting(
                project = project,
                cleanupRunId = cleanupRunId,
                policy = TypesettingPolicy(),
                createdAt = 6L,
                runId = TYPESETTING_RUN_ID,
                pageId = TYPESETTING_PAGE_ID,
            ),
        )
    }

    private fun publishTypesetting(
        project: Path,
        cleanupRunId: String,
        cleanupPageId: String = CLEANUP_PAGE_ID,
        policy: TypesettingPolicy,
        createdAt: Long,
        runId: String,
        pageId: String,
    ): StageRunRef {
        val dependencies = TypesettingDependencies(cleanupRunArtifactKey = cleanupRunId, policy = policy)
        val json = TypesettingJson()
        val directory = writePublished(
            project,
            "typesetting",
            runId,
            json.encodeRun(
                TypesettingRunArtifact(
                    runArtifactKey = runId,
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = createdAt,
                    dependencies = dependencies,
                    entries = listOf(
                        TypesettingRunEntry(
                            pageId = PAGE_ID,
                            pageOrder = 0,
                            cleanupPageArtifactKey = cleanupPageId,
                            pageArtifactKey = pageId,
                            state = TypesettingPageState.COMMITTED,
                            artifactPath = "pages/0000/typesetting.json",
                            imagePath = "pages/0000/typeset.png",
                            imageByteLength = 1L,
                        ),
                    ),
                ),
            ),
            json.encodeReport(
                TypesettingReport(
                    jobId = "typesetting-job-$createdAt",
                    projectId = PROJECT_ID,
                    runArtifactKey = runId,
                    startedAtEpochMillis = createdAt - 1L,
                    finishedAtEpochMillis = createdAt,
                    status = TypesettingJobStatus.SUCCEEDED,
                    totalPageCount = 1,
                    committedPageCount = 1,
                    preservedPageCount = 0,
                    typesetRegionCount = 0,
                    preservedRegionCount = 0,
                    changedPixelCount = 0,
                    retryCount = 0,
                ),
            ),
        )
        Files.createDirectories(directory.resolve("pages/0000"))
        Files.write(
            directory.resolve("pages/0000/typesetting.json"),
            json.encodePageArtifact(
                PageTypesettingArtifact(
                    pageId = PAGE_ID,
                    pageOrder = 0,
                    cleanupPageArtifactKey = cleanupPageId,
                    pageArtifactKey = pageId,
                    visibleWidth = 1,
                    visibleHeight = 1,
                    renderedImageByteLength = 1L,
                    dependencies = dependencies,
                    regions = emptyList(),
                ),
            ).toByteArray(),
        )
        Files.write(directory.resolve("pages/0000/typeset.png"), byteArrayOf(1))
        return StageRunRef(runId, pageId)
    }

    private fun publishExport(project: Path, typesetting: StageRunRef) {
        val destinationUri = "content://rs.masumi.test/tree/library"
        val destinationId = "export-destination"
        val dependencies = ExportDependencies(
            typesettingRunArtifactKey = typesetting.runId,
            policy = ExportPolicy(),
        )
        val exportId = "export-run"
        val page = ExportJobPage(
            pageId = PAGE_ID,
            pageOrder = 0,
            typesettingPageArtifactKey = typesetting.pageId,
            outputName = "0001.png",
            source = ExportPageSource.FLATTENED,
            state = ExportPageState.COMMITTED,
            attemptCount = 1,
            byteLength = 1L,
        )
        val job = ExportJobRecord(
            jobId = "export-job",
            projectId = PROJECT_ID,
            exportKey = exportId,
            destinationUri = destinationUri,
            destinationKey = destinationId,
            startedAtEpochMillis = 10L,
            updatedAtEpochMillis = 11L,
            status = ExportJobStatus.SUCCEEDED,
            dependencies = dependencies,
            pages = listOf(page),
        )
        ExportArtifactStore(project).commitSuccessfulExport(
            job,
            ExportReport(
                jobId = job.jobId,
                projectId = PROJECT_ID,
                exportKey = exportId,
                destinationKey = destinationId,
                typesettingRunArtifactKey = typesetting.runId,
                startedAtEpochMillis = 10L,
                finishedAtEpochMillis = 11L,
                status = ExportJobStatus.SUCCEEDED,
                totalPageCount = 1,
                exportedPageCount = 1,
                flattenedPageCount = 1,
                cleanedFallbackPageCount = 0,
                sourceFallbackPageCount = 0,
                reusedPageCount = 0,
                totalByteCount = 1L,
                retryCount = 0,
            ),
        )
    }

    private fun writePublished(
        project: Path,
        stage: String,
        runId: String,
        artifact: String,
        report: String,
    ): Path = Files.createDirectories(project.resolve("artifacts/$stage/$runId")).also { directory ->
        Files.write(directory.resolve("artifact.json"), artifact.toByteArray())
        Files.write(directory.resolve("report.json"), report.toByteArray())
    }

    private fun ProjectPipelineState?.requireState(): ProjectPipelineState = requireNotNull(this)

    private data class CurrentChain(
        val project: Path,
        val cleanupRunId: String,
        val typesetting: StageRunRef,
    )

    private data class StageRunRef(val runId: String, val pageId: String)

    private companion object {
        const val PROJECT_ID = "project"
        const val PAGE_ID = "page-1"
        const val DETECTION_RUN_ID = "detection-run-current"
        const val DETECTION_PAGE_ID = "detection-page-current"
        const val OCR_RUN_ID = "ocr-run-current"
        const val OCR_PAGE_ID = "ocr-page-current"
        const val TRANSLATION_RUN_ID = "translation-run-current"
        const val TRANSLATION_PAGE_ID = "translation-page-current"
        const val CLEANUP_RUN_ID = "cleanup-run-current"
        const val CLEANUP_PAGE_ID = "cleanup-page-current"
        const val TYPESETTING_RUN_ID = "typesetting-run-current"
        const val TYPESETTING_PAGE_ID = "typesetting-page-current"
    }
}
