package rs.masumi.app.library

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderBitmapFilteringTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun continuousReaderEnablesFilteredBitmapSampling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(ContinuousReaderView(context).usesFilteredBitmapSampling())
        }
    }

    @Test
    fun pagedReaderEnablesFilteredBitmapSampling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                val reader = ZoomableReaderView(context)
                reader.setImageBitmap(bitmap)
                assertTrue((reader.drawable as BitmapDrawable).paint.isFilterBitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
