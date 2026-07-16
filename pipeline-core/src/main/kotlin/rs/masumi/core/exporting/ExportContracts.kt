package rs.masumi.core.exporting

import kotlinx.serialization.Serializable

const val EXPORT_SCHEMA_VERSION = 1

@Serializable
data class ExportPolicy(
    val revision: String = "direct-folder-png-v1",
    val minimumPageNumberDigits: Int = 4,
    val imageMediaType: String = "image/png",
) {
    init {
        require(revision.isNotBlank())
        require(minimumPageNumberDigits in 1..12)
        require(imageMediaType == "image/png")
    }
}

@Serializable
data class ExportDependencies(
    val schemaVersion: Int = EXPORT_SCHEMA_VERSION,
    val typesettingRunArtifactKey: String,
    val policy: ExportPolicy = ExportPolicy(),
)

@Serializable
enum class ExportPageSource { FLATTENED, CLEANED_FALLBACK, SOURCE_FALLBACK }

@Serializable
enum class ExportPageState { PENDING, RUNNING, COMMITTED }

@Serializable
enum class ExportJobStatus { QUEUED, RUNNING, SUCCEEDED, CANCELLED, FAILED }

@Serializable
data class ExportError(val code: String)

@Serializable
data class ExportJobPage(
    val pageId: String,
    val pageOrder: Int,
    val sourceSha256: String,
    val typesettingPageArtifactKey: String,
    val outputName: String,
    val source: ExportPageSource,
    val state: ExportPageState = ExportPageState.PENDING,
    val attemptCount: Int = 0,
    val outputSha256: String? = null,
    val byteLength: Long = 0L,
    val reusedExisting: Boolean = false,
    val error: ExportError? = null,
)

@Serializable
data class ExportJobRecord(
    val schemaVersion: Int = EXPORT_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val exportKey: String,
    val destinationUri: String,
    val destinationKey: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: ExportJobStatus = ExportJobStatus.QUEUED,
    val dependencies: ExportDependencies,
    val pages: List<ExportJobPage>,
    val cancelRequested: Boolean = false,
    val error: ExportError? = null,
)

@Serializable
data class ExportReport(
    val schemaVersion: Int = EXPORT_SCHEMA_VERSION,
    val jobId: String,
    val projectId: String,
    val exportKey: String,
    val destinationKey: String,
    val typesettingRunArtifactKey: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val status: ExportJobStatus,
    val totalPageCount: Int,
    val exportedPageCount: Int,
    val flattenedPageCount: Int,
    val cleanedFallbackPageCount: Int,
    val sourceFallbackPageCount: Int,
    val reusedPageCount: Int,
    val totalByteCount: Long,
    val retryCount: Int,
    val error: ExportError? = null,
    val durationMillis: Long = finishedAtEpochMillis - startedAtEpochMillis,
)
