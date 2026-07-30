package rs.masumi.app.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmentationMaskTest {
    @Test
    fun `probability mask samples valid model area into page coordinates`() {
        val mask = TextProbabilityMask(
            pageWidth = 4,
            pageHeight = 4,
            modelWidth = 4,
            modelHeight = 4,
            validWidth = 2,
            validHeight = 2,
            values = byteArrayOf(
                0, 100, 0, 0,
                50, 200.toByte(), 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
            ),
        )

        assertEquals(0, mask.probability255At(0, 0))
        assertEquals(200, mask.probability255At(3, 3))
    }

    @Test
    fun `segmentation keeps a thin diagonal that component heuristics would reject`() {
        val values = ByteArray(12 * 12)
        for (value in 1..10) values[value * 12 + value] = 255.toByte()
        val probability = TextProbabilityMask(12, 12, 12, 12, 12, 12, values)
        val roi = MaskBounds(0, 0, 12, 12)
        val mask = SegmentationMaskRefiner.eraseMask(
            probability = probability,
            core = roi,
            roi = roi,
            threshold = 60.0 / 255.0,
            dilationRadius = 1,
            cancellation = { false },
        )

        assertTrue(mask[1 * 12 + 1])
        assertTrue(mask[10 * 12 + 10])
        assertFalse(mask[0 * 12 + 11])
    }

    @Test
    fun `residual audit ignores white halo and catches unchanged source ink`() {
        val width = 8
        val white = rgb(255, 255, 255)
        val black = rgb(0, 0, 0)
        val values = ByteArray(width * width) { 40 }
        val before = IntArray(width * width) { white }
        for (y in 1..6) {
            values[y * width + 3] = 200.toByte()
            before[y * width + 3] = black
        }
        val probability = TextProbabilityMask(width, width, width, width, width, width, values)
        val bounds = MaskBounds(0, 0, width, width)
        val auditMask = SegmentationMaskRefiner.auditMask(
            probability = probability,
            beforeRoi = before,
            core = bounds,
            roi = bounds,
            background = white,
            threshold = 30.0 / 255.0,
            minimumBackgroundDistance = 20,
            cancellation = { false },
        )

        assertEquals(6, auditMask.count { it })
        val unchanged = SegmentationMaskRefiner.auditResidual(
            before,
            before.copyOf(),
            width,
            bounds,
            auditMask,
            maximumUnchangedDistance = 8,
        )
        assertEquals(6, unchanged.residualPixelCount)

        val cleaned = before.copyOf().also { pixels ->
            for (y in 1..6) pixels[y * width + 3] = white
        }
        val passed = SegmentationMaskRefiner.auditResidual(
            before,
            cleaned,
            width,
            bounds,
            auditMask,
            maximumUnchangedDistance = 8,
        )
        assertEquals(0, passed.residualPixelCount)
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}
