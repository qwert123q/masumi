package rs.masumi.core.importer

import java.util.Locale

enum class PageMediaType(
    val mimeType: String,
    val extension: String,
) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    ;

    companion object {
        fun detect(displayName: String, declaredMediaType: String?): PageMediaType? {
            val normalizedMediaType = declaredMediaType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)

            when (normalizedMediaType) {
                "image/jpeg", "image/jpg" -> return JPEG
                "image/png", "image/x-png" -> return PNG
                "image/webp" -> return WEBP
            }

            if (normalizedMediaType?.startsWith("image/") == true) {
                return null
            }

            return when (displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)) {
                "jpg", "jpeg" -> JPEG
                "png" -> PNG
                "webp" -> WEBP
                else -> null
            }
        }
    }
}
