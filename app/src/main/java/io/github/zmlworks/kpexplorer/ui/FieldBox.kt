package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.text.method.PasswordTransformationMethod
import android.text.method.TransformationMethod
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Shows every character as "*" (fixed six stars, so the length is not revealed). */
object Stars : PasswordTransformationMethod() {
    override fun getTransformation(source: CharSequence, view: View): CharSequence =
        if (source.isEmpty()) "" else "******"
}

/**
 * One key field as its own raised classic frame: "Label:" + value well.
 *  - horizontal swipe = [onSwipe] (copy), with a short flash;
 *  - long press = edit in place (keyboard), leaving the field (focus lost / IME done) ends editing;
 *  - tap = [onTap] (Pass: show / hide).
 * While not editing the frame takes all touches; vertical moves are left to the parent ScrollView.
 */
class FieldBox(ctx: Context, label: String, val field: EditText, private val view: View? = null,
               var onSwipe: (() -> Unit)? = null, var onTap: (() -> Unit)? = null,
               var onLongPress: (() -> Unit)? = null) : LinearLayout(ctx) {
    var editing = false
        private set
    val labelView: TextView = W.text(ctx, label, 14f, true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val frameBg = W.Bevel(W.Style.RAISED)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = frameBg
        setPadding(W.dp(8), W.dp(5), W.dp(5), W.dp(5))
        addView(labelView, W.lp(W.dp(58), W.WRAP, r = 4))
        addView(view ?: field, W.lp(0, W.WRAP, 1f))
        setEditable(false)
        field.setOnFocusChangeListener { _, has -> if (!has && editing) endEdit() }
        field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { endEdit(); true } else false }
        field.imeOptions = field.imeOptions or EditorInfo.IME_ACTION_DONE
    }

    private fun setEditable(on: Boolean) {
        field.isFocusable = on; field.isFocusableInTouchMode = on; field.isCursorVisible = on
        field.isLongClickable = on
    }

    fun startEdit() {
        if (view != null) { onLongPress?.invoke(); return }
        editing = true; setEditable(true)
        frameBg.fill = W.LIGHT; invalidate()
        onLongPress?.invoke()
        field.requestFocus(); field.setSelection(field.text.length)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
    }

    fun endEdit() {
        if (!editing) return
        editing = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus(); setEditable(false)
        frameBg.fill = W.FACE; invalidate()
    }

    /** Short highlight after a copy. */
    fun flash() {
        frameBg.fill = W.NAVY; labelView.setTextColor(W.HILITE); invalidate()
        postDelayed({ frameBg.fill = if (editing) W.LIGHT else W.FACE; labelView.setTextColor(W.TEXT); invalidate() }, 250)
    }

    private val swipeMin = W.dp(48)
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onLongPress(e: MotionEvent) { startEdit() }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val s = e1 ?: return false
            val mx = e2.x - s.x; val my = e2.y - s.y
            if (abs(mx) > abs(my) * 1.5f) parent?.requestDisallowInterceptTouchEvent(true)
            return false
        }
    })
    private var x0 = 0f; private var y0 = 0f; private var swiped = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = !editing
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; swiped = false }
            MotionEvent.ACTION_MOVE -> if (!swiped && abs(e.x - x0) > swipeMin && abs(e.x - x0) > 2 * abs(e.y - y0)) {
                swiped = true; onSwipe?.invoke(); flash()
                val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }; gd.onTouchEvent(c); c.recycle()
                return true
            }
        }
        if (swiped) return true
        return gd.onTouchEvent(e)
    }

    companion object {
        fun mask(t: TextView, hidden: Boolean) { t.transformationMethod = if (hidden) Stars else null as TransformationMethod? }
    }
}
