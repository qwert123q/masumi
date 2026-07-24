package rs.masumi.app.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.detection.VisibleOrientation
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
import rs.masumi.core.serialization.OcrJson
import rs.masumi.core.serialization.ProjectJson
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.translation.TranslationArtifactStore
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationModelItem
import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationRole

@RunWith(AndroidJUnit4::class)
class TranslationRunnerTest {
    @Test
    fun glossaryPrefetchIsAppliedToTheSameWindowBeforeTranslation() {
        val workspace = Files.createTempDirectory("masumi-translation-glossary")
        try {
            publishOcr(workspace, "ルミリアさ・・・っ！")
            val provider = GlossaryAwareProvider()
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                idSource = IdSource { "translation-glossary" },
            )
            val result = runner.run(
                PROJECT_ID,
                TranslationProviderSettings(
                    apiUrl = "https://example.invalid/v1",
                    apiKey = "secret-not-for-artifacts",
                    model = "model-safe",
                ),
                { false },
            ) { }

            val run = requireNotNull(result.runArtifact)
            val store = TranslationArtifactStore(workspace.resolve("projects/$PROJECT_ID"))
            val page = requireNotNull(store.readPublishedPage(run.runArtifactKey, run.entries.single()))

            assertEquals(TranslationJobStatus.SUCCEEDED, result.job.status)
            assertEquals(2, provider.callCount)
            assertTrue(provider.translationSawPrefetchedGlossary)
            assertEquals("露米莉亚小姐……！", page.items.single().translatedText)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancelledWindowResumesFromPublishedOcrAndCompletedRunIsReused() {
        val workspace = Files.createTempDirectory("masumi-translation-runner")
        try {
            publishOcr(workspace)
            val provider = RecordingProvider()
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                idSource = IdSource { "translation-job" },
            )
            val settings = TranslationProviderSettings(
                apiUrl = "https://example.invalid/v1",
                apiKey = "secret-not-for-artifacts",
                model = "model-safe",
            )
            val cancel = AtomicBoolean(false)

            val cancelled = runner.run(PROJECT_ID, settings, cancel::get) { progress ->
                if (progress.currentWindowIndex != null) cancel.set(true)
            }

            assertEquals(TranslationJobStatus.CANCELLED, cancelled.job.status)
            assertEquals(0, provider.callCount)

            val completed = runner.run(PROJECT_ID, settings, { false }) { }

            assertEquals(TranslationJobStatus.SUCCEEDED, completed.job.status)
            assertEquals(2, provider.callCount)
            assertEquals(1, completed.report?.translatedItemCount)
            assertEquals(20L, completed.report?.promptTokens)
            assertEquals(10L, completed.report?.completionTokens)
            assertEquals(30L, completed.report?.totalTokens)
            assertNotNull(completed.publishedDirectory)

            val cached = runner.run(PROJECT_ID, settings, { false }) { }

            assertEquals(TranslationJobStatus.SUCCEEDED, cached.job.status)
            assertEquals(2, provider.callCount)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun allProviderFailuresAreNotPublishedAndCanBeRetried() {
        val workspace = Files.createTempDirectory("masumi-translation-retry")
        try {
            publishOcr(workspace)
            val provider = FailFirstWindowProvider()
            var nextJobId = 0
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                idSource = IdSource { "translation-retry-${++nextJobId}" },
            )
            val settings = TranslationProviderSettings(
                apiUrl = "https://example.invalid/v1",
                apiKey = "secret-not-for-artifacts",
                model = "model-safe",
            )

            val failed = runner.run(PROJECT_ID, settings, { false }) { }

            assertEquals(TranslationJobStatus.FAILED, failed.job.status)
            assertEquals("NETWORK", failed.job.error?.code)
            assertNull(failed.runArtifact)
            assertNull(failed.publishedDirectory)

            val retried = runner.run(PROJECT_ID, settings, { false }) { }

            assertEquals(TranslationJobStatus.SUCCEEDED, retried.job.status)
            assertEquals(4, provider.callCount)
            assertNotNull(retried.publishedDirectory)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun oversizedOnlyChapterPublishesProtectedOutputWithoutCallingProvider() {
        val workspace = Files.createTempDirectory("masumi-translation-oversized")
        try {
            publishOcr(workspace)
            val provider = RecordingProvider()
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                batching = TranslationBatchingConfig(maximumEstimatedInputTokens = 1),
                idSource = IdSource { "translation-oversized" },
            )
            val settings = TranslationProviderSettings(
                apiUrl = "https://example.invalid/v1",
                apiKey = "secret-not-for-artifacts",
                model = "model-safe",
            )

            val result = runner.run(PROJECT_ID, settings, { false }) { }

            assertEquals(TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS, result.job.status)
            assertEquals(0, provider.callCount)
            assertEquals(1, result.report?.preservedItemCount)
            assertNotNull(result.publishedDirectory)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun publishOcr(workspace: Path, sourceText: String = "今日は") {
        val project = workspace.resolve("projects/$PROJECT_ID")
        val runDirectory = project.resolve("artifacts/ocr/${"a".repeat(64)}")
        val pageDirectory = runDirectory.resolve("pages/${"b".repeat(64)}")
        val previewDirectory = runDirectory.resolve("previews")
        Files.createDirectories(pageDirectory)
        Files.createDirectories(previewDirectory)
        val pageId = "b".repeat(64)
        val page = PageRecord(
            order = 0,
            pageId = pageId,
            sourceSha256 = pageId,
            originalName = "page.png",
            mediaType = "image/png",
            byteLength = 1L,
            storedPath = "sources/$pageId.png",
        )
        Files.createDirectories(project.resolve("sources"))
        Files.write(project.resolve(page.storedPath), byteArrayOf(1))
        writeUtf8(
            project.resolve("manifest.json"),
            ProjectJson().encodeManifest(ProjectManifest(projectId = PROJECT_ID, createdAtEpochMillis = 1L, pages = listOf(page))),
        )

        val descriptor = PinnedPaddleOcrVl.descriptor
        val dependencies = OcrDependencies(
            modelPackage = descriptor.toRef(),
            runtime = descriptor.runtime.toRef(),
            generation = OcrGenerationConfig(prompt = descriptor.prompt),
        )
        val candidate = OcrCandidate(
            ocrRegionId = "c".repeat(64),
            sourceRegionIds = listOf("d".repeat(64)),
            representativeSourceRegionId = "d".repeat(64),
            sourceClass = DetectorClass.TEXT_IN_BUBBLE,
            detectorConfidence = 0.9,
            box = PixelBox(1.0, 1.0, 20.0, 20.0),
            semanticStatus = OcrSemanticStatus.REQUIRED_TEXT,
            protectionPolicy = OcrProtectionPolicy.NONE,
            readingOrderRank = 0,
        )
        val attempt = OcrAttemptArtifact(
            executionBackend = OcrExecutionBackend.VULKAN,
            strategy = OcrCropStrategy.PADDED_TEXT,
            cropBox = candidate.box,
            rawText = sourceText,
            normalizedText = sourceText,
            tokenIds = listOf(1),
            tokenProbabilities = listOf(0.9),
            sourceWidth = 20,
            sourceHeight = 20,
            processedWidth = 20,
            processedHeight = 20,
            visualTokenCount = 1,
            generatedTokenCount = 1,
            reachedEos = true,
            truncated = false,
            repetitionStopped = false,
            invalidUtf8 = false,
            promptEvaluationMillis = 1L,
            generationMillis = 1L,
        )
        val pageArtifactKey = "e".repeat(64)
        val pageArtifact = PageOcrArtifact(
            pageId = pageId,
            sourceSha256 = pageId,
            detectionPageArtifactKey = "f".repeat(64),
            pageArtifactKey = pageArtifactKey,
            visibleWidth = 100,
            visibleHeight = 100,
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
        val json = OcrJson()
        writeUtf8(pageDirectory.resolve("ocr.json"), json.encodePageArtifact(pageArtifact))
        Files.write(previewDirectory.resolve("0000.png"), byteArrayOf(1))
        val runKey = "a".repeat(64)
        val run = OcrRunArtifact(
            runArtifactKey = runKey,
            projectId = PROJECT_ID,
            detectionRunArtifactKey = "9".repeat(64),
            createdAtEpochMillis = 2L,
            dependencies = dependencies,
            entries = listOf(
                OcrRunEntry(
                    order = 0,
                    pageId = pageId,
                    sourceSha256 = pageId,
                    detectionPageArtifactKey = pageArtifact.detectionPageArtifactKey,
                    pageArtifactKey = pageArtifactKey,
                    state = OcrPageState.COMMITTED,
                    artifactPath = "pages/$pageId/ocr.json",
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
        writeUtf8(runDirectory.resolve("artifact.json"), json.encodeRun(run))
        writeUtf8(runDirectory.resolve("report.json"), json.encodeReport(report))
    }

    private fun writeUtf8(path: Path, content: String) {
        Files.newBufferedWriter(path, Charsets.UTF_8).use { it.write(content) }
    }

    private class RecordingProvider : TranslationProvider {
        var callCount: Int = 0

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                callCount += 1
                val id = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                    .findAll(messages.user).last().groupValues[1]
                return TranslationProviderResult(
                    response = TranslationModelResponse(
                        items = listOf(TranslationModelItem(id, TranslationRole.DIALOGUE, "今天")),
                    ),
                    usage = TranslationProviderUsage(10, 5, 15),
                    modelId = "model-safe",
                    attemptCount = 1,
                    durationMillis = 1L,
                )
            }

            override fun cancel() = Unit
        }
    }

    private class GlossaryAwareProvider : TranslationProvider {
        var callCount: Int = 0
        var translationSawPrefetchedGlossary: Boolean = false

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                callCount += 1
                val discovery = messages.system.contains("Build a reusable")
                val response = if (discovery) {
                    TranslationModelResponse(
                        items = emptyList(),
                        glossaryUpdates = mapOf("ルミリアさん" to "露米莉亚小姐"),
                    )
                } else {
                    translationSawPrefetchedGlossary =
                        messages.user.contains("\"source\":\"ルミリアさん\"") &&
                            messages.user.contains("\"translation\":\"露米莉亚小姐\"")
                    val id = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                        .findAll(messages.user).last().groupValues[1]
                    TranslationModelResponse(
                        items = listOf(
                            TranslationModelItem(id, TranslationRole.DIALOGUE, "露米莉亚小...！"),
                        ),
                    )
                }
                return TranslationProviderResult(
                    response = response,
                    usage = TranslationProviderUsage(10, 5, 15),
                    modelId = "model-safe",
                    attemptCount = 1,
                    durationMillis = 1L,
                )
            }

            override fun cancel() = Unit
        }
    }

    private class FailFirstWindowProvider : TranslationProvider {
        var callCount: Int = 0

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                callCount += 1
                if (callCount <= 2) {
                    throw TranslationProviderException(
                        code = TranslationProviderErrorCode.NETWORK,
                        retryable = true,
                        attemptCount = 3,
                    )
                }
                val id = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                    .findAll(messages.user).last().groupValues[1]
                return TranslationProviderResult(
                    response = TranslationModelResponse(
                        items = listOf(TranslationModelItem(id, TranslationRole.DIALOGUE, "今天")),
                    ),
                    usage = TranslationProviderUsage(10, 5, 15),
                    modelId = "model-safe",
                    attemptCount = 1,
                    durationMillis = 1L,
                )
            }

            override fun cancel() = Unit
        }
    }

    private companion object {
        const val PROJECT_ID = "translation-project"
    }
}
