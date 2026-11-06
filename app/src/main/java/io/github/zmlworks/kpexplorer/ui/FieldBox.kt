package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.method.PasswordTransformationMethod
import android.text.method.TransformationMethod
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
 * Gestures: swipe = copy (row slides over a lighter blue reveal with icon + 「コピー」),
 * long press = edit, tap = [onTap] (Pass show/hide). Secret values are never drawn on the reveal.
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
) : FrameLayout(ctx) {
    var editing = false
        private set
    private val labelColor = when {
        plain -> W.LABEL_DARK
        table -> W.TEXT
        else -> W.NAVY
    }
    val labelView: TextView = W.text(ctx, label,
        when {
            plain -> 13f
            table -> 14f
            else -> 11f
        }, table && !plain, labelColor).apply {
        isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private var idleFill = rowFill
    private val frameBg = W.Bevel(if (table || plain) W.Style.NONE else W.Style.FIELD, idleFill)

    /** Sliding foreground that holds the field content (not the reveal). */
    private val front: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = frameBg
        setPadding(
            W.dp(if (plain) 10 else if (table) 8 else 6),
            W.dp(if (plain) 8 else if (table) 8 else 4),
            W.dp(if (plain) 6 else 4),
            W.dp(if (plain) 8 else if (table) 8 else 4))
    }

    private val leftHint: LinearLayout
    private val rightHint: LinearLayout
    private val reveal: FrameLayout = FrameLayout(ctx).apply {
        background = ColorDrawable(COPY_BLUE)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        clipChildren = true
        fun hint(start: Boolean): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(W.dp(14), 0, W.dp(14), 0)
            }
            val iv = ImageView(ctx).apply { setImageDrawable(Glyph(Glyph.Kind.COPY, W.HILITE)) }
            val tv = W.text(ctx, "コピー", 15f, true, W.HILITE).apply { isSingleLine = true }
            if (start) {
                row.addView(iv, W.lp(W.dp(22), W.dp(22), r = 8))
                row.addView(tv, W.lp(W.WRAP, W.WRAP))
            } else {
                row.addView(tv, W.lp(W.WRAP, W.WRAP, r = 8))
                row.addView(iv, W.lp(W.dp(22), W.dp(22)))
            }
            return row
        }
        leftHint = hint(true)
        rightHint = hint(false)
        reveal.addView(leftHint, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL))
        reveal.addView(rightHint, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL))
        addView(reveal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        if (table && !plain) {
            val left = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (icon != null) left.addView(ImageView(ctx).apply { setImageDrawable(icon) }, W.lp(W.dp(22), W.dp(22), r = 6))
            left.addView(labelView, W.lp(0, W.WRAP, 1f))
            front.addView(left, W.lp(W.dp(132), W.WRAP, r = 6))
            val divider = View(ctx).apply { setBackgroundColor(W.SHADOW) }
            front.addView(divider, W.lp(W.px, W.MATCH, r = 6))
            front.addView(view ?: field, W.lp(0, W.WRAP, 1f))
        } else {
            // plain: larger left glyph (~26dp) and more inset so Title/value sit further right
            val iconDp = if (plain) 26 else 22
            val iconGap = if (plain) 12 else 8
            if (icon != null) front.addView(ImageView(ctx).apply { setImageDrawable(icon) }, W.lp(W.dp(iconDp), W.dp(iconDp), r = iconGap))
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(labelView, W.lp(W.MATCH, W.WRAP))
            col.addView(view ?: field, W.lp(W.MATCH, W.WRAP))
            front.addView(col, W.lp(0, W.WRAP, 1f))
        }
        // WRAP: text chips like 「生成」must stay horizontal; fixed 36dp crushed CJK to 2 lines
        if (trailing != null) front.addView(trailing, W.lp(W.WRAP, W.WRAP, l = 6).apply { gravity = Gravity.CENTER_VERTICAL })
        addView(front, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        field.background = null
        field.setPadding(0, W.dp(1), 0, W.dp(1))
        setEditable(false)
        field.setOnFocusChangeListener { _, has -> if (!has && editing) endEdit() }
        field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { endEdit(); true } else false }
        field.imeOptions = field.imeOptions or EditorInfo.IME_ACTION_DONE
        updateHints(0f, false)
    }

    fun setRowFill(c: Int) {
        idleFill = c
        if (!editing) { frameBg.fill = c; front.invalidate() }
    }

    private fun setEditable(on: Boolean) {
        field.isFocusable = on; field.isFocusableInTouchMode = on; field.isCursorVisible = on
        field.isLongClickable = on
    }

    fun startEdit() {
        if (view != null) { onLongPress?.invoke(); return }
        editing = true; setEditable(true)
        frameBg.fill = if (table || plain) EDIT_FILL else W.WINDOW; front.invalidate()
        onLongPress?.invoke()
        field.requestFocus(); field.setSelection(field.text.length)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
    }

    fun endEdit() {
        if (!editing) return
        editing = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus(); setEditable(false)
        frameBg.fill = idleFill; front.invalidate()
    }

    /** Brief flash after a successful copy — keep front navy pulse; do not paint secrets on reveal. */
    fun flash() {
        frameBg.fill = W.NAVY; labelView.setTextColor(W.HILITE); front.invalidate()
        reveal.setBackgroundColor(COPY_BLUE_DONE)
        setHintText("コピー済み")
        postDelayed({
            frameBg.fill = if (editing) (if (table || plain) EDIT_FILL else W.WINDOW) else idleFill
            labelView.setTextColor(labelColor); front.invalidate()
            reveal.setBackgroundColor(COPY_BLUE)
            setHintText("コピー")
            updateHints(0f, false)
        }, 280)
    }

    private fun setHintText(s: String) {
        fun labelOf(row: LinearLayout): TextView? {
            for (i in 0 until row.childCount) (row.getChildAt(i) as? TextView)?.let { return it }
            return null
        }
        labelOf(leftHint)?.text = s
        labelOf(rightHint)?.text = s
    }

    /** True when |translation| has crossed [copyThreshold] (reveal armed). For tests / screenshots. */
    val revealArmed: Boolean get() = abs(front.translationX) >= copyThreshold()
    fun revealColor(): Int = (reveal.background as? ColorDrawable)?.color ?: COPY_BLUE

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

    /** Exposed for tests / screenshots: translation of the sliding content (not the reveal). */
    override fun setTranslationX(translationX: Float) { front.translationX = translationX }
    override fun getTranslationX(): Float = front.translationX

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = !editing

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; sliding = false; front.animate().cancel() }
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
        front.translationX = dx
        val past = abs(dx) >= copyThreshold()
        // Keep front at idle fill while sliding so the blue reveal stays readable behind it.
        frameBg.fill = idleFill
        labelView.setTextColor(labelColor)
        updateHints(dx, past)
        front.invalidate()
    }

    private fun updateHints(dx: Float, past: Boolean) {
        val showL = dx > W.dp(4)
        val showR = dx < -W.dp(4)
        leftHint.alpha = if (showL) 1f else 0f
        rightHint.alpha = if (showR) 1f else 0f
        leftHint.scaleX = if (past) 1.05f else 1f
        rightHint.scaleX = if (past) 1.05f else 1f
        // Slightly brighter blue once past the copy threshold
        reveal.setBackgroundColor(if (past) COPY_BLUE_ARM else COPY_BLUE)
    }

    fun springBack() {
        front.animate().translationX(0f).setDuration(160).withEndAction {
            if (!editing) {
                frameBg.fill = idleFill
                labelView.setTextColor(labelColor)
                updateHints(0f, false)
                front.invalidate()
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
        /**
         * Swipe-reveal blue: lighter than [W.NAVY] (0x000080) / title bar navy.
         * Does not paint field secrets — icon + 「コピー」 only.
         */
        const val COPY_BLUE = 0xFF5BA3E8.toInt()
        /** Armed (past threshold) — a touch brighter. */
        const val COPY_BLUE_ARM = 0xFF6BB4F0.toInt()
        /** Brief post-copy flash on the reveal. */
        const val COPY_BLUE_DONE = 0xFF3D8FD6.toInt()
        fun mask(t: TextView, hidden: Boolean) { t.transformationMethod = if (hidden) Stars else null as TransformationMethod? }
    }
}
