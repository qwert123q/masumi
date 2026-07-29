package rs.masumi.app.typesetting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import rs.masumi.core.detection.PixelBox
import rs.masumi.core.typesetting.TypesettingDirection
import rs.masumi.core.typesetting.TypesettingPolicy
import rs.masumi.core.typesetting.TypesettingPreserveReason
import rs.masumi.core.typesetting.TypesettingRegionArtifact
import rs.masumi.core.typesetting.TypesettingRegionState
import rs.masumi.core.typesetting.TypesettingStyle
import rs.masumi.core.typesetting.VerticalTypography
import rs.masumi.core.translation.TranslationTextNormalizer

data class TypesettingTarget(
    val translationRegionId: String,
    val ocrRegionId: String,
    val translatedText: String,
    val textBox: PixelBox,
    val bubbleBox: PixelBox? = null,
    val style: TypesettingStyle,
)

data class TypesetPage(
    val bitmap: Bitmap,
    val regions: List<TypesettingRegionArtifact>,
)

class ChineseTypesetter {
    fun render(
        base: Bitmap,
        targets: List<TypesettingTarget>,
        policy: TypesettingPolicy,
        cancellation: () -> Boolean = { false },
    ): TypesetPage {
        require(!base.isRecycled)
        val output = base.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("base bitmap could not be copied")
        val artifacts = mutableListOf<TypesettingRegionArtifact>()
        try {
            targets.forEach { target ->
                if (cancellation()) throw TypesettingCancellationSignal()
                artifacts += renderTarget(output, target, policy, cancellation)
            }
            return TypesetPage(output, artifacts)
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    }

    private fun renderTarget(
        bitmap: Bitmap,
        target: TypesettingTarget,
        policy: TypesettingPolicy,
        cancellation: () -> Boolean,
    ): TypesettingRegionArtifact {
        val text = TranslationTextNormalizer.normalizeTypography(target.translatedText.trim())
        if (text.isBlank()) return target.preserved(TypesettingPreserveReason.BLANK_TEXT)
        val targetBox = (target.bubbleBox ?: target.textBox).toIntBox(bitmap.width, bitmap.height)
            ?: return target.preserved(TypesettingPreserveReason.GEOMETRY_INVALID)
        val layoutBoxes = when (target.style) {
            TypesettingStyle.BUBBLE -> {
                val shortSide = min(targetBox.width, targetBox.height)
                val relaxedInset = (policy.bubbleInsetFraction + policy.minimumBubbleInsetFraction) / 2.0
                listOf(policy.bubbleInsetFraction, relaxedInset, policy.minimumBubbleInsetFraction)
                    .map { fraction -> (shortSide * fraction).roundToInt() }
                    .distinct()
                    .map { inset -> targetBox.inset(inset) }
                    .filter { it.width > 0 && it.height > 0 }
            }
            TypesettingStyle.FREE_TEXT -> {
                val expandX = (targetBox.width * policy.freeTextExpansionFraction).roundToInt()
                val expandY = (targetBox.height * policy.freeTextExpansionFraction).roundToInt()
                listOf(targetBox.expand(expandX, expandY, bitmap.width, bitmap.height))
            }
        }
        if (layoutBoxes.isEmpty()) return target.preserved(TypesettingPreserveReason.GEOMETRY_INVALID)
        val shortSide = min(bitmap.width, bitmap.height).toFloat()
        val minimumSize = max(
            policy.minimumFontSizePixels.toFloat(),
            shortSide * policy.minimumFontSizePageFraction.toFloat(),
        )
        val pageMaximumSize = max(
            minimumSize,
            (shortSide * policy.maximumFontSizePageFraction).toFloat(),
        )
        val requestedWeight = if (target.style == TypesettingStyle.FREE_TEXT) {
            max(policy.fontWeight, FREE_TEXT_MINIMUM_FONT_WEIGHT)
        } else {
            policy.fontWeight
        }
        val typeface = Typeface.create(
            policy.fontFamily,
            if (requestedWeight >= 600) Typeface.BOLD else Typeface.NORMAL,
        )
        val selection = layoutBoxes.firstNotNullOfOrNull { layoutBox ->
            val maximumSize = if (target.style == TypesettingStyle.FREE_TEXT) {
                max(
                    pageMaximumSize,
                    min(targetBox.width, targetBox.height) * FREE_TEXT_MAXIMUM_BOX_FRACTION,
                )
            } else {
                pageMaximumSize
            }
            val direction = layoutBox.direction(policy)
            val plan = when (direction) {
                TypesettingDirection.HORIZONTAL_LTR -> fitHorizontal(
                    text,
                    layoutBox,
                    minimumSize,
                    maximumSize,
                    policy,
                    typeface,
                )
                TypesettingDirection.VERTICAL_RTL -> fitVertical(
                    VerticalTypography.normalize(text),
                    layoutBox,
                    minimumSize,
                    maximumSize,
                    policy,
                    typeface,
                )
            }
            plan?.let { LayoutSelection(layoutBox, direction, it) }
        } ?: layoutBoxes.last().let { smallestMarginBox ->
            return target.preserved(
                TypesettingPreserveReason.TEXT_DOES_NOT_FIT,
                layoutBox = smallestMarginBox.toPixelBox(),
                style = target.style,
                direction = smallestMarginBox.direction(policy),
            )
        }
        val layoutBox = selection.box
        val direction = selection.direction
        val plan = selection.plan
        val colors = resolveColors(bitmap, layoutBox, target.style)
        if (cancellation()) throw TypesettingCancellationSignal()
        val before = bitmap.readPixels(layoutBox)
        return try {
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.clipRect(layoutBox.left, layoutBox.top, layoutBox.right, layoutBox.bottom)
            when (plan) {
                is HorizontalPlan -> drawHorizontal(canvas, layoutBox, plan, policy, colors)
                is VerticalPlan -> drawVertical(canvas, layoutBox, plan, policy, colors)
            }
            canvas.restore()
            val changed = bitmap.countChangedPixels(layoutBox, before)
            if (changed == 0) {
                bitmap.writePixels(layoutBox, before)
                target.preserved(
                    TypesettingPreserveReason.RENDER_FAILED,
                    layoutBox.toPixelBox(),
                    target.style,
                    direction,
                )
            } else {
                TypesettingRegionArtifact(
                    translationRegionId = target.translationRegionId,
                    ocrRegionId = target.ocrRegionId,
                    targetBox = (target.bubbleBox ?: target.textBox),
                    layoutBox = layoutBox.toPixelBox(),
                    style = target.style,
                    direction = direction,
                    fontSizePx = plan.fontSize.toDouble(),
                    lineOrColumnCount = plan.count,
                    changedPixelCount = changed,
                    state = TypesettingRegionState.TYPESET,
                )
            }
        } catch (failure: Throwable) {
            bitmap.writePixels(layoutBox, before)
            if (failure is TypesettingCancellationSignal) throw failure
            target.preserved(
                TypesettingPreserveReason.RENDER_FAILED,
                layoutBox.toPixelBox(),
                target.style,
                direction,
            )
        }
    }

    private fun fitHorizontal(
        text: String,
        box: IntBox,
        minimumSize: Float,
        maximumSize: Float,
        policy: TypesettingPolicy,
        typeface: Typeface,
    ): HorizontalPlan? {
        fun plan(size: Float): HorizontalPlan? {
            val paint = createTextPaint(size, typeface, Color.BLACK, policy)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, box.width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(size * policy.lineSpacingEm.toFloat(), 1f)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            val complete = layout.lineCount > 0 && layout.getLineEnd(layout.lineCount - 1) >= text.length
            return if (complete && layout.height <= box.height) HorizontalPlan(size, layout) else null
        }
        if (plan(minimumSize) == null) return null
        var low = minimumSize
        var high = maximumSize
        var best = requireNotNull(plan(minimumSize))
        repeat(BINARY_SEARCH_STEPS) {
            val mid = (low + high) / 2f
            val candidate = plan(mid)
            if (candidate != null) {
                best = candidate
                low = mid
            } else {
                high = mid
            }
        }
        return best
    }

    private fun fitVertical(
        text: String,
        box: IntBox,
        minimumSize: Float,
        maximumSize: Float,
        policy: TypesettingPolicy,
        typeface: Typeface,
    ): VerticalPlan? {
        fun plan(size: Float): VerticalPlan? {
            val cell = size * (1f + policy.lineSpacingEm.toFloat())
            val rows = floor(box.height / cell).toInt()
            if (rows <= 0) return null
            val columns = buildVerticalColumns(text, rows)
            if (columns.isEmpty() || columns.maxOf(List<String>::size) > rows) return null
            if (columns.size * cell > box.width) return null
            return VerticalPlan(size, cell, columns, typeface)
        }
        if (plan(minimumSize) == null) return null
        var low = minimumSize
        var high = maximumSize
        var best = requireNotNull(plan(minimumSize))
        repeat(BINARY_SEARCH_STEPS) {
            val mid = (low + high) / 2f
            val candidate = plan(mid)
            if (candidate != null) {
                best = candidate
                low = mid
            } else {
                high = mid
            }
        }
        return best
    }

    private fun buildVerticalColumns(text: String, maximumRows: Int): List<List<String>> {
        val columns = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        fun flush() {
            if (current.isNotEmpty()) columns += current
            current = mutableListOf()
        }
        text.codePoints().toArray().forEach { codePoint ->
            val glyph = String(Character.toChars(codePoint))
            if (glyph == "\n") {
                flush()
            } else if (glyph.isNotBlank()) {
                if (current.size == maximumRows) flush()
                current += glyph
            }
        }
        flush()
        for (index in 1 until columns.size) {
            if (columns[index].firstOrNull() in VERTICAL_CLOSING_PUNCTUATION && columns[index - 1].size > 1) {
                columns[index].add(0, columns[index - 1].removeAt(columns[index - 1].lastIndex))
            }
        }
        return columns
    }

    private fun drawHorizontal(
        canvas: Canvas,
        box: IntBox,
        plan: HorizontalPlan,
        policy: TypesettingPolicy,
        colors: TextColors,
    ) {
        val top = box.top + (box.height - plan.layout.height) / 2f
        canvas.save()
        canvas.translate(box.left.toFloat(), top)
        val paint = plan.layout.paint
        if (colors.stroke != null && policy.freeTextStrokeEm > 0.0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = plan.fontSize * policy.freeTextStrokeEm.toFloat()
            paint.color = colors.stroke
            paint.strokeJoin = Paint.Join.ROUND
            plan.layout.draw(canvas)
        }
        paint.style = Paint.Style.FILL
        paint.color = colors.fill
        plan.layout.draw(canvas)
        canvas.restore()
    }

    private fun drawVertical(
        canvas: Canvas,
        box: IntBox,
        plan: VerticalPlan,
        policy: TypesettingPolicy,
        colors: TextColors,
    ) {
        val totalWidth = plan.columns.size * plan.cellSize
        val blockRight = box.left + (box.width + totalWidth) / 2f
        val blockHeight = plan.columns.maxOf(List<String>::size) * plan.cellSize
        val blockTop = box.top + (box.height - blockHeight) / 2f
        val paint = createTextPaint(plan.fontSize, plan.typeface, colors.fill, policy)
        val metrics = paint.fontMetrics
        val glyphBaselineOffset = (plan.cellSize - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        plan.columns.forEachIndexed { columnIndex, column ->
            val left = blockRight - (columnIndex + 1) * plan.cellSize
            column.forEachIndexed { rowIndex, glyph ->
                val centerX = left + plan.cellSize / 2f
                val baseline = blockTop + rowIndex * plan.cellSize + glyphBaselineOffset
                if (colors.stroke != null && policy.freeTextStrokeEm > 0.0) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = plan.fontSize * policy.freeTextStrokeEm.toFloat()
                    paint.color = colors.stroke
                    paint.strokeJoin = Paint.Join.ROUND
                    canvas.drawText(glyph, centerX - paint.measureText(glyph) / 2f, baseline, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = colors.fill
                canvas.drawText(glyph, centerX - paint.measureText(glyph) / 2f, baseline, paint)
            }
        }
    }

    private fun createTextPaint(
        size: Float,
        typeface: Typeface,
        color: Int,
        policy: TypesettingPolicy,
    ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = size
        this.typeface = typeface
        this.color = color
        textAlign = Paint.Align.LEFT
        letterSpacing = policy.letterSpacingEm.toFloat()
    }

    private fun resolveColors(bitmap: Bitmap, box: IntBox, style: TypesettingStyle): TextColors {
        val pixels = bitmap.readPixels(box)
        val luminance = pixels.asSequence().map { color ->
            (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
        }.average()
        // Bubble interiors are uniform after cleanup, so no outline is needed —
        // but the interior is not always light: a reverse-video balloon keeps
        // its dark fill, and black ink on it would be invisible.
        if (style == TypesettingStyle.BUBBLE) {
            return if (luminance >= 0.52) TextColors(Color.BLACK, null) else TextColors(Color.WHITE, null)
        }
        return if (luminance >= 0.52) {
            TextColors(Color.BLACK, Color.WHITE)
        } else {
            TextColors(Color.WHITE, Color.BLACK)
        }
    }

    private fun PixelBox.toIntBox(width: Int, height: Int): IntBox? {
        val left = floor(left).toInt().coerceIn(0, width)
        val top = floor(top).toInt().coerceIn(0, height)
        val right = ceil(right).toInt().coerceIn(0, width)
        val bottom = ceil(bottom).toInt().coerceIn(0, height)
        return if (right > left && bottom > top) IntBox(left, top, right, bottom) else null
    }

    private fun Bitmap.readPixels(box: IntBox): IntArray = IntArray(box.width * box.height).also {
        getPixels(it, 0, box.width, box.left, box.top, box.width, box.height)
    }

    private fun Bitmap.writePixels(box: IntBox, pixels: IntArray) {
        setPixels(pixels, 0, box.width, box.left, box.top, box.width, box.height)
    }

    private fun Bitmap.countChangedPixels(box: IntBox, before: IntArray): Int {
        val after = readPixels(box)
        return after.indices.count { after[it] != before[it] }
    }

    private fun TypesettingTarget.preserved(
        reason: TypesettingPreserveReason,
        layoutBox: PixelBox? = null,
        style: TypesettingStyle? = null,
        direction: TypesettingDirection? = null,
    ): TypesettingRegionArtifact = TypesettingRegionArtifact(
        translationRegionId = translationRegionId,
        ocrRegionId = ocrRegionId,
        targetBox = bubbleBox ?: textBox,
        layoutBox = layoutBox,
        style = style,
        direction = direction,
        state = TypesettingRegionState.PRESERVED_CLEANED_PAGE,
        preserveReason = reason,
    )

    private data class TextColors(val fill: Int, val stroke: Int?)

    private sealed interface LayoutPlan {
        val fontSize: Float
        val count: Int
    }

    private data class HorizontalPlan(
        override val fontSize: Float,
        val layout: StaticLayout,
    ) : LayoutPlan {
        override val count: Int get() = layout.lineCount
    }

    private data class VerticalPlan(
        override val fontSize: Float,
        val cellSize: Float,
        val columns: List<List<String>>,
        val typeface: Typeface,
    ) : LayoutPlan {
        override val count: Int get() = columns.size
    }

    private data class LayoutSelection(
        val box: IntBox,
        val direction: TypesettingDirection,
        val plan: LayoutPlan,
    )

    private data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        fun inset(amount: Int): IntBox = IntBox(left + amount, top + amount, right - amount, bottom - amount)

        fun direction(policy: TypesettingPolicy): TypesettingDirection =
            if (height >= width * policy.verticalAspectThreshold) {
                TypesettingDirection.VERTICAL_RTL
            } else {
                TypesettingDirection.HORIZONTAL_LTR
            }

        fun expand(horizontal: Int, vertical: Int, maximumWidth: Int, maximumHeight: Int): IntBox = IntBox(
            (left - horizontal).coerceAtLeast(0),
            (top - vertical).coerceAtLeast(0),
            (right + horizontal).coerceAtMost(maximumWidth),
            (bottom + vertical).coerceAtMost(maximumHeight),
        )

        fun toPixelBox(): PixelBox = PixelBox(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble())
    }

    private companion object {
        const val BINARY_SEARCH_STEPS = 12
        const val FREE_TEXT_MINIMUM_FONT_WEIGHT = 600
        const val FREE_TEXT_MAXIMUM_BOX_FRACTION = 0.52f
        val VERTICAL_CLOSING_PUNCTUATION = setOf("︑", "︒", "︐", "︓", "︔", "︕", "︖", "︶", "︼", "﹂", "﹄")
    }
}

class TypesettingCancellationSignal : RuntimeException()
