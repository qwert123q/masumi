package rs.masumi.core.quality

import kotlinx.serialization.Serializable

const val QUALITY_SCHEMA_VERSION = 1

@Serializable
data class QualityPolicy(
    val revision: String = "deterministic-visual-gate-v1",
    val maximumChangedPixelsOutsideLayout: Int = 32,
) {
    init {
        require(revision.isNotBlank())
        require(maximumChangedPixelsOutsideLayout in 0..10_000)
    }
}

@Serializable
data class QualityDependencies(
    val schemaVersion: Int = QUALITY_SCHEMA_VERSION,
    val typesettingRunArtifactKey: String,
    val policy: QualityPolicy = QualityPolicy(),
)

@Serializable
enum class QualitySeverity { WARNING, BLOCKING }

@Serializable
enum class QualityIssueCode {
    PAGE_PRESERVED_CLEANED,
    REGION_PRESERVED,
    RENDERED_DIMENSION_MISMATCH,
    LAYOUT_OUT_OF_BOUNDS,
    TYPESET_PIXELS_MISSING,
    CHANGED_PIXELS_OUTSIDE_LAYOUT,
}

@Serializable
data class QualityIssue(
    val code: QualityIssueCode,
    val severity: QualitySeverity,
    val ocrRegionId: String? = null,
    val observedCount: Int? = null,
)

@Serializable
enum class QualityPageVerdict { PASS, PASS_WITH_WARNINGS, BLOCKED }

@Serializable
data class PageQualityArtifact(
    val schemaVersion: Int = QUALITY_SCHEMA_VERSION,
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val typesettingPageArtifactKey: String,
    val pageArtifactKey: String,
    val renderedImageSha256: String? = null,
    val dependencies: QualityDependencies,
    val actualChangedPixelCount: Int = 0,
    val changedPixelsOutsideLayout: Int = 0,
    val verifiedTypesetRegionCount: Int = 0,
    val preservedRegionCount: Int = 0,
    val issues: List<QualityIssue> = emptyList(),
    val verdict: QualityPageVerdict,
)

@Serializable
enum class QualityPageState { PENDING, RUNNING, COMMITTED }

@Serializable
enum class QualityJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    SUCCEEDED_WITH_WARNINGS,
    BLOCKED,
    CANCELLED,
    FAILED,
}

@Serializable
data class QualityError(val code: String)

@Serializable
data class QualityJobPage(
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val typesettingPageArtifactKey: String,
    val pageArtifactKey: String,
    val state: QualityPageState = QualityPageState.PENDING,
    val attemptCount: Int = 0,
    val artifactPath: String? = null,
    val verdict: QualityPageVerdict? = null,
    val warningCount: Int = 0,
    val blockingCount: Int = 0,
    val error: QualityError? = null,
)

@Serializable
data class QualityJobRecord(
    val schemaVersion: Int = QUALITY_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: QualityJobStatus = QualityJobStatus.QUEUED,
    val dependencies: QualityDependencies,
    val pages: List<QualityJobPage>,
    val cancelRequested: Boolean = false,
    val error: QualityError? = null,
)

@Serializable
data class QualityRunEntry(
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val typesettingPageArtifactKey: String,
    val pageArtifactKey: String,
    val verdict: QualityPageVerdict,
    val artifactPath: String,
)

@Serializable
data class QualityRunArtifact(
    val schemaVersion: Int = QUALITY_SCHEMA_VERSION,
    val runArtifactKey: String,
    val projectId: String,
    val createdAtEpochMillis: Long,
    val dependencies: QualityDependencies,
    val entries: List<QualityRunEntry>,
)

@Serializable
data class QualityReport(
    val schemaVersion: Int = QUALITY_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val typesettingRunArtifactKey: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val status: QualityJobStatus,
    val totalPageCount: Int,
    val passedPageCount: Int,
    val warningPageCount: Int,
    val blockedPageCount: Int,
    val warningCount: Int,
    val blockingCount: Int,
    val verifiedTypesetRegionCount: Int,
    val preservedRegionCount: Int,
    val retryCount: Int,
    val error: QualityError? = null,
    val durationMillis: Long = finishedAtEpochMillis - startedAtEpochMillis,
)

fun QualityJobStatus.isPublished(): Boolean = this in setOf(
    QualityJobStatus.SUCCEEDED,
    QualityJobStatus.SUCCEEDED_WITH_WARNINGS,
    QualityJobStatus.BLOCKED,
)

fun QualityJobStatus.allowsExport(): Boolean =
    this == QualityJobStatus.SUCCEEDED || this == QualityJobStatus.SUCCEEDED_WITH_WARNINGS
