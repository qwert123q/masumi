package rs.masumi.app.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.masumi.app.ocr.currentOcrDependencies
import rs.masumi.core.cleanup.CleanupIdentity
import rs.masumi.core.cleanup.CleanupPageState
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.cleanup.CleanupRunEntry
import rs.masumi.core.ocr.OcrIdentity
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.ocr.OcrRunEntry
import rs.masumi.core.translation.TranslationArtifactIdentity
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
    fun `ocr requires current dependencies and a self-consistent current identity`() {
        val detectionRun = sha('d')
        val detectionPage = sha('e')
        val source = sha('a')
        val dependencies = currentOcrDependencies()
        val pageKey = OcrIdentity.pageArtifactKey(source, detectionPage, dependencies)
        val runKey = OcrIdentity.runArtifactKey(listOf(0 to pageKey))
        val current = OcrRunArtifact(
            runArtifactKey = runKey,
            projectId = "project",
            detectionRunArtifactKey = detectionRun,
            createdAtEpochMillis = 1L,
            dependencies = dependencies,
            entries = listOf(
                OcrRunEntry(
                    order = 0,
                    pageId = source,
                    sourceSha256 = source,
                    detectionPageArtifactKey = detectionPage,
                    pageArtifactKey = pageKey,
                    state = OcrPageState.COMMITTED,
                    artifactPath = "page.json",
                    previewPath = "preview.png",
                ),
            ),
        )

        assertTrue(PipelineArtifactFreshness.ocr(current, detectionRun))
        assertFalse(PipelineArtifactFreshness.ocr(current.copy(runArtifactKey = sha('f')), detectionRun))
        val stalePageIdentity = sha('9')
        assertFalse(
            PipelineArtifactFreshness.ocr(
                current.copy(
                    runArtifactKey = OcrIdentity.runArtifactKey(listOf(0 to stalePageIdentity)),
                    entries = current.entries.map { it.copy(pageArtifactKey = stalePageIdentity) },
                ),
                detectionRun,
            ),
        )

        val legacyDependencies = dependencies.copy(
            crop = dependencies.crop.copy(revision = "three-crops-mobile-v3"),
        )
        val legacyPageKey = OcrIdentity.pageArtifactKey(source, detectionPage, legacyDependencies)
        val legacy = current.copy(
            runArtifactKey = OcrIdentity.runArtifactKey(listOf(0 to legacyPageKey)),
            dependencies = legacyDependencies,
            entries = current.entries.map { it.copy(pageArtifactKey = legacyPageKey) },
        )
        assertFalse(PipelineArtifactFreshness.ocr(legacy, detectionRun))
    }

    @Test
    fun `translation rejects a stale key even when missing legacy fields decode to current defaults`() {
        val ocrRun = sha('b')
        val ocrPage = sha('c')
        val dependencies = TranslationDependencies(
            ocrRunArtifactKey = ocrRun,
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
            initialGlossarySha256 = TranslationArtifactIdentity.glossarySha256(emptyList()),
        )
        val pageKey = TranslationArtifactIdentity.pageArtifactKey(ocrPage, dependencies)
        val runKey = TranslationArtifactIdentity.runArtifactKey(listOf(0 to pageKey), dependencies)
        val current = TranslationRunArtifact(
            runArtifactKey = runKey,
            projectId = "project",
            createdAtEpochMillis = 1L,
            dependencies = dependencies,
            entries = listOf(
                TranslationRunEntry(
                    pageId = sha('a'),
                    pageOrder = 0,
                    ocrPageArtifactKey = ocrPage,
                    pageArtifactKey = pageKey,
                    artifactPath = "translation.json",
                ),
            ),
            glossaryPath = "glossary.json",
        )

        assertTrue(PipelineArtifactFreshness.translation(current, ocrRun))
        assertFalse(PipelineArtifactFreshness.translation(current.copy(runArtifactKey = sha('f')), ocrRun))
        val stalePageIdentity = sha('9')
        assertFalse(
            PipelineArtifactFreshness.translation(
                current.copy(
                    runArtifactKey = TranslationArtifactIdentity.runArtifactKey(
                        listOf(0 to stalePageIdentity),
                        dependencies,
                    ),
                    entries = current.entries.map { it.copy(pageArtifactKey = stalePageIdentity) },
                ),
                ocrRun,
            ),
        )
        assertFalse(
            PipelineArtifactFreshness.translation(
                current.copy(
                    dependencies = dependencies.copy(
                        outputValidation = TranslationOutputValidationConfig("legacy-validator"),
                    ),
                ),
                ocrRun,
            ),
        )
    }

    @Test
    fun `cleanup requires current neural model and self-consistent identity`() {
        val translationRun = sha('b')
        val source = sha('a')
        val translationPage = sha('c')
        val dependencies = currentCleanupDependencies(translationRun)
        val pageKey = CleanupIdentity.pageArtifactKey(
            0,
            source,
            translationPage,
            dependencies,
        )
        val current = CleanupRunArtifact(
            runArtifactKey = CleanupIdentity.runArtifactKey(listOf(0 to pageKey), dependencies),
            projectId = "project",
            createdAtEpochMillis = 1L,
            dependencies = dependencies,
            entries = listOf(
                CleanupRunEntry(
                    pageId = source,
                    pageOrder = 0,
                    sourceSha256 = source,
                    translationPageArtifactKey = translationPage,
                    pageArtifactKey = pageKey,
                    state = CleanupPageState.COMMITTED,
                    artifactPath = "cleanup.json",
                    imagePath = "cleaned.png",
                ),
            ),
        )

        assertTrue(PipelineArtifactFreshness.cleanup(current, translationRun))
        assertFalse(PipelineArtifactFreshness.cleanup(current.copy(runArtifactKey = sha('f')), translationRun))
        assertFalse(
            PipelineArtifactFreshness.cleanup(
                current.copy(
                    dependencies = dependencies.copy(neuralModel = null),
                ),
                translationRun,
            ),
        )
        assertFalse(
            PipelineArtifactFreshness.cleanup(
                current.copy(
                    dependencies = dependencies.copy(
                        neuralModel = dependencies.neuralModel?.copy(revision = "stale-revision"),
                    ),
                ),
                translationRun,
            ),
        )
    }

    private fun sha(character: Char): String = character.toString().repeat(64)
}
