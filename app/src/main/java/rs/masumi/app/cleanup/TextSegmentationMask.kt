package rs.masumi.app.cleanup

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

fun interface TextMaskProvider {
    fun predict(
        pixels: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        cancellation: () -> Boolean,
    ): TextProbabilityMask
}

/**
 * Compact page-to-model probability mapping. Probabilities remain in model
 * space and are sampled only inside cleanup regions, avoiding a second
 * page-sized float buffer on high-resolution manga pages.
 */
class TextProbabilityMask(
    val pageWidth: Int,
    val pageHeight: Int,
    private val modelWidth: Int,
    private val modelHeight: Int,
    private val validWidth: Int,
    private val validHeight: Int,
    private val values: ByteArray,
) {
    init {
        require(pageWidth > 0 && pageHeight > 0)
        require(modelWidth > 0 && modelHeight > 0)
        require(validWidth in 1..modelWidth && validHeight in 1..modelHeight)
        require(values.size == modelWidth * modelHeight)
    }

    fun probability255At(pageX: Int, pageY: Int): Int {
        require(pageX in 0 until pageWidth && pageY in 0 until pageHeight)
        val sourceX = ((pageX + 0.5) * validWidth / pageWidth - 0.5)
            .coerceIn(0.0, (validWidth - 1).toDouble())
        val sourceY = ((pageY + 0.5) * validHeight / pageHeight - 0.5)
            .coerceIn(0.0, (validHeight - 1).toDouble())
        val x0 = floor(sourceX).toInt()
        val y0 = floor(sourceY).toInt()
        val x1 = ceil(sourceX).toInt().coerceAtMost(validWidth - 1)
        val y1 = ceil(sourceY).toInt().coerceAtMost(validHeight - 1)
        val fx = sourceX - x0
        val fy = sourceY - y0
        fun sample(x: Int, y: Int): Int = values[y * modelWidth + x].toInt() and 0xff
        val top = sample(x0, y0) * (1.0 - fx) + sample(x1, y0) * fx
        val bottom = sample(x0, y1) * (1.0 - fx) + sample(x1, y1) * fx
        return (top * (1.0 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
    }
}

internal data class MaskBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top)
    }
}

internal data class ResidualTextAudit(
    val auditPixelCount: Int,
    val residualPixelCount: Int,
    val residualMask: BooleanArray = BooleanArray(0),
) {
    val residualRatio: Double
        get() = if (auditPixelCount == 0) 0.0 else residualPixelCount.toDouble() / auditPixelCount
}

internal object SegmentationMaskRefiner {
    fun eraseMask(
        probability: TextProbabilityMask,
        core: MaskBounds,
        roi: MaskBounds,
        threshold: Double,
        dilationRadius: Int,
        cancellation: () -> Boolean,
    ): BooleanArray {
        require(probability.pageWidth >= roi.right && probability.pageHeight >= roi.bottom)
        val threshold255 = (threshold * 255.0).roundToInt()
        val seed = BooleanArray(roi.width * roi.height)
        for (pageY in core.top until core.bottom) {
            if (cancellation()) throw CleanupCancellationSignal()
            for (pageX in core.left until core.right) {
                if (probability.probability255At(pageX, pageY) >= threshold255) {
                    seed[(pageY - roi.top) * roi.width + pageX - roi.left] = true
                }
            }
        }
        return BinaryMaskMorphology.dilate(seed, roi.width, roi.height, dilationRadius)
    }

    fun auditMask(
        probability: TextProbabilityMask,
        beforeRoi: IntArray,
        core: MaskBounds,
        roi: MaskBounds,
        background: Int,
        threshold: Double,
        minimumBackgroundDistance: Int,
        cancellation: () -> Boolean,
    ): BooleanArray {
        require(beforeRoi.size == roi.width * roi.height)
        val threshold255 = (threshold * 255.0).roundToInt()
        val minimumDistanceSquared = minimumBackgroundDistance * minimumBackgroundDistance
        return BooleanArray(beforeRoi.size).also { mask ->
            for (pageY in core.top until core.bottom) {
                if (cancellation()) throw CleanupCancellationSignal()
                for (pageX in core.left until core.right) {
                    if (probability.probability255At(pageX, pageY) < threshold255) continue
                    val local = (pageY - roi.top) * roi.width + pageX - roi.left
                    if (colorDistanceSquared(beforeRoi[local], background) >= minimumDistanceSquared) {
                        mask[local] = true
                    }
                }
            }
        }
    }

