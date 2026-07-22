package rs.masumi.app.cleanup

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupPreserveReason
import rs.masumi.core.cleanup.CleanupRegionArtifact
import rs.masumi.core.cleanup.CleanupRegionState
import rs.masumi.core.cleanup.CleanupStrategy
import rs.masumi.core.detection.PixelBox

data class CleanupTarget(
    val translationRegionId: String,
    val ocrRegionId: String,
    val box: PixelBox,
    val strategy: CleanupStrategy,
)

data class CleanedPage(
    val bitmap: Bitmap,
    val regions: List<CleanupRegionArtifact>,
)

class SourceCleanupEngine {
    fun clean(
        source: Bitmap,
        targets: List<CleanupTarget>,
        policy: CleanupPolicy,
        cancellation: () -> Boolean = { false },
        recycleSourceAfterCopy: Boolean = false,
    ): CleanedPage {
        require(!source.isRecycled)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("source bitmap could not be copied")
        if (recycleSourceAfterCopy) source.recycle()
        try {
            val pixels = IntArray(Math.multiplyExact(output.width, output.height))
            output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            val artifacts = mutableListOf<CleanupRegionArtifact>()
            targets.forEach { target ->
                if (cancellation()) throw CleanupCancellationSignal()
                artifacts += cleanTarget(pixels, output.width, output.height, target, policy, cancellation)
            }
            output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            return CleanedPage(output, artifacts)
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    }

    private fun cleanTarget(
        pixels: IntArray,
        width: Int,
        height: Int,
        target: CleanupTarget,
        policy: CleanupPolicy,
        cancellation: () -> Boolean,
    ): CleanupRegionArtifact {
        val core = target.box.toIntBox(width, height)
            ?: return target.preserved(CleanupPreserveReason.MASK_EMPTY)
        val padding = max(
            policy.minimumPaddingPixels,
            (max(core.width, core.height) * policy.boxPaddingFraction).roundToInt(),
        )
        val roi = core.expand(padding, width, height)
        val roiPixelCount = roi.width * roi.height
        if (roiPixelCount <= 0) return target.preserved(CleanupPreserveReason.MASK_EMPTY)
        val background = estimatePerimeterMedian(pixels, width, roi)
        val mask = BooleanArray(roiPixelCount)
        for (y in core.top until core.bottom) {
            if (cancellation()) throw CleanupCancellationSignal()
            for (x in core.left until core.right) {
                val local = (y - roi.top) * roi.width + (x - roi.left)
                val color = pixels[y * width + x]
                if (colorDistance(color, background) >= policy.colorDistanceThreshold) mask[local] = true
            }
        }
        val dilated = dilate(mask, roi.width, roi.height, policy.dilationRadiusPixels)
        val maskCount = dilated.count { it }
        val coverage = maskCount.toDouble() / roiPixelCount
        if (maskCount == 0 || coverage < policy.minimumMaskCoverage) {
            return target.preserved(CleanupPreserveReason.MASK_EMPTY, roiPixelCount, maskCount)
        }
        if (coverage > policy.maximumMaskCoverage) {
            return target.preserved(CleanupPreserveReason.MASK_UNSAFE, roiPixelCount, maskCount)
        }
        val maskedLocals = IntArray(maskCount)
        val before = IntArray(maskCount)
        var maskedIndex = 0
        for (local in dilated.indices) {
            if (!dilated[local]) continue
            val x = roi.left + local % roi.width
            val y = roi.top + local / roi.width
            maskedLocals[maskedIndex] = local
            before[maskedIndex] = pixels[y * width + x]
            maskedIndex += 1
        }
        when (target.strategy) {
            CleanupStrategy.FLAT_LOCAL_FILL -> fillFlat(pixels, width, roi, dilated, background)
            CleanupStrategy.LOCAL_BOUNDARY_INPAINT -> inpaintBoundary(pixels, width, roi, dilated, cancellation)
        }
        var changed = 0
        for (index in maskedLocals.indices) {
            val local = maskedLocals[index]
            val x = roi.left + local % roi.width
            val y = roi.top + local / roi.width
            if (pixels[y * width + x] != before[index]) changed += 1
        }
        if (changed == 0) return target.preserved(CleanupPreserveReason.ENGINE_FAILED, roiPixelCount, maskCount)
        return CleanupRegionArtifact(
            translationRegionId = target.translationRegionId,
            ocrRegionId = target.ocrRegionId,
            box = target.box,
            strategy = target.strategy,
            state = CleanupRegionState.CLEANED,
            roiPixelCount = roiPixelCount,
            maskPixelCount = maskCount,
            changedPixelCount = changed,
        )
    }

    private fun fillFlat(
        pixels: IntArray,
        stride: Int,
        roi: IntBox,
        mask: BooleanArray,
        background: Int,
    ) {
        mask.indices.forEach { local ->
            if (!mask[local]) return@forEach
            val x = roi.left + local % roi.width
            val y = roi.top + local / roi.width
            pixels[y * stride + x] = background
        }
    }

    private fun inpaintBoundary(
        pixels: IntArray,
        stride: Int,
        roi: IntBox,
        mask: BooleanArray,
        cancellation: () -> Boolean,
    ) {
        val unknown = mask.copyOf()
        val queued = BooleanArray(unknown.size)
        var current = IntArray(unknown.size)
        var next = IntArray(unknown.size)
        var currentSize = 0
        for (local in unknown.indices) {
            if (unknown[local] && hasKnownNeighbor(local, unknown, roi.width, roi.height)) {
                current[currentSize++] = local
                queued[local] = true
            }
        }
        val resolvedLocals = IntArray(unknown.size)
        val resolvedColors = IntArray(unknown.size)
        while (currentSize > 0) {
            if (cancellation()) throw CleanupCancellationSignal()
            var resolvedSize = 0
            for (index in 0 until currentSize) {
                val local = current[index]
                queued[local] = false
                if (!unknown[local]) continue
                averageKnownNeighbors(local, unknown, pixels, stride, roi)?.let { color ->
                    resolvedLocals[resolvedSize] = local
                    resolvedColors[resolvedSize] = color
                    resolvedSize += 1
                }
            }
            if (resolvedSize == 0) break
            for (index in 0 until resolvedSize) {
                val local = resolvedLocals[index]
                val x = roi.left + local % roi.width
                val y = roi.top + local / roi.width
                pixels[y * stride + x] = resolvedColors[index]
                unknown[local] = false
            }
            var nextSize = 0
            for (index in 0 until resolvedSize) {
                val local = resolvedLocals[index]
                forEachNeighbor(local, roi.width, roi.height) { neighbor ->
                    if (unknown[neighbor] && !queued[neighbor]) {
                        next[nextSize++] = neighbor
                        queued[neighbor] = true
                    }
                }
            }
            val swap = current
            current = next
            next = swap
            currentSize = nextSize
        }
        if (unknown.any { it }) {
            val fallback = estimatePerimeterMedian(pixels, stride, roi)
            fillFlat(pixels, stride, roi, unknown, fallback)
        }
    }

    private fun averageKnownNeighbors(
        local: Int,
        unknown: BooleanArray,
        pixels: IntArray,
        stride: Int,
        roi: IntBox,
    ): Int? {
        var knownCount = 0
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        forEachNeighbor(local, roi.width, roi.height) { neighbor ->
            if (unknown[neighbor]) return@forEachNeighbor
            val x = roi.left + neighbor % roi.width
            val y = roi.top + neighbor / roi.width
            val color = pixels[y * stride + x]
            knownCount += 1
            alpha += Color.alpha(color)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
        }
        if (knownCount < 2) return null
        return Color.argb(alpha / knownCount, red / knownCount, green / knownCount, blue / knownCount)
    }

    private fun hasKnownNeighbor(local: Int, unknown: BooleanArray, width: Int, height: Int): Boolean {
        var found = false
        forEachNeighbor(local, width, height) { neighbor ->
            if (!unknown[neighbor]) found = true
        }
        return found
    }

    private inline fun forEachNeighbor(local: Int, width: Int, height: Int, action: (Int) -> Unit) {
        val x = local % width
        val y = local / width
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until width && ny in 0 until height) action(ny * width + nx)
        }
    }

    private fun dilate(source: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return source
        val output = source.copyOf()
        for (local in source.indices) {
            if (!source[local]) continue
            val x = local % width
            val y = local / width
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (dx * dx + dy * dy > radius * radius) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until height) output[ny * width + nx] = true
            }
        }
        return output
    }

    private fun estimatePerimeterMedian(pixels: IntArray, stride: Int, box: IntBox): Int {
        val colors = mutableListOf<Int>()
        for (x in box.left until box.right) {
            colors += pixels[box.top * stride + x]
            if (box.bottom - 1 != box.top) colors += pixels[(box.bottom - 1) * stride + x]
        }
        for (y in box.top + 1 until box.bottom - 1) {
            colors += pixels[y * stride + box.left]
            if (box.right - 1 != box.left) colors += pixels[y * stride + box.right - 1]
        }
        if (colors.isEmpty()) return Color.WHITE
        fun median(component: (Int) -> Int): Int = colors.map(component).sorted()[colors.size / 2]
        return Color.argb(median(Color::alpha), median(Color::red), median(Color::green), median(Color::blue))
    }

    private fun colorDistance(first: Int, second: Int): Int {
        val red = Color.red(first) - Color.red(second)
        val green = Color.green(first) - Color.green(second)
        val blue = Color.blue(first) - Color.blue(second)
        return sqrt((red * red + green * green + blue * blue).toDouble()).roundToInt()
    }

    private fun PixelBox.toIntBox(width: Int, height: Int): IntBox? {
        val left = floor(left).toInt().coerceIn(0, width)
        val top = floor(top).toInt().coerceIn(0, height)
        val right = ceil(right).toInt().coerceIn(0, width)
        val bottom = ceil(bottom).toInt().coerceIn(0, height)
        if (right <= left || bottom <= top) return null
        return IntBox(left, top, right, bottom)
    }

    private fun CleanupTarget.preserved(
        reason: CleanupPreserveReason,
        roiPixelCount: Int = 0,
        maskPixelCount: Int = 0,
    ) = CleanupRegionArtifact(
        translationRegionId = translationRegionId,
        ocrRegionId = ocrRegionId,
        box = box,
        strategy = strategy,
        state = CleanupRegionState.PRESERVED_SOURCE,
        preserveReason = reason,
        roiPixelCount = roiPixelCount,
        maskPixelCount = maskPixelCount,
    )

    private data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        fun expand(padding: Int, width: Int, height: Int) = IntBox(
            (left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0),
            (right + padding).coerceAtMost(width),
            (bottom + padding).coerceAtMost(height),
        )
    }
}

class CleanupCancellationSignal : RuntimeException()
