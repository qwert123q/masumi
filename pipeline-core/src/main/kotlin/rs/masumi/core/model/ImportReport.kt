package rs.masumi.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ImportStatus {
    SUCCEEDED,
    FAILED,
}

@Serializable
enum class ImportErrorCode {
    NO_SUPPORTED_PAGES,
    IMPORT_IO_FAILED,
    PROJECT_ALREADY_EXISTS,
}

@Serializable
data class ImportError(
    val code: ImportErrorCode,
    val message: String,
)

@Serializable
data class ImportReport(
    val schemaVersion: Int = 1,
    val jobId: String,
    val projectId: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val status: ImportStatus,
    val discoveredCount: Int,
    val acceptedCount: Int,
    val importedCount: Int,
    val skippedCount: Int,
    val byteCount: Long,
    val warnings: List<String> = emptyList(),
    val error: ImportError? = null,
)
