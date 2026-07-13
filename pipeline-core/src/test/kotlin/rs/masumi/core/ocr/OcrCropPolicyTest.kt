package rs.masumi.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import rs.masumi.core.detection.PixelBox

class OcrCropPolicyTest {
    @Test
    fun `crop attempts use exact padding and context is clipped to bubble`() {
        val candidate = OcrFixtures.candidate().copy(
            box = PixelBox(20.0, 30.0, 60.0, 80.0),
            associatedBubbleBox = PixelBox(15.0, 10.0, 65.0, 90.0),
        )

        val attempts = OcrCropPolicy().attempts(
            candidate = candidate,
            pageWidth = 100,
            pageHeight = 120,
            bubbleBox = candidate.associatedBubbleBox,
        )

        assertEquals(listOf(OcrCropStrategy.PADDED_TEXT, OcrCropStrategy.TIGHT_TEXT, OcrCropStrategy.CONTEXT_TEXT), attempts.map { it.strategy })
        assertBox(PixelBox(15.2, 24.0, 64.8, 86.0), attempts[0].box)
        assertBox(PixelBox(16.0, 26.0, 64.0, 84.0), attempts[1].box)
        assertBox(PixelBox(15.0, 18.0, 65.0, 90.0), attempts[2].box)
    }

    @Test
    fun `all attempts clip to visible page and enforce four pixel minimum`() {
        val candidate = OcrFixtures.candidate().copy(
            box = PixelBox(1.0, 2.0, 11.0, 12.0),
            associatedBubbleBox = null,
        )

        val attempts = OcrCropPolicy().attempts(candidate, pageWidth = 15, pageHeight = 20, bubbleBox = null)

        assertBox(PixelBox(0.0, 0.0, 15.0, 16.0), attempts[0].box)
        assertBox(PixelBox(0.0, 0.0, 15.0, 16.0), attempts[1].box)
        assertBox(PixelBox(0.0, 0.0, 15.0, 16.0), attempts[2].box)
    }

    private fun assertBox(expected: PixelBox, actual: PixelBox) {
        assertEquals(expected.left, actual.left, 1e-9)
        assertEquals(expected.top, actual.top, 1e-9)
        assertEquals(expected.right, actual.right, 1e-9)
        assertEquals(expected.bottom, actual.bottom, 1e-9)
    }
}
