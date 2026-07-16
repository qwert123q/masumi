package rs.masumi.app.cleanup

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.cleanup.CleanupJobStatus
import rs.masumi.app.typesetting.TypesettingRunner
import rs.masumi.app.exporting.DestinationWriteResult
import rs.masumi.app.exporting.ExportCancellationSignal
import rs.masumi.app.exporting.ExportRunner
import rs.masumi.app.exporting.FolderExportDestination
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.importer.IdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl
import rs.masumi.core.ocr.OcrAttemptArtifact
import rs.masumi.core.ocr.OcrCandidate
import rs.masumi.core.ocr.OcrCropStrategy
import rs.masumi.core.ocr.OcrDependencies
import rs.masumi.core.ocr.OcrExecutionBackend
import rs.masumi.core.ocr.OcrGenerationConfig
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrProtectionPolicy
import rs.masumi.core.ocr.OcrRegionArtifact
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.ocr.OcrReport
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.ocr.OcrRunEntry
import rs.masumi.core.ocr.OcrSemanticStatus
import rs.masumi.core.ocr.PageOcrArtifact
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.detection.VisibleOrientation
import rs.masumi.core.serialization.OcrJson
import rs.masumi.core.serialization.ProjectJson
import rs.masumi.core.serialization.TranslationJson
import rs.masumi.core.translation.PageTranslationArtifact
import rs.masumi.core.translation.TranslationArtifactIdentity
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationDependencies
import rs.masumi.core.translation.TranslationGlossaryArtifact
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.TranslationProviderDependency
import rs.masumi.core.translation.TranslationReport
import rs.masumi.core.translation.TranslationResultState
import rs.masumi.core.translation.TranslationRole
import rs.masumi.core.translation.TranslationRunArtifact
import rs.masumi.core.translation.TranslationRunEntry
import rs.masumi.core.translation.ValidatedTranslationItem

