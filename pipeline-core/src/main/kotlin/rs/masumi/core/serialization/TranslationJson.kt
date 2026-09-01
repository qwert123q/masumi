package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rs.masumi.core.translation.PageTranslationInput
import rs.masumi.core.translation.TranslationBatchWindow
import rs.masumi.core.translation.TranslationGlossaryArtifact
import rs.masumi.core.translation.TranslationJobRecord
import rs.masumi.core.translation.TranslationGlossaryEntry
import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationReport
import rs.masumi.core.translation.TranslationResponseValidation
import rs.masumi.core.translation.TranslationRunArtifact
import rs.masumi.core.translation.TranslationWindowArtifact
import rs.masumi.core.translation.TranslationRecoveryCompatibility
import rs.masumi.core.translation.PageTranslationArtifact

class TranslationJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    },
) {
    fun encodePageInput(input: PageTranslationInput): String = json.encodeToString(input)

    fun decodePageInput(content: String): PageTranslationInput = json.decodeFromString(content)

    fun encodeModelResponse(response: TranslationModelResponse): String = json.encodeToString(response)

    fun decodeModelResponse(content: String): TranslationModelResponse = json.decodeFromString(content)

    fun encodeBatchWindow(window: TranslationBatchWindow): String = json.encodeToString(window)

    fun decodeBatchWindow(content: String): TranslationBatchWindow = json.decodeFromString(content)

    fun encodeResponseValidation(validation: TranslationResponseValidation): String =
        json.encodeToString(validation)

    fun decodeResponseValidation(content: String): TranslationResponseValidation =
        json.decodeFromString(content)

    fun encodeWindowArtifact(artifact: TranslationWindowArtifact): String = json.encodeToString(artifact)
    fun decodeWindowArtifact(content: String): TranslationWindowArtifact = json.decodeFromString(content)
    fun windowCheckpointNeedsLegacyInputGlossary(content: String): Boolean =
        "inputGlossary" !in json.parseToJsonElement(content).jsonObject

    fun decodeWindowCheckpoint(
        content: String,
        legacyInputGlossary: List<TranslationGlossaryEntry>,
        legacyContextTranslationRegionIds: List<String>,
        legacyTranslationRegionIds: List<String>,
    ): TranslationWindowArtifact {
        val fields = json.parseToJsonElement(content).jsonObject
        val decoded = json.decodeFromString<TranslationWindowArtifact>(content)
        return decoded.copy(
            inputGlossary = if ("inputGlossary" in fields) {
                decoded.inputGlossary
            } else {
                legacyInputGlossary
            },
            contextTranslationRegionIds = if ("contextTranslationRegionIds" in fields) {
                decoded.contextTranslationRegionIds
            } else {
                legacyContextTranslationRegionIds
            },
            translationRegionIds = if ("translationRegionIds" in fields) {
                decoded.translationRegionIds
            } else {
                legacyTranslationRegionIds
            },
        )
    }
    fun encodePageArtifact(artifact: PageTranslationArtifact): String = json.encodeToString(artifact)
    fun decodePageArtifact(content: String): PageTranslationArtifact = json.decodeFromString(content)
    fun encodeJob(job: TranslationJobRecord): String = json.encodeToString(job)
    fun decodeJob(content: String): TranslationJobRecord {
        val decoded = json.decodeFromString<TranslationJobRecord>(content)
        if (decoded.dependencies.provider.reference.endpoint.isNotBlank()) return decoded
        val legacyHost = runCatching {
            json.parseToJsonElement(content)
                .jsonObject.getValue("dependencies")
                .jsonObject.getValue("provider")
                .jsonObject.getValue("reference")
                .jsonObject["endpointHost"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }.getOrNull() ?: return decoded
        val reference = decoded.dependencies.provider.reference.copy(
            endpoint = TranslationRecoveryCompatibility.LEGACY_HOST_PREFIX + legacyHost,
        )
        return decoded.copy(
            dependencies = decoded.dependencies.copy(
                provider = decoded.dependencies.provider.copy(reference = reference),
            ),
        )
    }
    fun encodeRun(run: TranslationRunArtifact): String = json.encodeToString(run)
    fun decodeRun(content: String): TranslationRunArtifact = json.decodeFromString(content)
    fun encodeGlossary(glossary: TranslationGlossaryArtifact): String = json.encodeToString(glossary)
    fun decodeGlossary(content: String): TranslationGlossaryArtifact = json.decodeFromString(content)
    fun encodeReport(report: TranslationReport): String = json.encodeToString(report)
    fun decodeReport(content: String): TranslationReport = json.decodeFromString(content)
}
