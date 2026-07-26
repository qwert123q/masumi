package rs.masumi.app

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Full-page pipeline images (cleaned pages, typeset pages, and the exported
 * copies of them) must stay lossless — cleanup output is typesetting input,
 * and the finished page is the product. Lossless WebP encodes the same pixels
 * at roughly two thirds of the PNG size, so it is preferred wherever the
 * platform can encode it; older devices keep producing PNG. Readers never
 * care: every consumer resolves the recorded artifact path and decodes by
 * content.
 */
object PageImageEncoder {
    val preferredExtension: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "png"

    fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For WEBP_LOSSLESS the quality parameter trades encode effort for
            // size; 75 compresses well without stalling the pipeline.
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 75, output))
        } else {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        output.toByteArray()
    }
}
