package rs.masumi.app.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InpaintTilePlannerTest {
    @Test
    fun `long vertical mask splits at blank rows and keeps every masked pixel`() {
        val width = 40
        val height = 900
        val mask = BooleanArray(width * height)
        listOf(80..180, 330..430, 650..760).forEach { band ->
            band.forEach { y -> for (x in 12..27) mask[y * width + x] = true }
        }

        val tiles = InpaintTilePlanner.plan(
            width,
            height,
            mask,
            maximumSpan = 384,
            minimumSpan = 128,
            cutSearchRadius = 80,
        )

        assertTrue(tiles.size >= 3)
        assertTrue(tiles.all { it.height <= 384 })
        mask.indices.filter { mask[it] }.forEach { local ->
            val x = local % width
            val y = local / width
            assertEquals(1, tiles.count { x in it.left until it.right && y in it.top until it.bottom })
        }
    }

    @Test
    fun `compact mask remains one tile`() {
        val mask = BooleanArray(240 * 180).also { it[90 * 240 + 120] = true }

        assertEquals(
            listOf(InpaintTile(0, 0, 240, 180)),
            InpaintTilePlanner.plan(240, 180, mask, 384, 128, 64),
        )
    }
}
