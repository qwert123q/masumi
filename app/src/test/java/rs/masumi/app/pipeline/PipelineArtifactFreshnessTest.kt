package rs.masumi.app.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.app.ocr.currentOcrDependencies
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.cleanup.CleanupRunEntry
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectionPreprocessingConfig
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.DetectionRunEntry
import rs.masumi.core.detection.DetectionThresholdConfig
import rs.masumi.core.modelpackage.PinnedComicDetector
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.ocr.OcrRunEntry
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationDependencies
import rs.masumi.core.translation.TranslationOutputValidationConfig
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.TranslationProviderDependency
import rs.masumi.core.translation.TranslationRunArtifact
import rs.masumi.core.translation.TranslationRunEntry

class PipelineArtifactFreshnessTest {
    @Test
    fun `ocr requires current dependencies and exact detection lineage`() {
        val detection = detectionArtifact()
        val dependencies = currentOcrDependencies()
        val current = OcrRunArtifact(
            runArtifactKey = OCR_RUN_ID,
            projectId = PROJECT_ID,
            detectionRunArtifactKey = detection.runArtifactKey,
            createdAtEpochMillis = 1L,
            dependencies = dependencies,
            entries = listOf(
                OcrRunEntry(
                    order = 0,
                    pageId = PAGE_ID,
                    detectionPageArtifactKey = DETECTION_PAGE_ID,
                    pageArtifactKey = OCR_PAGE_ID,
                    state = OcrPageState.COMMITTED,
                    artifactPath = "page.json",
                    previewPath = "preview.png",
                ),
            ),
        )

        assertTrue(PipelineArtifactFreshness.ocr(current, detection))
        assertFalse(
            PipelineArtifactFreshness.ocr(
                current.copy(detectionRunArtifactKey = "detection-run-stale"),
                detection,
            ),
        )
        assertFalse(
            PipelineArtifactFreshness.ocr(
                current.copy(
                    entries = current.entries.map {
                        it.copy(detectionPageArtifactKey = "detection-page-stale")
                    },
                ),
                detection,
            ),
        )

        val legacyDependencies = dependencies.copy(
            crop = dependencies.crop.copy(revision = "three-crops-mobile-v3"),
        )
        assertFalse(
            PipelineArtifactFreshness.ocr(
                current.copy(dependencies = legacyDependencies),
                detection,
            ),
        )
    }

    @Test
    fun `translation requires current configuration and exact ocr lineage`() {
        val ocr = ocrArtifact()
        val dependencies = translationDependencies(ocr.runArtifactKey)
        val current = TranslationRunArtifact(
            runArtifactKey = TRANSLATION_RUN_ID,
            projectId = PROJECT_ID,
            createdAtEpochMillis = 1L,
            dependencies = dependencies,
            entries = listOf(
                TranslationRunEntry(
                    pageId = PAGE_ID,
                    pageOrder = 0,
                    ocrPageArtifactKey = OCR_PAGE_ID,
                    pageArtifactKey = TRANSLATION_PAGE_ID,
                    artifactPath = "translation.json",
                ),
            ),
            glossaryPath = "glossary.json",
        )

        assertTrue(PipelineArtifactFreshness.translation(current, ocr))
        assertFalse(
            PipelineArtifactFreshness.translation(
                current.copy(
                    dependencies = dependencies.copy(ocrRunArtifactKey = "ocr-run-stale"),
                ),
                ocr,
            ),
        )
        assertFalse(
            PipelineArtifactFreshness.translation(
                current.copy(
                    entries = current.entries.map {
                        it.copy(ocrPageArtifactKey = "ocr-page-stale")
                    },
                ),
                ocr,
            ),
        )
        assertFalse(
            PipelineArtifactFreshness.translation(
                current.copy(
                    dependencies = dependencies.copy(
                        outputValidation = TranslationOutputValidationConfig("legacy-validator"),
                    ),
                ),
                ocr,
            ),
        )
    }

