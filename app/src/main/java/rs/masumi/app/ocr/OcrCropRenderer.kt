package rs.masumi.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
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

        val sourceWidth = right - left
        val sourceHeight = bottom - top
        val (width, height) = boundedDimensions(
            sourceWidth,
            sourceHeight,
            descriptor.maximumSourcePixels,
        )
        val pixels = IntArray(Math.multiplyExact(width, height))
        if (width == sourceWidth && height == sourceHeight) {
            page.getPixels(pixels, 0, width, left, top, width, height)
        } else {
            val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(scaled).drawBitmap(
                    page,
                    Rect(left, top, right, bottom),
                    Rect(0, 0, width, height),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            } finally {
                scaled.recycle()
            }
        }
        val rgb = ByteArray(Math.multiplyExact(pixels.size, RGB_CHANNEL_COUNT))
        var index = 0
        while (index < pixels.size) {
            val color = pixels[index]
            val offset = index * RGB_CHANNEL_COUNT
            rgb[offset] = (color ushr 16).toByte()
            rgb[offset + 1] = (color ushr 8).toByte()
            rgb[offset + 2] = color.toByte()
            index += 1
        }
        return RenderedOcrCrop(rgb, width, height)
    }

    private fun boundedDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        maximumPixels: Int?,
    ): Pair<Int, Int> {
        val sourcePixels = sourceWidth.toLong() * sourceHeight
        if (maximumPixels == null || sourcePixels <= maximumPixels) return sourceWidth to sourceHeight
        val scale = sqrt(maximumPixels.toDouble() / sourcePixels)
        var width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        var height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        while (width.toLong() * height > maximumPixels) {
            if (width >= height && width > 1) width -= 1 else height -= 1
        }
        return width to height
    }

    private companion object {
        const val RGB_CHANNEL_COUNT = 3
    }
}
