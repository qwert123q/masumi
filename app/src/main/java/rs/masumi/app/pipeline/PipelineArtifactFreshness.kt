package rs.masumi.app.pipeline

import rs.masumi.app.ocr.currentOcrDependencies
import rs.masumi.core.cleanup.CLEANUP_SCHEMA_VERSION
import rs.masumi.core.cleanup.CleanupDependencies
import rs.masumi.core.cleanup.CleanupIdentity
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.modelpackage.PinnedAotInpainter
import rs.masumi.core.modelpackage.PinnedComicTextSegmenter
import rs.masumi.core.ocr.OCR_SCHEMA_VERSION
import rs.masumi.core.ocr.OcrIdentity
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.translation.TRANSLATION_SCHEMA_VERSION
import rs.masumi.core.translation.TranslationArtifactIdentity
import rs.masumi.core.translation.TranslationBatchingConfig
import rs.masumi.core.translation.TranslationOutputValidationConfig
import rs.masumi.core.translation.TranslationPolicy
import rs.masumi.core.translation.TranslationPromptRef
import rs.masumi.core.translation.TranslationRunArtifact

/** Rejects decoded legacy artifacts whose newly defaulted fields hide an old identity. */
internal object PipelineArtifactFreshness {
    fun ocr(artifact: OcrRunArtifact, detectionRunArtifactKey: String): Boolean = runCatching {
        artifact.schemaVersion == OCR_SCHEMA_VERSION &&
            artifact.detectionRunArtifactKey == detectionRunArtifactKey &&
            artifact.dependencies == currentOcrDependencies() &&
            artifact.entries.all { entry ->
                entry.pageArtifactKey == OcrIdentity.pageArtifactKey(
                    entry.sourceSha256,
                    entry.detectionPageArtifactKey,
                    artifact.dependencies,
                )
            } &&
            artifact.runArtifactKey == OcrIdentity.runArtifactKey(
                artifact.entries.sortedBy { it.order }.map { it.order to it.pageArtifactKey },
            )
    }.getOrDefault(false)

    fun translation(artifact: TranslationRunArtifact, ocrRunArtifactKey: String): Boolean = runCatching {
        val dependencies = artifact.dependencies
        artifact.schemaVersion == TRANSLATION_SCHEMA_VERSION &&
            dependencies.ocrRunArtifactKey == ocrRunArtifactKey &&
            dependencies.policy == TranslationPolicy() &&
            dependencies.prompt == TranslationPromptRef() &&
            dependencies.batching == TranslationBatchingConfig() &&
            dependencies.outputValidation == TranslationOutputValidationConfig() &&
            artifact.entries.all { entry ->
                entry.pageArtifactKey == TranslationArtifactIdentity.pageArtifactKey(
                    entry.ocrPageArtifactKey,
                    dependencies,
                )
            } &&
            artifact.runArtifactKey == TranslationArtifactIdentity.runArtifactKey(
                artifact.entries.sortedBy { it.pageOrder }.map { it.pageOrder to it.pageArtifactKey },
                dependencies,
            )
    }.getOrDefault(false)

    fun cleanup(artifact: CleanupRunArtifact, translationRunArtifactKey: String): Boolean = runCatching {
        val dependencies = currentCleanupDependencies(translationRunArtifactKey)
        artifact.schemaVersion == CLEANUP_SCHEMA_VERSION &&
            artifact.dependencies == dependencies &&
            artifact.entries.all { entry ->
                entry.pageArtifactKey == CleanupIdentity.pageArtifactKey(
                    entry.pageOrder,
                    entry.sourceSha256,
                    entry.translationPageArtifactKey,
                    dependencies,
                )
            } &&
            artifact.runArtifactKey == CleanupIdentity.runArtifactKey(
                artifact.entries.sortedBy { it.pageOrder }.map { it.pageOrder to it.pageArtifactKey },
                dependencies,
            )
    }.getOrDefault(false)
}

internal fun currentCleanupDependencies(translationRunArtifactKey: String): CleanupDependencies =
    CleanupDependencies(
        translationRunArtifactKey = translationRunArtifactKey,
        policy = CleanupPolicy(),
        maskModel = PinnedComicTextSegmenter.descriptor.toModelRef(),
        neuralModel = PinnedAotInpainter.descriptor.toModelRef(),
    )
