package rs.masumi.app.detection

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxInputPreprocessorTest {
    @Test
    fun createsRgbChwTensorWithPinnedShapeAndRescale() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.rgb(255, 128, 0))
        }

        val prepared = OnnxInputPreprocessor().prepare(bitmap)

        try {
            val plane = 640 * 640
            assertArrayEquals(longArrayOf(1, 3, 640, 640), prepared.imageShape)
            assertArrayEquals(longArrayOf(1, 2), prepared.originalSizeShape)
            assertArrayEquals(longArrayOf(1, 1), prepared.originalSizeValues)
            assertEquals(1f, prepared.imageBuffer.get(0), 0.0001f)
            assertEquals(128f / 255f, prepared.imageBuffer.get(plane), 0.0001f)
            assertEquals(0f, prepared.imageBuffer.get(plane * 2), 0.0001f)
            assertEquals(1, bitmap.width)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun passesEachNonSquarePageOriginalSizeAsWidthThenHeight() {
        val portrait = Bitmap.createBitmap(321, 777, Bitmap.Config.ARGB_8888)
        val landscape = Bitmap.createBitmap(913, 247, Bitmap.Config.ARGB_8888)

        try {
            assertArrayEquals(
                longArrayOf(321, 777),
                OnnxInputPreprocessor().prepare(portrait).originalSizeValues,
            )
            assertArrayEquals(
                longArrayOf(913, 247),
                OnnxInputPreprocessor().prepare(landscape).originalSizeValues,
            )
        } finally {
            portrait.recycle()
            landscape.recycle()
        }
    }
}
