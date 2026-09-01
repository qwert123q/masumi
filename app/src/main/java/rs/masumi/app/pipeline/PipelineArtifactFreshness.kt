package rs.masumi.app.pipeline

import rs.masumi.app.ocr.currentOcrDependencies
import rs.masumi.core.cleanup.CLEANUP_SCHEMA_VERSION
import rs.masumi.core.cleanup.CleanupDependencies
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.detection.DETECTION_SCHEMA_VERSION
import rs.masumi.core.detection.DetectionPreprocessingConfig
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.DetectionThresholdConfig
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.modelpackage.PinnedAotInpainter
import rs.masumi.core.modelpackage.PinnedComicDetector
import rs.masumi.core.modelpackage.PinnedComicTextSegmenter
import rs.masumi.core.ocr.OCR_SCHEMA_VERSION
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.translation.TRANSLATION_SCHEMA_VERSION
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationOutputValidationConfig
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.TranslationRunArtifact
import rs.masumi.core.typesetting.TYPESETTING_SCHEMA_VERSION
import rs.masumi.core.typesetting.TypesettingPolicy
import rs.masumi.core.typesetting.TypesettingRunArtifact

/** Matches reusable artifacts by explicit contracts and ordered upstream lineage. */
internal object PipelineArtifactFreshness {
    fun detection(artifact: DetectionRunArtifact, manifest: ProjectManifest): Boolean = runCatching {
        artifact.schemaVersion == DETECTION_SCHEMA_VERSION &&
            artifact.projectId == manifest.projectId &&
            artifact.model == PinnedComicDetector.descriptor.toModelRef() &&
            artifact.preprocessing == DetectionPreprocessingConfig() &&
            artifact.thresholds == DetectionThresholdConfig() &&
            artifact.entries.map { it.order to it.pageId } == manifest.pages.map { it.order to it.pageId }
    }.getOrDefault(false)

    fun ocr(artifact: OcrRunArtifact, detection: DetectionRunArtifact): Boolean = runCatching {
        artifact.schemaVersion == OCR_SCHEMA_VERSION &&
            artifact.projectId == detection.projectId &&
            artifact.detectionRunArtifactKey == detection.runArtifactKey &&
            artifact.dependencies == currentOcrDependencies() &&
            artifact.entries.map { it.order to it.pageId to it.detectionPageArtifactKey } ==
            detection.entries.map { it.order to it.pageId to it.pageArtifactKey }
    }.getOrDefault(false)

    fun translation(artifact: TranslationRunArtifact, ocr: OcrRunArtifact): Boolean = runCatching {
        val dependencies = artifact.dependencies
        artifact.schemaVersion == TRANSLATION_SCHEMA_VERSION &&
            artifact.projectId == ocr.projectId &&
            dependencies.ocrRunArtifactKey == ocr.runArtifactKey &&
            dependencies.policy == TranslationPolicy() &&
            dependencies.prompt == TranslationPromptRef() &&
            dependencies.batching == TranslationBatchingConfig() &&
            dependencies.outputValidation == TranslationOutputValidationConfig() &&
            artifact.entries.map { it.pageOrder to it.pageId to it.ocrPageArtifactKey } ==
            ocr.entries.map { it.order to it.pageId to it.pageArtifactKey }
    }.getOrDefault(false)

    fun cleanup(artifact: CleanupRunArtifact, translation: TranslationRunArtifact): Boolean = runCatching {
        val dependencies = currentCleanupDependencies(translation.runArtifactKey)
        artifact.schemaVersion == CLEANUP_SCHEMA_VERSION &&
            artifact.projectId == translation.projectId &&
            artifact.dependencies == dependencies &&
            artifact.entries.map { it.pageOrder to it.pageId to it.translationPageArtifactKey } ==
            translation.entries.map { it.pageOrder to it.pageId to it.pageArtifactKey }
    }.getOrDefault(false)

    fun typesetting(artifact: TypesettingRunArtifact, cleanup: CleanupRunArtifact): Boolean = runCatching {
        artifact.schemaVersion == TYPESETTING_SCHEMA_VERSION &&
            artifact.projectId == cleanup.projectId &&
            artifact.dependencies.cleanupRunArtifactKey == cleanup.runArtifactKey &&
            artifact.dependencies.policy == TypesettingPolicy() &&
            artifact.dependencies.reuseRunArtifactKey.isEmpty() &&
            artifact.dependencies.reprocessPageOrders.isEmpty() &&
            artifact.entries.map { it.pageOrder to it.pageId to it.cleanupPageArtifactKey } ==
            cleanup.entries.map { it.pageOrder to it.pageId to it.pageArtifactKey }
    }.getOrDefault(false)
}

internal fun currentCleanupDependencies(translationRunArtifactKey: String): CleanupDependencies =
    CleanupDependencies(
        translationRunArtifactKey = translationRunArtifactKey,
        policy = CleanupPolicy(),
        maskModel = PinnedComicTextSegmenter.descriptor.toModelRef(),
        neuralModel = PinnedAotInpainter.descriptor.toModelRef(),
    )
