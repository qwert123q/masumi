package rs.masumi.app.cleanup

import kotlin.math.max
import kotlin.math.min

/**
 * Adds the bright stroke surrounding a selected dark display-text glyph to its
 * cleanup mask. Without this pass the inpainter samples the unmasked white
 * stroke as background, replacing the black glyph with a conspicuous white
 * silhouette on full-color artwork.
 *
 * Growth is both color-gated and distance-bounded. A neutral bright stroke is
 * followed only when it is connected to the selected ink; even on a naturally
 * white area the bounded radius prevents the mask from flooding the page.
 */
internal object DisplayTextOutlineMask {
    fun expand(
        seed: BooleanArray,
        pixels: IntArray,
        pageStride: Int,
        roiLeft: Int,
        roiTop: Int,
        roiWidth: Int,
        roiHeight: Int,
        maximumRadius: Int,
    ): BooleanArray {
        require(seed.size == roiWidth * roiHeight)
        if (maximumRadius <= 0 || seed.none { it }) return seed

        val expanded = seed.copyOf()
        val distance = IntArray(seed.size) { UNVISITED }
        val queue = IntArray(seed.size)
        var head = 0
        var tail = 0
        seed.indices.forEach { local ->
            if (!seed[local]) return@forEach
            distance[local] = 0
            queue[tail++] = local
        }

        while (head < tail) {
            val local = queue[head++]
            val nextDistance = distance[local] + 1
            if (nextDistance > maximumRadius) continue
            val x = local % roiWidth
            val y = local / roiWidth
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val neighborX = x + dx
                val neighborY = y + dy
                if (neighborX !in 0 until roiWidth || neighborY !in 0 until roiHeight) continue
                val neighbor = neighborY * roiWidth + neighborX
                if (distance[neighbor] != UNVISITED) continue
                val color = pixels[(roiTop + neighborY) * pageStride + roiLeft + neighborX]
                if (!color.isBrightNeutral()) continue
                distance[neighbor] = nextDistance
                expanded[neighbor] = true
                queue[tail++] = neighbor
            }
        }
        return expanded
    }

    private fun Int.isBrightNeutral(): Boolean {
        val red = this ushr 16 and 0xff
        val green = this ushr 8 and 0xff
        val blue = this and 0xff
        val luminance = (red * 299 + green * 587 + blue * 114) / 1000
        val chroma = max(red, max(green, blue)) - min(red, min(green, blue))
        return luminance >= MINIMUM_OUTLINE_LUMINANCE && chroma <= MAXIMUM_OUTLINE_CHROMA
    }

    private const val UNVISITED = -1
    private const val MINIMUM_OUTLINE_LUMINANCE = 176
    private const val MAXIMUM_OUTLINE_CHROMA = 64
}
