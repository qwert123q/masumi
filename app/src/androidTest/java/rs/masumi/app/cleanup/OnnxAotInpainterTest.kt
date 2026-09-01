package rs.masumi.app.cleanup

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupRegionState
import rs.masumi.core.cleanup.CleanupStrategy
import rs.masumi.core.detection.PixelBox

@RunWith(AndroidJUnit4::class)
class OnnxAotInpainterTest {
    private fun loadInpainter(): OnnxAotInpainter {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        return OnnxAotInpainter(assets.open(OnnxAotInpainter.ASSET_PATH).use { it.readBytes() })
    }

    @Test
    fun synthesizesTextureForMaskedHoleInsteadOfFlatFill() {
        loadInpainter().use { inpainter ->
            val width = 160
            val height = 128
            val pixels = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                // Checkered screentone-like background.
                val shade = if ((x / 4 + y / 4) % 2 == 0) 235 else 150
                pixels[y * width + x] = Color.rgb(shade, shade, shade)
            }
            val roiLeft = 56
            val roiTop = 40
            val roiRight = 104
            val roiBottom = 88
            val roiWidth = roiRight - roiLeft
            val mask = BooleanArray(roiWidth * (roiBottom - roiTop))
            for (y in 8 until 40) for (x in 8 until 40) {
                mask[y * roiWidth + x] = true
                pixels[(roiTop + y) * width + roiLeft + x] = Color.BLACK
            }

            val applied = inpainter.inpaint(
                pixels, width, height, roiLeft, roiTop, roiRight, roiBottom, mask,
            )

            assertTrue("inpainter did not run", applied)
            var minimum = 255
            var maximum = 0
            var blackCount = 0
            var sum = 0L
            for (y in 8 until 40) for (x in 8 until 40) {
                val value = Color.red(pixels[(roiTop + y) * width + roiLeft + x])
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
                if (value < 60) blackCount += 1
                sum += value
            }
            val mean = sum / (32 * 32)
            // The hole must no longer be black, must land in the background
            // brightness range on average, and must not be one flat color.
            assertTrue("hole still black: $blackCount px", blackCount < 64)
            assertTrue("hole too dark: mean=$mean", mean in 120..250)
            assertTrue("hole is flat: min=$minimum max=$maximum", maximum - minimum > 20)
        }
    }

    @Test
    fun engineDoesNotUseNeuralPathForInitialFreeTextCleanup() {
        loadInpainter().use { inpainter ->
            var neuralCalls = 0
            val counting = NeuralInpainter { pixels, w, h, l, t, r, b, m, control ->
                neuralCalls += 1
                inpainter.inpaint(pixels, w, h, l, t, r, b, m, control)
            }
            val source = Bitmap.createBitmap(160, 128, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until height) for (x in 0 until width) {
                    val shade = if ((x / 4 + y / 4) % 2 == 0) 235 else 150
                    setPixel(x, y, Color.rgb(shade, shade, shade))
                }
                for (y in 52 until 76) for (x in 66 until 94) setPixel(x, y, Color.BLACK)
            }
            val cleaned = SourceCleanupEngine(
                neuralFallbackProvider = { counting },
                neuralRepairBudget = NeuralRepairBudget(8, 120_000L),
            ).clean(
                source,
                listOf(
                    CleanupTarget(
                        translationRegionId = "a".repeat(64),
                        ocrRegionId = "b".repeat(64),
                        box = PixelBox(60.0, 46.0, 100.0, 82.0),
                        strategy = CleanupStrategy.LOCAL_BOUNDARY_INPAINT,
                        expectedGlyphCount = 1,
                    ),
                ),
                CleanupPolicy(),
            )
            try {
                assertEquals(CleanupRegionState.CLEANED, cleaned.regions.single().state)
                assertEquals(0, neuralCalls)
                assertTrue(Color.red(cleaned.bitmap.getPixel(80, 64)) > 60)
            } finally {
                cleaned.bitmap.recycle()
                source.recycle()
            }

        }
    }
}
