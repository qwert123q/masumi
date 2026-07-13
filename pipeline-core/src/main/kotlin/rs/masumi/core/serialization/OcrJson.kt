package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.masumi.core.ocr.OcrJobRecord
import rs.masumi.core.ocr.OcrReport
import rs.masumi.core.ocr.OcrRegionArtifact
import rs.masumi.core.ocr.OcrRunArtifact
import rs.masumi.core.ocr.PageOcrArtifact
import rs.masumi.core.modelpackage.OcrModelPackageMetadata

class OcrJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
) {
    fun encodePageArtifact(artifact: PageOcrArtifact): String = json.encodeToString(artifact)

    fun decodePageArtifact(content: String): PageOcrArtifact = json.decodeFromString(content)

    fun encodeRegionArtifact(artifact: OcrRegionArtifact): String = json.encodeToString(artifact)

    fun decodeRegionArtifact(content: String): OcrRegionArtifact = json.decodeFromString(content)

    fun encodeJob(job: OcrJobRecord): String = json.encodeToString(job)

    fun decodeJob(content: String): OcrJobRecord = json.decodeFromString(content)

    fun encodeRun(run: OcrRunArtifact): String = json.encodeToString(run)

    fun decodeRun(content: String): OcrRunArtifact = json.decodeFromString(content)

    fun encodeReport(report: OcrReport): String = json.encodeToString(report)

    fun decodeReport(content: String): OcrReport = json.decodeFromString(content)

    fun encodeModelPackageMetadata(metadata: OcrModelPackageMetadata): String =
        json.encodeToString(metadata)

    fun decodeModelPackageMetadata(content: String): OcrModelPackageMetadata =
        json.decodeFromString(content)
}
