package rs.masumi.app.cleanup

import kotlin.math.max
import kotlin.math.min

/**
 * Projects the complete cleanup mask into one neural tile's model crop.
 *
 * A tile writes only its own pixels, but every masked glyph visible in its
 * context must still be hidden from the model. Otherwise adjacent source text
 * becomes conditioning input and is copied back as dark strokes.
 */
internal object InpaintContextMask {
    fun project(
        mask: BooleanArray,
        maskLeft: Int,
        maskTop: Int,
        maskWidth: Int,
        maskHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        scaleX: Double,
        scaleY: Double,
        inputWidth: Int,
        inputHeight: Int,
    ): FloatArray {
        require(maskWidth > 0 && maskHeight > 0 && mask.size == maskWidth * maskHeight)
        require(cropRight > cropLeft && cropBottom > cropTop)
        require(scaleX > 0.0 && scaleY > 0.0 && inputWidth > 0 && inputHeight > 0)
        val output = FloatArray(inputWidth * inputHeight)
        val intersectionLeft = max(maskLeft, cropLeft)
        val intersectionTop = max(maskTop, cropTop)
        val intersectionRight = min(maskLeft + maskWidth, cropRight)
        val intersectionBottom = min(maskTop + maskHeight, cropBottom)
        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) return output
        for (pageY in intersectionTop until intersectionBottom) {
            for (pageX in intersectionLeft until intersectionRight) {
                if (!mask[(pageY - maskTop) * maskWidth + pageX - maskLeft]) continue
                val startX = ((pageX - cropLeft) * scaleX).toInt().coerceIn(0, inputWidth - 1)
                val endX = ((pageX + 1 - cropLeft) * scaleX).toInt().coerceIn(0, inputWidth - 1)
                val startY = ((pageY - cropTop) * scaleY).toInt().coerceIn(0, inputHeight - 1)
                val endY = ((pageY + 1 - cropTop) * scaleY).toInt().coerceIn(0, inputHeight - 1)
                for (y in startY..endY) for (x in startX..endX) {
                    output[y * inputWidth + x] = 1f
                }
            }
        }
        return output
    }
}
