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

        val result = TranslationResponseValidator().validate(TranslationFixtures.window(requested), response)

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
            TranslationFixtures.window(listOf(requested)),
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
}
