package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationPromptBuilderTest {
    @Test
    fun `prompt contains stable ids ordered glossary and explicit sound-effect policy`() {
        val base = TranslationFixtures.window(
            listOf(
                TranslationFixtures.input(
                    id = "dialogue-id",
                    rank = 0,
                    source = "台詞",
                    roleHint = TranslationRoleHint.DIALOGUE,
                ),
            ),
        )
        val window = base.copy(
            glossary = listOf(
                TranslationGlossaryEntry("B", "乙"),
                TranslationGlossaryEntry("A", "甲"),
            ),
        )

        val messages = TranslationPromptBuilder().build(window)

        assertTrue(messages.system.contains("sound-effect translation=false"))
        assertTrue(messages.system.contains("Return only one JSON object"))
        assertTrue(messages.system.contains("never return context ids"))
        assertTrue(messages.user.contains("\"id\":\"dialogue-id\""))
        assertTrue(messages.user.indexOf("\"source\":\"A\"") < messages.user.indexOf("\"source\":\"B\""))
        assertEquals(messages, TranslationPromptBuilder().build(window))
    }

    @Test
    fun `glossary discovery sees context and targets but requests no translated items`() {
        val context = TranslationFixtures.batchItem(
            TranslationFixtures.input("context-id", 0, "ルミリアさん"),
        )
        val window = TranslationFixtures.window(
            listOf(TranslationFixtures.input("target-id", 1, "ルミリアさ・・・っ！")),
        ).copy(contextItems = listOf(context))

        val messages = TranslationPromptBuilder().buildGlossaryDiscovery(window)

        assertTrue(messages.system.contains("items []"))
        assertTrue(messages.system.contains("name-plus-honorific"))
        assertTrue(messages.user.contains("\"id\":\"context-id\""))
        assertTrue(messages.user.contains("\"id\":\"target-id\""))
        assertTrue(messages.user.contains("\"items\":[]"))
    }
}
