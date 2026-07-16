package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TranslationArtifactIdentityTest {
    @Test
    fun `identity covers model batching and initial glossary while glossary order is stable`() {
        val base = TranslationArtifactFixtures.dependencies
        val baseline = TranslationArtifactIdentity.pageArtifactKey("b".repeat(64), base)
        val changed = listOf(
            base.copy(provider = base.provider.copy(modelId = "other-model")),
            base.copy(batching = base.batching.copy(maximumContextItems = 12)),
            base.copy(initialGlossarySha256 = "f".repeat(64)),
        ).map { TranslationArtifactIdentity.pageArtifactKey("b".repeat(64), it) }

        assertEquals(64, baseline.length)
        changed.forEach { assertNotEquals(baseline, it) }
        assertEquals(
            TranslationArtifactIdentity.glossarySha256(
                listOf(TranslationGlossaryEntry("B", "乙"), TranslationGlossaryEntry("A", "甲")),
            ),
            TranslationArtifactIdentity.glossarySha256(
                listOf(TranslationGlossaryEntry("A", "甲"), TranslationGlossaryEntry("B", "乙")),
            ),
        )
    }
}
