package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.widget.LinearLayout
import io.github.zmlworks.kpexplorer.App
import kotlin.math.abs

/** Header grip at the right edge of a resizable column (two short raised lines like a column divider). */
class HeaderGrip : Drawable() {
    private val dark = Paint().apply { color = W.SHADOW }
    private val light = Paint().apply { color = W.HILITE }
    override fun draw(c: Canvas) {
        val b = bounds; val px = W.px.toFloat(); val x = b.right - 4 * px
        val t = b.top + b.height() * 0.22f; val bt = b.bottom - b.height() * 0.22f
        c.drawRect(x - 3 * px, t, x - 2 * px, bt, dark); c.drawRect(x - 2 * px, t, x - px, bt, light)
        c.drawRect(x, t, x + px, bt, dark); c.drawRect(x + px, t, x + 2 * px, bt, light)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * 0.4.41: list columns whose widths follow a drag on the header borders (the internal backup list). Every column but
 * the last has a width; the last takes the rest. [defaultsDp]: those widths, a value <= 0 = what the others and
 * [lastDefaultDp] leave; [minDp]: the smallest width of each column, the last included. Kept in dp under [pref]
 * ("a,b,c"), a device setting.
 */
class Columns(private val ctx: Context, private val pref: String, private val defaultsDp: IntArray, private val lastDefaultDp: Int,
              private val minDp: IntArray) {
    /** Widths in px of all but the last column. */
    var widths = IntArray(defaultsDp.size)
        private set
    private var header: LinearLayout? = null

    private fun clamp(w: IntArray, avail: Int): IntArray {
        val out = w.copyOf(); var used = 0
        for (i in out.indices) {
            val restMin = (i + 1 until minDp.size).sumOf { W.dp(minDp[it]) }
            out[i] = out[i].coerceIn(W.dp(minDp[i]), (avail - used - restMin).coerceAtLeast(W.dp(minDp[i])))
            used += out[i]
        }
        return out
    }

    /** Stored widths, else the defaults, fitted into [avail] px. */
    fun load(avail: Int) {
        val stored = App.prefs(ctx).getString(pref, null)?.split(',')?.mapNotNull { it.toIntOrNull() }
        val fixed = defaultsDp.filter { it > 0 }.sumOf { W.dp(it) }
        val w = IntArray(defaultsDp.size) { i ->
            stored?.getOrNull(i)?.takeIf { stored.size == defaultsDp.size && it > 0 }?.let { W.dp(it) }
                ?: if (defaultsDp[i] > 0) W.dp(defaultsDp[i]) else avail - fixed - W.dp(lastDefaultDp)
        }
        widths = clamp(w, avail)
    }

    /** Column [i] to [px] wide (relayouts the header; [persist] stores all). */
    fun set(i: Int, px: Int, avail: Int, persist: Boolean) {
        widths = clamp(widths.copyOf().also { it[i] = px }, avail)
        header?.let { h ->
            for (j in widths.indices) (h.getChildAt(j)?.layoutParams as? LinearLayout.LayoutParams)?.width = widths[j]
            h.requestLayout()
        }
        if (persist) {
            val d = ctx.resources.displayMetrics.density
            App.prefs(ctx).edit().putString(pref, widths.joinToString(",") { (it / d).toInt().coerceAtLeast(1).toString() }).apply()
        }
    }

    /** The column whose right border is at header x [x] (within 16 dp), or -1. */
    fun borderAt(x: Float): Int {
        var edge = 0; var best = -1; var bestD = W.dp(16).toFloat()
        for (i in widths.indices) { edge += widths[i]; val d = abs(x - edge); if (d <= bestD) { best = i; bestD = d } }
        return best
    }

    /**
     * The header row (add one child per column): a touch near a border drags it ([changed] runs as the widths change,
     * to relayout the rows); elsewhere the children get it (a tap on a title sorts).
     */
    fun header(avail: () -> Int, changed: () -> Unit): LinearLayout = object : LinearLayout(ctx) {
        var which = -1; var x0 = 0f; var w0 = 0
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                which = borderAt(e.x); x0 = e.x; w0 = widths.getOrElse(which) { 0 }
                if (which >= 0) parent?.requestDisallowInterceptTouchEvent(true)
            }
            return which >= 0
        }
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (which < 0) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> { set(which, w0 + (e.x - x0).toInt(), avail(), false); changed() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { set(which, widths[which], avail(), true); which = -1; changed() }
            }
            return true
        }
    }.also { it.orientation = LinearLayout.HORIZONTAL; it.gravity = android.view.Gravity.CENTER_VERTICAL; header = it }
}
