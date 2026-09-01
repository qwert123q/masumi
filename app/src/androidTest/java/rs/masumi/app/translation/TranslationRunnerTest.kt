package rs.masumi.app.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.DetectedRegion
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionPostProcessor
import rs.masumi.core.detection.DetectionPreprocessingConfig
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.DetectionRunEntry
import rs.masumi.core.detection.DetectionThresholdConfig
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.ModelQuery
import rs.masumi.core.detection.PageDetectionArtifact
import rs.masumi.core.detection.VisibleOrientation
import rs.masumi.core.importer.IdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.modelpackage.PinnedComicDetector
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl
import rs.masumi.core.ocr.OcrAttemptArtifact
import rs.masumi.core.ocr.OcrCandidate
import rs.masumi.core.ocr.OcrCropStrategy
import rs.masumi.core.ocr.OcrDependencies
import rs.masumi.core.ocr.OcrExecutionBackend
import rs.masumi.core.ocr.OcrGenerationConfig
import rs.masumi.core.ocr.OcrIdentity
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
import rs.masumi.core.serialization.DetectionJson
import rs.masumi.core.serialization.OcrJson
import rs.masumi.core.serialization.ProjectJson
import rs.masumi.core.translation.TranslationArtifactStore
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationGlossaryEntry
import rs.masumi.core.translation.TranslationJobStatus
import rs.masumi.core.translation.TranslationModelItem
import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationPreserveReason
import rs.masumi.core.translation.TranslationRole
import rs.masumi.core.translation.WorkspaceGlossaryStore

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
                profileId = "example-provider",
                providerName = "Example Provider",
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
            assertEquals("example-provider", completed.report?.provider?.profileId)
            assertEquals("Example Provider", completed.report?.provider?.displayName)
            assertEquals("example.invalid", completed.report?.provider?.endpointHost)
            assertFalse(
                completed.report.toString().contains("secret-not-for-artifacts"),
            )
            assertNotNull(completed.publishedDirectory)

            val cached = runner.run(PROJECT_ID, settings, { false }) { }

            assertEquals(TranslationJobStatus.SUCCEEDED, cached.job.status)
            assertEquals(2, provider.callCount)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun providerFailureIsNotPublishedAndCanBeRetriedOnlyByStartingAgain() {
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
            assertEquals(3, provider.callCount)
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

    @Test
    fun providerFailedItemsStopPublicationWithoutRetryingUnrelatedTransportErrors() {
        val workspace = Files.createTempDirectory("masumi-translation-salvage")
        try {
            publishOcr(workspace, listOf("今日は", "退避対象"))
            val provider = FailSecondWindowOnceProvider()
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                batching = TranslationBatchingConfig(maximumItemsPerWindow = 1),
                idSource = IdSource { "translation-salvage" },
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

            assertEquals(TranslationJobStatus.FAILED, result.job.status)
            assertEquals("HTTP_TRANSIENT", result.job.error?.code)
            assertNull(result.runArtifact)
            assertNull(result.publishedDirectory)
            assertFalse(provider.sawUnexpectedRetry)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun malformedBatchStopsWithoutBisection() {
        val workspace = Files.createTempDirectory("masumi-translation-malformed-batch")
        try {
            publishOcr(workspace, listOf("今日は", "大丈夫です"))
            val provider = MalformedBatchProvider()
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                batching = TranslationBatchingConfig(maximumItemsPerWindow = 2),
                idSource = IdSource { "translation-malformed-batch" },
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

            assertEquals(TranslationJobStatus.FAILED, result.job.status)
            assertEquals("MALFORMED_RESPONSE", result.job.error?.code)
            assertNull(result.runArtifact)
            assertEquals(1, provider.failedBatchCount)
            assertEquals(0, provider.isolatedRecoveryCount)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun responseOmittedItemStopsWithoutAnIsolatedProtocolRetry() {
        val workspace = Files.createTempDirectory("masumi-translation-missing-item")
        try {
            publishOcr(workspace, listOf("今日は", "退避対象"))
            val provider = OmitSecondItemOnceProvider()
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                batching = TranslationBatchingConfig(maximumItemsPerWindow = 2),
                idSource = IdSource { "translation-missing-item" },
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

            assertEquals(TranslationJobStatus.FAILED, result.job.status)
            assertEquals("INCOMPLETE_TRANSLATION_RESPONSE", result.job.error?.code)
            assertNull(result.runArtifact)
            assertEquals(0, provider.recoveryRequestCount)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidTranslationRetriesOnlyThatItemWithItsOriginalContextAndGlossary() {
        val workspace = Files.createTempDirectory("masumi-translation-invalid-retry")
        try {
            publishOcr(workspace, listOf("前文甲", "前文乙", "无效项……", "有效项……"))
            val glossary = WorkspaceGlossaryStore(workspace).also {
                it.record(listOf(rs.masumi.core.translation.TranslationGlossaryEntry("既有术语", "既有译法")))
            }
            val provider = InvalidItemRetryProvider()
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                batching = TranslationBatchingConfig(maximumItemsPerWindow = 2),
                idSource = IdSource { "translation-invalid-retry" },
                glossaryMemory = glossary,
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

            assertEquals(TranslationJobStatus.SUCCEEDED, result.job.status)
            assertEquals(1, provider.invalidOnlyRetryCount)
            assertTrue(provider.retryRetainedOriginalContext)
            assertTrue(provider.retryRetainedOriginalGlossary)
            assertTrue(provider.retryExcludedRejectedGlossary)
            val run = requireNotNull(result.runArtifact)
            val store = TranslationArtifactStore(workspace.resolve("projects/$PROJECT_ID"))
            val page = requireNotNull(store.readPublishedPage(run.runArtifactKey, run.entries.single()))
            val invalid = page.items[2]
            val valid = page.items[3]
            assertEquals("已翻译-有效项……", valid.translatedText)
            assertFalse(valid.translatedText.orEmpty().contains("恶意"))
            assertNull(invalid.translatedText)
            assertEquals(TranslationPreserveReason.SOURCE_TEXT_ECHO, invalid.preserveReason)
            assertTrue(glossary.load().none { it.translation.contains("恶意") })
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun isolatedRetryCannotPersistGlossaryUpdatesAlongsideAValidSibling() {
        val workspace = Files.createTempDirectory("masumi-translation-retry-glossary")
        try {
            publishOcr(
                workspace,
                listOf("最初甲", "最初乙", "再試行対象……", "成功済み兄弟項……"),
            )
            val glossary = WorkspaceGlossaryStore(workspace)
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = IsolatedRetryGlossaryProvider(),
                batching = TranslationBatchingConfig(maximumItemsPerWindow = 2),
                idSource = IdSource { "translation-retry-glossary" },
                glossaryMemory = glossary,
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

            assertEquals(TranslationJobStatus.SUCCEEDED, result.job.status)
            val run = requireNotNull(result.runArtifact)
            val store = TranslationArtifactStore(workspace.resolve("projects/$PROJECT_ID"))
            val page = requireNotNull(store.readPublishedPage(run.runArtifactKey, run.entries.single()))
            assertEquals("重试成功……", page.items[2].translatedText)
            assertEquals("同批成功……", page.items[3].translatedText)

            val acceptedFirstPassEntry = TranslationGlossaryEntry("初回用語", "首次术语")
            val publishedGlossary = requireNotNull(store.readPublishedGlossary(run.runArtifactKey)).entries
            assertEquals(listOf(acceptedFirstPassEntry), publishedGlossary)
            assertEquals(listOf(acceptedFirstPassEntry), glossary.load())
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidTargetWhoseOnlyRetryIsBlankPublishesWithoutAThirdSalvageRequest() {
        val workspace = Files.createTempDirectory("masumi-translation-invalid-blank-retry")
        try {
            publishOcr(workspace, listOf("无效项……"))
            val provider = InvalidThenBlankProvider()
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                idSource = IdSource { "translation-invalid-blank-retry" },
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

            assertEquals(2, provider.translationRequestCount)
            assertEquals(TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS, result.job.status)
            val run = requireNotNull(result.runArtifact)
            val page = requireNotNull(
                TranslationArtifactStore(workspace.resolve("projects/$PROJECT_ID"))
                    .readPublishedPage(run.runArtifactKey, run.entries.single()),
            )
            assertEquals(TranslationPreserveReason.SOURCE_TEXT_ECHO, page.items.single().preserveReason)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun allInvalidItemsAreRetriedOnceInStrictlySmallerIsolatedWindows() {
        val workspace = Files.createTempDirectory("masumi-translation-all-invalid-retry")
        try {
            val sources = listOf("无效甲", "无效乙", "无效丙")
            publishOcr(workspace, sources)
            val provider = AllEchoProvider()
            val runner = TranslationRunner(
                workspaceRoot = workspace,
                provider = provider,
                batching = TranslationBatchingConfig(maximumItemsPerWindow = sources.size),
                idSource = IdSource { "translation-all-invalid-retry" },
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

            assertEquals(listOf(3, 1, 1, 1), provider.translationRequestSizes)
            assertEquals(sources.associateWith { 2 }, provider.sourceRequestCounts)
            assertEquals(TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS, result.job.status)
            assertNotNull(result.runArtifact)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun publishOcr(workspace: Path, sourceText: String = "今日は") =
        publishOcr(workspace, listOf(sourceText))

    private fun publishOcr(workspace: Path, sourceTexts: List<String>) {
        val project = workspace.resolve("projects/$PROJECT_ID")
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

        val detectionRunArtifactKey = "9".repeat(64)
        val detectionPageArtifactKey = "f".repeat(64)
        val detectionRegions = publishDetection(
            project = project,
            page = page,
            sourceTextCount = sourceTexts.size,
            runArtifactKey = detectionRunArtifactKey,
            pageArtifactKey = detectionPageArtifactKey,
        )

        val descriptor = PinnedPaddleOcrVl.descriptor
        val dependencies = OcrDependencies(
            modelPackage = descriptor.toRef(),
            runtime = descriptor.runtime.toRef(),
            generation = OcrGenerationConfig(prompt = descriptor.prompt),
        )
        val pageArtifactKey = OcrIdentity.pageArtifactKey(
            page.sourceSha256,
            detectionPageArtifactKey,
            dependencies,
        )
        val runKey = OcrIdentity.runArtifactKey(listOf(page.order to pageArtifactKey))
        val runDirectory = project.resolve("artifacts/ocr/$runKey")
        val pageDirectory = runDirectory.resolve("pages/$pageId")
        val previewDirectory = runDirectory.resolve("previews")
        Files.createDirectories(pageDirectory)
        Files.createDirectories(previewDirectory)
        fun candidate(index: Int): OcrCandidate {
            val sourceRegion = detectionRegions[index]
            val sourceRegionIds = listOf(sourceRegion.regionId)
            return OcrCandidate(
                ocrRegionId = OcrIdentity.regionId(
                    pageId = pageId,
                    detectionPageArtifactKey = detectionPageArtifactKey,
                    sourceRegionIds = sourceRegionIds,
                    semantic = OcrSemanticStatus.REQUIRED_TEXT,
                ),
                sourceRegionIds = sourceRegionIds,
                representativeSourceRegionId = sourceRegion.regionId,
                sourceClass = sourceRegion.detectorClass,
                detectorConfidence = sourceRegion.confidence,
                box = sourceRegion.box,
                semanticStatus = OcrSemanticStatus.REQUIRED_TEXT,
                protectionPolicy = OcrProtectionPolicy.NONE,
                readingOrderRank = index,
            )
        }
        fun attempt(index: Int) = OcrAttemptArtifact(
            executionBackend = OcrExecutionBackend.VULKAN,
            strategy = OcrCropStrategy.PADDED_TEXT,
            cropBox = candidate(index).box,
            rawText = sourceTexts[index],
            normalizedText = sourceTexts[index],
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
        val pageArtifact = PageOcrArtifact(
            pageId = pageId,
            sourceSha256 = pageId,
            detectionPageArtifactKey = detectionPageArtifactKey,
            pageArtifactKey = pageArtifactKey,
            visibleWidth = 100,
            visibleHeight = 100,
            orientation = VisibleOrientation.NORMAL,
            dependencies = dependencies,
            regions = sourceTexts.indices.map { index ->
                OcrRegionArtifact(
                    candidate = candidate(index),
                    attempts = listOf(attempt(index)),
                    selectedAttemptIndex = 0,
                    quality = null,
                    state = OcrRegionState.RECOGNIZED,
                )
            },
        )
        val json = OcrJson()
        writeUtf8(pageDirectory.resolve("ocr.json"), json.encodePageArtifact(pageArtifact))
        Files.write(previewDirectory.resolve("0000.png"), byteArrayOf(1))
        val run = OcrRunArtifact(
            runArtifactKey = runKey,
            projectId = PROJECT_ID,
            detectionRunArtifactKey = detectionRunArtifactKey,
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
            totalRegionCount = sourceTexts.size,
            recognizedRegionCount = sourceTexts.size,
            needsFallbackRegionCount = 0,
            noTextRegionCount = 0,
            preservedRegionCount = 0,
            retryCount = 0,
        )
        writeUtf8(runDirectory.resolve("artifact.json"), json.encodeRun(run))
        writeUtf8(runDirectory.resolve("report.json"), json.encodeReport(report))
    }

    private fun publishDetection(
        project: Path,
        page: PageRecord,
        sourceTextCount: Int,
        runArtifactKey: String,
        pageArtifactKey: String,
    ): List<DetectedRegion> {
        val runDirectory = project.resolve("artifacts/detection/$runArtifactKey")
        val regionsPath = "pages/${page.pageId}/regions.json"
        val previewPath = "previews/0000.webp"
        Files.createDirectories(runDirectory.resolve("pages/${page.pageId}"))
        Files.createDirectories(runDirectory.resolve("previews"))

        val model = PinnedComicDetector.descriptor.toModelRef()
        val preprocessing = DetectionPreprocessingConfig()
        val thresholds = DetectionThresholdConfig()
        val processed = DetectionPostProcessor.process(
            pageId = page.pageId,
            pageArtifactKey = pageArtifactKey,
            pageWidth = 100,
            pageHeight = 100,
            thresholds = thresholds,
            queries = List(300) { index ->
                val accepted = index < sourceTextCount
                val left = 1f + (index % 4) * 25f
                val top = 1f + (index / 4) * 25f
                ModelQuery(
                    queryIndex = index,
                    label = 1L,
                    score = if (accepted) 0.9f else 0f,
                    box = if (accepted) {
                        floatArrayOf(left, top, left + 19f, top + 19f)
                    } else {
                        floatArrayOf(0f, 0f, 1f, 1f)
                    },
                )
            },
        )
        check(processed.textRegions.size == sourceTextCount)
        val pageArtifact = PageDetectionArtifact(
            pageId = page.pageId,
            sourceSha256 = page.sourceSha256,
            pageArtifactKey = pageArtifactKey,
            visibleWidth = 100,
            visibleHeight = 100,
            orientation = VisibleOrientation.NORMAL,
            model = model,
            preprocessing = preprocessing,
            thresholds = thresholds,
            rawQueries = processed.rawQueries,
            bubbleCandidates = processed.bubbles,
            textRegions = processed.textRegions,
        )
        val run = DetectionRunArtifact(
            runArtifactKey = runArtifactKey,
            projectId = PROJECT_ID,
            createdAtEpochMillis = 1L,
            model = model,
            preprocessing = preprocessing,
            thresholds = thresholds,
            entries = listOf(
                DetectionRunEntry(
                    order = page.order,
                    pageId = page.pageId,
                    pageArtifactKey = pageArtifactKey,
                    state = DetectionPageState.COMMITTED,
                    regionsPath = regionsPath,
                    previewPath = previewPath,
                ),
            ),
        )
        val report = DetectionReport(
            jobId = "detection-job",
            projectId = PROJECT_ID,
            runArtifactKey = runArtifactKey,
            startedAtEpochMillis = 0L,
            finishedAtEpochMillis = 1L,
            status = DetectionJobStatus.SUCCEEDED,
            totalPageCount = 1,
            committedPageCount = 1,
            preservedPageCount = 0,
            retryCount = 0,
            classCounts = mapOf(DetectorClass.TEXT_IN_BUBBLE.name to sourceTextCount),
        )
        val json = DetectionJson()
        writeUtf8(runDirectory.resolve(regionsPath), json.encodePageArtifact(pageArtifact))
        Files.write(runDirectory.resolve(previewPath), byteArrayOf(1))
        writeUtf8(runDirectory.resolve("artifact.json"), json.encodeRun(run))
        writeUtf8(runDirectory.resolve("report.json"), json.encodeReport(report))
        return processed.textRegions.sortedBy(DetectedRegion::queryIndex)
    }

    private fun writeUtf8(path: Path, content: String) {
        Files.newBufferedWriter(path, Charsets.UTF_8).use { it.write(content) }
    }

    private class FailSecondWindowOnceProvider : TranslationProvider {
        private val failedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var sawUnexpectedRetry: Boolean = false

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                val discovery = messages.system.contains("Build a reusable")
                if (discovery) {
                    return TranslationProviderResult(
                        response = TranslationModelResponse(items = emptyList()),
                        usage = TranslationProviderUsage(1, 1, 2),
                        modelId = "model-safe",
                        attemptCount = 1,
                        durationMillis = 1L,
                    )
                }
                // Prompt ids are translation-region hashes, so the poisoned
                // item is recognized by its source text instead.
                val poisoned = messages.user.contains("退避対象")
                if (poisoned && failedOnce.compareAndSet(false, true)) {
                    throw TranslationProviderException(
                        code = TranslationProviderErrorCode.HTTP_TRANSIENT,
                        httpStatus = 502,
                        attemptCount = 1,
                    )
                }
                val id = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                    .findAll(messages.user).last().groupValues[1]
                if (poisoned) sawUnexpectedRetry = true
                return TranslationProviderResult(
                    response = TranslationModelResponse(
                        items = listOf(TranslationModelItem(id, TranslationRole.DIALOGUE, "已翻译")),
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

    private class OmitSecondItemOnceProvider : TranslationProvider {
        private var translationRequestCount = 0
        var recoveryRequestCount = 0

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                val discovery = messages.system.contains("Build a reusable")
                if (discovery) {
                    return TranslationProviderResult(
                        response = TranslationModelResponse(items = emptyList()),
                        usage = TranslationProviderUsage(1, 1, 2),
                        modelId = "model-safe",
                        attemptCount = 1,
                        durationMillis = 1L,
                    )
                }
                translationRequestCount += 1
                val ids = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                    .findAll(messages.user)
                    .map { it.groupValues[1] }
                    .toList()
                    .distinct()
                val returnedIds = if (translationRequestCount == 1) ids.take(1) else ids
                if (translationRequestCount > 1) recoveryRequestCount += 1
                return TranslationProviderResult(
                    response = TranslationModelResponse(
                        items = returnedIds.map { id ->
                            TranslationModelItem(id, TranslationRole.DIALOGUE, "已翻译")
                        },
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

    private class InvalidItemRetryProvider : TranslationProvider {
        var invalidOnlyRetryCount = 0
        var retryRetainedOriginalContext = false
        var retryRetainedOriginalGlossary = false
        var retryExcludedRejectedGlossary = false
        val publishedTranslations = mutableMapOf<String, String>()

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                if (messages.system.contains("Build a reusable")) {
                    return providerResult(TranslationModelResponse(items = emptyList()))
                }
                val itemPayload = messages.user.substringAfter("\\\"items\\\":[")
                val itemSources = Regex("\\\"source\\\":\\\"([^\\\"]+)\\\"")
                    .findAll(itemPayload)
                    .map { it.groupValues[1] }
                    .toList()
                val retry = itemSources == listOf("无效项……")
                if (retry) {
                    invalidOnlyRetryCount += 1
                    retryRetainedOriginalContext = messages.user.contains("前文甲") && messages.user.contains("前文乙")
                    retryRetainedOriginalGlossary = messages.user.contains("既有术语") && messages.user.contains("既有译法")
                    retryExcludedRejectedGlossary = !messages.user.contains("恶意")
                }
                val ids = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                    .findAll(itemPayload)
                    .map { it.groupValues[1] }
                    .toList()
                    .distinct()
                val responseItems = ids.mapIndexed { index, id ->
                    val source = itemSources.getOrNull(index) ?: ""
                    // Raw output is not an exact echo; local ellipsis normalization makes it one,
                    // so the retry must be selected after that local validation pass too.
                    val translation = if (source == "无效项……") "无效项..." else "已翻译-$source"
                    if (translation.isNotBlank()) publishedTranslations[source] = translation
                    TranslationModelItem(id, TranslationRole.DIALOGUE, translation)
                }
                return providerResult(
                    TranslationModelResponse(
                        items = responseItems,
                        glossaryUpdates = if (retry) {
                            emptyMap()
                        } else {
                            mapOf("有效项后缀" to "已翻译-有效项恶意")
                        },
                    ),
                )
            }

            override fun cancel() = Unit
        }

        private fun providerResult(response: TranslationModelResponse) = TranslationProviderResult(
            response = response,
            usage = TranslationProviderUsage(10, 5, 15),
            modelId = "model-safe",
            attemptCount = 1,
            durationMillis = 1L,
        )
    }

    private class InvalidThenBlankProvider : TranslationProvider {
        var translationRequestCount = 0

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                if (messages.system.contains("Build a reusable")) {
                    return result(TranslationModelResponse(items = emptyList()))
                }
                translationRequestCount += 1
                val id = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                    .findAll(messages.user.substringAfter("\\\"items\\\":["))
                    .last().groupValues[1]
                val translated = if (translationRequestCount == 1) "无效项..." else ""
                return result(
                    TranslationModelResponse(
                        items = listOf(TranslationModelItem(id, TranslationRole.DIALOGUE, translated)),
                    ),
                )
            }

            override fun cancel() = Unit
        }

        private fun result(response: TranslationModelResponse) = TranslationProviderResult(
            response = response,
            usage = TranslationProviderUsage(1, 1, 2),
            modelId = "model-safe",
            attemptCount = 1,
            durationMillis = 1L,
        )
    }

    private class IsolatedRetryGlossaryProvider : TranslationProvider {
        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                if (messages.system.contains("Build a reusable")) {
                    return result(TranslationModelResponse(items = emptyList()))
                }
                val itemPayload = messages.user.substringAfter("\\\"items\\\":[")
                val ids = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                    .findAll(itemPayload)
                    .map { it.groupValues[1] }
                    .toList()
                    .distinct()
                val sources = Regex("\\\"source\\\":\\\"([^\\\"]+)\\\"")
                    .findAll(itemPayload)
                    .map { it.groupValues[1] }
                    .toList()
                val isolatedRetry = sources == listOf("再試行対象……")
                val translations = sources.map { source ->
                    when (source) {
                        "最初甲" -> "首次甲"
                        "最初乙" -> "首次乙"
                        "再試行対象……" -> if (isolatedRetry) "重试成功……" else "再試行対象..."
                        "成功済み兄弟項……" -> "同批成功……"
                        else -> error("unexpected translation source: $source")
                    }
                }
                val glossaryUpdates = when {
                    sources == listOf("最初甲", "最初乙") -> mapOf("初回用語" to "首次术语")
                    isolatedRetry -> mapOf(
                        "再試行対象完全" to "重试成功污染",
                        "成功済み兄弟項完全" to "同批成功污染",
                    )
                    else -> emptyMap()
                }
                return result(
                    TranslationModelResponse(
                        items = ids.zip(translations).map { (id, translation) ->
                            TranslationModelItem(id, TranslationRole.DIALOGUE, translation)
                        },
                        glossaryUpdates = glossaryUpdates,
                    ),
                )
            }

            override fun cancel() = Unit
        }

        private fun result(response: TranslationModelResponse) = TranslationProviderResult(
            response = response,
            usage = TranslationProviderUsage(1, 1, 2),
            modelId = "model-safe",
            attemptCount = 1,
            durationMillis = 1L,
        )
    }

    private class AllEchoProvider : TranslationProvider {
        val translationRequestSizes = mutableListOf<Int>()
        val sourceRequestCounts = linkedMapOf<String, Int>()

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                if (messages.system.contains("Build a reusable")) {
                    return result(TranslationModelResponse(items = emptyList()))
                }
                val itemPayload = messages.user.substringAfter("\\\"items\\\":[")
                val ids = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                    .findAll(itemPayload).map { it.groupValues[1] }.toList().distinct()
                val sources = Regex("\\\"source\\\":\\\"([^\\\"]+)\\\"")
                    .findAll(itemPayload).map { it.groupValues[1] }.toList()
                translationRequestSizes += ids.size
                sources.forEach { source -> sourceRequestCounts[source] = sourceRequestCounts.getOrDefault(source, 0) + 1 }
                return result(
                    TranslationModelResponse(
                        items = ids.zip(sources).map { (id, source) ->
                            TranslationModelItem(id, TranslationRole.DIALOGUE, source)
                        },
                    ),
                )
            }

            override fun cancel() = Unit
        }

        private fun result(response: TranslationModelResponse) = TranslationProviderResult(
            response = response,
            usage = TranslationProviderUsage(1, 1, 2),
            modelId = "model-safe",
            attemptCount = 1,
            durationMillis = 1L,
        )
    }

    private class MalformedBatchProvider : TranslationProvider {
        var failedBatchCount = 0
        var isolatedRecoveryCount = 0

        override fun newCall(
            settings: TranslationProviderSettings,
            messages: rs.masumi.core.translation.TranslationPromptMessages,
        ): TranslationProviderCall = object : TranslationProviderCall {
            override fun execute(): TranslationProviderResult {
                if (messages.system.contains("Build a reusable")) {
                    return TranslationProviderResult(
                        response = TranslationModelResponse(items = emptyList()),
                        usage = TranslationProviderUsage(1, 1, 2),
                        modelId = "model-safe",
                        attemptCount = 1,
                        durationMillis = 1L,
                    )
                }
                val ids = Regex("\\\"id\\\":\\\"([0-9a-f]{64})\\\"")
                    .findAll(messages.user)
                    .map { it.groupValues[1] }
                    .toList()
                    .distinct()
                if (ids.size > 1) {
                    failedBatchCount += 1
                    throw TranslationProviderException(
                        code = TranslationProviderErrorCode.MALFORMED_RESPONSE,
                        attemptCount = 2,
                    )
                }
                isolatedRecoveryCount += 1
                return TranslationProviderResult(
                    response = TranslationModelResponse(
                        items = listOf(
                            TranslationModelItem(ids.single(), TranslationRole.DIALOGUE, "已翻译"),
                        ),
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
                if (callCount == 1) {
                    throw TranslationProviderException(
                        code = TranslationProviderErrorCode.NETWORK,
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
