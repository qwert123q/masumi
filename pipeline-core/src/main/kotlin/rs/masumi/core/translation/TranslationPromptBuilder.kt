package rs.masumi.core.translation

import kotlin.math.ceil
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TranslationPromptBuilder(
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
    },
) {
    fun build(window: TranslationBatchWindow): TranslationPromptMessages = TranslationPromptMessages(
        system = buildSystemPrompt(window.policy, window.prompt),
        user = json.encodeToString(
            PromptPayload(
                schema = window.prompt.responseSchemaRevision,
                targetLanguage = window.policy.targetLanguage.name,
                glossary = window.glossary.sortedWith(
                    compareBy(TranslationGlossaryEntry::source, TranslationGlossaryEntry::translation),
                ),
                context = window.contextItems.map(::promptItem),
                items = window.items.map(::promptItem),
            ),
        ),
    )

    fun estimateInputTokens(window: TranslationBatchWindow): Int {
        val messages = build(window)
        return estimateTextTokens(messages.system) + estimateTextTokens(messages.user) + MESSAGE_OVERHEAD_TOKENS
    }

    private fun buildSystemPrompt(policy: TranslationPolicy, prompt: TranslationPromptRef): String = buildString {
        append("Protocol ")
        append(prompt.revision)
        append(". Translate Japanese manga text into Simplified Chinese. Return only one JSON object matching schema ")
        append(prompt.responseSchemaRevision)
        append(" with items [{id,role,translation}] and glossaryUpdates. glossaryUpdates must be a JSON object mapping Japanese source strings to Simplified Chinese translations; use {} when there are no updates. Return every id from the items array exactly once, never return context ids, and add no ids. ")
        append("Allowed roles: DIALOGUE, NARRATION, SOUND_EFFECT, OTHER_TEXT. DIALOGUE hints must remain DIALOGUE; CLASSIFY_FREE_TEXT must be classified. ")
        append("Use concise, idiomatic Chinese that preserves meaning and fits the original manga region; do not expand, explain, or repeat information. Dialogue translation=")
        append(policy.translateDialogue)
        append(", narration translation=")
        append(policy.translateNarration)
        append(", sound-effect translation=")
        append(policy.translateSoundEffects)
        append(", other-text translation=")
        append(policy.translateOtherText)
        append(". When a role is not translated by policy, set translation to null. Never invent source text or commentary.")
    }

    private fun promptItem(item: TranslationBatchItem): PromptItem = PromptItem(
        id = item.input.translationRegionId,
        pageOrder = item.pageOrder,
        readingOrderRank = item.input.readingOrderRank,
        source = item.input.sourceText,
        roleHint = item.input.roleHint,
    )

    private fun estimateTextTokens(text: String): Int = ceil(text.toByteArray(Charsets.UTF_8).size / 3.0).toInt()

    @Serializable
    private data class PromptPayload(
        val schema: String,
        val targetLanguage: String,
        val glossary: List<TranslationGlossaryEntry>,
        val context: List<PromptItem>,
        val items: List<PromptItem>,
    )

    @Serializable
    private data class PromptItem(
        val id: String,
        val pageOrder: Int,
        val readingOrderRank: Int,
        val source: String,
        val roleHint: TranslationRoleHint,
    )

    private companion object {
        const val MESSAGE_OVERHEAD_TOKENS = 16
    }
}
