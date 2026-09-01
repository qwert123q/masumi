package rs.masumi.app.pipeline

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.app.ocr.currentOcrDependencies
import rs.masumi.core.cleanup.CleanupDependencies
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.core.cleanup.CleanupReport
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPreprocessingConfig
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.DetectionThresholdConfig
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.modelpackage.PinnedComicDetector
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrReport
import rs.masumi.core.ocr.OcrRunArtifact
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
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.typesetting.TypesettingReport
import rs.masumi.core.typesetting.TypesettingRunArtifact

class WorkspaceJanitorLineageTest {
    @Test
    fun `policy bump retains current lineages and removes cold superseded and legacy quality runs`() {
        val workspace = Files.createTempDirectory("masumi-janitor-lineage")
        try {
            val projectId = "project"
            val project = Files.createDirectories(workspace.resolve("projects/$projectId"))
            Files.write(
                project.resolve("manifest.json"),
                ProjectJson().encodeManifest(
                    ProjectManifest(
                        projectId = projectId,
                        createdAtEpochMillis = 1L,
                        pages = listOf(
                            PageRecord(
                                order = 0,
                                pageId = sha('a'),
                                sourceSha256 = sha('a'),
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
            val ocrKey = sha('2')
            val translationKey = sha('3')
            val cleanupKey = sha('4')
            val typesettingKey = sha('5')
            val qualityKey = sha('6')
            publishDetection(project, projectId, detectionKey)
            publishStaleOcr(project, projectId, detectionKey, ocrKey)
            publishTranslation(project, projectId, ocrKey, translationKey)
            publishCleanup(project, projectId, translationKey, cleanupKey)
            publishTypesetting(project, projectId, cleanupKey, typesettingKey)
            val legacyQuality = Files.createDirectories(project.resolve("artifacts/quality/$qualityKey"))
            Files.write(legacyQuality.resolve("legacy.bin"), ByteArray(1024))

            val superseded = Files.createDirectories(
                project.resolve("artifacts/cleanup/${sha('7')}"),
            )
            Files.write(superseded.resolve("orphan.bin"), ByteArray(4096))
            val stale = FileTime.from(
                System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2),
                TimeUnit.MILLISECONDS,
            )
            Files.walk(project.resolve("artifacts")).use { paths ->
                paths.forEach { Files.setLastModifiedTime(it, stale) }
            }

            WorkspaceJanitor.sweepProject(workspace, projectId)

            listOf(
                "detection" to detectionKey,
                "ocr" to ocrKey,
                "translation" to translationKey,
                "cleanup" to cleanupKey,
                "typesetting" to typesettingKey,
            ).forEach { (stage, runKey) ->
                assertTrue(Files.isDirectory(project.resolve("artifacts/$stage/$runKey")))
            }
            assertFalse(Files.exists(legacyQuality))
            assertFalse(Files.exists(superseded))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun publishDetection(project: Path, projectId: String, runKey: String) {
        val json = DetectionJson()
        writePublished(
            project,
            "detection",
            runKey,
            json.encodeRun(
                DetectionRunArtifact(
                    runArtifactKey = runKey,
                    projectId = projectId,
                    createdAtEpochMillis = 1L,
                    model = PinnedComicDetector.descriptor.toModelRef(),
                    preprocessing = DetectionPreprocessingConfig(),
                    thresholds = DetectionThresholdConfig(),
                    entries = emptyList(),
                ),
            ),
            json.encodeReport(
                DetectionReport(
                    jobId = "detection-job",
                    projectId = projectId,
                    runArtifactKey = runKey,
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
    }

    private fun publishStaleOcr(
        project: Path,
        projectId: String,
        detectionKey: String,
        runKey: String,
    ) {
        val json = OcrJson()
        val staleDependencies = currentOcrDependencies().copy(
            crop = currentOcrDependencies().crop.copy(revision = "legacy-three-crops"),
        )
        writePublished(
            project,
            "ocr",
            runKey,
            json.encodeRun(
                OcrRunArtifact(
                    runArtifactKey = runKey,
                    projectId = projectId,
                    detectionRunArtifactKey = detectionKey,
                    createdAtEpochMillis = 3L,
                    dependencies = staleDependencies,
                    entries = emptyList(),
                ),
            ),
            json.encodeReport(
                OcrReport(
                    jobId = "ocr-job",
                    projectId = projectId,
                    runArtifactKey = runKey,
                    startedAtEpochMillis = 2L,
                    finishedAtEpochMillis = 3L,
                    status = OcrJobStatus.SUCCEEDED,
                    totalPageCount = 0,
                    committedPageCount = 0,
                    totalRegionCount = 0,
                    recognizedRegionCount = 0,
                    needsFallbackRegionCount = 0,
                    noTextRegionCount = 0,
                    preservedRegionCount = 0,
                    retryCount = 0,
                ),
            ),
        )
    }

    private fun publishTranslation(project: Path, projectId: String, ocrKey: String, runKey: String) {
        val json = TranslationJson()
        val glossarySha = TranslationArtifactIdentity.glossarySha256(emptyList())
        val dependencies = TranslationDependencies(
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
        val directory = writePublished(
            project,
            "translation",
            runKey,
            json.encodeRun(
                TranslationRunArtifact(
                    runArtifactKey = runKey,
                    projectId = projectId,
                    createdAtEpochMillis = 4L,
                    dependencies = dependencies,
                    entries = emptyList(),
                    glossaryPath = "glossary.json",
                ),
            ),
            json.encodeReport(
                TranslationReport(
                    jobId = "translation-job",
                    projectId = projectId,
                    runArtifactKey = runKey,
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
            directory.resolve("glossary.json"),
            json.encodeGlossary(
                TranslationGlossaryArtifact(
                    sha256 = glossarySha,
                    entries = emptyList(),
                ),
            ).toByteArray(),
        )
    }

    private fun publishCleanup(
        project: Path,
        projectId: String,
        translationKey: String,
        runKey: String,
    ) {
        val json = CleanupJson()
        val dependencies = CleanupDependencies(translationRunArtifactKey = translationKey)
        writePublished(
            project,
            "cleanup",
            runKey,
            json.encodeRun(
                CleanupRunArtifact(
                    runArtifactKey = runKey,
                    projectId = projectId,
                    createdAtEpochMillis = 5L,
                    dependencies = dependencies,
                    entries = emptyList(),
                ),
            ),
            json.encodeReport(
                CleanupReport(
                    jobId = "cleanup-job",
                    projectId = projectId,
                    runArtifactKey = runKey,
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
    }

    private fun publishTypesetting(
        project: Path,
        projectId: String,
        cleanupKey: String,
        runKey: String,
    ) {
        val json = TypesettingJson()
        val dependencies = TypesettingDependencies(cleanupRunArtifactKey = cleanupKey)
        writePublished(
            project,
            "typesetting",
            runKey,
            json.encodeRun(
                TypesettingRunArtifact(
                    runArtifactKey = runKey,
                    projectId = projectId,
                    createdAtEpochMillis = 6L,
                    dependencies = dependencies,
                    entries = emptyList(),
                ),
            ),
            json.encodeReport(
                TypesettingReport(
                    jobId = "typesetting-job",
                    projectId = projectId,
                    runArtifactKey = runKey,
                    startedAtEpochMillis = 5L,
                    finishedAtEpochMillis = 6L,
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
    }

    private fun writePublished(
        project: Path,
        stage: String,
        runKey: String,
        artifact: String,
        report: String,
    ): Path = Files.createDirectories(project.resolve("artifacts/$stage/$runKey")).also {
        Files.write(it.resolve("artifact.json"), artifact.toByteArray())
        Files.write(it.resolve("report.json"), report.toByteArray())
    }

    private fun sha(character: Char): String = character.toString().repeat(64)
}
