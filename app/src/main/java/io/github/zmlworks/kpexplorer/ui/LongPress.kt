package io.github.zmlworks.kpexplorer.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 0.4.31: a long press after [W.longPressMs] (GestureDetector only knows the system delay). Feed it every touch
 * event of [view]; [onLong] gets the down point (view coordinates). Moving past the touch slop, lifting the
 * finger or a second finger cancels it. [fired] tells whether this gesture's long press has happened.
 */
class LongPress(private val view: View, private val onLong: (Float, Float) -> Unit) {
    private var x0 = 0f
    private var y0 = 0f
    var fired = false
        private set
    private val run = Runnable { fired = true; onLong(x0, y0) }

    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; fired = false
                view.removeCallbacks(run); view.postDelayed(run, W.longPressMs())
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                if (abs(e.x - x0) > slop || abs(e.y - y0) > slop) view.removeCallbacks(run)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> view.removeCallbacks(run)
        }
    }
}
