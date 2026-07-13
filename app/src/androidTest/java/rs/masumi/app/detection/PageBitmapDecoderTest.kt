package rs.masumi.app.detection

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.VisibleOrientation
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class PageBitmapDecoderTest {
    @Test
    fun appliesExifRotationWithoutChangingSourceBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = Files.createTempFile(context.cacheDir.toPath(), "oriented-", ".jpg")
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        Files.newOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output) }
        bitmap.recycle()
        ExifInterface(file.toString()).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val before = Files.readAllBytes(file)

        val decoded = PageBitmapDecoder().decode(file)

        try {
            assertEquals(3, decoded.bitmap.width)
            assertEquals(2, decoded.bitmap.height)
            assertEquals(VisibleOrientation.ROTATE_90, decoded.orientation)
            assertArrayEquals(before, Files.readAllBytes(file))
        } finally {
            decoded.bitmap.recycle()
            Files.deleteIfExists(file)
        }
    }
}
