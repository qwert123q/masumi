package rs.masumi.app.cleanup

import kotlin.math.abs

internal data class InpaintTile(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Splits a long text mask at its sparsest rows or columns. AOT then sees each
 * short run at close to source resolution instead of shrinking a page-height
 * detector box into one 512 px input and hallucinating large parts of the art.
 */
internal object InpaintTilePlanner {
    fun plan(
        width: Int,
        height: Int,
        mask: BooleanArray,
        maximumSpan: Int,
        minimumSpan: Int,
        cutSearchRadius: Int,
    ): List<InpaintTile> {
        require(width > 0 && height > 0 && mask.size == width * height)
        val pending = ArrayDeque<InpaintTile>()
        val result = mutableListOf<InpaintTile>()
        pending += InpaintTile(0, 0, width, height)
        while (pending.isNotEmpty()) {
            val tile = pending.removeFirst()
            val splitVertically = tile.height >= tile.width && tile.height > maximumSpan
            val splitHorizontally = tile.width > tile.height && tile.width > maximumSpan
            if (!splitVertically && !splitHorizontally) {
                if (tile.containsMask(mask, width)) result += tile
                continue
            }
            val cut = if (splitVertically) {
                bestHorizontalCut(tile, mask, width, minimumSpan, cutSearchRadius)
            } else {
                bestVerticalCut(tile, mask, width, minimumSpan, cutSearchRadius)
            }
            if (cut == null) {
                if (tile.containsMask(mask, width)) result += tile
            } else if (splitVertically) {
                pending += InpaintTile(tile.left, tile.top, tile.right, cut)
                pending += InpaintTile(tile.left, cut, tile.right, tile.bottom)
            } else {
                pending += InpaintTile(tile.left, tile.top, cut, tile.bottom)
                pending += InpaintTile(cut, tile.top, tile.right, tile.bottom)
            }
        }
        return result.sortedWith(compareBy(InpaintTile::top, InpaintTile::left))
    }

    private fun bestHorizontalCut(
        tile: InpaintTile,
        mask: BooleanArray,
        stride: Int,
        minimumSpan: Int,
        searchRadius: Int,
    ): Int? {
        val minimum = tile.top + minimumSpan
        val maximum = tile.bottom - minimumSpan
        if (minimum > maximum) return null
        val midpoint = (tile.top + tile.bottom) / 2
        val start = (midpoint - searchRadius).coerceAtLeast(minimum)
        val end = (midpoint + searchRadius).coerceAtMost(maximum)
        return (start..end).minWithOrNull(
            compareBy<Int> { y ->
                (tile.left until tile.right).count { x -> mask[y * stride + x] }
            }.thenBy { y -> abs(y - midpoint) },
        )
    }

    private fun bestVerticalCut(
        tile: InpaintTile,
        mask: BooleanArray,
        stride: Int,
        minimumSpan: Int,
        searchRadius: Int,
    ): Int? {
        val minimum = tile.left + minimumSpan
        val maximum = tile.right - minimumSpan
        if (minimum > maximum) return null
        val midpoint = (tile.left + tile.right) / 2
        val start = (midpoint - searchRadius).coerceAtLeast(minimum)
        val end = (midpoint + searchRadius).coerceAtMost(maximum)
        return (start..end).minWithOrNull(
            compareBy<Int> { x ->
                (tile.top until tile.bottom).count { y -> mask[y * stride + x] }
            }.thenBy { x -> abs(x - midpoint) },
        )
    }

    private fun InpaintTile.containsMask(mask: BooleanArray, stride: Int): Boolean {
        for (y in top until bottom) for (x in left until right) {
            if (mask[y * stride + x]) return true
        }
        return false
    }
}
