package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.masumi.core.quality.PageQualityArtifact
import rs.masumi.core.quality.QualityJobRecord
import rs.masumi.core.quality.QualityReport
import rs.masumi.core.quality.QualityRunArtifact

class QualityJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
) {
    fun encodePageArtifact(value: PageQualityArtifact): String = json.encodeToString(value)
    fun decodePageArtifact(value: String): PageQualityArtifact = json.decodeFromString(value)
    fun encodeJob(value: QualityJobRecord): String = json.encodeToString(value)
    fun decodeJob(value: String): QualityJobRecord = json.decodeFromString(value)
    fun encodeRun(value: QualityRunArtifact): String = json.encodeToString(value)
    fun decodeRun(value: String): QualityRunArtifact = json.decodeFromString(value)
    fun encodeReport(value: QualityReport): String = json.encodeToString(value)
    fun decodeReport(value: String): QualityReport = json.decodeFromString(value)
}
