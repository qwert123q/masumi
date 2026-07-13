package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.masumi.core.detection.DetectionJobRecord
import rs.masumi.core.detection.DetectionReport
import rs.masumi.core.detection.DetectionRunArtifact
import rs.masumi.core.detection.PageDetectionArtifact

class DetectionJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
) {
    fun encodePageArtifact(artifact: PageDetectionArtifact): String = json.encodeToString(artifact)

    fun decodePageArtifact(content: String): PageDetectionArtifact = json.decodeFromString(content)

    fun encodeJob(job: DetectionJobRecord): String = json.encodeToString(job)

    fun decodeJob(content: String): DetectionJobRecord = json.decodeFromString(content)

    fun encodeRun(run: DetectionRunArtifact): String = json.encodeToString(run)

    fun decodeRun(content: String): DetectionRunArtifact = json.decodeFromString(content)

    fun encodeReport(report: DetectionReport): String = json.encodeToString(report)

    fun decodeReport(content: String): DetectionReport = json.decodeFromString(content)
}
