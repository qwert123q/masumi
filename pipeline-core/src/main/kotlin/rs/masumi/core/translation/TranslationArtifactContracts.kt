package rs.masumi.core.translation

import kotlinx.serialization.Serializable

@Serializable
data class TranslationProviderDependency(
    val protocolRevision: String = "openai-chat-completions-v1",
    val modelId: String,
    val temperature: Double,
    val maximumOutputTokens: Int,
    val requestJsonObjectFormat: Boolean,
)

@Serializable
data class TranslationDependencies(
    val schemaVersion: Int = TRANSLATION_SCHEMA_VERSION,
    val ocrRunArtifactKey: String,
    val policy: TranslationPolicy,
    val prompt: TranslationPromptRef,
    val batching: TranslationBatchingConfig,
    val provider: TranslationProviderDependency,
    val initialGlossarySha256: String,
)

@Serializable
data class TranslationError(
    val code: String,
    val httpStatus: Int? = null,
)

@Serializable
data class TranslationUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
)

@Serializable
enum class TranslationWindowState { PENDING, RUNNING, COMMITTED, PRESERVED_SOURCE }

@Serializable
enum class TranslationPageState { PENDING, RUNNING, COMMITTED }

@Serializable
enum class TranslationJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    SUCCEEDED_WITH_PROTECTED_ITEMS,
    CANCELLED,
    FAILED,
}

@Serializable
data class TranslationWindowArtifact(
    val schemaVersion: Int = TRANSLATION_SCHEMA_VERSION,
    val windowIndex: Int,
    val windowArtifactKey: String,
    val inputGlossarySha256: String,
    val outputGlossarySha256: String,
    val outputGlossary: List<TranslationGlossaryEntry>,
    val items: List<ValidatedTranslationItem>,
    val ignoredResponseIds: List<String>,
    val usage: TranslationUsage? = null,
    val providerModelId: String? = null,
    val attemptCount: Int,
    val durationMillis: Long,
    val error: TranslationError? = null,
)

@Serializable
data class PageTranslationArtifact(
    val schemaVersion: Int = TRANSLATION_SCHEMA_VERSION,
    val pageId: String,
    val pageOrder: Int,
    val ocrPageArtifactKey: String,
    val pageArtifactKey: String,
    val dependencies: TranslationDependencies,
    val items: List<ValidatedTranslationItem>,
    val protectedOcrRegions: List<ProtectedTranslationRegion>,
)

@Serializable
data class TranslationJobWindow(
    val windowIndex: Int,
    val windowArtifactKey: String,
    val translationRegionIds: List<String>,
    val state: TranslationWindowState = TranslationWindowState.PENDING,
    val attemptCount: Int = 0,
    val checkpointPath: String? = null,
    val translatedItemCount: Int = 0,
    val preservedItemCount: Int = 0,
    val usage: TranslationUsage? = null,
    val error: TranslationError? = null,
)

@Serializable
data class TranslationJobPage(
    val pageId: String,
    val pageOrder: Int,
    val ocrPageArtifactKey: String,
    val pageArtifactKey: String,
    val translationRegionIds: List<String>,
    val protectedOcrRegionCount: Int,
    val state: TranslationPageState = TranslationPageState.PENDING,
    val artifactPath: String? = null,
    val error: TranslationError? = null,
)

@Serializable
data class TranslationJobRecord(
    val schemaVersion: Int = TRANSLATION_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: TranslationJobStatus = TranslationJobStatus.QUEUED,
    val dependencies: TranslationDependencies,
    val windows: List<TranslationJobWindow>,
    val pages: List<TranslationJobPage>,
    val cancelRequested: Boolean = false,
    val error: TranslationError? = null,
)

@Serializable
data class TranslationRunEntry(
    val pageId: String,
    val pageOrder: Int,
    val ocrPageArtifactKey: String,
    val pageArtifactKey: String,
    val artifactPath: String,
)

@Serializable
data class TranslationRunArtifact(
    val schemaVersion: Int = TRANSLATION_SCHEMA_VERSION,
    val runArtifactKey: String,
    val projectId: String,
    val createdAtEpochMillis: Long,
    val dependencies: TranslationDependencies,
    val entries: List<TranslationRunEntry>,
    val glossaryPath: String,
)

@Serializable
data class TranslationGlossaryArtifact(
    val schemaVersion: Int = TRANSLATION_SCHEMA_VERSION,
    val sha256: String,
    val entries: List<TranslationGlossaryEntry>,
)

@Serializable
data class TranslationReport(
    val schemaVersion: Int = TRANSLATION_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val status: TranslationJobStatus,
    val totalPageCount: Int,
    val committedPageCount: Int,
    val totalWindowCount: Int,
    val committedWindowCount: Int,
    val translatedItemCount: Int,
    val preservedItemCount: Int,
    val protectedOcrRegionCount: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val retryCount: Int,
    val error: TranslationError? = null,
    val durationMillis: Long = finishedAtEpochMillis - startedAtEpochMillis,
)

fun TranslationJobStatus.isSuccessful(): Boolean =
    this == TranslationJobStatus.SUCCEEDED || this == TranslationJobStatus.SUCCEEDED_WITH_PROTECTED_ITEMS

fun TranslationWindowState.isTerminal(): Boolean =
    this == TranslationWindowState.COMMITTED || this == TranslationWindowState.PRESERVED_SOURCE
