package rs.masumi.app.cleanup

import rs.masumi.app.PageImageEncoder
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
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
import rs.masumi.app.exporting.ExportDestinationException
import rs.masumi.app.exporting.ExportRunner
import rs.masumi.app.exporting.ExpectedDestinationOutput
import rs.masumi.app.exporting.FolderExportDestination
import rs.masumi.core.exporting.ExportJobStatus
import rs.masumi.core.typesetting.TypesettingJobStatus
import rs.masumi.core.importer.IdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl
import rs.masumi.core.modelpackage.PinnedAotInpainter
import rs.masumi.core.modelpackage.PinnedComicDetector
import rs.masumi.core.modelpackage.PinnedComicTextSegmenter
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
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionPostProcessor
import rs.masumi.core.detection.DetectionPreprocessingConfig
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.DetectionRunEntry
import rs.masumi.core.detection.DetectionThresholdConfig
import rs.masumi.core.detection.ModelQuery
import rs.masumi.core.detection.PageDetectionArtifact
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.detection.VisibleOrientation
import rs.masumi.core.serialization.DetectionJson
import rs.masumi.core.serialization.OcrJson
import rs.masumi.core.serialization.ProjectJson
import rs.masumi.core.serialization.TranslationJson
import rs.masumi.core.translation.PageTranslationArtifact
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
                maskModel = PinnedComicTextSegmenter.descriptor.toModelRef(),
                neuralModel = PinnedAotInpainter.descriptor.toModelRef(),
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
            assertEquals(2, completed.report?.cleanedRegionCount)
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
            assertEquals(2, completedTypesetting.report?.typesetRegionCount)
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
                destinationFactory = { _, _, _ -> destination },
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
            val pageExtension = PageImageEncoder.preferredExtension
            assertEquals(setOf("0001.$pageExtension", "0002.$pageExtension"), destination.files.keys)
            assertEquals(2, completedExport.report?.flattenedPageCount)
            assertEquals(0, completedExport.report?.reusedPageCount)

            val repeatedExport = exporter.run(PROJECT_ID, DESTINATION_URI, { false }) { }
            assertEquals(ExportJobStatus.SUCCEEDED, repeatedExport.job.status)
            assertEquals(2, repeatedExport.report?.reusedPageCount)
            val stableOutput = destination.snapshotFiles()
            val pruneCallsAfterSuccess = destination.pruneCalls

            cancel.set(false)
            val cancelledOverStableOutput = exporter.run(PROJECT_ID, DESTINATION_URI, cancel::get) { progress ->
                if (progress.currentPageOrder != null) cancel.set(true)
            }
            assertEquals(ExportJobStatus.CANCELLED, cancelledOverStableOutput.job.status)
            destination.assertFilesEqual(stableOutput)
            assertEquals(pruneCallsAfterSuccess, destination.pruneCalls)

            cancel.set(false)
            destination.failNextPublish = true
            val failedOverStableOutput = exporter.run(PROJECT_ID, DESTINATION_URI, cancel::get) { }
            assertEquals(ExportJobStatus.FAILED, failedOverStableOutput.job.status)
            destination.assertFilesEqual(stableOutput)
            assertEquals(pruneCallsAfterSuccess, destination.pruneCalls)

            assertArrayEquals(sourceBytes, Files.readAllBytes(sourcePath(workspace)))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun publishProjectAndDependencies(workspace: Path, sourceBytes: ByteArray) {
        val pageId = "source-page"
        val project = workspace.resolve("projects/$PROJECT_ID")
        val source = project.resolve("sources/$pageId.png")
        Files.createDirectories(source.parent)
        Files.write(source, sourceBytes)
        val firstPage = PageRecord(
            order = 0,
            pageId = pageId,
            originalName = "page.png",
            mediaType = "image/png",
            byteLength = sourceBytes.size.toLong(),
            storedPath = "sources/$pageId.png",
        )
        val pages = listOf(
            firstPage,
            firstPage.copy(order = 1, originalName = "page-2.png"),
        )
        writeUtf8(
            project.resolve("manifest.json"),
            ProjectJson().encodeManifest(
                ProjectManifest(projectId = PROJECT_ID, createdAtEpochMillis = 1L, pages = pages),
            ),
        )
        publishDetection(project, pages)
        val ocr = publishOcr(project, pages)
        publishTranslation(project, pages, ocr)
    }

    private fun publishDetection(project: Path, pages: List<PageRecord>) {
        val page = pages.first()
        val runKey = "f".repeat(64)
        val pageKey = "e".repeat(64)
        val runDirectory = project.resolve("artifacts/detection/$runKey")
        val regionsPath = "pages/${page.pageId}/regions.json"
        Files.createDirectories(runDirectory.resolve("pages/${page.pageId}"))
        Files.createDirectories(runDirectory.resolve("previews"))
        val model = PinnedComicDetector.descriptor.toModelRef()
        val preprocessing = DetectionPreprocessingConfig()
        val thresholds = DetectionThresholdConfig()
        val processed = DetectionPostProcessor.process(
            pageId = page.pageId,
            pageArtifactKey = pageKey,
            pageWidth = 64,
            pageHeight = 64,
            thresholds = thresholds,
            queries = List(300) { index ->
                ModelQuery(index, 0L, 0f, floatArrayOf(0f, 0f, 1f, 1f))
            },
        )
        val pageArtifact = PageDetectionArtifact(
            pageId = page.pageId,
            pageArtifactKey = pageKey,
            visibleWidth = 64,
            visibleHeight = 64,
            orientation = VisibleOrientation.NORMAL,
            model = model,
            preprocessing = preprocessing,
            thresholds = thresholds,
            rawQueries = processed.rawQueries,
            bubbleCandidates = processed.bubbles,
            textRegions = processed.textRegions,
        )
        val entries = pages.map { orderedPage ->
            DetectionRunEntry(
                order = orderedPage.order,
                pageId = orderedPage.pageId,
                pageArtifactKey = pageKey,
                state = DetectionPageState.COMMITTED,
                regionsPath = regionsPath,
                previewPath = "previews/${orderedPage.order.toString().padStart(4, '0')}.webp",
            )
        }
        val run = DetectionRunArtifact(
            runArtifactKey = runKey,
            projectId = PROJECT_ID,
            createdAtEpochMillis = 1L,
            model = model,
            preprocessing = preprocessing,
            thresholds = thresholds,
            entries = entries,
        )
        val report = DetectionReport(
            jobId = "detection-job",
            projectId = PROJECT_ID,
            runArtifactKey = runKey,
            startedAtEpochMillis = 0L,
            finishedAtEpochMillis = 1L,
            status = DetectionJobStatus.SUCCEEDED,
            totalPageCount = pages.size,
            committedPageCount = pages.size,
            preservedPageCount = 0,
            retryCount = 0,
            classCounts = emptyMap(),
        )
        val json = DetectionJson()
        writeUtf8(runDirectory.resolve(regionsPath), json.encodePageArtifact(pageArtifact))
        entries.forEach { Files.write(runDirectory.resolve(requireNotNull(it.previewPath)), byteArrayOf(1)) }
        writeUtf8(runDirectory.resolve("artifact.json"), json.encodeRun(run))
        writeUtf8(runDirectory.resolve("report.json"), json.encodeReport(report))
    }

    private fun publishOcr(project: Path, pages: List<PageRecord>): OcrFixture {
        val page = pages.first()
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
            entries = pages.map { orderedPage ->
                OcrRunEntry(
                    order = orderedPage.order,
                    pageId = page.pageId,
                    detectionPageArtifactKey = pageArtifact.detectionPageArtifactKey,
                    pageArtifactKey = pageKey,
                    state = OcrPageState.COMMITTED,
                    artifactPath = "pages/${page.pageId}/ocr.json",
                    previewPath = "previews/${orderedPage.order.toString().padStart(4, '0')}.png",
                )
            },
        )
        val report = OcrReport(
            jobId = "ocr-job",
            projectId = PROJECT_ID,
            runArtifactKey = runKey,
            startedAtEpochMillis = 1L,
            finishedAtEpochMillis = 2L,
            status = OcrJobStatus.SUCCEEDED,
            totalPageCount = pages.size,
            committedPageCount = pages.size,
            totalRegionCount = pages.size,
            recognizedRegionCount = pages.size,
            needsFallbackRegionCount = 0,
            noTextRegionCount = 0,
            preservedRegionCount = 0,
            retryCount = 0,
        )
        val json = OcrJson()
        writeUtf8(runDirectory.resolve("pages/${page.pageId}/ocr.json"), json.encodePageArtifact(pageArtifact))
        Files.createDirectories(runDirectory.resolve("previews"))
        pages.forEach { orderedPage ->
            Files.write(
                runDirectory.resolve("previews/${orderedPage.order.toString().padStart(4, '0')}.png"),
                byteArrayOf(1),
            )
        }
        writeUtf8(runDirectory.resolve("artifact.json"), json.encodeRun(run))
        writeUtf8(runDirectory.resolve("report.json"), json.encodeReport(report))
        return OcrFixture(runKey, pageKey, regionId)
    }

    private fun publishTranslation(project: Path, pages: List<PageRecord>, ocr: OcrFixture) {
        val runKey = "1".repeat(64)
        val pageKey = "2".repeat(64)
        val translationRegionId = "3".repeat(64)
        val runDirectory = project.resolve("artifacts/translation/$runKey")
        Files.createDirectories(runDirectory)
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
            initialGlossary = emptyList(),
        )
        val pageArtifacts = pages.map { page ->
            PageTranslationArtifact(
                pageId = page.pageId,
                pageOrder = page.order,
                ocrPageArtifactKey = ocr.pageKey,
                pageArtifactKey = if (page.order == 0) pageKey else "4".repeat(64),
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
        }
        val run = TranslationRunArtifact(
            runArtifactKey = runKey,
            projectId = PROJECT_ID,
            createdAtEpochMillis = 3L,
            dependencies = dependencies,
            entries = pageArtifacts.map { pageArtifact ->
                TranslationRunEntry(
                    pageId = pageArtifact.pageId,
                    pageOrder = pageArtifact.pageOrder,
                    ocrPageArtifactKey = ocr.pageKey,
                    pageArtifactKey = pageArtifact.pageArtifactKey,
                    artifactPath = "pages/${pageArtifact.pageOrder.toString().padStart(4, '0')}-${pageArtifact.pageId}/translation.json",
                )
            },
            glossaryPath = "glossary.json",
        )
        val report = TranslationReport(
            jobId = "translation-job",
            projectId = PROJECT_ID,
            runArtifactKey = runKey,
            startedAtEpochMillis = 2L,
            finishedAtEpochMillis = 3L,
            status = TranslationJobStatus.SUCCEEDED,
            totalPageCount = pages.size,
            committedPageCount = pages.size,
            totalWindowCount = 1,
            committedWindowCount = 1,
            translatedItemCount = pages.size,
            preservedItemCount = 0,
            protectedOcrRegionCount = 0,
            promptTokens = 1,
            completionTokens = 1,
            totalTokens = 2,
            retryCount = 0,
        )
        val json = TranslationJson()
        pageArtifacts.forEach { pageArtifact ->
            writeUtf8(
                runDirectory.resolve(
                    "pages/${pageArtifact.pageOrder.toString().padStart(4, '0')}-${pageArtifact.pageId}/translation.json",
                ),
                json.encodePageArtifact(pageArtifact),
            )
        }
        writeUtf8(
            runDirectory.resolve("glossary.json"),
            json.encodeGlossary(TranslationGlossaryArtifact(entries = emptyList())),
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
        return project.resolve(manifest.pages.first().storedPath)
    }

    private fun writeUtf8(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, Charsets.UTF_8).use { it.write(content) }
    }

    private data class OcrFixture(val runKey: String, val pageKey: String, val regionId: String)

    private class InMemoryExportDestination : FolderExportDestination {
        val files = linkedMapOf<String, ByteArray>()
        private val staged = linkedMapOf<String, ByteArray>()
        private var previousFiles = linkedMapOf<String, ByteArray>()
        var failNextPublish = false
        var pruneCalls = 0

        override fun matches(outputName: String, expectedByteLength: Long): Boolean {
            val bytes = (staged[outputName] ?: files[outputName]) ?: return false
            return bytes.isNotEmpty() && bytes.size.toLong() == expectedByteLength
        }

        override fun publish(
            outputName: String,
            bytes: ByteArray,
            cancellation: () -> Boolean,
        ): DestinationWriteResult {
            if (cancellation()) throw ExportCancellationSignal()
            if (failNextPublish) {
                failNextPublish = false
                throw ExportDestinationException("DESTINATION_WRITE_FAILED")
            }
            val existing = staged[outputName]
            if (existing != null && existing.isNotEmpty() && existing.size == bytes.size) {
                return DestinationWriteResult(reusedExisting = true)
            }
            staged[outputName] = bytes.copyOf()
            return DestinationWriteResult(reusedExisting = false)
        }

        override fun commitCompleteSet(expectedOutputs: List<ExpectedDestinationOutput>) {
            check(staged.keys == expectedOutputs.mapTo(linkedSetOf()) { it.outputName })
            previousFiles = LinkedHashMap(files)
            files.clear()
            files.putAll(staged.mapValues { it.value.copyOf() })
        }

        override fun rollbackCommittedSet() {
            files.clear()
            files.putAll(previousFiles)
        }

        override fun pruneManagedOutputs() {
            pruneCalls += 1
            previousFiles.clear()
        }

        fun snapshotFiles(): Map<String, ByteArray> = files.mapValues { it.value.copyOf() }

        fun assertFilesEqual(expected: Map<String, ByteArray>) {
            assertEquals(expected.keys, files.keys)
            expected.forEach { (name, bytes) -> assertArrayEquals(bytes, files.getValue(name)) }
        }
    }

    private companion object {
        const val PROJECT_ID = "cleanup-project"
        const val DESTINATION_URI = "content://provider/tree/export"

    }
}
