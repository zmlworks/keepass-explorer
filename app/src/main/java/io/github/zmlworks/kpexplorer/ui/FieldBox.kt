package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.method.PasswordTransformationMethod
import android.text.method.TransformationMethod
import android.os.Handler
import android.os.Looper
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
 * At most one [FieldBox] is in inline edit. Outside [ACTION_DOWN] commits via [commitActive]
 * (draft-only unless host sets onCommitted). IME window taps do not reach the host view tree.
 */
/**
 * 0.4.7: privacy-safe ring buffer of Back/edit event NAMES only (no field names, values or tags).
 * Shown in バージョン情報 so a real-device ordering can be read without a debugger.
 */
object BackTrace {
    private val buf = ArrayDeque<String>()
    fun log(ev: String) {
        val t = android.os.SystemClock.uptimeMillis() % 100000
        synchronized(buf) { buf.addLast("$t $ev"); while (buf.size > 40) buf.removeFirst() }
    }
    fun dump(): String = synchronized(buf) { buf.joinToString("\n") }
    fun clear() = synchronized(buf) { buf.clear() }
}

object FieldEditSession {
    var active: FieldBox? = null
        private set
    /**
     * Uptime at which an edit was ended IMPLICITLY (keyboard hidden). A Back whose KeyEvent.downTime is
     * not later than this belongs to the same physical press that hid the keyboard and must not navigate.
     * No timeout: compared against the press's own timestamp, and cleared by the next Back DOWN.
     */
    var implicitEndAt = 0L
    val editing: Boolean get() = active?.editing == true
    /** Invoked after a successful commit (text already live in host maps). */
    var onCommitted: (() -> Unit)? = null
    fun begin(box: FieldBox) {
        if (active === box) return
        val prev = active
        if (prev != null && prev.editing) {
            prev.endEdit(commit = true)
            active = null
            onCommitted?.invoke()
        }
        active = box
    }
    fun clear(box: FieldBox) { if (active === box) active = null }
    fun commitActive(): Boolean {
        val b = active ?: return false
        if (!b.editing) { active = null; return false }
        b.endEdit(commit = true)
        active = null
        onCommitted?.invoke()
        return true
    }
}


