package rs.masumi.core.translation

object TranslationTextNormalizer {
    fun normalize(
        sourceText: String,
        translatedText: String,
        glossary: List<TranslationGlossaryEntry>,
    ): String {
        val typography = normalizeTypography(translatedText.trim())
        if (!sourceText.containsEllipsis()) return typography

        val sourceBeforeEllipsis = sourceText.substringBeforeEllipsis().trimEnd()
        val translatedBeforeEllipsis = typography.substringBeforeEllipsis().trimEnd()
        val repair = glossary.asSequence()
            .mapNotNull { entry ->
                val sourcePrefixLength = longestSuffixPrefixLength(sourceBeforeEllipsis, entry.source)
                val translationPrefixLength = longestSuffixPrefixLength(translatedBeforeEllipsis, entry.translation)
                if (
                    sourcePrefixLength < MINIMUM_SOURCE_REPAIR_PREFIX ||
                    sourcePrefixLength >= entry.source.length ||
                    translationPrefixLength < MINIMUM_TRANSLATION_REPAIR_PREFIX ||
                    translationPrefixLength >= entry.translation.length
                ) {
                    null
                } else {
                    RepairCandidate(entry.translation, translationPrefixLength, sourcePrefixLength)
                }
            }
            .maxWithOrNull(
                compareBy<RepairCandidate> { it.sourcePrefixLength }
                    .thenBy { it.translationPrefixLength },
            )
            ?: return typography

        val prefixEnd = typography.indexOfEllipsis().coerceAtLeast(0)
        val prefixStart = prefixEnd - repair.translationPrefixLength
        if (prefixStart < 0) return typography
        return buildString(typography.length + repair.replacement.length) {
            append(typography, 0, prefixStart)
            append(repair.replacement)
            append(typography, prefixEnd, typography.length)
        }
    }

    fun normalizeTypography(text: String): String = text
        .replace(ELLIPSIS_PATTERN, "……")
        .replace(SPACE_BEFORE_PUNCTUATION, "$1")
        .replace(SPACE_AFTER_OPENING_PUNCTUATION, "$1")

    private fun longestSuffixPrefixLength(text: String, candidate: String): Int {
        val maximum = minOf(text.length, candidate.length)
        return (maximum downTo MINIMUM_SOURCE_REPAIR_PREFIX)
            .firstOrNull { length -> text.endsWith(candidate.take(length)) }
            ?: 0
    }

    private fun String.containsEllipsis(): Boolean = ELLIPSIS_PATTERN.containsMatchIn(this)

    private fun String.substringBeforeEllipsis(): String {
        val match = ELLIPSIS_PATTERN.find(this) ?: return this
        return substring(0, match.range.first)
    }

    private fun String.indexOfEllipsis(): Int =
        ELLIPSIS_PATTERN.find(this)?.range?.first ?: -1

    private data class RepairCandidate(
        val replacement: String,
        val translationPrefixLength: Int,
        val sourcePrefixLength: Int,
    )

    private const val MINIMUM_SOURCE_REPAIR_PREFIX = 2
    private const val MINIMUM_TRANSLATION_REPAIR_PREFIX = 3
    private val ELLIPSIS_PATTERN = Regex(
        "(?:[.．。](?:\\s*[.．。]){2,}|…+|⋯+|・(?:\\s*・){2,})",
    )
    private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([，。！？；：、])")
    private val SPACE_AFTER_OPENING_PUNCTUATION = Regex("([（【「『《])\\s+")
}
