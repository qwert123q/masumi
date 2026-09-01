package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationResponseValidatorTest {
    @Test
    fun `validator isolates invalid items while committing valid siblings`() {
        val requested = listOf(
            TranslationFixtures.input("dialogue", 0, roleHint = TranslationRoleHint.DIALOGUE),
            TranslationFixtures.input("narration", 1),
            TranslationFixtures.input("sound", 2),
            TranslationFixtures.input("missing", 3),
            TranslationFixtures.input("duplicate", 4),
            TranslationFixtures.input("wrong-role", 5, roleHint = TranslationRoleHint.DIALOGUE),
            TranslationFixtures.input("blank", 6),
        )
        val response = TranslationModelResponse(
            items = listOf(
                TranslationModelItem("dialogue", TranslationRole.DIALOGUE, "  对白  "),
                TranslationModelItem("narration", TranslationRole.NARRATION, "旁白"),
                TranslationModelItem("sound", TranslationRole.SOUND_EFFECT, "咚"),
                TranslationModelItem("duplicate", TranslationRole.NARRATION, "一"),
                TranslationModelItem("duplicate", TranslationRole.NARRATION, "二"),
                TranslationModelItem("wrong-role", TranslationRole.NARRATION, "错误"),
                TranslationModelItem("blank", TranslationRole.NARRATION, "  "),
                TranslationModelItem("extra", TranslationRole.DIALOGUE, "多余"),
            ),
            glossaryUpdates = linkedMapOf(
                " 名字 " to " 译名 ",
                " " to "无效",
                "称呼" to "甲",
                " 称呼 " to "乙",
            ),
        )

        // Sound effects are translated by default now, so opt out explicitly to
        // keep exercising the POLICY_PRESERVED path.
        val result = TranslationResponseValidator().validate(
            TranslationFixtures.window(requested, TranslationPolicy(translateSoundEffects = false)),
            response,
        )

        assertEquals(
            listOf(
                TranslationResultState.TRANSLATED,
                TranslationResultState.TRANSLATED,
                TranslationResultState.PRESERVED_SOURCE,
                TranslationResultState.PRESERVED_SOURCE,
                TranslationResultState.PRESERVED_SOURCE,
                TranslationResultState.TRANSLATED,
                TranslationResultState.PRESERVED_SOURCE,
            ),
            result.items.map { it.state },
        )
        assertEquals("对白", result.items[0].translatedText)
        assertEquals("旁白", result.items[1].translatedText)
        assertEquals("错误", result.items[5].translatedText)
        assertEquals(TranslationRole.DIALOGUE, result.items[5].role)
        assertEquals(
            listOf(
                TranslationPreserveReason.POLICY_PRESERVED,
                TranslationPreserveReason.MISSING_RESPONSE,
                TranslationPreserveReason.DUPLICATE_RESPONSE,
                null,
                TranslationPreserveReason.BLANK_TRANSLATION,
            ),
            result.items.drop(2).map { it.preserveReason },
        )
        assertEquals(listOf("extra"), result.ignoredResponseIds)
        assertEquals(listOf(TranslationGlossaryEntry("名字", "译名")), result.glossaryUpdates)
        assertEquals(3, result.discardedGlossaryEntryCount)
    }

    @Test
    fun `sound effect is translated only when policy enables it`() {
        val requested = TranslationFixtures.input("sound", 0)
        val response = TranslationModelResponse(
            items = listOf(TranslationModelItem("sound", TranslationRole.SOUND_EFFECT, "轰")),
        )

        val preserved = TranslationResponseValidator().validate(
            TranslationFixtures.window(
                listOf(requested),
                policy = TranslationPolicy(translateSoundEffects = false),
            ),
            response,
        ).items.single()
        val translated = TranslationResponseValidator().validate(
            TranslationFixtures.window(
                listOf(requested),
                policy = TranslationPolicy(translateSoundEffects = true),
            ),
            response,
        ).items.single()

        assertEquals(TranslationResultState.PRESERVED_SOURCE, preserved.state)
        assertEquals(TranslationPreserveReason.POLICY_PRESERVED, preserved.preserveReason)
        assertEquals(TranslationResultState.TRANSLATED, translated.state)
        assertEquals("轰", translated.translatedText)
    }

    @Test
    fun `invalid Japanese or source echo glossary updates are discarded`() {
        val result = TranslationResponseValidator().validate(
            TranslationFixtures.window(emptyList()),
            TranslationModelResponse(
                items = emptyList(),
                glossaryUpdates = linkedMapOf(
                    "名前" to "ナマエ",
                    "同じ" to "同じ",
                    "人物" to "角色",
                ),
            ),
        )

        assertEquals(listOf(TranslationGlossaryEntry("人物", "角色")), result.glossaryUpdates)
        assertEquals(2, result.discardedGlossaryEntryCount)
    }

    @Test
    fun `validator preserves target output containing hiragana or katakana`() {
        val requested = TranslationFixtures.input("script", 0, source = "今日は")

        val hiragana = TranslationResponseValidator().validate(
            TranslationFixtures.window(listOf(requested)),
            TranslationModelResponse(
                items = listOf(TranslationModelItem("script", TranslationRole.NARRATION, "今日は")),
            ),
        ).items.single()
        val katakana = TranslationResponseValidator().validate(
            TranslationFixtures.window(listOf(requested)),
            TranslationModelResponse(
                items = listOf(TranslationModelItem("script", TranslationRole.NARRATION, "テスト")),
            ),
        ).items.single()

        assertEquals(TranslationResultState.PRESERVED_SOURCE, hiragana.state)
        assertEquals(TranslationResultState.PRESERVED_SOURCE, katakana.state)
        assertEquals(TranslationPreserveReason.INVALID_TARGET_SCRIPT, hiragana.preserveReason)
        assertEquals(TranslationPreserveReason.INVALID_TARGET_SCRIPT, katakana.preserveReason)
    }

    @Test
    fun `validator preserves a non numeric normalized source echo but accepts numeric and symbol echoes`() {
        val text = TranslationFixtures.input("text", 0, source = " 日本 ")
        val number = TranslationFixtures.input("number", 1, source = " １２３－４５ ")

        val result = TranslationResponseValidator().validate(
            TranslationFixtures.window(listOf(text, number)),
            TranslationModelResponse(
                items = listOf(
                    TranslationModelItem("text", TranslationRole.NARRATION, "日本"),
                    TranslationModelItem("number", TranslationRole.NARRATION, "１２３－４５"),
                ),
            ),
        )

        assertEquals(TranslationResultState.PRESERVED_SOURCE, result.items[0].state)
        assertEquals(TranslationPreserveReason.SOURCE_TEXT_ECHO, result.items[0].preserveReason)
        assertEquals(TranslationResultState.TRANSLATED, result.items[1].state)
    }

    @Test
    fun `post normalization output is rechecked for a source echo`() {
        val validator = TranslationResponseValidator()
        val source = "回声……"
        val rawModelOutput = "回声..."

        assertEquals(null, validator.invalidOutputReason(source, rawModelOutput))
        val locallyNormalized = TranslationTextNormalizer.normalize(source, rawModelOutput, emptyList())

        assertEquals("回声……", locallyNormalized)
        assertEquals(
            TranslationPreserveReason.SOURCE_TEXT_ECHO,
            validator.invalidOutputReason(source, locallyNormalized),
        )
    }
}
