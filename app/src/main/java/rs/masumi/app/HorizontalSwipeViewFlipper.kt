package rs.masumi.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

class HorizontalSwipeViewFlipper @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    enum class Direction {
        LEFT,
        RIGHT,
    }

    var onSwipe: ((Direction) -> Unit)? = null

    var displayedChild: Int = 0
        set(value) {
            val target = if (childCount == 0) 0 else value.coerceIn(0, childCount - 1)
            field = target
            updateChildVisibility()
        }

    private val minimumSwipeDistance = maxOf(
        48f * resources.displayMetrics.density,
        ViewConfiguration.get(context).scaledTouchSlop * 3f,
    )
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var interceptingSwipe = false
    private var swipeBlockedByTextEditor = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        updateChildVisibility()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                interceptingSwipe = false
                swipeBlockedByTextEditor = findTouchTarget(this, event.x, event.y)
                    ?.onCheckIsTextEditor() == true
            }

            MotionEvent.ACTION_MOVE -> {
                if (swipeBlockedByTextEditor) return false
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false
                val horizontalDistance = event.getX(pointerIndex) - downX
                val verticalDistance = event.getY(pointerIndex) - downY
                if (
                    abs(horizontalDistance) >= minimumSwipeDistance &&
                    abs(horizontalDistance) > abs(verticalDistance) * HORIZONTAL_BIAS
                ) {
                    interceptingSwipe = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> if (!interceptingSwipe) resetGesture()
        }
        return interceptingSwipe
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interceptingSwipe) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                val horizontalDistance = event.getX(pointerIndex) - downX
                val verticalDistance = event.getY(pointerIndex) - downY
                if (
                    abs(horizontalDistance) >= minimumSwipeDistance &&
                    abs(horizontalDistance) > abs(verticalDistance) * HORIZONTAL_BIAS
                ) {
                    onSwipe?.invoke(
                        if (horizontalDistance < 0f) Direction.LEFT else Direction.RIGHT,
                    )
                }
                resetGesture()
            }

            MotionEvent.ACTION_CANCEL -> resetGesture()
        }
        return true
    }

    private fun resetGesture() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        interceptingSwipe = false
        swipeBlockedByTextEditor = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun updateChildVisibility() {
        repeat(childCount) { index ->
            getChildAt(index).visibility = if (index == displayedChild) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun findTouchTarget(group: ViewGroup, x: Float, y: Float): View? {
        for (index in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            val childX = x + group.scrollX - child.left
            val childY = y + group.scrollY - child.top
            if (childX < 0f || childY < 0f || childX >= child.width || childY >= child.height) {
                continue
            }
            if (child is ViewGroup) {
                return findTouchTarget(child, childX, childY) ?: child
            }
            return child
        }
        return null
    }

    private companion object {
        const val HORIZONTAL_BIAS = 1.25f
    }
}
