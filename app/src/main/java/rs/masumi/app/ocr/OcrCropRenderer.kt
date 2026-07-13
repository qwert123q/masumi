package rs.masumi.app.ocr

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.ceil
import kotlin.math.floor
import rs.masumi.core.ocr.OcrCropDescriptor

data class RenderedOcrCrop(
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
)

class OcrCropRenderer {
    fun render(page: Bitmap, descriptor: OcrCropDescriptor): RenderedOcrCrop {
        require(!page.isRecycled) { "source bitmap is recycled" }
        val box = descriptor.box
        require(
            box.left.isFinite() && box.top.isFinite() &&
                box.right.isFinite() && box.bottom.isFinite(),
        ) { "crop box is not finite" }
        val left = floor(box.left).toInt().coerceIn(0, page.width)
        val top = floor(box.top).toInt().coerceIn(0, page.height)
        val right = ceil(box.right).toInt().coerceIn(0, page.width)
        val bottom = ceil(box.bottom).toInt().coerceIn(0, page.height)
        require(right > left && bottom > top) { "crop is empty after visible-page clipping" }

        val width = right - left
        val height = bottom - top
        val pixels = IntArray(width * height)
        page.getPixels(pixels, 0, width, left, top, width, height)
        val rgb = ByteArray(pixels.size * RGB_CHANNEL_COUNT)
        pixels.forEachIndexed { index, color ->
            val offset = index * RGB_CHANNEL_COUNT
            rgb[offset] = Color.red(color).toByte()
            rgb[offset + 1] = Color.green(color).toByte()
            rgb[offset + 2] = Color.blue(color).toByte()
        }
        return RenderedOcrCrop(rgb, width, height)
    }

    private companion object {
        const val RGB_CHANNEL_COUNT = 3
    }
}
