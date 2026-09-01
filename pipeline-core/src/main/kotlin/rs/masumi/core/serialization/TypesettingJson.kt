package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.masumi.core.typesetting.PageTypesettingArtifact
import rs.masumi.core.typesetting.TypesettingJobRecord
import rs.masumi.core.typesetting.TypesettingReport
import rs.masumi.core.typesetting.TypesettingRunArtifact

class TypesettingJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    },
) {
    fun encodePageArtifact(value: PageTypesettingArtifact): String = json.encodeToString(value)
    fun decodePageArtifact(value: String): PageTypesettingArtifact = json.decodeFromString(value)
    fun encodeJob(value: TypesettingJobRecord): String = json.encodeToString(value)
    fun decodeJob(value: String): TypesettingJobRecord = json.decodeFromString(value)
    fun encodeRun(value: TypesettingRunArtifact): String = json.encodeToString(value)
    fun decodeRun(value: String): TypesettingRunArtifact = json.decodeFromString(value)
    fun encodeReport(value: TypesettingReport): String = json.encodeToString(value)
    fun decodeReport(value: String): TypesettingReport = json.decodeFromString(value)
}
