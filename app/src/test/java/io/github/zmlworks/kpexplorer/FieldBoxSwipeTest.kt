package io.github.zmlworks.kpexplorer

import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import io.github.zmlworks.kpexplorer.ui.FieldBox
import io.github.zmlworks.kpexplorer.ui.W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FieldBoxSwipeTest {
    private fun box(onSwipe: () -> Unit = {}): FieldBox {
        val ctx = RuntimeEnvironment.getApplication()
        W.init(ctx)
        val field = EditText(ctx)
        val b = FieldBox(ctx, "User", field, plain = true, onSwipe = onSwipe)
        b.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        b.layout(0, 0, 400, 80)
        return b
    }

    @Test fun thresholdAbout110dpAndArmAliasesSameColor() {
        val b = box()
        val th = b.copyThreshold()
        assertTrue("threshold ~110dp got=$th", abs(th - W.dp(110)) < 1f)
        assertEquals(FieldBox.COPY_BLUE, FieldBox.COPY_BLUE_ARM)
        assertEquals(FieldBox.COPY_BLUE, FieldBox.COPY_BLUE_DONE)
    }

    @Test fun visualClampAtThresholdFixedColorBothDirections() {
        val b = box()
        val th = b.copyThreshold()
        b.slideTo(th + 80f)
        assertEquals(th, b.translationX)
        assertTrue(b.revealArmed)
        assertEquals(FieldBox.COPY_BLUE, b.revealColor())
        b.slideTo(-(th + 80f))
        assertEquals(-th, b.translationX)
        assertTrue(b.revealArmed)
        assertEquals(FieldBox.COPY_BLUE, b.revealColor())
        b.slideTo(th / 2)
        assertEquals(FieldBox.COPY_BLUE, b.revealColor())
        assertFalse(b.revealArmed)
    }

    @Test fun releaseBeyondThresholdCopiesOnceBothDirections() {
        var n = 0
        val b = box { n++ }
        val th = b.copyThreshold()
        fun swipe(dx: Float) {
            b.dispatchTouchEvent(MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 200f, 40f, 0))
            val steps = 8
            for (i in 1..steps) {
                val x = 200f + dx * i / steps
                b.dispatchTouchEvent(MotionEvent.obtain(0, 1 + i.toLong(), MotionEvent.ACTION_MOVE, x, 40f, 0))
            }
            b.dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, 200f + dx, 40f, 0))
        }
        swipe(th + 30f)
        assertEquals(1, n)
        swipe(-(th + 30f))
        assertEquals(2, n)
        swipe(th - 40f)
        assertEquals(2, n)
        b.dispatchTouchEvent(MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 200f, 40f, 0))
        b.dispatchTouchEvent(MotionEvent.obtain(0, 2, MotionEvent.ACTION_MOVE, 200f + th + 50f, 40f, 0))
        b.dispatchTouchEvent(MotionEvent.obtain(0, 3, MotionEvent.ACTION_CANCEL, 200f + th + 50f, 40f, 0))
        assertEquals(2, n)
    }

    @Test fun noCopyDuringMoveOnlyOnRelease() {
        var n = 0
        val b = box { n++ }
        val th = b.copyThreshold()
        b.dispatchTouchEvent(MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 200f, 40f, 0))
        b.dispatchTouchEvent(MotionEvent.obtain(0, 2, MotionEvent.ACTION_MOVE, 200f + th + 50f, 40f, 0))
        assertEquals(0, n)
        assertEquals(FieldBox.COPY_BLUE, b.revealColor())
        b.dispatchTouchEvent(MotionEvent.obtain(0, 3, MotionEvent.ACTION_UP, 200f + th + 50f, 40f, 0))
        assertEquals(1, n)
    }

    @Test fun revealHasNoCopyLabelText() {
        val b = box()
        fun texts(v: View): List<String> {
            val out = ArrayList<String>()
            if (v is android.widget.TextView && v.text?.isNotEmpty() == true) out.add(v.text.toString())
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) out.addAll(texts(v.getChildAt(i)))
            return out
        }
        b.slideTo(b.copyThreshold())
        val all = texts(b)
        assertFalse("reveal must not show コピー: $all", all.any { it.contains("コピー") })
    }
}
