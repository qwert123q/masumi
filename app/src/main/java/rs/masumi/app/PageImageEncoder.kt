package rs.masumi.app

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Full-page pipeline images (cleaned pages, typeset pages, and the exported
 * copies of them) use PNG. Keeping one stable, broadly supported format avoids
 * device-specific WebP decode/render differences in the finished manga.
 * Phone-sized detection/OCR previews remain separate WebP artifacts because
 * they are UI caches rather than pipeline inputs or exported pages.
 */
object PageImageEncoder {
    const val preferredExtension = "png"

    fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
    }
}
