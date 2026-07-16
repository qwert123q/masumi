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
}
