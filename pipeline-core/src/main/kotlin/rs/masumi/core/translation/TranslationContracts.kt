package rs.masumi.core.translation

import kotlinx.serialization.Serializable

const val TRANSLATION_SCHEMA_VERSION = 1

@Serializable
enum class TranslationSourceLanguage {
    JA,
}

@Serializable
enum class TranslationTargetLanguage {
    ZH_HANS,
}

@Serializable
enum class TranslationRoleHint {
    DIALOGUE,
    CLASSIFY_FREE_TEXT,
}

@Serializable
enum class TranslationRole {
    DIALOGUE,
    NARRATION,
    SOUND_EFFECT,
    OTHER_TEXT,
}

@Serializable
enum class TranslationProtectionReason {
    OCR_NOT_TRUSTED,
}

@Serializable
enum class TranslationResultState {
    TRANSLATED,
    PRESERVED_SOURCE,
}

@Serializable
enum class TranslationPreserveReason {
    POLICY_PRESERVED,
    MISSING_RESPONSE,
    DUPLICATE_RESPONSE,
    INVALID_ROLE,
    BLANK_TRANSLATION,
    PROVIDER_FAILURE,
    OVERSIZED_INPUT,
}

@Serializable
data class TranslationPolicy(
    val revision: String = "ja-zh-hans-v2",
    val sourceLanguage: TranslationSourceLanguage = TranslationSourceLanguage.JA,
    val targetLanguage: TranslationTargetLanguage = TranslationTargetLanguage.ZH_HANS,
    val translateDialogue: Boolean = true,
    val translateNarration: Boolean = true,
    val translateSoundEffects: Boolean = false,
    val translateOtherText: Boolean = true,
    val automaticApproval: Boolean = true,
) {
    init {
        require(translateDialogue) { "dialogue translation is mandatory" }
        require(translateNarration) { "narration translation is mandatory" }
        require(automaticApproval) { "manual approval is not supported" }
    }
}

@Serializable
data class TranslationPromptRef(
    val revision: String = "window-glossary-prefetch-v3",
    val responseSchemaRevision: String = "items-by-id-v1",
)

@Serializable
data class TranslationInputItem(
    val translationRegionId: String,
    val ocrRegionId: String,
    val readingOrderRank: Int,
    val sourceText: String,
    val roleHint: TranslationRoleHint,
)

@Serializable
data class ProtectedTranslationRegion(
    val ocrRegionId: String,
    val readingOrderRank: Int,
    val reason: TranslationProtectionReason,
)

@Serializable
data class PageTranslationInput(
    val schemaVersion: Int = TRANSLATION_SCHEMA_VERSION,
    val pageId: String,
    val pageOrder: Int,
    val ocrPageArtifactKey: String,
    val policy: TranslationPolicy,
    val prompt: TranslationPromptRef,
    val items: List<TranslationInputItem>,
    val protectedRegions: List<ProtectedTranslationRegion>,
)

@Serializable
data class TranslationModelItem(
    val id: String,
    val role: TranslationRole,
    val translation: String? = null,
)

@Serializable
data class TranslationModelResponse(
    val items: List<TranslationModelItem>,
    val glossaryUpdates: Map<String, String> = emptyMap(),
)

@Serializable
data class TranslationBatchingConfig(
    val revision: String = "chapter-window-v3",
    val maximumEstimatedInputTokens: Int = 6_000,
    val maximumItemsPerWindow: Int = 12,
    val maximumContextItems: Int = 24,
) {
    init {
        require(maximumEstimatedInputTokens > 0) { "maximumEstimatedInputTokens must be positive" }
        require(maximumItemsPerWindow > 0) { "maximumItemsPerWindow must be positive" }
        require(maximumContextItems >= 0) { "maximumContextItems must not be negative" }
    }
}

@Serializable
data class TranslationGlossaryEntry(
    val source: String,
    val translation: String,
)

@Serializable
data class TranslationBatchItem(
    val pageId: String,
    val pageOrder: Int,
    val ocrPageArtifactKey: String,
    val input: TranslationInputItem,
)

@Serializable
data class TranslationBatchWindow(
    val windowIndex: Int,
    val policy: TranslationPolicy,
    val prompt: TranslationPromptRef,
    val batching: TranslationBatchingConfig,
    val glossary: List<TranslationGlossaryEntry>,
    val contextItems: List<TranslationBatchItem>,
    val items: List<TranslationBatchItem>,
    val estimatedInputTokens: Int,
    val exceedsBudget: Boolean,
)

@Serializable
data class TranslationPromptMessages(
    val system: String,
    val user: String,
)

@Serializable
data class ValidatedTranslationItem(
    val translationRegionId: String,
    val ocrRegionId: String,
    val role: TranslationRole? = null,
    val translatedText: String? = null,
    val state: TranslationResultState,
    val preserveReason: TranslationPreserveReason? = null,
)

@Serializable
data class TranslationResponseValidation(
    val items: List<ValidatedTranslationItem>,
    val ignoredResponseIds: List<String>,
    val glossaryUpdates: List<TranslationGlossaryEntry>,
    val discardedGlossaryEntryCount: Int,
)
