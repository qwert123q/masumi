package rs.masumi.core.ocr

import kotlinx.serialization.Serializable
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.detection.VisibleOrientation

const val OCR_SCHEMA_VERSION: Int = 2

@Serializable
enum class OcrSemanticStatus {
    REQUIRED_TEXT,
    UNRESOLVED_FREE_TEXT,
}

@Serializable
enum class OcrProtectionPolicy {
    NONE,
    PRESERVE_UNTIL_CLASSIFIED,
}

@Serializable
enum class OcrRegionState {
    PENDING,
    RUNNING,
    RECOGNIZED,
    NEEDS_FALLBACK,
    NO_TEXT_CONFIRMED,
    PRESERVED_SOURCE,
}

@Serializable
enum class OcrPageState {
    PENDING,
    RUNNING,
    COMMITTED,
    PRESERVED_SOURCE,
}

@Serializable
enum class OcrJobStatus {
    QUEUED,
    DOWNLOADING_MODEL,
    LOADING_MODEL,
    RUNNING,
    SUCCEEDED,
    SUCCEEDED_WITH_PRESERVED_REGIONS,
    CANCELLED,
    FAILED,
}

@Serializable
enum class OcrCropStrategy {
    PADDED_TEXT,
    TIGHT_TEXT,
    CONTEXT_TEXT,
    HIGH_DETAIL_CONTEXT,
}

@Serializable
enum class OcrVisualDetailProfile {
    STANDARD,
    HIGH_DETAIL,
}

@Serializable
enum class OcrExecutionBackend {
    VULKAN,
    CPU,
}

@Serializable
data class OcrError(
    val code: String,
    val message: String,
)

@Serializable
data class OcrModelFileRef(
    val fileName: String,
    val byteLength: Long,
)

@Serializable
data class OcrModelPackageRef(
    val packageId: String,
    val repository: String,
    val revision: String,
    val model: OcrModelFileRef,
    val projector: OcrModelFileRef,
    val license: String,
)

@Serializable
data class OcrRuntimeRef(
    val llamaTag: String,
    val llamaCommit: String,
    val abi: String,
    val backend: String,
    val buildContract: String,
)

@Serializable
data class OcrConsolidationConfig(
    val revision: String = "ocr-consolidation-v2",
    val sameClassIouThreshold: Double = 0.75,
    val sameClassContainmentThreshold: Double = 0.90,
    val crossClassSmallerCoverageThreshold: Double = 0.70,
    val bubbleAssociationCoverageThreshold: Double = 0.50,
    val lowConfidenceFreeTextThreshold: Double = 0.50,
    val minimumFreeTextWidthFraction: Double = 0.05,
    val pageEdgeMarginFraction: Double = 0.01,
)

@Serializable
data class OcrReadingOrderConfig(
    val revision: String = "ja-rtl-bands-v1",
    val verticalOverlapThreshold: Double = 0.35,
)

@Serializable
data class OcrCropConfig(
    val revision: String = "three-crops-mobile-v4",
    val paddedTextFraction: Double = 0.12,
    val tightTextFraction: Double = 0.04,
    val contextTextFraction: Double = 0.24,
    val minimumPaddingPixels: Int = 4,
)

@Serializable
data class OcrHighDetailRetryConfig(
    val revision: String = "one-context-high-detail-v1",
    val maximumVisualTokens: Int = 192,
    val maximumSourcePixels: Int = 4_000_000,
) {
    init {
        require(maximumVisualTokens in 129..256) {
            "maximumVisualTokens must be between 129 and 256"
        }
        require(maximumSourcePixels in 1..4_000_000) {
            "maximumSourcePixels must be between 1 and 4000000"
        }
    }
}

@Serializable
data class OcrNormalizationConfig(
    val revision: String = "trim-fence-nfc-v1",
)

@Serializable
data class OcrQualityConfig(
    val revision: String = "paddle-vl-quality-v3-high-detail-terminal",
    val agreementSimilarityThreshold: Double = 0.90,
    val primaryTokenProbabilityThreshold: Double = 0.55,
    val emptyConfirmationAttemptCount: Int = 2,
    val lowConfidenceFreeTextThreshold: Double = 0.50,
)

@Serializable
data class OcrGenerationConfig(
    val prompt: String = "OCR:",
    val maximumGeneratedTokens: Int = 256,
    val temperature: Double = 0.0,
    val repetitionPenalty: Double = 1.2,
)

@Serializable
data class OcrDependencies(
    val schemaVersion: Int = OCR_SCHEMA_VERSION,
    val modelPackage: OcrModelPackageRef,
    val runtime: OcrRuntimeRef,
    val consolidation: OcrConsolidationConfig = OcrConsolidationConfig(),
    val readingOrder: OcrReadingOrderConfig = OcrReadingOrderConfig(),
    val crop: OcrCropConfig = OcrCropConfig(),
    val highDetailRetry: OcrHighDetailRetryConfig = OcrHighDetailRetryConfig(),
    val normalization: OcrNormalizationConfig = OcrNormalizationConfig(),
    val quality: OcrQualityConfig = OcrQualityConfig(),
    val generation: OcrGenerationConfig = OcrGenerationConfig(),
)

