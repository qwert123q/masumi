package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.masumi.core.cleanup.CleanupJobRecord
import rs.masumi.core.cleanup.CleanupReport
import rs.masumi.core.cleanup.CleanupRunArtifact
import rs.masumi.core.cleanup.PageCleanupArtifact
import rs.masumi.core.modelpackage.TextSegmenterModelPackageMetadata

class CleanupJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    },
) {
    fun encodePageArtifact(value: PageCleanupArtifact): String = json.encodeToString(value)
    fun decodePageArtifact(value: String): PageCleanupArtifact = json.decodeFromString(value)
    fun encodeJob(value: CleanupJobRecord): String = json.encodeToString(value)
    fun decodeJob(value: String): CleanupJobRecord = json.decodeFromString(value)
    fun encodeRun(value: CleanupRunArtifact): String = json.encodeToString(value)
    fun decodeRun(value: String): CleanupRunArtifact = json.decodeFromString(value)
    fun encodeReport(value: CleanupReport): String = json.encodeToString(value)
    fun decodeReport(value: String): CleanupReport = json.decodeFromString(value)
    fun encodeTextSegmenterModelPackageMetadata(value: TextSegmenterModelPackageMetadata): String =
        json.encodeToString(value)
    fun decodeTextSegmenterModelPackageMetadata(value: String): TextSegmenterModelPackageMetadata =
        json.decodeFromString(value)
}
