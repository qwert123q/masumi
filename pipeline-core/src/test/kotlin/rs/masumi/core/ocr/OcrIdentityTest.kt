package rs.masumi.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OcrIdentityTest {
    @Test
    fun `region identity sorts provenance and changes with semantics`() {
        val first = OcrIdentity.regionId(
            pageId = "a".repeat(64),
            detectionPageArtifactKey = "b".repeat(64),
            sourceRegionIds = listOf("d".repeat(64), "c".repeat(64)),
            semantic = OcrSemanticStatus.REQUIRED_TEXT,
        )
        val reordered = OcrIdentity.regionId(
            pageId = "a".repeat(64),
            detectionPageArtifactKey = "b".repeat(64),
            sourceRegionIds = listOf("c".repeat(64), "d".repeat(64)),
            semantic = OcrSemanticStatus.REQUIRED_TEXT,
        )

        assertEquals(first, reordered)
        assertEquals(64, first.length)
        assertNotEquals(
            first,
            OcrIdentity.regionId(
                "a".repeat(64),
                "b".repeat(64),
                listOf("c".repeat(64), "d".repeat(64)),
                OcrSemanticStatus.UNRESOLVED_FREE_TEXT,
            ),
        )
    }

    @Test
    fun `page identity changes for model runtime crop quality and prompt dependencies`() {
        val dependencies = dependencies()
        val baseline = OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies)
        val changed = listOf(
            OcrIdentity.pageArtifactKey("c".repeat(64), "b".repeat(64), dependencies),
            OcrIdentity.pageArtifactKey("a".repeat(64), "d".repeat(64), dependencies),
            OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies.copy(modelPackage = dependencies.modelPackage.copy(packageSha256 = "e".repeat(64)))),
            OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies.copy(runtime = dependencies.runtime.copy(backend = "cpu"))),
            OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies.copy(runtime = dependencies.runtime.copy(buildContract = "mtmd-v2"))),
            OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies.copy(consolidation = dependencies.consolidation.copy(minimumFreeTextWidthFraction = 0.04))),
            OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies.copy(crop = dependencies.crop.copy(contextTextFraction = 0.25))),
            OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies.copy(highDetailRetry = dependencies.highDetailRetry.copy(maximumVisualTokens = 193))),
            OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies.copy(quality = dependencies.quality.copy(primaryTokenProbabilityThreshold = 0.56))),
            OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies.copy(quality = dependencies.quality.copy(lowConfidenceFreeTextThreshold = 0.49))),
            OcrIdentity.pageArtifactKey("a".repeat(64), "b".repeat(64), dependencies.copy(generation = dependencies.generation.copy(prompt = "Read:"))),
        )

        changed.forEach { assertNotEquals(baseline, it) }
        assertEquals(changed.size, changed.toSet().size)
    }

    @Test
    fun `run identity preserves page order and requires contiguous entries`() {
        val first = OcrIdentity.runArtifactKey(listOf(0 to "a".repeat(64), 1 to "b".repeat(64)))
        val repeated = OcrIdentity.runArtifactKey(listOf(0 to "a".repeat(64), 1 to "b".repeat(64)))
        val reversed = OcrIdentity.runArtifactKey(listOf(0 to "b".repeat(64), 1 to "a".repeat(64)))

        assertEquals(first, repeated)
        assertNotEquals(first, reversed)
        assertFailsWith<IllegalArgumentException> {
            OcrIdentity.runArtifactKey(listOf(1 to "a".repeat(64)))
        }
    }

    private fun dependencies(): OcrDependencies = OcrFixtures.dependencies().copy(
        modelPackage = OcrFixtures.modelPackage().copy(
            model = OcrModelFileRef("model.gguf", 11L, "1".repeat(64)),
            projector = OcrModelFileRef("projector.gguf", 12L, "2".repeat(64)),
            packageSha256 = "3".repeat(64),
        ),
    )
}
