package rs.masumi.app.ocr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.ocr.OcrCropDescriptor
import rs.masumi.core.ocr.OcrCropStrategy

@RunWith(AndroidJUnit4::class)
class OcrCropRendererTest {
    @Test
    fun clipsFractionalPageEdgeAndReturnsExactRgbPixels() {
        val page = checkerboard(4, 4)
        val before = IntArray(16).also { page.getPixels(it, 0, 4, 0, 0, 4, 4) }

        val crop = OcrCropRenderer().render(
            page,
            OcrCropDescriptor(
                strategy = OcrCropStrategy.PADDED_TEXT,
                box = PixelBox(-0.2, 0.2, 2.1, 2.8),
            ),
        )

        assertEquals(3, crop.width)
        assertEquals(3, crop.height)
        assertArrayEquals(
            rgbOf(
                Color.BLACK, Color.WHITE, Color.BLACK,
                Color.WHITE, Color.BLACK, Color.WHITE,
                Color.BLACK, Color.WHITE, Color.BLACK,
            ),
            crop.rgb,
        )
        val after = IntArray(16).also { page.getPixels(it, 0, 4, 0, 0, 4, 4) }
        assertArrayEquals(before, after)
        page.recycle()
    }

    @Test
    fun everyCropStrategyUsesFloorCeilVisibleCoordinates() {
        val page = checkerboard(6, 6)
        OcrCropStrategy.entries.forEachIndexed { index, strategy ->
            val crop = OcrCropRenderer().render(
                page,
                OcrCropDescriptor(
                    strategy = strategy,
                    box = PixelBox(index + 0.4, index + 0.6, index + 2.1, index + 3.2),
                ),
            )
            assertEquals(3, crop.width)
            assertEquals(4, crop.height)
        }
        page.recycle()
    }

    private fun checkerboard(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun rgbOf(vararg colors: Int): ByteArray = ByteArray(colors.size * 3).also { rgb ->
        colors.forEachIndexed { index, color ->
            rgb[index * 3] = Color.red(color).toByte()
            rgb[index * 3 + 1] = Color.green(color).toByte()
            rgb[index * 3 + 2] = Color.blue(color).toByte()
        }
    }
}