@Serializable
data class OcrCandidate(
    val ocrRegionId: String,
    val sourceRegionIds: List<String>,
    val representativeSourceRegionId: String,
    val sourceClass: DetectorClass,
    val detectorConfidence: Double,
    val box: PixelBox,
    val semanticStatus: OcrSemanticStatus,
    val protectionPolicy: OcrProtectionPolicy,
    val associatedBubbleRegionId: String? = null,
    val associatedBubbleBox: PixelBox? = null,
    val readingOrderRank: Int,
)

@Serializable
data class OcrCropDescriptor(
    val strategy: OcrCropStrategy,
    val box: PixelBox,
    val visualDetailProfile: OcrVisualDetailProfile = OcrVisualDetailProfile.STANDARD,
    val maximumSourcePixels: Int? = null,
)

@Serializable
data class OcrAttemptArtifact(
    val executionBackend: OcrExecutionBackend,
    val strategy: OcrCropStrategy,
    val cropBox: PixelBox,
    val rawText: String,
    val normalizedText: String,
    val tokenIds: List<Int>,
    val tokenProbabilities: List<Double>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val processedWidth: Int,
    val processedHeight: Int,
    val visualTokenCount: Int,
    val generatedTokenCount: Int,
    val reachedEos: Boolean,
    val truncated: Boolean,
    val repetitionStopped: Boolean,
    val invalidUtf8: Boolean,
    val promptEvaluationMillis: Long,
    val generationMillis: Long,
    val error: OcrError? = null,
)

@Serializable
data class OcrQualityRecord(
    val geometricMeanTokenProbability: Double?,
    val detectorConfidence: Double,
    val maximumAttemptSimilarity: Double?,
    val scriptCounts: Map<String, Int>,
    val emptyOutput: Boolean,
    val repeatedUnit: Boolean,
    val forcedTruncation: Boolean,
    val invalidUtf8: Boolean,
    val abnormalLength: Boolean,
    val aggregateScore: Double?,
    val decisionReason: String,
)

@Serializable
data class OcrRegionArtifact(
    val candidate: OcrCandidate,
    val attempts: List<OcrAttemptArtifact>,
    val selectedAttemptIndex: Int?,
    val quality: OcrQualityRecord?,
    val state: OcrRegionState,
    val error: OcrError? = null,
)

@Serializable
data class PageOcrArtifact(
    val schemaVersion: Int = OCR_SCHEMA_VERSION,
    val pageId: String,
    val detectionPageArtifactKey: String,
    val pageArtifactKey: String,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val orientation: VisibleOrientation,
    val dependencies: OcrDependencies,
    val regions: List<OcrRegionArtifact>,
)

@Serializable
data class OcrRegionCheckpoint(
    val ocrRegionId: String,
    val state: OcrRegionState = OcrRegionState.PENDING,
    val attemptCount: Int = 0,
    val checkpointPath: String? = null,
    val error: OcrError? = null,
)

@Serializable
data class OcrJobPage(
    val order: Int,
    val pageId: String,
    val detectionPageArtifactKey: String,
    val pageArtifactKey: String,
    val state: OcrPageState = OcrPageState.PENDING,
    val regions: List<OcrRegionCheckpoint>,
    val artifactPath: String? = null,
    val previewPath: String? = null,
    val error: OcrError? = null,
)

@Serializable
data class OcrJobRecord(
    val schemaVersion: Int = OCR_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val detectionRunArtifactKey: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: OcrJobStatus = OcrJobStatus.QUEUED,
    val dependencies: OcrDependencies,
    val pages: List<OcrJobPage>,
    val cancelRequested: Boolean = false,
    val error: OcrError? = null,
)

@Serializable
data class OcrRunEntry(
    val order: Int,
    val pageId: String,
    val detectionPageArtifactKey: String,
    val pageArtifactKey: String,
    val state: OcrPageState,
    val artifactPath: String? = null,
    val previewPath: String? = null,
    val error: OcrError? = null,
)

@Serializable
data class OcrRunArtifact(
    val schemaVersion: Int = OCR_SCHEMA_VERSION,
    val runArtifactKey: String,
    val projectId: String,
    val detectionRunArtifactKey: String,
    val createdAtEpochMillis: Long,
    val dependencies: OcrDependencies,
    val entries: List<OcrRunEntry>,
)

@Serializable
data class OcrReport(
    val schemaVersion: Int = OCR_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val runArtifactKey: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val status: OcrJobStatus,
    val totalPageCount: Int,
    val committedPageCount: Int,
    val totalRegionCount: Int,
    val recognizedRegionCount: Int,
    val needsFallbackRegionCount: Int,
    val noTextRegionCount: Int,
    val preservedRegionCount: Int,
    val retryCount: Int,
    val stateCounts: Map<String, Int> = emptyMap(),
    val preservedRegionIds: List<String> = emptyList(),
    val error: OcrError? = null,
    val durationMillis: Long = finishedAtEpochMillis - startedAtEpochMillis,
)

fun OcrJobStatus.isSuccessful(): Boolean =
    this == OcrJobStatus.SUCCEEDED || this == OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS

fun OcrRegionState.isTerminal(): Boolean = when (this) {
    OcrRegionState.RECOGNIZED,
    OcrRegionState.NEEDS_FALLBACK,
    OcrRegionState.NO_TEXT_CONFIRMED,
    OcrRegionState.PRESERVED_SOURCE,
    -> true
    OcrRegionState.PENDING,
    OcrRegionState.RUNNING,
    -> false
}