/**
 * One key field.
 * Default (cream well): [glyph] [label over value] — used by the new-key form.
 * [table]=true: two-column property sheet. [plain]=true: proposal #3 plain rows (no boxes).
 * Gestures: swipe = copy (row slides over a fixed lighter-blue reveal with clipboard glyph only),
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
        // 0.4.5: small labels sit a bit further right (label only; values keep their position)
        if (plain) setPadding(W.dp(7), 0, 0, 0)
    }
    private var idleFill = rowFill
    /** Optional action for a LEFT swipe (finger dx<0). When null, both directions use [onSwipe]. */
    var onSwipeLeft: (() -> Unit)? = null
    private var rightHintIcon: ImageView? = null
    /** Glyph revealed (on the right) during a left swipe, e.g. an open-in-browser icon. */
    fun setLeftSwipeGlyph(d: android.graphics.drawable.Drawable) { rightHintIcon?.setImageDrawable(d) }
    private fun canSlide(dx: Float) = if (dx < 0) (onSwipeLeft ?: onSwipe) != null else onSwipe != null
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

    /** Called whenever an edit ends (any path: Back, Done, outside tap, other field). */
    var onEditEnd: (() -> Unit)? = null
    private var preImeBackDown = false

    init {
        (field as? W.BackEditText)?.onPreImeBack = { e ->
            when (e.action) {
                android.view.KeyEvent.ACTION_DOWN -> { if (e.repeatCount == 0) preImeBackDown = editing; BackTrace.log("preIme DOWN ed=$editing"); preImeBackDown }
                android.view.KeyEvent.ACTION_UP -> {
                    val mine = preImeBackDown; preImeBackDown = false
                    BackTrace.log("preIme UP mine=$mine")
                    if (mine && !e.isCanceled) FieldEditSession.commitActive()
                    mine
                }
                else -> false
            }
        }
        clipChildren = true
        fun hint(@Suppress("UNUSED_PARAMETER") start: Boolean): LinearLayout {
            // Clipboard glyph only (no 「コピー」 text) — 0.4.3
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(W.dp(16), 0, W.dp(16), 0)
            }
            val iv = ImageView(ctx).apply { setImageDrawable(Glyph(Glyph.Kind.COPY, W.HILITE)) }
            row.addView(iv, W.lp(W.dp(24), W.dp(24)))
            if (!start) rightHintIcon = iv
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
        // 0.4.7: focus loss no longer ends the edit — editing is explicit (Back, Done, outside tap,
        // another field, 戻る/×). Silent focus-loss finalization made the next Back navigate.
        field.setOnFocusChangeListener { _, has -> if (!has && editing) BackTrace.log("focusLost(editing)") }
        field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { FieldEditSession.commitActive(); true } else false }
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
        FieldEditSession.begin(this)
        BackTrace.log("editStart")
        editing = true; setEditable(true)
        frameBg.fill = if (table || plain) EDIT_FILL else W.WINDOW; front.invalidate()
        onLongPress?.invoke()
        field.requestFocus(); field.setSelection(field.text.length)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
    }

    /**
     * Leave edit mode. [commit]=true keeps the typed value (already mirrored by TextWatchers)
     * and clears the session; false just leaves without [FieldEditSession.onCommitted].
     */
    fun endEdit(commit: Boolean = true) {
        if (!editing) return
        BackTrace.log("editEnd")
        editing = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus(); setEditable(false)
        frameBg.fill = idleFill; front.invalidate()
        FieldEditSession.clear(this)
        onEditEnd?.invoke()
        // commit path for outside-tap is FieldEditSession.commitActive → onCommitted
    }

    /** Post-copy: no color flash (navy/arm flicker removed 0.4.3). Host may toast. */
    fun flash() {
        // Keep reveal at fixed COPY_BLUE; do not recolor front/reveal.
        updateHints(0f, false)
    }

    /** True when |translation| has crossed [copyThreshold] (reveal armed). For tests / screenshots. */
    val revealArmed: Boolean get() = abs(front.translationX) >= copyThreshold()
    fun revealColor(): Int = (reveal.background as? ColorDrawable)?.color ?: COPY_BLUE

    /** Required travel to arm a copy (~110dp). Visual slide clamps here; release uses raw dx. */
    fun copyThreshold(): Float = W.dp(110).toFloat()
    private val slop = W.dp(10)
    private val handler = Handler(Looper.getMainLooper())
    /** Shorter than the system long-press (~500ms); horizontal swipe / scroll cancels. */
    private val longPressRunnable = Runnable { if (!sliding && !editing) startEdit() }
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap?.invoke(); return true }
    }).also { it.setIsLongpressEnabled(false) }
    private var x0 = 0f; private var y0 = 0f
    var sliding = false
        private set

    /** Exposed for tests / screenshots: translation of the sliding content (not the reveal). */
    override fun setTranslationX(translationX: Float) { front.translationX = translationX }
    override fun getTranslationX(): Float = front.translationX

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = !editing

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return super.onTouchEvent(e)
        // Another field is being edited → this DOWN commits it (outside tap)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val other = FieldEditSession.active
            if (other != null && other !== this && other.editing) {
                FieldEditSession.commitActive()
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY; sliding = false; front.animate().cancel()
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, EDIT_LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - x0; val dy = e.rawY - y0
                if (abs(dx) > slop || abs(dy) > slop) handler.removeCallbacks(longPressRunnable)
                if (!sliding && abs(dx) > slop && abs(dx) > 1.5f * abs(dy) && canSlide(dx)) {
                    sliding = true; parent?.requestDisallowInterceptTouchEvent(true)
                    handler.removeCallbacks(longPressRunnable)
                    val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }; gd.onTouchEvent(c); c.recycle()
                }
                if (sliding) { slideTo(dx); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (sliding) {
                    val dx = e.rawX - x0
                    sliding = false
                    if (e.actionMasked == MotionEvent.ACTION_UP && abs(dx) >= copyThreshold()) {
                        if (dx < 0 && onSwipeLeft != null) onSwipeLeft?.invoke() else if (canSlide(dx)) onSwipe?.invoke()
                        flash()
                    }
                    springBack()
                    return true
                }
            }
        }
        return gd.onTouchEvent(e)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPressRunnable)
        if (FieldEditSession.active === this) FieldEditSession.clear(this)
        super.onDetachedFromWindow()
    }

    fun slideTo(dx: Float) {
        val lim = copyThreshold()
        // Visual clamp at required distance; raw finger can go further for release check.
        front.translationX = if (canSlide(dx)) dx.coerceIn(-lim, lim) else 0f
        frameBg.fill = idleFill
        labelView.setTextColor(labelColor)
        updateHints(front.translationX, abs(dx) >= lim)
        front.invalidate()
    }

    private fun updateHints(dx: Float, @Suppress("UNUSED_PARAMETER") past: Boolean) {
        val showL = dx > W.dp(4)
        val showR = dx < -W.dp(4)
        leftHint.alpha = if (showL) 1f else 0f
        rightHint.alpha = if (showR) 1f else 0f
        leftHint.scaleX = 1f
        rightHint.scaleX = 1f
        // Fixed lighter-blue reveal for the whole gesture (no threshold accent / navy flash).
        reveal.setBackgroundColor(COPY_BLUE)
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
        /** Long-press to enter inline edit (ms). System default is ~500; user asked shorter. */
        const val EDIT_LONG_PRESS_MS = 320L
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
         * Swipe-reveal blue: lighter than [W.NAVY]. Fixed for whole gesture (0.4.3).
         * Reveal shows clipboard glyph only — never field secrets.
         */
        const val COPY_BLUE = 0xFF5BA3E8.toInt()
        /** @deprecated Kept for binary compat / old tests; unused — same as COPY_BLUE. */
        const val COPY_BLUE_ARM = COPY_BLUE
        /** @deprecated Unused since 0.4.3 (no post-copy color flash). */
        const val COPY_BLUE_DONE = COPY_BLUE
        fun mask(t: TextView, hidden: Boolean) { t.transformationMethod = if (hidden) Stars else null as TransformationMethod? }
    }
}
