package rs.masumi.app.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import rs.masumi.core.detection.VisibleOrientation
import java.nio.file.Path

data class DecodedPage(
    val bitmap: Bitmap,
    val orientation: VisibleOrientation,
)

class PageDecodeException(cause: Throwable? = null) :
    RuntimeException("Page image could not be decoded", cause)

class PageBitmapDecoder {
    fun decode(path: Path): DecodedPage = try {
        val exifOrientation = ExifInterface(path.toString()).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val source = BitmapFactory.decodeFile(path.toString()) ?: throw PageDecodeException()
        val orientation = exifOrientation.toVisibleOrientation()
        val matrix = exifOrientation.toMatrix()
        if (matrix == null) {
            DecodedPage(source, orientation)
        } else {
            val transformed = Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                matrix,
                true,
            )
            if (transformed !== source) source.recycle()
            DecodedPage(transformed, orientation)
        }
    } catch (failure: PageDecodeException) {
        throw failure
    } catch (failure: Throwable) {
        throw PageDecodeException(failure)
    }

    private fun Int.toVisibleOrientation(): VisibleOrientation = when (this) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> VisibleOrientation.FLIP_HORIZONTAL
        ExifInterface.ORIENTATION_ROTATE_180 -> VisibleOrientation.ROTATE_180
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> VisibleOrientation.FLIP_VERTICAL
        ExifInterface.ORIENTATION_TRANSPOSE -> VisibleOrientation.TRANSPOSE
        ExifInterface.ORIENTATION_ROTATE_90 -> VisibleOrientation.ROTATE_90
        ExifInterface.ORIENTATION_TRANSVERSE -> VisibleOrientation.TRANSVERSE
        ExifInterface.ORIENTATION_ROTATE_270 -> VisibleOrientation.ROTATE_270
        else -> VisibleOrientation.NORMAL
    }

    private fun Int.toMatrix(): Matrix? = when (this) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
            setRotate(90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
            setRotate(-90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }
}
