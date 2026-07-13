package rs.masumi.app.detection

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class PreparedOnnxInput(
    val imageBuffer: FloatBuffer,
    val imageShape: LongArray,
    val originalSizeValues: LongArray,
    val originalSizeShape: LongArray,
)

class OnnxInputPreprocessor {
    fun prepare(bitmap: Bitmap): PreparedOnnxInput {
        require(bitmap.width > 0 && bitmap.height > 0) { "Bitmap dimensions must be positive" }
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)
        try {
            val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
            scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
            val buffer = ByteBuffer
                .allocateDirect(pixels.size * CHANNEL_COUNT * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            pixels.forEach { pixel -> buffer.put(Color.red(pixel) / RESCALE_DIVISOR) }
            pixels.forEach { pixel -> buffer.put(Color.green(pixel) / RESCALE_DIVISOR) }
            pixels.forEach { pixel -> buffer.put(Color.blue(pixel) / RESCALE_DIVISOR) }
            buffer.rewind()

            return PreparedOnnxInput(
                imageBuffer = buffer,
                imageShape = longArrayOf(1, CHANNEL_COUNT.toLong(), INPUT_HEIGHT.toLong(), INPUT_WIDTH.toLong()),
                originalSizeValues = longArrayOf(bitmap.height.toLong(), bitmap.width.toLong()),
                originalSizeShape = longArrayOf(1, 2),
            )
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private companion object {
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 640
        const val CHANNEL_COUNT = 3
        const val RESCALE_DIVISOR = 255f
    }
}
