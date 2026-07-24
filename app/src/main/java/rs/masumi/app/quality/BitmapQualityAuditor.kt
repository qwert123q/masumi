package rs.masumi.app.quality

import android.graphics.Bitmap
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.floor
import rs.masumi.core.quality.QualityPixelAudit
import rs.masumi.core.typesetting.PageTypesettingArtifact
import rs.masumi.core.typesetting.TypesettingRegionState

class BitmapQualityAuditor {
    fun audit(
        cleaned: Bitmap,
        flattened: Bitmap,
        typesettingPage: PageTypesettingArtifact,
        cancellation: () -> Boolean,
    ): QualityPixelAudit {
        if (flattened.width != cleaned.width || flattened.height != cleaned.height) {
            return QualityPixelAudit(flattened.width, flattened.height, 0, 0, emptyMap())
        }
        val width = flattened.width
        val height = flattened.height
        val layoutMask = ByteArray(Math.multiplyExact(width, height))
        val regions = typesettingPage.regions.filter { it.state == TypesettingRegionState.TYPESET }
        regions.forEach { region ->
            val box = requireNotNull(region.layoutBox)
            val left = floor(box.left).toInt().coerceIn(0, width)
            val top = floor(box.top).toInt().coerceIn(0, height)
            val right = ceil(box.right).toInt().coerceIn(0, width)
            val bottom = ceil(box.bottom).toInt().coerceIn(0, height)
            for (y in top until bottom) {
                Arrays.fill(layoutMask, y * width + left, y * width + right, 1.toByte())
            }
        }
        val cleanRow = IntArray(width)
        val flatRow = IntArray(width)
        var actualChanged = 0
        var outsideChanged = 0
        for (y in 0 until height) {
            if (y % 64 == 0 && cancellation()) throw QualityCancellationSignal()
            cleaned.getPixels(cleanRow, 0, width, 0, y, width, 1)
            flattened.getPixels(flatRow, 0, width, 0, y, width, 1)
            val rowOffset = y * width
            for (x in 0 until width) {
                if (cleanRow[x] != flatRow[x]) {
                    actualChanged++
                    if (layoutMask[rowOffset + x].toInt() == 0) outsideChanged++
                }
            }
        }
        val byRegion = regions.associate { region ->
            val box = requireNotNull(region.layoutBox)
            val left = floor(box.left).toInt().coerceIn(0, width)
            val top = floor(box.top).toInt().coerceIn(0, height)
            val right = ceil(box.right).toInt().coerceIn(0, width)
            val bottom = ceil(box.bottom).toInt().coerceIn(0, height)
            var changed = 0
            loop@ for (y in top until bottom) {
                if (cancellation()) throw QualityCancellationSignal()
                cleaned.getPixels(cleanRow, 0, width, 0, y, width, 1)
                flattened.getPixels(flatRow, 0, width, 0, y, width, 1)
                for (x in left until right) {
                    if (cleanRow[x] != flatRow[x]) {
                        changed = 1
                        break@loop
                    }
                }
            }
            region.ocrRegionId to changed
        }
        return QualityPixelAudit(width, height, actualChanged, outsideChanged, byRegion)
    }
}
