package rs.masumi.core.translation

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import rs.masumi.core.serialization.TranslationJson

class TranslationJsonTest {
    @Test
    fun `translation input JSON is strict and round trips`() {
        val input = PageTranslationInput(
            pageId = "page-1",
            pageOrder = 0,
            ocrPageArtifactKey = "ocr-page-key",
            policy = TranslationPolicy(),
            prompt = TranslationPromptRef(),
            items = listOf(
                TranslationInputItem(
                    translationRegionId = "translation-region-1",
                    ocrRegionId = "ocr-region-1",
                    readingOrderRank = 0,
                    sourceText = "原文",
                    roleHint = TranslationRoleHint.DIALOGUE,
                ),
            ),
            protectedRegions = emptyList(),
        )
        val encoded = TranslationJson().encodePageInput(input)

        assertEquals(input, TranslationJson().decodePageInput(encoded))
        assertFailsWith<SerializationException> {
            TranslationJson().decodePageInput(encoded.dropLast(2) + ",\"unexpected\":true\n}")
        }
    }

    @Test
    fun `region identity changes with sound-effect policy and prompt revision`() {
        val baseline = TranslationIdentity.regionId("page", "region", TranslationPolicy(), TranslationPromptRef())
        val soundEffects = TranslationIdentity.regionId(
            "page",
            "region",
            TranslationPolicy(translateSoundEffects = true),
            TranslationPromptRef(),
        )
        val prompt = TranslationIdentity.regionId(
            "page",
            "region",
            TranslationPolicy(),
            TranslationPromptRef(revision = "chapter-structured-v2"),
        )

        assertEquals(64, baseline.length)
        assertNotEquals(baseline, soundEffects)
        assertNotEquals(baseline, prompt)
    }
}
