package rs.masumi.core.detection

import kotlinx.serialization.Serializable

const val DETECTION_SCHEMA_VERSION: Int = 1

@Serializable
enum class DetectorClass {
    BUBBLE,
    TEXT_IN_BUBBLE,
    TEXT_FREE,
}

@Serializable
enum class RawQueryValidation {
    ACCEPTED,
    BELOW_THRESHOLD,
    UNKNOWN_CLASS,
    NON_FINITE_SCORE,
    NON_FINITE_BOX,
    EMPTY_AFTER_CLIP,
}

@Serializable
enum class RegionSemanticStatus {
    BUBBLE_CANDIDATE,
    TEXT_IN_BUBBLE,
    UNRESOLVED_FREE_TEXT,
}

@Serializable
enum class RegionProtectionPolicy {
    NONE,
    PRESERVE_UNTIL_CLASSIFIED,
}

@Serializable
enum class VisibleOrientation {
    NORMAL,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

@Serializable
data class PixelBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

@Serializable
data class DetectorModelRef(
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
data class DetectionPreprocessingConfig(
    val inputWidth: Int = 640,
    val inputHeight: Int = 640,
    val colorOrder: String = "RGB",
    val interpolation: String = "BILINEAR",
    val rescaleDivisor: Double = 255.0,
    val normalize: Boolean = false,
    val pad: Boolean = false,
)

@Serializable
data class DetectionThresholdConfig(
    val bubble: Double = 0.25,
    val textInBubble: Double = 0.25,
    val textFree: Double = 0.25,
)

@Serializable
data class RawQueryRecord(
    val queryIndex: Int,
    val label: Long,
    val score: Double?,
    val rawBox: List<Double?>,
    val nonFiniteCoordinateIndexes: List<Int> = emptyList(),
    val validation: RawQueryValidation,
)

@Serializable
data class DetectedRegion(
    val regionId: String,
    val queryIndex: Int,
    val detectorClass: DetectorClass,
    val confidence: Double,
    val box: PixelBox,
    val semanticStatus: RegionSemanticStatus,
    val protectionPolicy: RegionProtectionPolicy,
)

@Serializable
data class PageDetectionArtifact(
    val schemaVersion: Int = DETECTION_SCHEMA_VERSION,
    val pageId: String,
    val sourceSha256: String,
    val pageArtifactKey: String,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val orientation: VisibleOrientation,
    val model: DetectorModelRef,
    val preprocessing: DetectionPreprocessingConfig,
    val thresholds: DetectionThresholdConfig,
    val rawQueries: List<RawQueryRecord>,
    val bubbleCandidates: List<DetectedRegion>,
    val textRegions: List<DetectedRegion>,
)

@Serializable
enum class DetectionPageState {
    PENDING,
    RUNNING,
    COMMITTED,
    PRESERVED_SOURCE,
}

@Serializable
enum class DetectionJobStatus {
    QUEUED,
    DOWNLOADING_MODEL,
    RUNNING,
    SUCCEEDED,
    SUCCEEDED_WITH_PRESERVED_PAGES,
    CANCELLED,
    FAILED,
}

@Serializable
data class DetectionError(
    val code: String,
    val message: String,
)

@Serializable
data class DetectionJobPage(
    val order: Int,
    val pageId: String,
    val pageArtifactKey: String,
    val state: DetectionPageState = DetectionPageState.PENDING,
    val attemptCount: Int = 0,
    val regionsPath: String? = null,
    val previewPath: String? = null,
    val error: DetectionError? = null,
)

@Serializable
data class DetectionJobRecord(
    val schemaVersion: Int = DETECTION_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: DetectionJobStatus = DetectionJobStatus.QUEUED,
    val model: DetectorModelRef,
    val preprocessing: DetectionPreprocessingConfig,
    val thresholds: DetectionThresholdConfig,
    val pages: List<DetectionJobPage>,
    val cancelRequested: Boolean = false,
    val error: DetectionError? = null,
)

@Serializable
data class DetectionRunEntry(
    val order: Int,
    val pageId: String,
    val pageArtifactKey: String,
    val state: DetectionPageState,
    val regionsPath: String? = null,
    val previewPath: String? = null,
    val error: DetectionError? = null,
)

@Serializable
data class DetectionRunArtifact(
    val schemaVersion: Int = DETECTION_SCHEMA_VERSION,
    val runArtifactKey: String,
    val projectId: String,
    val createdAtEpochMillis: Long,
    val model: DetectorModelRef,
    val preprocessing: DetectionPreprocessingConfig,
    val thresholds: DetectionThresholdConfig,
    val entries: List<DetectionRunEntry>,
)

@Serializable
data class DetectionReport(
    val schemaVersion: Int = DETECTION_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val status: DetectionJobStatus,
    val totalPageCount: Int,
    val committedPageCount: Int,
    val preservedPageCount: Int,
    val retryCount: Int,
    val classCounts: Map<String, Int> = emptyMap(),
    val confidenceBuckets: Map<String, Int> = emptyMap(),
    val preservedOrders: List<Int> = emptyList(),
    val error: DetectionError? = null,
    val durationMillis: Long = finishedAtEpochMillis - startedAtEpochMillis,
)