    @Test
    fun `cleanup requires current neural models and exact translation lineage`() {
        val translation = translationArtifact()
        val dependencies = currentCleanupDependencies(translation.runArtifactKey)
        val current = CleanupRunArtifact(
            runArtifactKey = CLEANUP_RUN_ID,
            projectId = PROJECT_ID,
            createdAtEpochMillis = 1L,
            dependencies = dependencies,
            entries = listOf(
                CleanupRunEntry(
                    pageId = PAGE_ID,
                    pageOrder = 0,
                    translationPageArtifactKey = TRANSLATION_PAGE_ID,
                    pageArtifactKey = CLEANUP_PAGE_ID,
                    state = CleanupPageState.COMMITTED,
                    artifactPath = "cleanup.json",
                    imagePath = "cleaned.png",
                    imageByteLength = 1L,
                ),
            ),
        )

        assertTrue(PipelineArtifactFreshness.cleanup(current, translation))
        assertFalse(
            PipelineArtifactFreshness.cleanup(
                current.copy(
                    dependencies = dependencies.copy(
                        translationRunArtifactKey = "translation-run-stale",
                    ),
                ),
                translation,
            ),
        )
        assertFalse(
            PipelineArtifactFreshness.cleanup(
                current.copy(
                    entries = current.entries.map {
                        it.copy(translationPageArtifactKey = "translation-page-stale")
                    },
                ),
                translation,
            ),
        )
        assertFalse(
            PipelineArtifactFreshness.cleanup(
                current.copy(dependencies = dependencies.copy(neuralModel = null)),
                translation,
            ),
        )
        assertFalse(
            PipelineArtifactFreshness.cleanup(
                current.copy(
                    dependencies = dependencies.copy(
                        neuralModel = dependencies.neuralModel?.copy(revision = "stale-revision"),
                    ),
                ),
                translation,
            ),
        )
    }

    private fun detectionArtifact() = DetectionRunArtifact(
        runArtifactKey = DETECTION_RUN_ID,
        projectId = PROJECT_ID,
        createdAtEpochMillis = 1L,
        model = PinnedComicDetector.descriptor.toModelRef(),
        preprocessing = DetectionPreprocessingConfig(),
        thresholds = DetectionThresholdConfig(),
        entries = listOf(
            DetectionRunEntry(
                order = 0,
                pageId = PAGE_ID,
                pageArtifactKey = DETECTION_PAGE_ID,
                state = DetectionPageState.COMMITTED,
                regionsPath = "regions.json",
                previewPath = "preview.png",
            ),
        ),
    )

    private fun ocrArtifact() = OcrRunArtifact(
        runArtifactKey = OCR_RUN_ID,
        projectId = PROJECT_ID,
        detectionRunArtifactKey = DETECTION_RUN_ID,
        createdAtEpochMillis = 1L,
        dependencies = currentOcrDependencies(),
        entries = listOf(
            OcrRunEntry(
                order = 0,
                pageId = PAGE_ID,
                detectionPageArtifactKey = DETECTION_PAGE_ID,
                pageArtifactKey = OCR_PAGE_ID,
                state = OcrPageState.COMMITTED,
                artifactPath = "ocr.json",
                previewPath = "preview.png",
            ),
        ),
    )

    private fun translationArtifact() = TranslationRunArtifact(
        runArtifactKey = TRANSLATION_RUN_ID,
        projectId = PROJECT_ID,
        createdAtEpochMillis = 1L,
        dependencies = translationDependencies(OCR_RUN_ID),
        entries = listOf(
            TranslationRunEntry(
                pageId = PAGE_ID,
                pageOrder = 0,
                ocrPageArtifactKey = OCR_PAGE_ID,
                pageArtifactKey = TRANSLATION_PAGE_ID,
                artifactPath = "translation.json",
            ),
        ),
        glossaryPath = "glossary.json",
    )

    private fun translationDependencies(ocrRunId: String) = TranslationDependencies(
        ocrRunArtifactKey = ocrRunId,
        policy = TranslationPolicy(),
        prompt = TranslationPromptRef(),
        batching = TranslationBatchingConfig(),
        outputValidation = TranslationOutputValidationConfig(),
        provider = TranslationProviderDependency(
            modelId = "model",
            temperature = 0.0,
            maximumOutputTokens = 4096,
            requestJsonObjectFormat = true,
        ),
        initialGlossary = emptyList(),
    )

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
    }
}
