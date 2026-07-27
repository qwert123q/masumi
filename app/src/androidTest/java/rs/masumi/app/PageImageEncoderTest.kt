package rs.masumi.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageImageEncoderTest {
    @Test
    fun fullPageOutputIsPixelExactPng() {
        val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.BLACK)
            setPixel(1, 0, Color.WHITE)
            setPixel(2, 0, Color.rgb(17, 83, 241))
            setPixel(0, 1, Color.rgb(250, 120, 4))
            setPixel(1, 1, Color.rgb(67, 68, 69))
            setPixel(2, 1, Color.TRANSPARENT)
        }

        val encoded = PageImageEncoder.encode(source)
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)

        try {
            assertEquals("png", PageImageEncoder.preferredExtension)
            assertArrayEquals(
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4e,
                    0x47,
                    0x0d,
                    0x0a,
                    0x1a,
                    0x0a,
                ),
                encoded.copyOfRange(0, 8),
            )
            for (y in 0 until source.height) for (x in 0 until source.width) {
                assertEquals(source.getPixel(x, y), decoded.getPixel(x, y))
            }
        } finally {
            decoded.recycle()
            source.recycle()
        }
    }
}
