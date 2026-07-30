package rs.masumi.app.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.min

/**
 * Webtoon-style continuous vertical reader. Page heights come from a
 * pre-computed aspect list, so the total layout is stable regardless of which
 * bitmaps are currently decoded; only pages intersecting the viewport are
 * drawn and only a small window around the viewport is kept decoded.
 */
class ContinuousReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    /** height/width of every page, in reading order. */
    private var aspects: List<Float> = emptyList()
    private var pageBitmap: (Int) -> Bitmap? = { null }

    var onVisiblePageChanged: ((Int) -> Unit)? = null
    var onNeedPage: ((Int) -> Unit)? = null
    var onTap: ((Float) -> Unit)? = null

    private val scroller = OverScroller(context)
    private var scrollOffset = 0f
    // Most comic pages are light; a white loading sheet avoids a full-screen
    // black flash when the user outruns decoding during a fast fling.
    private val placeholderPaint = Paint().apply { color = Color.WHITE }
    private val dividerPaint = Paint().apply { color = Color.BLACK }
    private val bitmapPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
    )
    private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(154, 158, 166)
        textAlign = Paint.Align.CENTER
        textSize = 16f * resources.displayMetrics.scaledDensity
    }
    private val sourceRect = Rect()
    private val targetRect = RectF()
    private var lastReportedPage = -1

    internal fun usesFilteredBitmapSampling(): Boolean = bitmapPaint.isFilterBitmap

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onScroll(
                down: MotionEvent?,
                event: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                scrollBy(distanceY)
                return true
            }

            override fun onFling(
                down: MotionEvent?,
                event: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                scroller.forceFinished(true)
                scroller.fling(
                    0,
                    scrollOffset.toInt(),
                    0,
                    -velocityY.toInt(),
                    0,
                    0,
                    0,
                    maximumScroll().toInt(),
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (width > 0) onTap?.invoke(event.x / width)
                return true
            }
        },
    )

    fun bind(pageAspects: List<Float>, bitmapProvider: (Int) -> Bitmap?) {
        aspects = pageAspects.toList()
        pageBitmap = bitmapProvider
        scrollOffset = scrollOffset.coerceIn(0f, maximumScroll())
        lastReportedPage = -1
        invalidate()
    }

    fun updatePageAspect(index: Int, aspect: Float) {
        if (index !in aspects.indices || !aspect.isFinite() || aspect <= 0f) return
        val oldAspect = aspects[index]
        if (oldAspect == aspect) return
        val anchor = pageAt(scrollOffset)
        val offsetInsideAnchor = scrollOffset - pageTop(anchor)
        aspects = aspects.toMutableList().also { it[index] = aspect }
        scrollOffset = (pageTop(anchor) + offsetInsideAnchor).coerceIn(0f, maximumScroll())
        invalidate()
    }

    fun scrollToPage(index: Int) {
        if (width <= 0) {
            post { scrollToPage(index) }
            return
        }
        scroller.forceFinished(true)
        scrollOffset = pageTop(index.coerceIn(0, aspects.lastIndex.coerceAtLeast(0)))
            .coerceIn(0f, maximumScroll())
        invalidate()
    }

    fun firstVisiblePage(): Int = pageAt(scrollOffset)

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat().coerceIn(0f, maximumScroll())
            postInvalidateOnAnimation()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || aspects.isEmpty()) return
        val viewportTop = scrollOffset
        val viewportBottom = scrollOffset + height
        var top = 0f
        var firstVisible = -1
        for (index in aspects.indices) {
            val aspect = aspects[index]
            val pageHeight = width * aspect
            val bottom = top + pageHeight
            if (bottom > viewportTop && top < viewportBottom) {
                if (firstVisible < 0) firstVisible = index
                val bitmap = pageBitmap(index)
                targetRect.set(0f, top - viewportTop, width.toFloat(), bottom - viewportTop)
                if (bitmap != null && !bitmap.isRecycled) {
                    sourceRect.set(0, 0, bitmap.width, bitmap.height)
                    canvas.drawBitmap(bitmap, sourceRect, targetRect, bitmapPaint)
                } else {
                    canvas.drawRect(targetRect, placeholderPaint)
                    canvas.drawText(
                        resources.getString(rs.masumi.app.R.string.reader_page_loading, index + 1),
                        targetRect.centerX(),
                        targetRect.centerY(),
                        loadingPaint,
                    )
                    onNeedPage?.invoke(index)
                }
                canvas.drawRect(0f, targetRect.bottom, width.toFloat(), targetRect.bottom + 2f, dividerPaint)
            }
            top = bottom
            if (top > viewportBottom && firstVisible >= 0) break
        }
        if (firstVisible >= 0) {
            onNeedPage?.invoke(firstVisible)
            onNeedPage?.invoke(firstVisible + 1)
            onNeedPage?.invoke(firstVisible - 1)
            if (firstVisible != lastReportedPage) {
                lastReportedPage = firstVisible
                onVisiblePageChanged?.invoke(firstVisible)
            }
        }
    }

    private fun scrollBy(deltaY: Float) {
        scrollOffset = (scrollOffset + deltaY).coerceIn(0f, maximumScroll())
        invalidate()
    }

    private fun totalHeight(): Float {
        if (width <= 0) return 0f
        var total = 0f
        aspects.forEach { total += width * it }
        return total
    }

    private fun maximumScroll(): Float = max(0f, totalHeight() - height)

    private fun pageTop(index: Int): Float {
        var top = 0f
        for (position in 0 until min(index, aspects.size)) top += width * aspects[position]
        return top
    }

    private fun pageAt(offset: Float): Int {
        var top = 0f
        aspects.forEachIndexed { index, aspect ->
            val bottom = top + width * aspect
            if (offset < bottom) return index
            top = bottom
        }
        return aspects.lastIndex.coerceAtLeast(0)
    }
}