@RunWith(AndroidJUnit4::class)
class CleanupRunnerTest {
    @Test
    fun cancelledPageResumesFromPublishedTranslationAndCompletedRunIsReused() {
        val workspace = Files.createTempDirectory("masumi-cleanup-runner")
        try {
            val sourceBytes = createSourcePng()
            publishProjectAndDependencies(workspace, sourceBytes)
            val runner = CleanupRunner(
                workspaceRoot = workspace,
                idSource = IdSource { "cleanup-job" },
            )
            val cancel = AtomicBoolean(false)

            val cancelled = runner.run(PROJECT_ID, cancel::get) { progress ->
                if (progress.currentPageOrder != null) cancel.set(true)
            }

            assertEquals(CleanupJobStatus.CANCELLED, cancelled.job.status)
            assertArrayEquals(sourceBytes, Files.readAllBytes(sourcePath(workspace)))

            val completed = runner.run(PROJECT_ID, { false }) { }

            assertEquals(CleanupJobStatus.SUCCEEDED, completed.job.status)
            assertEquals(1, completed.report?.cleanedRegionCount)
            assertTrue((completed.report?.changedPixelCount ?: 0) > 0)
            assertNotNull(completed.publishedDirectory)
            assertArrayEquals(sourceBytes, Files.readAllBytes(sourcePath(workspace)))

            val cached = runner.run(PROJECT_ID, { false }) { }

            assertEquals(completed.runArtifact, cached.runArtifact)
            assertEquals(completed.report, cached.report)

            val typesetter = TypesettingRunner(
                workspaceRoot = workspace,
                idSource = IdSource { "typesetting-job" },
            )
            cancel.set(false)
            val cancelledTypesetting = typesetter.run(PROJECT_ID, cancel::get) { progress ->
                if (progress.currentPageOrder != null) cancel.set(true)
            }
            assertEquals(TypesettingJobStatus.CANCELLED, cancelledTypesetting.job.status)

            val completedTypesetting = typesetter.run(PROJECT_ID, { false }) { }
            assertEquals(TypesettingJobStatus.SUCCEEDED, completedTypesetting.job.status)
            assertEquals(1, completedTypesetting.report?.typesetRegionCount)
            assertTrue((completedTypesetting.report?.changedPixelCount ?: 0) > 0)
            assertNotNull(completedTypesetting.publishedDirectory)
            assertArrayEquals(sourceBytes, Files.readAllBytes(sourcePath(workspace)))

            val cachedTypesetting = typesetter.run(PROJECT_ID, { false }) { }
            assertEquals(completedTypesetting.runArtifact, cachedTypesetting.runArtifact)
            assertEquals(completedTypesetting.report, cachedTypesetting.report)

            val destination = InMemoryExportDestination()
            val exportIds = AtomicInteger()
            val exporter = ExportRunner(
                workspaceRoot = workspace,
                destinationFactory = { _, _ -> destination },
                idSource = IdSource { "export-job-${exportIds.incrementAndGet()}" },
            )
            cancel.set(false)
            val cancelledExport = exporter.run(PROJECT_ID, DESTINATION_URI, cancel::get) { progress ->
                if (progress.currentPageOrder != null) cancel.set(true)
            }
            assertEquals(ExportJobStatus.CANCELLED, cancelledExport.job.status)
            assertTrue(destination.files.isEmpty())

            cancel.set(false)
            val completedExport = exporter.run(PROJECT_ID, DESTINATION_URI, cancel::get) { }
            assertEquals(ExportJobStatus.SUCCEEDED, completedExport.job.status)
            assertEquals(setOf("0001.png"), destination.files.keys)
            assertEquals(1, completedExport.report?.flattenedPageCount)
            assertEquals(0, completedExport.report?.reusedPageCount)

            val repeatedExport = exporter.run(PROJECT_ID, DESTINATION_URI, { false }) { }
            assertEquals(ExportJobStatus.SUCCEEDED, repeatedExport.job.status)
            assertEquals(1, repeatedExport.report?.reusedPageCount)
            assertArrayEquals(sourceBytes, Files.readAllBytes(sourcePath(workspace)))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun publishProjectAndDependencies(workspace: Path, sourceBytes: ByteArray) {
        val sourceSha = sha256(sourceBytes)
        val project = workspace.resolve("projects/$PROJECT_ID")
        val source = project.resolve("sources/$sourceSha.png")
        Files.createDirectories(source.parent)
        Files.write(source, sourceBytes)
        val page = PageRecord(
            order = 0,
            pageId = sourceSha,
            sourceSha256 = sourceSha,
            originalName = "page.png",
            mediaType = "image/png",
            byteLength = sourceBytes.size.toLong(),
            storedPath = "sources/$sourceSha.png",
        )
        writeUtf8(
            project.resolve("manifest.json"),
            ProjectJson().encodeManifest(
                ProjectManifest(projectId = PROJECT_ID, createdAtEpochMillis = 1L, pages = listOf(page)),
            ),
        )
        val ocr = publishOcr(project, page)
        publishTranslation(project, page, ocr)
    }

    private fun publishOcr(project: Path, page: PageRecord): OcrFixture {
        val runKey = "a".repeat(64)
        val pageKey = "b".repeat(64)
        val regionId = "c".repeat(64)
        val runDirectory = project.resolve("artifacts/ocr/$runKey")
        Files.createDirectories(runDirectory.resolve("pages/${page.pageId}"))
        val descriptor = PinnedPaddleOcrVl.descriptor
        val dependencies = OcrDependencies(
            modelPackage = descriptor.toRef(),
            runtime = descriptor.runtime.toRef(),
            generation = OcrGenerationConfig(prompt = descriptor.prompt),
        )
        val candidate = OcrCandidate(
            ocrRegionId = regionId,
            sourceRegionIds = listOf("d".repeat(64)),
            representativeSourceRegionId = "d".repeat(64),
            sourceClass = DetectorClass.TEXT_IN_BUBBLE,
            detectorConfidence = 0.95,
            box = PixelBox(10.0, 10.0, 54.0, 54.0),
            semanticStatus = OcrSemanticStatus.REQUIRED_TEXT,
            protectionPolicy = OcrProtectionPolicy.NONE,
            readingOrderRank = 0,
        )
        val attempt = OcrAttemptArtifact(
            executionBackend = OcrExecutionBackend.VULKAN,
            strategy = OcrCropStrategy.PADDED_TEXT,
            cropBox = candidate.box,
            rawText = "テスト",
            normalizedText = "テスト",
            tokenIds = listOf(1),
            tokenProbabilities = listOf(0.99),
            sourceWidth = 44,
            sourceHeight = 44,
            processedWidth = 44,
            processedHeight = 44,
            visualTokenCount = 1,
            generatedTokenCount = 1,
            reachedEos = true,
            truncated = false,
            repetitionStopped = false,
            invalidUtf8 = false,
            promptEvaluationMillis = 1L,
            generationMillis = 1L,
        )
        val pageArtifact = PageOcrArtifact(
            pageId = page.pageId,
            sourceSha256 = page.sourceSha256,
            detectionPageArtifactKey = "e".repeat(64),
            pageArtifactKey = pageKey,
            visibleWidth = 64,
            visibleHeight = 64,
            orientation = VisibleOrientation.NORMAL,
            dependencies = dependencies,
            regions = listOf(
                OcrRegionArtifact(
                    candidate = candidate,
                    attempts = listOf(attempt),
                    selectedAttemptIndex = 0,
                    quality = null,
                    state = OcrRegionState.RECOGNIZED,
                ),
            ),
        )
        val run = OcrRunArtifact(
            runArtifactKey = runKey,
            projectId = PROJECT_ID,
            detectionRunArtifactKey = "f".repeat(64),
            createdAtEpochMillis = 2L,
            dependencies = dependencies,
            entries = listOf(
                OcrRunEntry(
                    order = 0,
                    pageId = page.pageId,
                    sourceSha256 = page.sourceSha256,
                    detectionPageArtifactKey = pageArtifact.detectionPageArtifactKey,
                    pageArtifactKey = pageKey,
                    state = OcrPageState.COMMITTED,
                    artifactPath = "pages/${page.pageId}/ocr.json",
                    previewPath = "previews/0000.png",
                ),
            ),
        )
        val report = OcrReport(
            jobId = "ocr-job",
            projectId = PROJECT_ID,
            runArtifactKey = runKey,
            startedAtEpochMillis = 1L,
            finishedAtEpochMillis = 2L,
            status = OcrJobStatus.SUCCEEDED,
            totalPageCount = 1,
            committedPageCount = 1,
            totalRegionCount = 1,
            recognizedRegionCount = 1,
            needsFallbackRegionCount = 0,
            noTextRegionCount = 0,
            preservedRegionCount = 0,
            retryCount = 0,
        )
        val json = OcrJson()
        writeUtf8(runDirectory.resolve("pages/${page.pageId}/ocr.json"), json.encodePageArtifact(pageArtifact))
        Files.createDirectories(runDirectory.resolve("previews"))
        Files.write(runDirectory.resolve("previews/0000.png"), byteArrayOf(1))
        writeUtf8(runDirectory.resolve("artifact.json"), json.encodeRun(run))
        writeUtf8(runDirectory.resolve("report.json"), json.encodeReport(report))
        return OcrFixture(runKey, pageKey, regionId)
    }

    private fun publishTranslation(project: Path, page: PageRecord, ocr: OcrFixture) {
        val runKey = "1".repeat(64)
        val pageKey = "2".repeat(64)
        val translationRegionId = "3".repeat(64)
        val runDirectory = project.resolve("artifacts/translation/$runKey")
        Files.createDirectories(runDirectory.resolve("pages/${page.pageId}"))
        val emptyGlossarySha = TranslationArtifactIdentity.glossarySha256(emptyList())
        val dependencies = TranslationDependencies(
            ocrRunArtifactKey = ocr.runKey,
            policy = TranslationPolicy(),
            prompt = TranslationPromptRef(),
            batching = TranslationBatchingConfig(),
            provider = TranslationProviderDependency(
                modelId = "model-safe",
                temperature = 0.2,
                maximumOutputTokens = 512,
                requestJsonObjectFormat = true,
            ),
            initialGlossarySha256 = emptyGlossarySha,
        )
        val pageArtifact = PageTranslationArtifact(
            pageId = page.pageId,
            pageOrder = 0,
            ocrPageArtifactKey = ocr.pageKey,
            pageArtifactKey = pageKey,
            dependencies = dependencies,
            items = listOf(
                ValidatedTranslationItem(
                    translationRegionId = translationRegionId,
                    ocrRegionId = ocr.regionId,
                    role = TranslationRole.DIALOGUE,
                    translatedText = "测试",
                    state = TranslationResultState.TRANSLATED,
                ),
            ),
            protectedOcrRegions = emptyList(),
        )
        val run = TranslationRunArtifact(
            runArtifactKey = runKey,
            projectId = PROJECT_ID,
            createdAtEpochMillis = 3L,
            dependencies = dependencies,
            entries = listOf(
                TranslationRunEntry(
                    pageId = page.pageId,
                    pageOrder = 0,
                    ocrPageArtifactKey = ocr.pageKey,
                    pageArtifactKey = pageKey,
                    artifactPath = "pages/${page.pageId}/translation.json",
                ),
            ),
            glossaryPath = "glossary.json",
        )
        val report = TranslationReport(
            jobId = "translation-job",
            projectId = PROJECT_ID,
            runArtifactKey = runKey,
            startedAtEpochMillis = 2L,
            finishedAtEpochMillis = 3L,
            status = TranslationJobStatus.SUCCEEDED,
            totalPageCount = 1,
            committedPageCount = 1,
            totalWindowCount = 1,
            committedWindowCount = 1,
            translatedItemCount = 1,
            preservedItemCount = 0,
            protectedOcrRegionCount = 0,
            promptTokens = 1,
            completionTokens = 1,
            totalTokens = 2,
            retryCount = 0,
        )
        val json = TranslationJson()
        writeUtf8(
            runDirectory.resolve("pages/${page.pageId}/translation.json"),
            json.encodePageArtifact(pageArtifact),
        )
        writeUtf8(
            runDirectory.resolve("glossary.json"),
            json.encodeGlossary(TranslationGlossaryArtifact(sha256 = emptyGlossarySha, entries = emptyList())),
        )
        writeUtf8(runDirectory.resolve("artifact.json"), json.encodeRun(run))
        writeUtf8(runDirectory.resolve("report.json"), json.encodeReport(report))
    }

    private fun createSourcePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (y in 18 until 46) {
            for (x in 25 until 29) bitmap.setPixel(x, y, Color.BLACK)
            for (x in 36 until 40) bitmap.setPixel(x, y, Color.BLACK)
        }
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sourcePath(workspace: Path): Path {
        val project = workspace.resolve("projects/$PROJECT_ID")
        val manifest = Files.newBufferedReader(project.resolve("manifest.json")).use {
            ProjectJson().decodeManifest(it.readText())
        }
        return project.resolve(manifest.pages.single().storedPath)
    }

    private fun writeUtf8(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, Charsets.UTF_8).use { it.write(content) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class OcrFixture(val runKey: String, val pageKey: String, val regionId: String)

    private class InMemoryExportDestination : FolderExportDestination {
        val files = linkedMapOf<String, ByteArray>()

        override fun matches(outputName: String, expectedSha256: String, expectedByteLength: Long): Boolean {
            val bytes = files[outputName] ?: return false
            return bytes.size.toLong() == expectedByteLength && sha256Static(bytes) == expectedSha256
        }

        override fun publish(
            outputName: String,
            bytes: ByteArray,
            expectedSha256: String,
            cancellation: () -> Boolean,
        ): DestinationWriteResult {
            if (cancellation()) throw ExportCancellationSignal()
            val existing = files[outputName]
            if (existing != null && sha256Static(existing) == expectedSha256) {
                return DestinationWriteResult(reusedExisting = true)
            }
            files[outputName] = bytes.copyOf()
            return DestinationWriteResult(reusedExisting = false)
        }

        override fun pruneManagedOutputs(expectedNames: Set<String>) {
            files.keys.removeAll { name -> name.matches(Regex("[0-9]{1,12}\\.png")) && name !in expectedNames }
        }
    }

    private companion object {
        const val PROJECT_ID = "cleanup-project"
        const val DESTINATION_URI = "content://provider/tree/export"

        fun sha256Static(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
