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
        user = encodePayload(window, window.contextItems, window.items),
    )

    fun buildGlossaryDiscovery(window: TranslationBatchWindow): TranslationPromptMessages = TranslationPromptMessages(
        system = buildGlossarySystemPrompt(window.prompt),
        user = encodePayload(
            window = window,
            context = (window.contextItems + window.items)
                .distinctBy { it.input.translationRegionId },
            items = emptyList(),
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
        append("Before returning, verify that the response items count equals the request items count and that every requested id is present exactly once. ")
        append("Allowed roles: DIALOGUE, NARRATION, SOUND_EFFECT, OTHER_TEXT. DIALOGUE hints must remain DIALOGUE; CLASSIFY_FREE_TEXT must be classified. ")
        append("Use concise, idiomatic Chinese that preserves meaning and fits the original manga region; do not expand, explain, or repeat information. Dialogue translation=")
        append(policy.translateDialogue)
        append(", narration translation=")
        append(policy.translateNarration)
        append(", sound-effect translation=")
        append(policy.translateSoundEffects)
        append(", other-text translation=")
        append(policy.translateOtherText)
        append(". Japanese OCR can cut off a name, honorific, or final syllable immediately before an ellipsis. Use repeated names, glossary entries, and nearby chapter context to restore an obvious truncation; never emit a visibly incomplete Chinese word such as 小…… when context establishes 小姐. ")
        append("Use Chinese typography: write ellipses as …… with no spaces, never as ..., 。。。 or separated dots. Keep names and forms of address consistent within the chapter. ")
        append("For every role enabled by policy, translation must be a non-empty JSON string. If a punctuation- or symbol-only item is ever provided, copy its source exactly instead of returning null or an empty string. ")
        append("Only when a role is disabled by policy may translation be null. Never invent source text or commentary.")
    }

    private fun buildGlossarySystemPrompt(prompt: TranslationPromptRef): String = buildString {
        append("Protocol ")
        append(prompt.revision)
        append(". Build a reusable Japanese-to-Simplified-Chinese manga glossary before translation. ")
        append("Return only one JSON object matching schema ")
        append(prompt.responseSchemaRevision)
        append(" with items [] and glossaryUpdates. glossaryUpdates must be a JSON object. ")
        append("Inspect every context source and extract only recurring names, name-plus-honorific forms, titles, places, organizations, and domain terms whose consistent rendering matters. ")
        append("For a recurring name, include both its base form and any observed name-plus-honorific form, for example さん as 小姐/先生 when the context establishes that address. ")
        append("Use existing glossary translations exactly. Restore an obvious OCR-truncated final syllable only when another source or existing glossary establishes the complete term. ")
        append("Use concise Simplified Chinese and Chinese punctuation. Do not translate full sentences, return item ids, add commentary, or invent unsupported terms.")
    }

    private fun encodePayload(
        window: TranslationBatchWindow,
        context: List<TranslationBatchItem>,
        items: List<TranslationBatchItem>,
    ): String = json.encodeToString(
        PromptPayload(
            schema = window.prompt.responseSchemaRevision,
            targetLanguage = window.policy.targetLanguage.name,
            glossary = window.glossary.sortedWith(
                compareBy(TranslationGlossaryEntry::source, TranslationGlossaryEntry::translation),
            ),
            context = context.map(::promptItem),
            items = items.map(::promptItem),
        ),
    )

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
