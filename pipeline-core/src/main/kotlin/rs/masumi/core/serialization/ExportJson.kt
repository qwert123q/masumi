package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.masumi.core.exporting.ExportJobRecord
import rs.masumi.core.exporting.ExportReport

class ExportJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
) {
    fun encodeJob(value: ExportJobRecord): String = json.encodeToString(value)
    fun decodeJob(value: String): ExportJobRecord = json.decodeFromString(value)
    fun encodeReport(value: ExportReport): String = json.encodeToString(value)
    fun decodeReport(value: String): ExportReport = json.decodeFromString(value)
}
