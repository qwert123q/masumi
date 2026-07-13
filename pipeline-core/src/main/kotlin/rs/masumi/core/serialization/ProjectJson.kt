package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.masumi.core.model.ImportReport
import rs.masumi.core.model.ProjectManifest

class ProjectJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
) {
    fun encodeManifest(manifest: ProjectManifest): String = json.encodeToString(manifest)

    fun decodeManifest(content: String): ProjectManifest = json.decodeFromString(content)

    fun encodeReport(report: ImportReport): String = json.encodeToString(report)

    fun decodeReport(content: String): ImportReport = json.decodeFromString(content)
}
