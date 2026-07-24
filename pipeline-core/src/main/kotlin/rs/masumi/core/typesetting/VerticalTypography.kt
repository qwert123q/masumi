package rs.masumi.core.typesetting

import rs.masumi.core.translation.TranslationTextNormalizer

object VerticalTypography {
    fun normalize(text: String): String = buildString {
        TranslationTextNormalizer.normalizeTypography(text).forEach { character ->
            append(VERTICAL_FORMS[character] ?: character)
        }
    }

    private val VERTICAL_FORMS = mapOf(
        '，' to '︐',
        '、' to '︑',
        '。' to '︒',
        '：' to '︓',
        '；' to '︔',
        '！' to '︕',
        '？' to '︖',
        '…' to '︙',
        '—' to '︱',
        '（' to '︵',
        '）' to '︶',
        '【' to '︻',
        '】' to '︼',
        '「' to '﹁',
        '」' to '﹂',
        '『' to '﹃',
        '』' to '﹄',
    )
}
