package rs.masumi.app.typesetting

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.typesetting.TypesettingDirection
import rs.masumi.core.typesetting.TypesettingPolicy
import rs.masumi.core.typesetting.TypesettingPreserveReason
import rs.masumi.core.typesetting.TypesettingRegionState
import rs.masumi.core.typesetting.TypesettingStyle

@RunWith(AndroidJUnit4::class)
class ChineseTypesetterTest {
    @Test
    fun wideBubbleUsesCenteredHorizontalTextInsideTheLayoutBox() {
        val base = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val result = ChineseTypesetter().render(
            base,
            listOf(target("今天一起去看海吧", PixelBox(30.0, 40.0, 270.0, 150.0))),
            TypesettingPolicy(),
        )
        try {
            val region = result.regions.single()
            assertEquals(TypesettingRegionState.TYPESET, region.state)
            assertEquals(TypesettingDirection.HORIZONTAL_LTR, region.direction)
            assertTrue(region.fontSizePx!! > 0.0)
            assertTrue(region.changedPixelCount > 0)
            val layout = requireNotNull(region.layoutBox)
            for (y in 0 until result.bitmap.height) {
                for (x in 0 until result.bitmap.width) {
                    val outside = x < layout.left || x >= layout.right || y < layout.top || y >= layout.bottom
                    if (outside) assertEquals(Color.WHITE, result.bitmap.getPixel(x, y))
                }
            }
        } finally {
            result.bitmap.recycle()
            base.recycle()
        }
    }

    @Test
    fun tallBubbleUsesRightToLeftVerticalColumnsAndVerticalPunctuation() {
        val base = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val result = ChineseTypesetter().render(
            base,
            listOf(target("今天，一起去吧！", PixelBox(80.0, 30.0, 220.0, 450.0))),
            TypesettingPolicy(),
        )
        try {
            val region = result.regions.single()
            assertEquals(TypesettingRegionState.TYPESET, region.state)
            assertEquals(TypesettingDirection.VERTICAL_RTL, region.direction)
            assertTrue(region.lineOrColumnCount >= 1)
            val layout = requireNotNull(region.layoutBox)
            for (y in 0 until result.bitmap.height) {
                for (x in 0 until result.bitmap.width) {
                    val outside = x < layout.left || x >= layout.right || y < layout.top || y >= layout.bottom
                    if (outside) assertEquals(Color.WHITE, result.bitmap.getPixel(x, y))
                }
            }
        } finally {
            result.bitmap.recycle()
            base.recycle()
        }
    }

    @Test
    fun unreadablySmallLayoutPreservesEveryBasePixel() {
        val base = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(240, 240, 240)) }
        val before = IntArray(base.width * base.height).also {
            base.getPixels(it, 0, base.width, 0, 0, base.width, base.height)
        }
        val result = ChineseTypesetter().render(
            base,
            listOf(target("这是一段绝对无法放进如此狭小区域的完整中文译文", PixelBox(90.0, 140.0, 110.0, 160.0))),
            TypesettingPolicy(),
        )
        try {
            val region = result.regions.single()
            assertEquals(TypesettingRegionState.PRESERVED_CLEANED_PAGE, region.state)
            assertEquals(TypesettingPreserveReason.TEXT_DOES_NOT_FIT, region.preserveReason)
            val after = IntArray(result.bitmap.width * result.bitmap.height).also {
                result.bitmap.getPixels(it, 0, result.bitmap.width, 0, 0, result.bitmap.width, result.bitmap.height)
            }
            assertTrue(before.contentEquals(after))
        } finally {
            result.bitmap.recycle()
            base.recycle()
        }
    }

    @Test
    fun longBubbleTextRelaxesMarginBeforeRejectingReadableText() {
        val base = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bubble = PixelBox(120.0, 180.0, 180.0, 280.0)
        val result = ChineseTypesetter().render(
            base,
            listOf(target("这是用于验证较长译文会先减少留白而不是缩成难以阅读的小字", bubble)),
            TypesettingPolicy(),
        )
        try {
            val region = result.regions.single()
            assertEquals(TypesettingRegionState.TYPESET, region.state)
            val layout = requireNotNull(region.layoutBox)
            val preferredInset = (bubble.right - bubble.left) * TypesettingPolicy().bubbleInsetFraction
            assertTrue(layout.left - bubble.left < preferredInset)
            assertTrue(region.fontSizePx!! >= TypesettingPolicy().minimumFontSizePixels)
        } finally {
            result.bitmap.recycle()
            base.recycle()
        }
    }

    private fun target(text: String, bubble: PixelBox): TypesettingTarget = TypesettingTarget(
        translationRegionId = "a".repeat(64),
        ocrRegionId = "b".repeat(64),
        translatedText = text,
        textBox = bubble,
        bubbleBox = bubble,
        style = TypesettingStyle.BUBBLE,
    )
}
