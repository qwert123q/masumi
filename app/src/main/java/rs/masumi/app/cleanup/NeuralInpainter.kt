package rs.masumi.app.cleanup

/**
 * Pluggable texture-synthesis inpainter for masked free-text regions.
 * Implementations write replacement colors for every masked pixel directly
 * into the page pixel array and must be safe to call from multiple cleanup
 * worker threads. Returning false (or throwing) makes the caller fall back
 * to the classical interpolation inpainter.
 */
fun interface NeuralInpainter {
    fun inpaint(
        pixels: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int,
        roiMask: BooleanArray,
    ): Boolean
}
