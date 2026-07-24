package rs.masumi.core.cleanup

import kotlinx.serialization.Serializable
import rs.masumi.core.detection.PixelBox

const val CLEANUP_SCHEMA_VERSION = 1

@Serializable
data class CleanupPolicy(
    val revision: String = "adaptive-glyph-mask-v12",
    val boxPaddingFraction: Double = 0.08,
    val minimumPaddingPixels: Int = 2,
    val colorDistanceThreshold: Int = 20,
    val dilationRadiusPixels: Int = 2,
    val minimumMaskCoverage: Double = 0.004,
    val maximumMaskCoverage: Double = 0.95,
) {
    init {
        require(boxPaddingFraction in 0.0..0.5)
        require(minimumPaddingPixels in 0..64)
        require(colorDistanceThreshold in 1..441)
        require(dilationRadiusPixels in 0..8)
        require(minimumMaskCoverage in 0.0..1.0)
        require(maximumMaskCoverage in 0.0..1.0)
        require(minimumMaskCoverage < maximumMaskCoverage)
    }
}

@Serializable
data class CleanupDependencies(
    val schemaVersion: Int = CLEANUP_SCHEMA_VERSION,
    val translationRunArtifactKey: String,
    val policy: CleanupPolicy = CleanupPolicy(),
)

@Serializable
enum class CleanupStrategy { FLAT_LOCAL_FILL, LOCAL_BOUNDARY_INPAINT }

@Serializable
enum class CleanupRegionState { CLEANED, PRESERVED_SOURCE }

@Serializable
enum class CleanupPreserveReason {
    TRANSLATION_PRESERVED,
    OCR_PROTECTED,
    MASK_EMPTY,
    MASK_UNSAFE,
    ENGINE_FAILED,
}

@Serializable
data class CleanupRegionArtifact(
    val translationRegionId: String? = null,
    val ocrRegionId: String,
    val box: PixelBox,
    val strategy: CleanupStrategy? = null,
    val state: CleanupRegionState,
    val preserveReason: CleanupPreserveReason? = null,
    val roiPixelCount: Int = 0,
    val maskPixelCount: Int = 0,
    val changedPixelCount: Int = 0,
)

@Serializable
data class PageCleanupArtifact(
    val schemaVersion: Int = CLEANUP_SCHEMA_VERSION,
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val translationPageArtifactKey: String,
    val pageArtifactKey: String,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val cleanedImageSha256: String,
    val dependencies: CleanupDependencies,
    val regions: List<CleanupRegionArtifact>,
)

@Serializable
enum class CleanupPageState { PENDING, RUNNING, COMMITTED, PRESERVED_SOURCE }

@Serializable
enum class CleanupJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    SUCCEEDED_WITH_PRESERVED_REGIONS,
    CANCELLED,
    FAILED,
}

@Serializable
data class CleanupError(val code: String)

@Serializable
data class CleanupJobPage(
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val translationPageArtifactKey: String,
    val pageArtifactKey: String,
    val state: CleanupPageState = CleanupPageState.PENDING,
    val attemptCount: Int = 0,
    val artifactPath: String? = null,
    val imagePath: String? = null,
    val cleanedRegionCount: Int = 0,
    val preservedRegionCount: Int = 0,
    val error: CleanupError? = null,
)

@Serializable
data class CleanupJobRecord(
    val schemaVersion: Int = CLEANUP_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: CleanupJobStatus = CleanupJobStatus.QUEUED,
    val dependencies: CleanupDependencies,
    val pages: List<CleanupJobPage>,
    val cancelRequested: Boolean = false,
    val error: CleanupError? = null,
)

@Serializable
data class CleanupRunEntry(
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val translationPageArtifactKey: String,
    val pageArtifactKey: String,
    val state: CleanupPageState,
    val artifactPath: String? = null,
    val imagePath: String? = null,
    val error: CleanupError? = null,
)

@Serializable
data class CleanupRunArtifact(
    val schemaVersion: Int = CLEANUP_SCHEMA_VERSION,
    val runArtifactKey: String,
    val projectId: String,
    val createdAtEpochMillis: Long,
    val dependencies: CleanupDependencies,
    val entries: List<CleanupRunEntry>,
)

@Serializable
data class CleanupReport(
    val schemaVersion: Int = CLEANUP_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val status: CleanupJobStatus,
    val totalPageCount: Int,
    val committedPageCount: Int,
    val preservedPageCount: Int,
    val cleanedRegionCount: Int,
    val preservedRegionCount: Int,
    val changedPixelCount: Long,
    val retryCount: Int,
    val error: CleanupError? = null,
    val durationMillis: Long = finishedAtEpochMillis - startedAtEpochMillis,
)

fun CleanupJobStatus.isSuccessful(): Boolean =
    this == CleanupJobStatus.SUCCEEDED || this == CleanupJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS
