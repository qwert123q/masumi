package rs.masumi.app.quality

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.typesetting.PageTypesettingArtifact
import rs.masumi.core.typesetting.TypesettingDependencies
import rs.masumi.core.typesetting.TypesettingDirection
import rs.masumi.core.typesetting.TypesettingRegionArtifact
import rs.masumi.core.typesetting.TypesettingRegionState
import rs.masumi.core.typesetting.TypesettingStyle

@RunWith(AndroidJUnit4::class)
class BitmapQualityAuditorTest {
    @Test
    fun countsRegionPixelsAndUnexpectedOutsideChanges() {
        val cleaned = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val flattened = cleaned.copy(Bitmap.Config.ARGB_8888, true).apply {
            setPixel(5, 5, Color.BLACK)
            setPixel(18, 18, Color.BLACK)
        }
        try {
            val audit = BitmapQualityAuditor().audit(cleaned, flattened, pageArtifact(), cancellation = { false })

            assertEquals(2, audit.actualChangedPixelCount)
            assertEquals(1, audit.changedPixelsOutsideLayout)
            assertEquals(1, audit.changedPixelsByOcrRegionId.getValue("1".repeat(64)))
        } finally {
            flattened.recycle()
            cleaned.recycle()
        }
    }

    private fun pageArtifact(): PageTypesettingArtifact = PageTypesettingArtifact(
        pageId = "a".repeat(64),
        pageOrder = 0,
        sourceSha256 = "a".repeat(64),
        cleanupPageArtifactKey = "b".repeat(64),
        pageArtifactKey = "c".repeat(64),
        visibleWidth = 20,
        visibleHeight = 20,
        renderedImageSha256 = "d".repeat(64),
        dependencies = TypesettingDependencies(cleanupRunArtifactKey = "e".repeat(64)),
        regions = listOf(
            TypesettingRegionArtifact(
                translationRegionId = "f".repeat(64),
                ocrRegionId = "1".repeat(64),
                targetBox = PixelBox(2.0, 2.0, 10.0, 10.0),
                layoutBox = PixelBox(2.0, 2.0, 10.0, 10.0),
                style = TypesettingStyle.BUBBLE,
                direction = TypesettingDirection.VERTICAL_RTL,
                fontSizePx = 12.0,
                lineOrColumnCount = 1,
                changedPixelCount = 1,
                state = TypesettingRegionState.TYPESET,
            ),
        ),
    )
}
