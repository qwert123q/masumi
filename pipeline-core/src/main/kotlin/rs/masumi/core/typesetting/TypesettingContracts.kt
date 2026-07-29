package rs.masumi.core.typesetting

import kotlinx.serialization.Serializable
import rs.masumi.core.detection.PixelBox

const val TYPESETTING_SCHEMA_VERSION = 1

@Serializable
data class TypesettingPolicy(
    val revision: String = "display-text-scale-v7",
    val fontFamily: String = "sans-serif",
    val fontWeight: Int = 400,
    val minimumFontSizePixels: Double = 10.0,
    val minimumFontSizePageFraction: Double = 0.014,
    val maximumFontSizePageFraction: Double = 0.038,
    val bubbleInsetFraction: Double = 0.18,
    val minimumBubbleInsetFraction: Double = 0.06,
    val freeTextExpansionFraction: Double = 0.08,
    val verticalAspectThreshold: Double = 1.18,
    val lineSpacingEm: Double = 0.10,
    val letterSpacingEm: Double = 0.02,
    val freeTextStrokeEm: Double = 0.09,
    val verticalPunctuationRevision: String = "compact-chinese-ellipsis-v2",
) {
    init {
        require(fontFamily.isNotBlank())
        require(fontWeight in 100..900)
        require(minimumFontSizePixels in 1.0..128.0)
        require(minimumFontSizePageFraction in 0.001..0.1)
        require(maximumFontSizePageFraction in minimumFontSizePageFraction..0.2)
        require(bubbleInsetFraction in 0.0..0.4)
        require(minimumBubbleInsetFraction in 0.0..bubbleInsetFraction)
        require(freeTextExpansionFraction in 0.0..0.5)
        require(verticalAspectThreshold in 0.5..4.0)
        require(lineSpacingEm in 0.0..1.0)
        require(letterSpacingEm in 0.0..0.5)
        require(freeTextStrokeEm in 0.0..0.5)
        require(verticalPunctuationRevision.isNotBlank())
    }
}

@Serializable
data class TypesettingDependencies(
    val schemaVersion: Int = TYPESETTING_SCHEMA_VERSION,
    val cleanupRunArtifactKey: String,
    val policy: TypesettingPolicy = TypesettingPolicy(),
)

@Serializable
enum class TypesettingDirection { HORIZONTAL_LTR, VERTICAL_RTL }

@Serializable
enum class TypesettingStyle { BUBBLE, FREE_TEXT }

@Serializable
enum class TypesettingRegionState { TYPESET, PRESERVED_CLEANED_PAGE }

@Serializable
enum class TypesettingPreserveReason {
    TRANSLATION_PRESERVED,
    OCR_PROTECTED,
    CLEANUP_NOT_CLEANED,
    BLANK_TEXT,
    GEOMETRY_INVALID,
    TEXT_DOES_NOT_FIT,
    RENDER_FAILED,
}

@Serializable
data class TypesettingRegionArtifact(
    val translationRegionId: String? = null,
    val ocrRegionId: String,
    val targetBox: PixelBox,
    val layoutBox: PixelBox? = null,
    val style: TypesettingStyle? = null,
    val direction: TypesettingDirection? = null,
    val fontSizePx: Double? = null,
    val lineOrColumnCount: Int = 0,
    val changedPixelCount: Int = 0,
    val state: TypesettingRegionState,
    val preserveReason: TypesettingPreserveReason? = null,
)

@Serializable
data class PageTypesettingArtifact(
    val schemaVersion: Int = TYPESETTING_SCHEMA_VERSION,
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val cleanupPageArtifactKey: String,
    val pageArtifactKey: String,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val renderedImageSha256: String,
    val reusedFromPageArtifactKey: String? = null,
    val dependencies: TypesettingDependencies,
    val regions: List<TypesettingRegionArtifact>,
)

@Serializable
enum class TypesettingPageState { PENDING, RUNNING, COMMITTED, PRESERVED_CLEANED_PAGE }

@Serializable
enum class TypesettingJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    SUCCEEDED_WITH_PRESERVED_REGIONS,
    CANCELLED,
    FAILED,
}

@Serializable
data class TypesettingError(val code: String)

@Serializable
data class TypesettingJobPage(
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val cleanupPageArtifactKey: String,
    val pageArtifactKey: String,
    val state: TypesettingPageState = TypesettingPageState.PENDING,
    val attemptCount: Int = 0,
    val artifactPath: String? = null,
    val imagePath: String? = null,
    val typesetRegionCount: Int = 0,
    val preservedRegionCount: Int = 0,
    val error: TypesettingError? = null,
)

@Serializable
data class TypesettingJobRecord(
    val schemaVersion: Int = TYPESETTING_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: TypesettingJobStatus = TypesettingJobStatus.QUEUED,
    val dependencies: TypesettingDependencies,
    val pages: List<TypesettingJobPage>,
    val cancelRequested: Boolean = false,
    val error: TypesettingError? = null,
)

@Serializable
data class TypesettingRunEntry(
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val cleanupPageArtifactKey: String,
    val pageArtifactKey: String,
    val state: TypesettingPageState,
    val artifactPath: String? = null,
    val imagePath: String? = null,
    val error: TypesettingError? = null,
)

@Serializable
data class TypesettingRunArtifact(
    val schemaVersion: Int = TYPESETTING_SCHEMA_VERSION,
    val runArtifactKey: String,
    val projectId: String,
    val createdAtEpochMillis: Long,
    val dependencies: TypesettingDependencies,
    val entries: List<TypesettingRunEntry>,
)

@Serializable
data class TypesettingReport(
    val schemaVersion: Int = TYPESETTING_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val status: TypesettingJobStatus,
    val totalPageCount: Int,
    val committedPageCount: Int,
    val preservedPageCount: Int,
    val typesetRegionCount: Int,
    val preservedRegionCount: Int,
    val changedPixelCount: Long,
    val retryCount: Int,
    val reusedPageCount: Int = 0,
    val error: TypesettingError? = null,
    val durationMillis: Long = finishedAtEpochMillis - startedAtEpochMillis,
)

fun TypesettingJobStatus.isSuccessful(): Boolean =
    this == TypesettingJobStatus.SUCCEEDED || this == TypesettingJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS
