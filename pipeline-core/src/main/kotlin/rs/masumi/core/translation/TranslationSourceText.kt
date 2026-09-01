package rs.masumi.core.translation

import java.text.Normalizer

/**
 * Separates translatable OCR text from standalone punctuation and detector
 * noise. Punctuation that belongs to real dialogue remains attached to a
 * source containing at least one letter or digit.
 */
object TranslationSourceText {
    fun isTranslationCandidate(text: String): Boolean =
        text.any(Char::isLetterOrDigit)

    fun normalizeForOutputValidation(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFC)

    fun containsJapanesePhoneticScript(text: String): Boolean = text.codePoints().anyMatch { codePoint ->
        Character.UnicodeScript.of(codePoint) in JAPANESE_PHONETIC_SCRIPTS
    }

    fun isPureNumberOrSymbol(text: String): Boolean {
        val normalized = normalizeForOutputValidation(text)
        return normalized.isNotEmpty() && normalized.codePoints().allMatch(::isNumberOrSymbol)
    }

    private fun isNumberOrSymbol(codePoint: Int): Boolean {
        if (Character.isWhitespace(codePoint)) return true
        return when (Character.getType(codePoint)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            -> true
            else -> false
        }
    }

    private val JAPANESE_PHONETIC_SCRIPTS = setOf(
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
    )
}
