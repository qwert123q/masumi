package rs.masumi.app.cleanup

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupPreserveReason
import rs.masumi.core.cleanup.CleanupRegionState
import rs.masumi.core.cleanup.CleanupStrategy
import rs.masumi.core.detection.PixelBox

@RunWith(AndroidJUnit4::class)
class SourceCleanupEngineTest {
    @Test
    fun flatBubbleFillRemovesDarkGlyphsAndLeavesPixelsOutsideMaskUntouched() {
        val source = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 14 until 26) for (x in 18 until 22) setPixel(x, y, Color.BLACK)
        }
        val outsideBefore = source.getPixel(2, 2)

        val cleaned = SourceCleanupEngine().clean(
            source,
            listOf(
                CleanupTarget(
                    translationRegionId = "a".repeat(64),
                    ocrRegionId = "b".repeat(64),
                    box = PixelBox(14.0, 10.0, 26.0, 30.0),
                    strategy = CleanupStrategy.FLAT_LOCAL_FILL,
                ),
            ),
            CleanupPolicy(),
        )

        try {
            assertEquals(CleanupRegionState.CLEANED, cleaned.regions.single().state)
            assertEquals(Color.WHITE, cleaned.bitmap.getPixel(20, 20))
            assertEquals(outsideBefore, cleaned.bitmap.getPixel(2, 2))
            assertTrue(cleaned.regions.single().changedPixelCount > 0)
        } finally {
            source.recycle()
            cleaned.bitmap.recycle()
        }
    }

    @Test
    fun emptyOrImplausiblyLargeMaskPreservesTheRegion() {
        val blank = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val empty = SourceCleanupEngine().clean(
            blank,
            listOf(target(PixelBox(5.0, 5.0, 18.0, 18.0))),
            CleanupPolicy(),
        )
        try {
            assertEquals(CleanupPreserveReason.MASK_EMPTY, empty.regions.single().preserveReason)
        } finally {
            empty.bitmap.recycle()
            blank.recycle()
        }

        val unsafe = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 4 until 20) for (x in 4 until 20) setPixel(x, y, Color.BLACK)
        }
        val protected = SourceCleanupEngine().clean(
            unsafe,
            listOf(target(PixelBox(4.0, 4.0, 20.0, 20.0))),
            CleanupPolicy(maximumMaskCoverage = 0.4),
        )
        try {
            assertEquals(CleanupPreserveReason.MASK_UNSAFE, protected.regions.single().preserveReason)
            assertEquals(Color.BLACK, protected.bitmap.getPixel(10, 10))
        } finally {
            protected.bitmap.recycle()
            unsafe.recycle()
        }
    }

    @Test
    fun freeTextUsesBoundaryInpainting() {
        val source = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, Color.rgb(100 + x, 120 + x, 140 + x))
            }
            for (x in 14 until 26) setPixel(x, 12, Color.BLACK)
        }
        val cleaned = SourceCleanupEngine().clean(
            source,
            listOf(target(PixelBox(12.0, 9.0, 28.0, 16.0), CleanupStrategy.LOCAL_BOUNDARY_INPAINT)),
            CleanupPolicy(),
        )
        try {
            assertEquals(CleanupRegionState.CLEANED, cleaned.regions.single().state)
            assertTrue(Color.red(cleaned.bitmap.getPixel(20, 12)) > 100)
        } finally {
            cleaned.bitmap.recycle()
            source.recycle()
        }
    }

    private fun target(
        box: PixelBox,
        strategy: CleanupStrategy = CleanupStrategy.FLAT_LOCAL_FILL,
    ) = CleanupTarget("a".repeat(64), "b".repeat(64), box, strategy)
}
