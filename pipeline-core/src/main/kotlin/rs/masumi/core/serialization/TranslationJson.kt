package rs.masumi.core.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rs.masumi.core.translation.PageTranslationInput
import rs.masumi.core.translation.TranslationModelResponse

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
}
