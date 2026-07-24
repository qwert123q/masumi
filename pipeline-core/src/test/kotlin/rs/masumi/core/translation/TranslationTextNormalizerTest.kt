package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationTextNormalizerTest {
    @Test
    fun `restores an OCR-truncated honorific from the chapter glossary`() {
        val normalized = TranslationTextNormalizer.normalize(
            sourceText = "ルミリアさ・・・っ！",
            translatedText = "露米莉亚小...！",
            glossary = listOf(
                TranslationGlossaryEntry("ルミリア", "露米莉亚"),
                TranslationGlossaryEntry("ルミリアさん", "露米莉亚小姐"),
            ),
        )

        assertEquals("露米莉亚小姐……！", normalized)
    }

    @Test
    fun `normalizes loose dot sequences to one Chinese ellipsis`() {
        assertEquals(
            "差不多该回去了……",
            TranslationTextNormalizer.normalizeTypography("差不多该回去了. . ."),
        )
        assertEquals("为什么……", TranslationTextNormalizer.normalizeTypography("为什么。。。"))
    }

    @Test
    fun `does not expand a complete word without source truncation evidence`() {
        val normalized = TranslationTextNormalizer.normalize(
            sourceText = "小さい・・・",
            translatedText = "很小……",
            glossary = listOf(TranslationGlossaryEntry("小さい町", "很小的城镇")),
        )

        assertEquals("很小……", normalized)
    }
}
