package rs.masumi.app.pipeline

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.app.ocr.currentOcrDependencies
import rs.masumi.core.cleanup.CleanupIdentity
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.cleanup.CleanupReport
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPreprocessingConfig
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.DetectionThresholdConfig
import rs.masumi.core.exporting.ExportArtifactStore
import rs.masumi.core.exporting.ExportDependencies
import rs.masumi.core.exporting.ExportIdentity
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
import rs.masumi.core.ocr.OcrIdentity
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrReport
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.ocr.OcrRunEntry
import rs.masumi.core.ocr.PageOcrArtifact
import rs.masumi.core.detection.VisibleOrientation
import rs.masumi.core.serialization.CleanupJson
import rs.masumi.core.serialization.DetectionJson
import rs.masumi.core.serialization.OcrJson
import rs.masumi.core.serialization.ProjectJson
import rs.masumi.core.serialization.TranslationJson
import rs.masumi.core.serialization.TypesettingJson
import rs.masumi.core.translation.TranslationArtifactIdentity
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
import rs.masumi.core.typesetting.TypesettingDependencies
import rs.masumi.core.typesetting.TypesettingIdentity
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.typesetting.TypesettingPolicy
import rs.masumi.core.typesetting.TypesettingReport
import rs.masumi.core.typesetting.TypesettingRunArtifact

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

            publishExport(chain.project, chain.typesettingRunKey)
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
            val stalePolicyKey = publishTypesetting(
                project = chain.project,
                cleanupRunKey = chain.cleanupRunKey,
                policy = TypesettingPolicy(revision = "legacy-typesetting-policy"),
                createdAt = 7L,
            )
            publishTypesetting(
                project = chain.project,
                cleanupRunKey = sha('c'),
                policy = TypesettingPolicy(),
                createdAt = 8L,
            )

            val current = CurrentPipelineArtifactsReader(workspace).read(PROJECT_ID)

            assertEquals(chain.typesettingRunKey, current?.typesetting?.artifact?.runArtifactKey)
            publishExport(chain.project, stalePolicyKey)
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
        Files.write(
            project.resolve("manifest.json"),
            ProjectJson().encodeManifest(
                ProjectManifest(
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = 1L,
                    pages = listOf(
                        PageRecord(
                            order = 0,
                            pageId = SOURCE_SHA,
                            sourceSha256 = SOURCE_SHA,
                            originalName = "page.png",
                            mediaType = "image/png",
                            byteLength = 1L,
                            storedPath = "source/page.png",
                        ),
                    ),
                ),
            ).toByteArray(),
        )

        val detectionKey = sha('1')
        val detectionJson = DetectionJson()
        writePublished(
            project,
            "detection",
            detectionKey,
            detectionJson.encodeRun(
                DetectionRunArtifact(
                    runArtifactKey = detectionKey,
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = 2L,
                    model = PinnedComicDetector.descriptor.toModelRef(),
                    preprocessing = DetectionPreprocessingConfig(),
                    thresholds = DetectionThresholdConfig(),
                    entries = emptyList(),
                ),
            ),
            detectionJson.encodeReport(
                DetectionReport(
                    jobId = "detection-job",
                    projectId = PROJECT_ID,
                    runArtifactKey = detectionKey,
                    startedAtEpochMillis = 1L,
                    finishedAtEpochMillis = 2L,
                    status = DetectionJobStatus.SUCCEEDED,
                    totalPageCount = 0,
                    committedPageCount = 0,
                    preservedPageCount = 0,
                    retryCount = 0,
                ),
            ),
        )

        val ocrDependencies = currentOcrDependencies()
        val detectionPageKey = sha('d')
        val ocrPageKey = OcrIdentity.pageArtifactKey(
            SOURCE_SHA,
            detectionPageKey,
            ocrDependencies,
        )
        val ocrKey = OcrIdentity.runArtifactKey(listOf(0 to ocrPageKey))
        val ocrJson = OcrJson()
        val ocrDirectory = writePublished(
            project,
            "ocr",
            ocrKey,
            ocrJson.encodeRun(
                OcrRunArtifact(
                    runArtifactKey = ocrKey,
                    projectId = PROJECT_ID,
                    detectionRunArtifactKey = detectionKey,
                    createdAtEpochMillis = 3L,
                    dependencies = ocrDependencies,
                    entries = listOf(
                        OcrRunEntry(
                            order = 0,
                            pageId = SOURCE_SHA,
                            sourceSha256 = SOURCE_SHA,
                            detectionPageArtifactKey = detectionPageKey,
                            pageArtifactKey = ocrPageKey,
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
                    runArtifactKey = ocrKey,
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
                    pageId = SOURCE_SHA,
                    sourceSha256 = SOURCE_SHA,
                    detectionPageArtifactKey = detectionPageKey,
                    pageArtifactKey = ocrPageKey,
                    visibleWidth = 1,
                    visibleHeight = 1,
                    orientation = VisibleOrientation.NORMAL,
                    dependencies = ocrDependencies,
                    regions = emptyList(),
                ),
            ).toByteArray(),
        )
        Files.write(ocrDirectory.resolve("pages/0000/preview.png"), byteArrayOf(0))

        val glossarySha = TranslationArtifactIdentity.glossarySha256(emptyList())
        val translationDependencies = TranslationDependencies(
            ocrRunArtifactKey = ocrKey,
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
            initialGlossarySha256 = glossarySha,
        )
        val translationKey = TranslationArtifactIdentity.runArtifactKey(emptyList(), translationDependencies)
        val translationJson = TranslationJson()
        val translationDirectory = writePublished(
            project,
            "translation",
            translationKey,
            translationJson.encodeRun(
                TranslationRunArtifact(
                    runArtifactKey = translationKey,
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = 4L,
                    dependencies = translationDependencies,
                    entries = emptyList(),
                    glossaryPath = "glossary.json",
                ),
            ),
            translationJson.encodeReport(
                TranslationReport(
                    jobId = "translation-job",
                    projectId = PROJECT_ID,
                    runArtifactKey = translationKey,
                    startedAtEpochMillis = 3L,
                    finishedAtEpochMillis = 4L,
                    status = TranslationJobStatus.SUCCEEDED,
                    totalPageCount = 0,
                    committedPageCount = 0,
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
        Files.write(
            translationDirectory.resolve("glossary.json"),
            translationJson.encodeGlossary(
                TranslationGlossaryArtifact(sha256 = glossarySha, entries = emptyList()),
            ).toByteArray(),
        )

        val cleanupDependencies = currentCleanupDependencies(translationKey)
        val cleanupKey = CleanupIdentity.runArtifactKey(emptyList(), cleanupDependencies)
        val cleanupJson = CleanupJson()
        writePublished(
            project,
            "cleanup",
            cleanupKey,
            cleanupJson.encodeRun(
                CleanupRunArtifact(
                    runArtifactKey = cleanupKey,
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = 5L,
                    dependencies = cleanupDependencies,
                    entries = emptyList(),
                ),
            ),
            cleanupJson.encodeReport(
                CleanupReport(
                    jobId = "cleanup-job",
                    projectId = PROJECT_ID,
                    runArtifactKey = cleanupKey,
                    startedAtEpochMillis = 4L,
                    finishedAtEpochMillis = 5L,
                    status = CleanupJobStatus.SUCCEEDED,
                    totalPageCount = 0,
                    committedPageCount = 0,
                    preservedPageCount = 0,
                    cleanedRegionCount = 0,
                    preservedRegionCount = 0,
                    changedPixelCount = 0,
                    retryCount = 0,
                ),
            ),
        )

        return CurrentChain(
            project = project,
            cleanupRunKey = cleanupKey,
            typesettingRunKey = publishTypesetting(
                project = project,
                cleanupRunKey = cleanupKey,
                policy = TypesettingPolicy(),
                createdAt = 6L,
            ),
        )
    }

    private fun publishTypesetting(
        project: Path,
        cleanupRunKey: String,
        policy: TypesettingPolicy,
        createdAt: Long,
    ): String {
        val dependencies = TypesettingDependencies(cleanupRunArtifactKey = cleanupRunKey, policy = policy)
        val runKey = TypesettingIdentity.runArtifactKey(emptyList(), dependencies)
        val json = TypesettingJson()
        writePublished(
            project,
            "typesetting",
            runKey,
            json.encodeRun(
                TypesettingRunArtifact(
                    runArtifactKey = runKey,
                    projectId = PROJECT_ID,
                    createdAtEpochMillis = createdAt,
                    dependencies = dependencies,
                    entries = emptyList(),
                ),
            ),
            json.encodeReport(
                TypesettingReport(
                    jobId = "typesetting-job-$createdAt",
                    projectId = PROJECT_ID,
                    runArtifactKey = runKey,
                    startedAtEpochMillis = createdAt - 1L,
                    finishedAtEpochMillis = createdAt,
                    status = TypesettingJobStatus.SUCCEEDED,
                    totalPageCount = 0,
                    committedPageCount = 0,
                    preservedPageCount = 0,
                    typesetRegionCount = 0,
                    preservedRegionCount = 0,
                    changedPixelCount = 0,
                    retryCount = 0,
                ),
            ),
        )
        return runKey
    }

    private fun publishExport(project: Path, typesettingRunKey: String) {
        val destinationUri = "content://rs.masumi.test/tree/library"
        val destinationKey = ExportIdentity.destinationKey(destinationUri)
        val dependencies = ExportDependencies(
            typesettingRunArtifactKey = typesettingRunKey,
            policy = ExportPolicy(),
        )
        val exportKey = ExportIdentity.exportKey(destinationKey, dependencies)
        val page = ExportJobPage(
            pageId = SOURCE_SHA,
            pageOrder = 0,
            sourceSha256 = SOURCE_SHA,
            typesettingPageArtifactKey = sha('8'),
            outputName = ExportIdentity.outputName(0, 1, dependencies.policy, "png"),
            source = ExportPageSource.FLATTENED,
            state = ExportPageState.COMMITTED,
            attemptCount = 1,
            outputSha256 = sha('9'),
            byteLength = 1L,
        )
        val job = ExportJobRecord(
            jobId = "export-job",
            projectId = PROJECT_ID,
            exportKey = exportKey,
            destinationUri = destinationUri,
            destinationKey = destinationKey,
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
                exportKey = exportKey,
                destinationKey = destinationKey,
                typesettingRunArtifactKey = typesettingRunKey,
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
        runKey: String,
        artifact: String,
        report: String,
    ): Path = Files.createDirectories(project.resolve("artifacts/$stage/$runKey")).also { directory ->
        Files.write(directory.resolve("artifact.json"), artifact.toByteArray())
        Files.write(directory.resolve("report.json"), report.toByteArray())
    }

    private fun ProjectPipelineState?.requireState(): ProjectPipelineState = requireNotNull(this)

    private fun sha(character: Char): String = character.toString().repeat(64)

    private data class CurrentChain(
        val project: Path,
        val cleanupRunKey: String,
        val typesettingRunKey: String,
    )

    private companion object {
        const val PROJECT_ID = "project"
        val SOURCE_SHA = "a".repeat(64)
    }
}