    fun retryMask(
        eraseMask: BooleanArray,
        auditMask: BooleanArray,
        width: Int,
        height: Int,
        dilationRadius: Int,
    ): BooleanArray {
        require(eraseMask.size == auditMask.size && eraseMask.size == width * height)
        val expandedAudit = BinaryMaskMorphology.dilate(
            auditMask,
            width,
            height,
            dilationRadius,
        )
        return BooleanArray(eraseMask.size) { eraseMask[it] || expandedAudit[it] }
    }

    fun residualRetryMask(
        residualMask: BooleanArray,
        width: Int,
        height: Int,
        dilationRadius: Int,
    ): BooleanArray {
        require(residualMask.size == width * height)
        return BinaryMaskMorphology.dilate(residualMask, width, height, dilationRadius)
    }

    fun auditResidual(
        beforeRoi: IntArray,
        currentPixels: IntArray,
        pageStride: Int,
        roi: MaskBounds,
        auditMask: BooleanArray,
        background: Int,
        maximumUnchangedDistance: Int,
    ): ResidualTextAudit {
        require(beforeRoi.size == roi.width * roi.height && auditMask.size == beforeRoi.size)
        val maximumDistanceSquared = maximumUnchangedDistance * maximumUnchangedDistance
        var auditPixelCount = 0
        var residualPixelCount = 0
        val residualMask = BooleanArray(auditMask.size)
        auditMask.indices.forEach { local ->
            if (!auditMask[local]) return@forEach
            auditPixelCount += 1
            val pageX = roi.left + local % roi.width
            val pageY = roi.top + local / roi.width
            val before = beforeRoi[local]
            val current = currentPixels[pageY * pageStride + pageX]
            val changedEnough = colorDistanceSquared(before, current) > maximumDistanceSquared
            // A tiny dark-to-gray shift used to count as erased even though a
            // Japanese stroke remained plainly visible. Require meaningful
            // progress toward the sampled local background as well as a raw
            // pixel change. Later retries stay best-effort, so this tightens
            // cleanup without blocking the downstream pipeline.
            val beforeBackgroundDistance = colorDistanceSquared(before, background)
            val currentBackgroundDistance = colorDistanceSquared(current, background)
            val closeEnoughToBackground = currentBackgroundDistance.toDouble() <=
                beforeBackgroundDistance * MAXIMUM_REMAINING_BACKGROUND_DISTANCE_RATIO_SQUARED
            if (!changedEnough || !closeEnoughToBackground) {
                residualPixelCount += 1
                residualMask[local] = true
            }
        }
        return ResidualTextAudit(auditPixelCount, residualPixelCount, residualMask)
    }

    private fun colorDistanceSquared(first: Int, second: Int): Int {
        val red = ((first ushr 16) and 0xff) - ((second ushr 16) and 0xff)
        val green = ((first ushr 8) and 0xff) - ((second ushr 8) and 0xff)
        val blue = (first and 0xff) - (second and 0xff)
        return red * red + green * green + blue * blue
    }

    private const val MAXIMUM_REMAINING_BACKGROUND_DISTANCE_RATIO_SQUARED = 0.49
}

internal object BinaryMaskMorphology {
    fun dilate(source: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        require(source.size == width * height)
        if (radius <= 0) return source.copyOf()
        val distance = IntArray(source.size) { if (source[it]) 0 else DISTANCE_INFINITY }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                var value = distance[index]
                if (value == 0) continue
                if (x > 0) value = minOf(value, distance[index - 1] + CHAMFER_ORTHOGONAL)
                if (y > 0) {
                    value = minOf(value, distance[index - width] + CHAMFER_ORTHOGONAL)
                    if (x > 0) value = minOf(value, distance[index - width - 1] + CHAMFER_DIAGONAL)
                    if (x < width - 1) {
                        value = minOf(value, distance[index - width + 1] + CHAMFER_DIAGONAL)
                    }
                }
                distance[index] = value
            }
        }
        val limit = radius * CHAMFER_ORTHOGONAL
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in width - 1 downTo 0) {
                val index = row + x
                var value = distance[index]
                if (value == 0) continue
                if (x < width - 1) value = minOf(value, distance[index + 1] + CHAMFER_ORTHOGONAL)
                if (y < height - 1) {
                    value = minOf(value, distance[index + width] + CHAMFER_ORTHOGONAL)
                    if (x < width - 1) {
                        value = minOf(value, distance[index + width + 1] + CHAMFER_DIAGONAL)
                    }
                    if (x > 0) {
                        value = minOf(value, distance[index + width - 1] + CHAMFER_DIAGONAL)
                    }
                }
                distance[index] = value
            }
        }
        return BooleanArray(source.size) { distance[it] <= limit }
    }

    private const val DISTANCE_INFINITY = Int.MAX_VALUE / 4
    private const val CHAMFER_ORTHOGONAL = 3
    private const val CHAMFER_DIAGONAL = 4
}
