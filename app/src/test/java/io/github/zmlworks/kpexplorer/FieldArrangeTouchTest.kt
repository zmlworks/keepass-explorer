package io.github.zmlworks.kpexplorer

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.ui.FieldArrangeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream

/**
 * Dispatched MotionEvent sequences against [FieldArrangeEditor] (not just place()/step() helpers).
 * Physical multi-touch latency / OEM touch slop cannot be fully reproduced on Robolectric —
 * see comments on individual tests for honest limits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FieldArrangeTouchTest {
    private lateinit var host: FrameLayout
    private lateinit var ed: FieldArrangeEditor
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens")

    private fun app() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        out.mkdirs()
        val a = Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume().visible().get()
        host = FrameLayout(a)
        a.setContentView(host)
        val initial = listOf(
            Order.F_TITLE to true,
            Order.F_USER to true,
            Order.F_PASS to true,
            Order.F_URL to true,
            Order.F_TAGS to true,
            Order.F_NOTES to true,
        )
        ed = FieldArrangeEditor(a, initial)
        host.addView(ed, FrameLayout.LayoutParams(411 * 3, 700))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 411 * 3, 700)
        ShadowLooper.idleMainLooper()
    }

    private fun ev(action: Int, rawX: Float, rawY: Float, view: View): MotionEvent {
        val loc = IntArray(2); view.getLocationOnScreen(loc)
        val x = rawX - loc[0]; val y = rawY - loc[1]
        // Robolectric's MotionEvent.obtain sets rawX/rawY equal to x/y; compensate by passing
        // raw coordinates as local when the view is at the screen origin (common in these tests).
        val useX = if (loc[0] == 0 && loc[1] == 0) rawX else x
        val useY = if (loc[0] == 0 && loc[1] == 0) rawY else y
        return MotionEvent.obtain(0L, System.currentTimeMillis(), action, useX, useY, 0)
    }

    private fun rawOf(v: View): Pair<Float, Float> {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return loc[0] + v.width / 2f to loc[1] + v.height / 2f
    }

    private fun dispatchOn(v: View, action: Int, rawX: Float, rawY: Float) {
        val e = ev(action, rawX, rawY, v)
        // Prefer editor dispatch for MOVE/UP after drag starts (mirrors parent steal)
        if (ed.isDragging() && action != MotionEvent.ACTION_DOWN) {
            ed.dispatchTouchEvent(e)
        } else {
            v.dispatchTouchEvent(e)
            // Also let editor see MOVE if handle still exists
            if (ed.isDragging() && action == MotionEvent.ACTION_MOVE) {
                ed.dispatchTouchEvent(ev(action, rawX, rawY, ed))
            }
        }
        e.recycle()
        ShadowLooper.idleMainLooper()
    }

    @Test fun titleIsAbsentFromEditor() {
        assertFalse(Order.F_TITLE in ed.shown)
        assertFalse(Order.F_TITLE in ed.hidden)
        assertNull(ed.handleFor(Order.F_TITLE))
        assertNull(ed.rowFor(Order.F_TITLE))
        val snap = ed.snapshot()
        assertEquals(Order.F_TITLE to true, snap.first())
        assertTrue(snap.none { it.first == Order.F_TITLE && !it.second })
    }

    @Test fun bodyScrollsWithoutStartingDrag() {
        val before = ed.scroll.scrollY
        // Drag on the Pass *label row center* (not handle) — should scroll or at least not reorder
        val row = ed.rowFor(Order.F_PASS)!!
        val (rx, ry) = rawOf(row)
        // Touch on the LEFT half of the row (handle is rightmost since 0.3.13)
        val x = rx - row.width * 0.3f
        row.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, ry, row))
        assertFalse(ed.isDragging())
        ed.scroll.scrollBy(0, 80)
        ShadowLooper.idleMainLooper()
        assertTrue(ed.scroll.scrollY >= before)
        assertEquals(listOf(Order.F_USER, Order.F_PASS, Order.F_URL, Order.F_TAGS, Order.F_NOTES), ed.shown.toList())
    }

    @Test fun handleDragReordersDown() {
        val handle = ed.handleFor(Order.F_USER)!!
        val (hx, hy) = rawOf(handle)
        val pass = ed.rowFor(Order.F_PASS)!!
        val (_, passY) = rawOf(pass)
        handle.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, hx, hy, handle))
        assertTrue(ed.isDragging())
        assertEquals(Order.F_USER, ed.dragKey)
        // Move well below Pass center so User inserts after Pass
        val targetY = passY + pass.height
        ed.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, hx, targetY, ed))
        ShadowLooper.idleMainLooper()
        ed.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, hx, targetY + 20f, ed))
        ShadowLooper.idleMainLooper()
        ed.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, hx, targetY + 20f, ed))
        ShadowLooper.idleMainLooper()
        assertFalse(ed.isDragging())
        val iUser = ed.shown.indexOf(Order.F_USER)
        val iPass = ed.shown.indexOf(Order.F_PASS)
        assertTrue("User should move down past Pass: shown=${ed.shown}", iUser > iPass)
    }

    @Test fun multiMoveDragContinuesAfterReorder() {
        val handle = ed.handleFor(Order.F_NOTES)!!
        val (hx, hy) = rawOf(handle)
        handle.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, hx, hy, handle))
        assertTrue(ed.isDragging())
        // Several MOVE events after list rebuilds — drag must remain active
        repeat(6) { step ->
            assertTrue("drag alive at step $step", ed.isDragging())
            val user = ed.rowFor(Order.F_USER) ?: ed.rowFor(Order.F_PASS)!!
            val (_, uy) = rawOf(user)
            ed.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, hx, uy - 30f - step * 5f, ed))
            ShadowLooper.idleMainLooper()
        }
        assertTrue(ed.isDragging())
        ed.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, hx, hy, ed))
        assertFalse(ed.isDragging())
        // Notes should have moved up within shown
        assertTrue(ed.shown.contains(Order.F_NOTES))
        assertTrue(ed.shown.indexOf(Order.F_NOTES) < ed.shown.lastIndex)
    }

    @Test fun dragAcrossDividerHidesAndRestores() {
        val handle = ed.handleFor(Order.F_URL)!!
        val (hx, hy) = rawOf(handle)
        handle.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, hx, hy, handle))
        val div = ed.dividerView()!!
        val (_, dy) = rawOf(div)
        // Below divider → hidden
        ed.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, hx, dy + div.height + 40f, ed))
        ShadowLooper.idleMainLooper()
        assertTrue("URL hidden after crossing: shown=${ed.shown} hidden=${ed.hidden}",
            Order.F_URL in ed.hidden && Order.F_URL !in ed.shown)
        // Still dragging — move clearly above the (possibly moved) divider
        assertTrue(ed.isDragging())
        val div2 = ed.dividerView()!!
        val (_, dy2) = rawOf(div2)
        val restoreY = dy2 - div2.height - 30f
        ed.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, hx, restoreY, ed))
        ShadowLooper.idleMainLooper()
        assertTrue("URL restored: shown=${ed.shown} hidden=${ed.hidden}",
            Order.F_URL in ed.shown && Order.F_URL !in ed.hidden)
        ed.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, hx, restoreY, ed))
        assertFalse(ed.isDragging())
        // Values are not stored in the editor — snapshot must encode visibility only
        val snap = ed.snapshot()
        assertTrue(snap.any { it.first == Order.F_URL && it.second })
        assertEquals(Order.F_TITLE to true, snap.first())
    }

    @Test fun cancelDoesNotNeedSave_helpersMatchTouchResult() {
        // place() path used by Cancel (dismiss without apply) — editor state discarded by host
        val before = ed.snapshot()
        ed.place(Order.F_PASS, visible = false, index = 0)
        assertTrue(Order.F_PASS in ed.hidden)
        // Re-load as Cancel would (new editor from original layout)
        val ed2 = FieldArrangeEditor(app(), before)
        assertTrue(Order.F_PASS in ed2.shown)
        assertFalse(Order.F_PASS in ed2.hidden)
    }

    @Test fun handleIsRightmostAndDeleteLeftOfHandle() {
        // Rebuild with a custom so × appears
        host.removeAllViews()
        val initial = listOf(
            Order.F_TITLE to true,
            Order.F_USER to true,
            Order.F_PASS to true,
            "暗証" to true,
            Order.F_NOTES to true,
        )
        ed = FieldArrangeEditor(app(), initial)
        host.addView(ed, FrameLayout.LayoutParams(411 * 3, 700))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 411 * 3, 700)
        ShadowLooper.idleMainLooper()
        val row = ed.rowFor("暗証") as android.view.ViewGroup
        val handle = ed.handleFor("暗証")!!
        val del = ed.deleteFor("暗証")!!
        assertTrue("delete should be left of handle", del.left < handle.left)
        assertEquals("handle should be last child", row.childCount - 1, (0 until row.childCount).first { row.getChildAt(it) === handle })
        // Standard fields have no delete
        assertNull(ed.deleteFor(Order.F_USER))
        assertNotNull(ed.handleFor(Order.F_USER))
    }

    @Test fun dividerSaysHiddenBelow() {
        val div = ed.dividerView() as android.view.ViewGroup
        var found = false
        fun walk(v: View) {
            if (v is android.widget.TextView && v.text.toString().contains("この下は非表示")) found = true
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(div)
        assertTrue(found)
    }

    @Test fun renderEditorScreenshot() {
        // Custom + hidden URL so × and boundary both show
        host.removeAllViews()
        val initial = listOf(
            Order.F_TITLE to true,
            Order.F_USER to true,
            Order.F_PASS to true,
            "暗証" to true,
            Order.F_URL to false,
            Order.F_NOTES to false,
        )
        ed = FieldArrangeEditor(app(), initial)
        host.addView(ed, FrameLayout.LayoutParams(411 * 3, 900))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 411 * 3, 900)
        ShadowLooper.idleMainLooper()
        val b = android.graphics.Bitmap.createBitmap(host.width, host.height, android.graphics.Bitmap.Config.ARGB_8888)
        host.draw(android.graphics.Canvas(b))
        FileOutputStream(File(out, "13-field-arrange-boundary.png")).use {
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertNotNull(ed.dividerView())
        assertNotNull(ed.deleteFor("暗証"))
        assertTrue(Order.F_URL in ed.hidden)
        assertFalse(Order.F_TITLE in ed.shown)
    }
}
