package rs.masumi.app.cleanup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTextOutlineMaskTest {
    @Test
    fun `connected white stroke joins dark glyph mask without consuming colored artwork`() {
        val width = 11
        val height = 11
        val blue = rgb(35, 95, 180)
        val pixels = IntArray(width * height) { blue }
        val seed = BooleanArray(width * height)
        for (y in 4..6) for (x in 4..6) {
            pixels[y * width + x] = rgb(8, 8, 8)
            seed[y * width + x] = true
        }
        for (y in 2..8) for (x in 2..8) {
            if (x in 4..6 && y in 4..6) continue
            pixels[y * width + x] = rgb(246, 244, 242)
        }

        val expanded = DisplayTextOutlineMask.expand(
            seed = seed,
            pixels = pixels,
            pageStride = width,
            roiLeft = 0,
            roiTop = 0,
            roiWidth = width,
            roiHeight = height,
            maximumRadius = 2,
        )

        assertTrue(expanded[3 * width + 5])
        assertTrue(expanded[2 * width + 5])
        assertFalse(expanded[1 * width + 5])
    }

    @Test
    fun `growth on white artwork stays inside the configured radius`() {
        val width = 9
        val pixels = IntArray(width * width) { rgb(250, 250, 250) }
        val seed = BooleanArray(width * width).also { it[4 * width + 4] = true }

        val expanded = DisplayTextOutlineMask.expand(
            seed = seed,
            pixels = pixels,
            pageStride = width,
            roiLeft = 0,
            roiTop = 0,
            roiWidth = width,
            roiHeight = width,
            maximumRadius = 2,
        )

        assertTrue(expanded[4 * width + 2])
        assertFalse(expanded[4 * width + 1])
    }

    @Test
    fun `bright saturated artwork does not join the outline mask`() {
        val width = 7
        val yellow = rgb(255, 220, 20)
        val pixels = IntArray(width * width) { rgb(25, 80, 160) }
        val seed = BooleanArray(width * width).also { it[3 * width + 3] = true }
        pixels[3 * width + 4] = yellow

        val expanded = DisplayTextOutlineMask.expand(
            seed = seed,
            pixels = pixels,
            pageStride = width,
            roiLeft = 0,
            roiTop = 0,
            roiWidth = width,
            roiHeight = width,
            maximumRadius = 3,
        )

        assertFalse(expanded[3 * width + 4])
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}
