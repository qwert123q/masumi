package rs.masumi.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectManifest(
    val schemaVersion: Int = 1,
    val projectId: String,
    val createdAtEpochMillis: Long,
    val pages: List<PageRecord>,
)

@Serializable
data class PageRecord(
    val order: Int,
    val pageId: String,
    val originalName: String,
    val mediaType: String,
    val byteLength: Long,
    val storedPath: String,
)
