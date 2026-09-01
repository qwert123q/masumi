package rs.masumi.core.cleanup

import kotlinx.serialization.Serializable
import rs.masumi.core.detection.PixelBox

const val CLEANUP_SCHEMA_VERSION = 4

@Serializable
data class CleanupMaskModelRef(
    val modelId: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val sha256: String,
    val byteLength: Long,
    val license: String,
    val opset: Int,
    val runtimeRevision: String,
)

@Serializable
data class CleanupNeuralModelRef(
    val modelId: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val sha256: String,
    val byteLength: Long,
    val license: String,
    val opset: Int,
    val runtimeRevision: String,
)

@Serializable
data class CleanupPolicy(
    val revision: String = "comic-text-segmentation-local-residual-budgeted-aot-v33",
    val boxPaddingFraction: Double = 0.08,
    val minimumPaddingPixels: Int = 2,
    val colorDistanceThreshold: Int = 20,
    val dilationRadiusPixels: Int = 2,
    val minimumMaskCoverage: Double = 0.004,
    val maximumMaskCoverage: Double = 0.95,
    val segmentationThreshold: Double = 60.0 / 255.0,
    val segmentationAuditThreshold: Double = 30.0 / 255.0,
    val residualColorDistanceThreshold: Int = 8,
    val maximumResidualRatio: Double = 0.001,
    val maximumResidualPixelCount: Int = 8,
    val residualRetryDilationPixels: Int = 2,
    val maximumNeuralFallbackAttempts: Int = 8,
    val maximumNeuralFallbackMillis: Long = 120_000L,
) {
    init {
        require(boxPaddingFraction in 0.0..0.5)
        require(minimumPaddingPixels in 0..64)
        require(colorDistanceThreshold in 1..441)
        require(dilationRadiusPixels in 0..8)
        require(minimumMaskCoverage in 0.0..1.0)
        require(maximumMaskCoverage in 0.0..1.0)
        require(minimumMaskCoverage < maximumMaskCoverage)
        require(segmentationThreshold in 0.0..1.0)
        require(segmentationAuditThreshold in 0.0..segmentationThreshold)
        require(residualColorDistanceThreshold in 0..441)
        require(maximumResidualRatio in 0.0..1.0)
        require(maximumResidualPixelCount >= 0)
        require(residualRetryDilationPixels in 0..16)
        require(maximumNeuralFallbackAttempts in 0..64)
        require(maximumNeuralFallbackMillis in 0L..600_000L)
    }
}

@Serializable
data class CleanupDependencies(
    val schemaVersion: Int = CLEANUP_SCHEMA_VERSION,
    val translationRunArtifactKey: String,
    val policy: CleanupPolicy = CleanupPolicy(),
    val maskModel: CleanupMaskModelRef? = null,
    val neuralModel: CleanupNeuralModelRef? = null,
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
    RESIDUAL_TEXT,
}

@Serializable
enum class CleanupMaskSource {
    FLAT_COLOR,
    HEURISTIC_GLYPH,
    COMIC_TEXT_SEGMENTATION,
    COMIC_TEXT_SEGMENTATION_RETRY,
    COMIC_TEXT_SEGMENTATION_NEURAL_RETRY,
}

@Serializable
enum class CleanupCompletionMode {
    STRICT,
    BEST_EFFORT_RESIDUAL,
}

@Serializable
enum class NeuralFallbackOutcome {
    NOT_ATTEMPTED,
    NOT_ELIGIBLE,
    BUDGET_SKIPPED,
    SUCCEEDED,
    FAILED,
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
    val maskSource: CleanupMaskSource? = null,
    val auditPixelCount: Int = 0,
    val initialResidualPixelCount: Int = 0,
    val residualRetryPixelCount: Int = 0,
    val residualPixelCount: Int = 0,
    val cleanupAttemptCount: Int = 0,
    val completionMode: CleanupCompletionMode = CleanupCompletionMode.STRICT,
    val neuralFallbackOutcome: NeuralFallbackOutcome = NeuralFallbackOutcome.NOT_ATTEMPTED,
    val neuralFallbackMillis: Long = 0L,
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
