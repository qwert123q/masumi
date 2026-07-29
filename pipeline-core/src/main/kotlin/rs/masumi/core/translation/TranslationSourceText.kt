package rs.masumi.core.translation

/**
 * Separates translatable OCR text from standalone punctuation and detector
 * noise. Punctuation that belongs to real dialogue remains attached to a
 * source containing at least one letter or digit.
 */
object TranslationSourceText {
    fun isTranslationCandidate(text: String): Boolean =
        text.any(Char::isLetterOrDigit)
}
