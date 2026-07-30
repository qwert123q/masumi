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
            for (y in 9 until 16) for (x in 14 until 26) setPixel(x, y, Color.BLACK)
        }
        val cleaned = SourceCleanupEngine().clean(
            source,
            listOf(
                target(
                    PixelBox(12.0, 7.0, 28.0, 18.0),
                    CleanupStrategy.LOCAL_BOUNDARY_INPAINT,
                    expectedGlyphCount = 1,
                ),
            ),
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

    @Test
    fun freeTextGlyphLimitLeavesAdjacentArtworkUntouched() {
        val source = Bitmap.createBitmap(100, 70, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 20 until 45) for (x in 18 until 30) setPixel(x, y, Color.BLACK)
            for (y in 8 until 62) for (x in 72 until 96) setPixel(x, y, Color.BLACK)
        }

        val cleaned = SourceCleanupEngine().clean(
            source,
            listOf(
                target(
                    PixelBox(10.0, 5.0, 98.0, 65.0),
                    CleanupStrategy.LOCAL_BOUNDARY_INPAINT,
                    expectedGlyphCount = 1,
                ),
            ),
            CleanupPolicy(),
        )

        try {
            assertEquals(CleanupRegionState.CLEANED, cleaned.regions.single().state)
            assertTrue(cleaned.bitmap.getPixel(23, 30) != Color.BLACK)
            assertEquals(Color.BLACK, cleaned.bitmap.getPixel(84, 30))
        } finally {
            cleaned.bitmap.recycle()
            source.recycle()
        }
    }

    @Test
    fun freeTextRemovesDisconnectedBoldStrokesBelongingToOneJapaneseGlyph() {
        val source = Bitmap.createBitmap(90, 58, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                val shade = 220 + (x + y) % 21
                setPixel(x, y, Color.rgb(shade, shade, shade))
            }
            // One stylized bold glyph made from four disconnected strokes.
            for (y in 14 until 25) for (x in 13 until 18) setPixel(x, y, Color.BLACK)
            for (y in 14 until 19) for (x in 21 until 32) setPixel(x, y, Color.BLACK)
            for (y in 28 until 39) for (x in 21 until 26) setPixel(x, y, Color.BLACK)
            for (y in 34 until 39) for (x in 29 until 40) setPixel(x, y, Color.BLACK)
            // A large illustration mass in the same detector box must remain.
            for (y in 11 until 45) for (x in 60 until 82) setPixel(x, y, Color.BLACK)
        }

        val cleaned = SourceCleanupEngine().clean(
            source,
            listOf(
                target(
                    PixelBox(8.0, 7.0, 85.0, 49.0),
                    CleanupStrategy.LOCAL_BOUNDARY_INPAINT,
                    expectedGlyphCount = 1,
                ),
            ),
            CleanupPolicy(),
        )

        try {
            assertEquals(CleanupRegionState.CLEANED, cleaned.regions.single().state)
            listOf(15 to 18, 25 to 16, 23 to 33, 34 to 36).forEach { (x, y) ->
                assertTrue("disconnected glyph stroke remained at $x,$y", cleaned.bitmap.getPixel(x, y) != Color.BLACK)
            }
            assertEquals(Color.BLACK, cleaned.bitmap.getPixel(70, 25))
        } finally {
            cleaned.bitmap.recycle()
            source.recycle()
        }
    }

    @Test
    fun texturedNarrationBoxUsesGlyphMaskInsteadOfErasingThePattern() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                val shade = when ((x + y) % 3) {
                    0 -> 255
                    1 -> 210
                    else -> 170
                }
                setPixel(x, y, Color.rgb(shade, shade, shade))
            }
            for (y in 22 until 40) for (x in 27 until 35) setPixel(x, y, Color.BLACK)
        }
        val patternBefore = source.getPixel(12, 12)

        val cleaned = SourceCleanupEngine().clean(
            source,
            listOf(
                target(
                    PixelBox(8.0, 8.0, 56.0, 56.0),
                    CleanupStrategy.FLAT_LOCAL_FILL,
                    expectedGlyphCount = 1,
                ),
            ),
            CleanupPolicy(),
        )

        try {
            assertEquals(CleanupRegionState.CLEANED, cleaned.regions.single().state)
            assertTrue(cleaned.bitmap.getPixel(30, 30) != Color.BLACK)
            assertEquals(patternBefore, cleaned.bitmap.getPixel(12, 12))
        } finally {
            cleaned.bitmap.recycle()
            source.recycle()
        }
    }

    @Test
    fun consumingModeReleasesTheSourceAfterCreatingTheMutableCopy() {
        val source = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

        val cleaned = SourceCleanupEngine().clean(
            source,
            emptyList(),
            CleanupPolicy(),
            recycleSourceAfterCopy = true,
        )

        try {
            assertTrue(source.isRecycled)
            assertEquals(24, cleaned.bitmap.width)
            assertEquals(Color.WHITE, cleaned.bitmap.getPixel(0, 0))
        } finally {
            cleaned.bitmap.recycle()
        }
    }

    @Test
    fun pageWithoutTargetsSkipsTextSegmentation() {
        var inferenceCount = 0
        val segmenter = TextMaskProvider { _, width, height, _ ->
            inferenceCount += 1
            TextProbabilityMask(
                pageWidth = width,
                pageHeight = height,
                modelWidth = 1,
                modelHeight = 1,
                validWidth = 1,
                validHeight = 1,
                values = byteArrayOf(0),
            )
        }
        val source = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val cleaned = SourceCleanupEngine(textMaskProvider = segmenter).clean(
            source,
            emptyList(),
            CleanupPolicy(),
        )

        try {
            assertEquals(0, inferenceCount)
            assertTrue(cleaned.regions.isEmpty())
        } finally {
            cleaned.bitmap.recycle()
            source.recycle()
        }
    }

    private fun target(
        box: PixelBox,
        strategy: CleanupStrategy = CleanupStrategy.FLAT_LOCAL_FILL,
        expectedGlyphCount: Int? = null,
    ) = CleanupTarget("a".repeat(64), "b".repeat(64), box, strategy, expectedGlyphCount)
}
