package rs.masumi.app.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.DetectedRegion
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.detection.RegionProtectionPolicy
import rs.masumi.core.detection.RegionSemanticStatus
import rs.masumi.core.detection.VisibleOrientation

@RunWith(AndroidJUnit4::class)
class DetectionPreviewRendererTest {
    @Test
    fun rendersApprovedColorsOnDerivedBitmapOnly() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val page = DecodedPage(source, VisibleOrientation.NORMAL)
        val renderer = DetectionPreviewRenderer(labelTextSizePx = 10f, strokeWidthPx = 3f)

        val png = renderer.render(
            page = page,
            bubbles = listOf(region(0, DetectorClass.BUBBLE, PixelBox(10.0, 10.0, 30.0, 30.0))),
            textRegions = listOf(
                region(1, DetectorClass.TEXT_IN_BUBBLE, PixelBox(40.0, 40.0, 60.0, 60.0)),
                region(2, DetectorClass.TEXT_FREE, PixelBox(70.0, 70.0, 90.0, 90.0)),
            ),
        )
        val preview = BitmapFactory.decodeByteArray(png, 0, png.size)

        try {
            assertEquals(100, preview.width)
            assertEquals(100, preview.height)
            // Previews are lossy-encoded, so overlay colors only survive
            // approximately.
            assertColorNear(Color.rgb(25, 118, 210), preview.getPixel(20, 30))
            assertColorNear(Color.rgb(46, 125, 50), preview.getPixel(50, 60))
            assertColorNear(Color.rgb(239, 108, 0), preview.getPixel(80, 90))
            assertEquals(Color.WHITE, source.getPixel(20, 30))
            assertEquals(Color.WHITE, source.getPixel(50, 60))
            assertEquals(Color.WHITE, source.getPixel(80, 90))
        } finally {
            preview.recycle()
            source.recycle()
        }
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        val delta = maxOf(
            kotlin.math.abs(Color.red(expected) - Color.red(actual)),
            kotlin.math.abs(Color.green(expected) - Color.green(actual)),
            kotlin.math.abs(Color.blue(expected) - Color.blue(actual)),
        )
        org.junit.Assert.assertTrue("expected ~$expected but was $actual (delta $delta)", delta <= 16)
    }

    private fun region(index: Int, type: DetectorClass, box: PixelBox): DetectedRegion =
        DetectedRegion(
            regionId = index.toString().repeat(64),
            queryIndex = index,
            detectorClass = type,
            confidence = 0.75,
            box = box,
            semanticStatus = when (type) {
                DetectorClass.BUBBLE -> RegionSemanticStatus.BUBBLE_CANDIDATE
                DetectorClass.TEXT_IN_BUBBLE -> RegionSemanticStatus.TEXT_IN_BUBBLE
                DetectorClass.TEXT_FREE -> RegionSemanticStatus.UNRESOLVED_FREE_TEXT
            },
            protectionPolicy = if (type == DetectorClass.TEXT_FREE) {
                RegionProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED
            } else {
                RegionProtectionPolicy.NONE
            },
        )
}
