package rs.masumi.app

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Stage previews exist only for the details screen, yet they used to be
 * committed as full-resolution PNGs — around 2.5 MB per page and stage, which
 * made previews the second biggest consumer in a chapter's workspace. A
 * phone-width lossy WebP is visually identical there at a fraction of the
 * size.
 */
object PreviewImageEncoder {
    const val FILE_EXTENSION = "webp"
    private const val MAXIMUM_LONG_EDGE = 1280
    private const val QUALITY = 80

    fun encode(bitmap: Bitmap): ByteArray {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge > MAXIMUM_LONG_EDGE) {
            val scale = MAXIMUM_LONG_EDGE.toDouble() / longEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        try {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            return ByteArrayOutputStream().use { output ->
                check(scaled.compress(format, QUALITY, output)) {
                    "preview image could not be encoded"
                }
                output.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
