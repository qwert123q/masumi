package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.masumi.core.translation.PageTranslationInput
import rs.masumi.core.translation.TranslationBatchWindow
import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationResponseValidation

class TranslationJson(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
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
}
