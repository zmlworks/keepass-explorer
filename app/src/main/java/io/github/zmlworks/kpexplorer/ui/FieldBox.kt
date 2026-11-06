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
 * One key field.
 * Default (cream well): [glyph] [label over value] — used by the new-key form.
 * [table]=true: two-column property sheet. [plain]=true: proposal #3 plain rows (no boxes).
 * Gestures: swipe = copy, long press = edit, tap = [onTap] (Pass show/hide).
 */
class FieldBox(
    ctx: Context,
    label: String,
    val field: EditText,
    private val view: View? = null,
    var onSwipe: (() -> Unit)? = null,
    var onTap: (() -> Unit)? = null,
    var onLongPress: (() -> Unit)? = null,
    icon: android.graphics.drawable.Drawable? = null,
    val trailing: View? = null,
    val table: Boolean = false,
    /** Proposal #3: no boxes — gray face, small shadow label, thin separators added by host. */
    val plain: Boolean = false,
    rowFill: Int = when {
        plain -> PLAIN_FILL
        table -> TABLE_A
        else -> FILL
    },
) : LinearLayout(ctx) {
    var editing = false
        private set
    private val labelColor = when {
        plain -> W.SHADOW
        table -> W.TEXT
        else -> W.NAVY
    }
    val labelView: TextView = W.text(ctx, label, if (table) 14f else 11f, table && !plain, labelColor).apply {
        isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private var idleFill = rowFill
    private val frameBg = W.Bevel(if (table || plain) W.Style.NONE else W.Style.FIELD, idleFill)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = frameBg
        setPadding(
            W.dp(if (table || plain) 8 else 6),
            W.dp(if (plain) 6 else if (table) 8 else 4),
            W.dp(4),
            W.dp(if (plain) 6 else if (table) 8 else 4))
        if (table && !plain) {
            val left = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (icon != null) left.addView(android.widget.ImageView(ctx).apply { setImageDrawable(icon) }, W.lp(W.dp(22), W.dp(22), r = 6))
            left.addView(labelView, W.lp(0, W.WRAP, 1f))
            addView(left, W.lp(W.dp(132), W.WRAP, r = 6))
            val divider = View(ctx).apply { setBackgroundColor(W.SHADOW) }
            addView(divider, W.lp(W.px, W.MATCH, r = 6))
            addView(view ?: field, W.lp(0, W.WRAP, 1f))
        } else {
            if (icon != null) addView(android.widget.ImageView(ctx).apply { setImageDrawable(icon) }, W.lp(W.dp(22), W.dp(22), r = 8))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(labelView, W.lp(W.MATCH, W.WRAP))
            col.addView(view ?: field, W.lp(W.MATCH, W.WRAP))
            addView(col, W.lp(0, W.WRAP, 1f))
        }
        if (trailing != null) addView(trailing, W.lp(W.dp(36), W.dp(36), l = 4))
        field.background = null
        field.setPadding(0, W.dp(1), 0, W.dp(1))
        setEditable(false)
        field.setOnFocusChangeListener { _, has -> if (!has && editing) endEdit() }
        field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { endEdit(); true } else false }
        field.imeOptions = field.imeOptions or EditorInfo.IME_ACTION_DONE
    }

    fun setRowFill(c: Int) {
        idleFill = c
        if (!editing) { frameBg.fill = c; invalidate() }
    }

    private fun setEditable(on: Boolean) {
        field.isFocusable = on; field.isFocusableInTouchMode = on; field.isCursorVisible = on
        field.isLongClickable = on
    }

    fun startEdit() {
        if (view != null) { onLongPress?.invoke(); return }
        editing = true; setEditable(true)
        frameBg.fill = if (table || plain) EDIT_FILL else W.WINDOW; invalidate()
        onLongPress?.invoke()
        field.requestFocus(); field.setSelection(field.text.length)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
    }

    fun endEdit() {
        if (!editing) return
        editing = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus(); setEditable(false)
        frameBg.fill = idleFill; invalidate()
    }

    fun flash() {
        frameBg.fill = W.NAVY; labelView.setTextColor(W.HILITE); invalidate()
        postDelayed({
            frameBg.fill = if (editing) (if (table || plain) EDIT_FILL else W.WINDOW) else idleFill
            labelView.setTextColor(labelColor); invalidate()
        }, 250)
    }

    fun copyThreshold(): Float = maxOf(W.dp(72).toFloat(), minOf(width * 0.4f, W.dp(120).toFloat()))
    private val slop = W.dp(10)
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onLongPress(e: MotionEvent) { if (!sliding) startEdit() }
    })
    private var x0 = 0f; private var y0 = 0f
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

    fun slideTo(dx: Float) {
        translationX = dx
        val past = abs(dx) >= copyThreshold()
        frameBg.fill = if (past) W.NAVY else idleFill
        labelView.setTextColor(if (past) W.HILITE else labelColor)
        invalidate()
    }

    fun springBack() {
        animate().translationX(0f).setDuration(160).withEndAction {
            if (!editing) {
                frameBg.fill = idleFill
                labelView.setTextColor(labelColor)
                invalidate()
            }
        }.start()
    }

    companion object {
        /** Cream well for the new-key form (kept). */
        const val FILL = 0xFFFFFFE1.toInt()
        /** Property-sheet row fills (no cream / white). */
        const val TABLE_A = 0xFFC0C0C0.toInt()
        const val TABLE_B = 0xFFD4D0C8.toInt()
        /** Plain-row face (proposal #3). */
        const val PLAIN_FILL = 0xFFC0C0C0.toInt()
        /** While editing a table cell — light gray, not cream. */
        const val EDIT_FILL = 0xFFE8E4DC.toInt()
        fun mask(t: TextView, hidden: Boolean) { t.transformationMethod = if (hidden) Stars else null as TransformationMethod? }
    }
}
