package rs.masumi.app

import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HorizontalSwipeViewFlipperTest {
    @Test
    fun swipeOverButtonChangesPageWithoutClickingButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var direction: HorizontalSwipeViewFlipper.Direction? = null
        var buttonClicked = false

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Masumi)
            val button = Button(context).apply {
                setOnClickListener { buttonClicked = true }
            }
            val flipper = createLaidOutFlipper(context, button)
            flipper.onSwipe = { direction = it }

            dispatchGesture(flipper, startX = 900f, endX = 100f)
        }

        assertEquals(HorizontalSwipeViewFlipper.Direction.LEFT, direction)
        assertFalse(buttonClicked)
    }

    @Test
    fun ordinaryTapIsNotInterceptedByPager() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var downIntercepted = true
        var upIntercepted = true

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Masumi)
            val button = Button(context)
            val flipper = createLaidOutFlipper(context, button)

            val now = System.currentTimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 500f, 500f, 0)
            val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 500f, 500f, 0)
            downIntercepted = flipper.onInterceptTouchEvent(down)
            upIntercepted = flipper.onInterceptTouchEvent(up)
            down.recycle()
            up.recycle()
        }

        assertFalse(downIntercepted)
        assertFalse(upIntercepted)
    }

    @Test
    fun horizontalGestureInsideTextEditorDoesNotChangePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var direction: HorizontalSwipeViewFlipper.Direction? = null

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Masumi)
            val editor = EditText(context)
            val flipper = createLaidOutFlipper(context, editor)
            flipper.onSwipe = { direction = it }

            dispatchGesture(flipper, startX = 900f, endX = 100f)
        }

        assertNull(direction)
    }

    private fun createLaidOutFlipper(
        context: ContextThemeWrapper,
        firstChild: View,
    ): HorizontalSwipeViewFlipper {
        return HorizontalSwipeViewFlipper(context).apply {
            addView(firstChild, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, VIEW_SIZE, VIEW_SIZE)
        }
    }

    private fun dispatchGesture(
        flipper: HorizontalSwipeViewFlipper,
        startX: Float,
        endX: Float,
    ) {
        val now = System.currentTimeMillis()
        val events = listOf(
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, 500f, 0),
            MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_MOVE, (startX + endX) / 2f, 500f, 0),
            MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, endX, 500f, 0),
        )
        events.forEach { event ->
            flipper.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private companion object {
        const val VIEW_SIZE = 1_000
    }
}
