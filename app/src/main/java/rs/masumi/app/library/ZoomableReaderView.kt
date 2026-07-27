package rs.masumi.app.library

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Manga page view with the interaction set readers expect: pinch zoom with
 * focal-point anchoring, drag panning with edge clamping, fling, double-tap
 * zoom toggle, single-tap zones, and horizontal page-turn swipes whenever the
 * page is not zoomed in. Rendering uses a plain ImageView in matrix mode, so
 * existing bitmap plumbing and tests keep working.
 */
class ZoomableReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    enum class SwipeDirection { LEFT, RIGHT }

    /** Horizontal fraction (0..1) of a confirmed single tap. */
    var onTap: ((Float) -> Unit)? = null

    /** Page-turn swipe; only fired while the page is at base zoom. */
    var onHorizontalSwipe: ((SwipeDirection) -> Unit)? = null

    private val renderMatrix = Matrix()
    private val scroller = OverScroller(context)
    private var baseScale = 1f
    private var zoom = 1f
    private var translationXPixels = 0f
    private var translationYPixels = 0f
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        },
    )

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
                if (scaleDetector.isInProgress) return true
                panBy(-distanceX, -distanceY)
                return true
            }

            override fun onFling(
                down: MotionEvent?,
                event: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (isAtBaseZoom() &&
                    abs(velocityX) > abs(velocityY) * HORIZONTAL_SWIPE_BIAS &&
                    abs(velocityX) >= MINIMUM_SWIPE_VELOCITY
                ) {
                    onHorizontalSwipe?.invoke(
                        if (velocityX < 0f) SwipeDirection.LEFT else SwipeDirection.RIGHT,
                    )
                    return true
                }
                startFling(velocityX, velocityY)
                return true
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (width > 0) onTap?.invoke(event.x / width)
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                animateZoomTo(
                    if (zoom > 1f + ZOOM_EPSILON) 1f else DOUBLE_TAP_ZOOM,
                    event.x,
                    event.y,
                )
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bitmap: Bitmap?) {
        super.setImageBitmap(bitmap)
        (drawable as? BitmapDrawable)?.paint?.isFilterBitmap = true
        resetTransform()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        (drawable as? BitmapDrawable)?.paint?.isFilterBitmap = true
        super.setImageDrawable(drawable)
        resetTransform()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resetTransform()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaled = scaleDetector.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            if (zoom < 1f) animateZoomTo(1f, width / 2f, height / 2f)
        }
        return scaled || handled || super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            translationXPixels = scroller.currX.toFloat()
            translationYPixels = scroller.currY.toFloat()
            clampTranslation()
            applyMatrix()
            postInvalidateOnAnimation()
        }
    }

    fun isAtBaseZoom(): Boolean = zoom <= 1f + ZOOM_EPSILON

    private fun contentWidth(): Float = (drawable?.intrinsicWidth ?: 0) * baseScale * zoom

    private fun contentHeight(): Float = (drawable?.intrinsicHeight ?: 0) * baseScale * zoom

    private fun resetTransform() {
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
        val drawableWidth = drawable?.intrinsicWidth ?: 0
        if (width <= 0 || height <= 0 || drawableWidth <= 0) return
        baseScale = width.toFloat() / drawableWidth
        zoom = 1f
        translationXPixels = 0f
        translationYPixels = 0f
        clampTranslation()
        applyMatrix()
    }

    private fun applyZoom(requested: Float, focusX: Float, focusY: Float) {
        val next = requested.coerceIn(MINIMUM_PINCH_ZOOM, MAXIMUM_ZOOM)
        if (next == zoom) return
        // Keep the content point under the gesture focus stationary.
        val ratio = next / zoom
        translationXPixels = focusX - (focusX - translationXPixels) * ratio
        translationYPixels = focusY - (focusY - translationYPixels) * ratio
        zoom = next
        clampTranslation()
        applyMatrix()
    }

    private fun panBy(deltaX: Float, deltaY: Float) {
        translationXPixels += deltaX
        translationYPixels += deltaY
        clampTranslation()
        applyMatrix()
    }

    private fun startFling(velocityX: Float, velocityY: Float) {
        val horizontalRange = horizontalRange()
        val verticalRange = verticalRange()
        scroller.forceFinished(true)
        scroller.fling(
            translationXPixels.toInt(),
            translationYPixels.toInt(),
            velocityX.toInt(),
            velocityY.toInt(),
            horizontalRange.first.toInt(),
            horizontalRange.second.toInt(),
            verticalRange.first.toInt(),
            verticalRange.second.toInt(),
        )
        postInvalidateOnAnimation()
    }

    private fun animateZoomTo(targetZoom: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        val startZoom = zoom
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_ANIMATION_MILLIS
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                applyZoom(startZoom + (targetZoom - startZoom) * fraction, focusX, focusY)
            }
            start()
        }
    }

    private fun horizontalRange(): Pair<Float, Float> {
        val extra = contentWidth() - width
        return if (extra > 0f) -extra to 0f else -extra / 2f to -extra / 2f
    }

    private fun verticalRange(): Pair<Float, Float> {
        val extra = contentHeight() - height
        return if (extra > 0f) -extra to 0f else -extra / 2f to -extra / 2f
    }

    private fun clampTranslation() {
        val horizontal = horizontalRange()
        val vertical = verticalRange()
        translationXPixels = translationXPixels.coerceIn(horizontal.first, horizontal.second)
        translationYPixels = translationYPixels.coerceIn(vertical.first, vertical.second)
    }

    private fun applyMatrix() {
        renderMatrix.reset()
        renderMatrix.postScale(baseScale * zoom, baseScale * zoom)
        renderMatrix.postTranslate(translationXPixels, translationYPixels)
        imageMatrix = renderMatrix
    }

    private companion object {
        const val MAXIMUM_ZOOM = 4f
        const val MINIMUM_PINCH_ZOOM = 0.7f
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val ZOOM_EPSILON = 0.01f
        const val ZOOM_ANIMATION_MILLIS = 180L
        const val HORIZONTAL_SWIPE_BIAS = 1.25f
        const val MINIMUM_SWIPE_VELOCITY = 900f
    }
}
