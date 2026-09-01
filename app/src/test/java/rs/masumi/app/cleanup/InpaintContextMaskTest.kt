package rs.masumi.app.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class InpaintContextMaskTest {
    @Test
    fun `tile crop hides masked glyphs from adjacent write tiles`() {
        val fullMask = BooleanArray(4 * 8).also { mask ->
            mask[1 * 4 + 1] = true
            mask[6 * 4 + 2] = true
        }

        val projected = InpaintContextMask.project(
            mask = fullMask,
            maskLeft = 10,
            maskTop = 20,
            maskWidth = 4,
            maskHeight = 8,
            cropLeft = 10,
            cropTop = 20,
            cropRight = 14,
            cropBottom = 28,
            scaleX = 1.0,
            scaleY = 1.0,
            inputWidth = 4,
            inputHeight = 8,
        )

        assertEquals(1f, projected[1 * 4 + 1])
        assertEquals(1f, projected[6 * 4 + 2])
    }
}
