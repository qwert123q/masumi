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
data class TranslationPolicy(
    val revision: String = "ja-zh-hans-v1",
    val sourceLanguage: TranslationSourceLanguage = TranslationSourceLanguage.JA,
    val targetLanguage: TranslationTargetLanguage = TranslationTargetLanguage.ZH_HANS,
    val translateDialogue: Boolean = true,
    val translateNarration: Boolean = true,
    val translateSoundEffects: Boolean = false,
    val automaticApproval: Boolean = true,
)

@Serializable
data class TranslationPromptRef(
    val revision: String = "chapter-structured-v1",
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
