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
 * One key field as a classic sunken well (KeePassDX-like layout, Win 98 look):
 * [leading glyph] [small label over the value] [optional trailing action, e.g. eye / open link].
 *  - horizontal swipe = [onSwipe] (copy), with a short flash;
 *  - long press = edit in place (keyboard), leaving the field (focus lost / IME done) ends editing;
 *  - tap = [onTap] (Pass: show / hide).
 * While not editing the frame takes all touches (except on the trailing button); vertical moves go to the ScrollView.
 */
class FieldBox(ctx: Context, label: String, val field: EditText, private val view: View? = null,
               var onSwipe: (() -> Unit)? = null, var onTap: (() -> Unit)? = null,
               var onLongPress: (() -> Unit)? = null, icon: android.graphics.drawable.Drawable? = null,
               val trailing: View? = null) : LinearLayout(ctx) {
    var editing = false
        private set
    val labelView: TextView = W.text(ctx, label, 11f, false, W.NAVY).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val frameBg = W.Bevel(W.Style.FIELD, FILL)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = frameBg
        setPadding(W.dp(6), W.dp(4), W.dp(4), W.dp(4))
        if (icon != null) addView(android.widget.ImageView(ctx).apply { setImageDrawable(icon) }, W.lp(W.dp(22), W.dp(22), r = 8))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(labelView, W.lp(W.MATCH, W.WRAP))
        col.addView(view ?: field, W.lp(W.MATCH, W.WRAP))
        addView(col, W.lp(0, W.WRAP, 1f))
        if (trailing != null) addView(trailing, W.lp(W.dp(36), W.dp(36), l = 4))
        field.background = null
        field.setPadding(0, W.dp(1), 0, W.dp(1))
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
        frameBg.fill = W.WINDOW; invalidate()
        onLongPress?.invoke()
        field.requestFocus(); field.setSelection(field.text.length)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
    }

    fun endEdit() {
        if (!editing) return
        editing = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus(); setEditable(false)
        frameBg.fill = FILL; invalidate()
    }

    /** Short highlight after a copy. */
    fun flash() {
        frameBg.fill = W.NAVY; labelView.setTextColor(W.HILITE); invalidate()
        postDelayed({ frameBg.fill = if (editing) W.WINDOW else FILL; labelView.setTextColor(W.NAVY); invalidate() }, 250)
    }

    /** Copy needs a long swipe: 40 % of the width, at most 120 dp (at least 72 dp). */
    fun copyThreshold(): Float = maxOf(W.dp(72).toFloat(), minOf(width * 0.4f, W.dp(120).toFloat()))
    private val slop = W.dp(10)
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onLongPress(e: MotionEvent) { if (!sliding) startEdit() }
    })
    private var x0 = 0f; private var y0 = 0f
    /** True while the box follows the finger sideways. */
    var sliding = false
        private set

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = !editing

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; sliding = false; animate().cancel() }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - x0; val dy = e.rawY - y0
                if (!sliding && abs(dx) > slop && abs(dx) > 1.5f * abs(dy)) {
                    sliding = true; parent?.requestDisallowInterceptTouchEvent(true)
                    val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }; gd.onTouchEvent(c); c.recycle()
                }
                if (sliding) { slideTo(dx); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (sliding) {
                val dx = e.rawX - x0
                sliding = false
                if (e.actionMasked == MotionEvent.ACTION_UP && abs(dx) >= copyThreshold()) { onSwipe?.invoke(); flash() }
                springBack()
                return true
            }
        }
        return gd.onTouchEvent(e)
    }

    /** Box offset while sliding (also used by tests / mocks); past the threshold the frame turns navy. */
    fun slideTo(dx: Float) {
        translationX = dx
        val past = abs(dx) >= copyThreshold()
        frameBg.fill = if (past) W.NAVY else FILL; labelView.setTextColor(if (past) W.HILITE else W.NAVY); invalidate()
    }

    fun springBack() {
        animate().translationX(0f).setDuration(160).withEndAction {
            if (!editing) { frameBg.fill = FILL; labelView.setTextColor(W.NAVY); invalidate() }
        }.start()
    }

    companion object {
        /** Field well fill: classic tooltip cream (#FFFFE1), black text stays high-contrast. */
        const val FILL = 0xFFFFFFE1.toInt()
        fun mask(t: TextView, hidden: Boolean) { t.transformationMethod = if (hidden) Stars else null as TransformationMethod? }
    }
}
