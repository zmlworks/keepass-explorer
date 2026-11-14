package io.github.zmlworks.kpexplorer.ui

import io.github.zmlworks.kpexplorer.L
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.keemobile.kotpass.models.Group

/** Shared 0.3.0 widgets: "└" path tree, navy app bar with ←, ruled notebook text. */
object Parts {
    /**
     * Folder path as an indented tree, one line per level:
     *   /
     *     └フォルダ１
     *         └フォルダ２
     * Every line is a [View] tagged with the group uuid (drop target / tap to navigate).
     */
    /**
     * Folder path as indented tree. [showCounts] is for rare callers; NewKey / Key location paths
     * omit "(folders,files)" counts (0.4.2/0.4.3). Explorer tree uses [FolderTree] separately.
     */
    fun pathTree(ctx: Context, path: List<Group>, onTap: ((Group) -> Unit)? = null,
                 lines: MutableList<View>? = null, showCounts: Boolean = false): LinearLayout {
        val box = W.vbox(ctx).apply { setPadding(W.dp(6), W.dp(3), W.dp(6), W.dp(4)) }
        val binId = io.github.zmlworks.kpexplorer.Session.db?.let { io.github.zmlworks.kpexplorer.db.Tree.recycleBinId(it) }
        path.forEachIndexed { i, g ->
            val name = if (i == 0) "/" else g.name.ifEmpty { L.t("(名前なし)", "(no name)") }
            val row = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL; tag = g.uuid; minimumHeight = W.dp(26) }
            row.setPadding(W.dp(20) * i, 0, 0, 0)
            if (i > 0) row.addView(W.text(ctx, "└", 15f), W.lp(W.WRAP, W.WRAP))
            row.addView(W.icon(ctx, if (g.uuid == binId) Px.bin16 else Px.folder16, maxOf(1, W.iconScale() - 2)).apply { setPadding(W.dp(2), 0, W.dp(3), 0) }, W.lp(W.WRAP, W.WRAP))
            row.addView(W.text(ctx, name, 15f, bold = i == path.size - 1).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END },
                W.lp(W.WRAP, W.WRAP))
            if (showCounts) {
                row.addView(W.text(ctx, " " + io.github.zmlworks.kpexplorer.db.Order.counts(g, io.github.zmlworks.kpexplorer.Session.db?.let { io.github.zmlworks.kpexplorer.db.Tree.hiddenGroups(it) }.orEmpty()), 13f, color = W.SHADOW).apply { isSingleLine = true; tag = "count" },
                    W.lp(0, W.WRAP, 1f))
            }
            if (onTap != null) { row.isClickable = true; row.background = W.Bevel(W.Style.NONE); row.setOnClickListener { onTap(g) } }
            row.setPadding(W.dp(20) * i, 0, 0, 0)
            box.addView(row, W.lp(W.MATCH, W.WRAP))
            lines?.add(row)
        }
        return box
    }

    /** Bottom 戻る / 保存 bar (key and memo screens): tall buttons, 17 sp text, floppy glyph on 保存. */
    fun footer(ctx: Context, onBack: () -> Unit, onSave: () -> Unit): LinearLayout {
        val foot = W.hbox(ctx).apply { gravity = Gravity.CENTER; setPadding(W.dp(6), W.dp(8), W.dp(6), W.dp(8)) }
        foot.addView(W.text(ctx, L.t("← 戻る", "← Back"), 17f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON); isClickable = true; contentDescription = L.t("戻る", "Back")
            setOnClickListener { onBack() }
        }, W.lp(0, W.dp(56), 1f, r = 6))
        val sv = W.hbox(ctx).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.DEFAULT_BUTTON); isClickable = true; contentDescription = L.t("保存", "Save")
            setOnClickListener { onSave() }
        }
        sv.addView(ImageView(ctx).apply { setImageDrawable(Glyph(Glyph.Kind.FLOPPY)) }, W.lp(W.dp(24), W.dp(24), r = 8))
        sv.addView(W.text(ctx, L.t("保存", "Save"), 17f, true), W.lp(W.WRAP, W.WRAP))
        foot.addView(sv, W.lp(0, W.dp(56), 1f, l = 6))
        return foot
    }

    /** 0.4.14 key-page footer: a single long [OK] when unchanged, ← 戻る / 保存 once something changed. */
    class OkFooter(val view: LinearLayout, val pair: View, val ok: View) {
        var changed = false
            private set
        fun setChanged(c: Boolean) {
            changed = c
            pair.visibility = if (c) View.VISIBLE else View.GONE
            ok.visibility = if (c) View.GONE else View.VISIBLE
        }
    }

    fun okFooter(ctx: Context, onBack: () -> Unit, onSave: () -> Unit, onOk: () -> Unit): OkFooter {
        val box = W.vbox(ctx)
        val pair = footer(ctx, onBack, onSave)
        box.addView(pair, W.lp(W.MATCH, W.WRAP))
        val okRow = W.hbox(ctx).apply { gravity = Gravity.CENTER; setPadding(W.dp(6), W.dp(8), W.dp(6), W.dp(8)) }
        okRow.addView(W.text(ctx, "OK", 17f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.DEFAULT_BUTTON); isClickable = true; contentDescription = "OK"
            setOnClickListener { onOk() }
        }, W.lp(W.MATCH, W.dp(56)))
        box.addView(okRow, W.lp(W.MATCH, W.WRAP))
        return OkFooter(box, pair, okRow).also { it.setChanged(false) }
    }

    class AppBar(val bar: LinearLayout, val title: TextView)


    /**
     * Key detail title bar (0.3.13): navy gradient; physically centered title; flat × only on the right
     * (no ⋮ — actions live on fields / 「項目の編集」 / bottom secondary row).
     */
    fun keyDetailBar(ctx: Context, title: String, onClose: () -> Unit): AppBar {
        val bar = W.hbox(ctx)
        bar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(W.accent1(), W.accent2()))
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.minimumHeight = W.dp(48)
        val frame = android.widget.FrameLayout(ctx)
        val t = W.text(ctx, title, 17f, true, W.HILITE).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER; setPadding(W.dp(56), 0, W.dp(56), 0)
        }
        frame.addView(t, android.widget.FrameLayout.LayoutParams(W.MATCH, W.MATCH, Gravity.CENTER))
        val close = ImageView(ctx).apply {
            setImageDrawable(Glyph(Glyph.Kind.CLOSE, W.HILITE))
            scaleType = ImageView.ScaleType.CENTER
            background = null
            contentDescription = L.t("閉じる", "Close"); tooltipText = L.t("閉じる", "Close")
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true; isFocusable = true
            minimumWidth = W.dp(48); minimumHeight = W.dp(48)
            setPadding(W.dp(12), W.dp(12), W.dp(12), W.dp(12))
            setOnClickListener { onClose() }
        }
        frame.addView(close, android.widget.FrameLayout.LayoutParams(W.dp(48), W.MATCH, Gravity.END or Gravity.CENTER_VERTICAL))
        bar.addView(frame, W.lp(0, W.dp(48), 1f))
        return AppBar(bar, t)
    }

    /** Navy gradient bar: big "←" back button, title, optional right-side tool buttons. */
    fun appBar(ctx: Context, title: String, onBack: () -> Unit, titleView: TextView? = null, back: Boolean = true): AppBar {
        val bar = W.hbox(ctx)
        bar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(W.accent1(), W.accent2()))
        bar.setPadding(W.dp(3), W.dp(3), W.dp(3), W.dp(3))
        bar.gravity = Gravity.CENTER_VERTICAL
        val backButton = W.text(ctx, "←", 20f, true).apply {
            background = W.Bevel(W.Style.BUTTON); gravity = Gravity.CENTER; contentDescription = L.t("戻る", "Back")
            isClickable = true; setOnClickListener { onBack() }
        }
        if (back) bar.addView(backButton, W.lp(W.dp(44), W.dp(38), r = 6))
        else bar.minimumHeight = W.dp(44)
        val t = titleView ?: W.text(ctx, title, 17f, true, W.HILITE).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        bar.addView(t, W.lp(0, W.WRAP, 1f))
        return AppBar(bar, t)
    }

    fun barButton(ctx: Context, label: String, desc: String, f: (View) -> Unit): TextView =
        W.text(ctx, label, 15f, true).apply {
            background = W.Bevel(W.Style.BUTTON); gravity = Gravity.CENTER; contentDescription = desc; tooltipText = desc
            setPadding(W.dp(10), 0, W.dp(10), 0); minWidth = W.dp(44)
            isClickable = true; setOnClickListener(f)
        }

    fun barIcon(ctx: Context, bmp: android.graphics.Bitmap, desc: String, f: (View) -> Unit): ImageView = ImageView(ctx).apply {
        setImageDrawable(Px.drawable(ctx, bmp, W.iconScale() - 1)); scaleType = ImageView.ScaleType.CENTER
        background = W.Bevel(W.Style.BUTTON); contentDescription = desc; tooltipText = desc
        isClickable = true; setOnClickListener(f)
    }

    /** Classic toggle buttons for tags; [on] tags are shown pressed with a check mark. */
    fun tagChips(ctx: Context, tags: List<String>, on: Set<String>, onTap: (String) -> Unit): FlowLayout {
        val chips = FlowLayout(ctx)
        for (t in tags.take(80)) {
            val sel = t in on
            chips.addView(W.text(ctx, if (sel) "✓ $t" else t, 14f).apply {
                background = W.Bevel(W.Style.BUTTON, if (sel) W.LIGHT else W.FACE).also { it.forcePressed = sel }
                setPadding(W.dp(10), W.dp(6), W.dp(10), W.dp(6))
                isClickable = true; setOnClickListener { onTap(t) }
            })
        }
        return chips
    }

    /**
     * Notebook paper. One rule per text line, drawn just under that line's baseline from the real [getLayout]
     * line bounds (so wrapped lines and mixed line heights stay aligned); below the last line the rules continue
     * at [getLineHeight] spacing down to the bottom of the visible area. Everything is drawn in content
     * coordinates, so the rules scroll together with the text. 0.4.14: dotted rules, no red margin line.
     */
    /**
     * Ruled notebook text. [centred] (0.4.29, the memo page): a memo shorter than the page sits in the middle of
     * the page's height (the block of lines; each line stays left-aligned). A taller one starts at the top and
     * scrolls as before. The rules run over the whole page, above the text too, in step with the lines.
     */
    class RuledText(ctx: Context, editable: Boolean, private val centred: Boolean = false) : EditText(ctx) {
        private val rule = Paint().apply {
            color = 0xFF8FAAD6.toInt(); strokeWidth = maxOf(1f, W.px.toFloat())
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(RULE_DOT_DP * W.dp(1), RULE_GAP_DP * W.dp(1)), 0f)
        }
        private val r = android.graphics.Rect()
        /** Rules under each line, then on down the page (content coordinates) — exposed for tests. */
        val ruleYs = ArrayList<Int>()
        /** Rules above the first line, nearest first (only when the text does not start at the top; tests). */
        val ruleYsAbove = ArrayList<Int>()
        init {
            setBackgroundColor(0xFFFFFFF4.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(W.TEXT)
            gravity = (if (centred) Gravity.CENTER_VERTICAL else Gravity.TOP) or Gravity.START
            setPadding(W.dp(12), W.dp(8), W.dp(8), W.dp(8))
            setLineSpacing(W.dp(8).toFloat(), 1f)
            includeFontPadding = false
            isFallbackLineSpacing = false   // keep every line the same height (emoji / CJK fallback fonts)
            isSingleLine = false
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            if (!editable) { isFocusable = false; isCursorVisible = false; keyListener = null; setTextIsSelectable(true) }
        }
        /**
         * A rule just under each baseline (0.4.27; 0.4.28's rules midway between lines were a misreading of
         * 「行を中央寄せ」 and are gone), on down the page, and (0.4.29) up the page above a centred text.
         */
        override fun onDraw(c: Canvas) {
            ruleYs.clear(); ruleYsAbove.clear()
            val gap = W.dp(4)                       // rule sits a little below the baseline (under descenders of Latin text)
            val bottom = scrollY + height           // visible bottom in content coordinates
            val l = layout
            var last = -1
            var step = lineHeight
            if (l != null) {
                for (i in 0 until lineCount) {
                    val base = getLineBounds(i, r)  // baseline incl. padding and the centring offset, content coordinates
                    val y = base + gap
                    ruleYs.add(y); last = y
                    if (i > 0) step = maxOf(1, l.getLineBaseline(i) - l.getLineBaseline(i - 1))
                }
            }
            var y = if (last < 0) extendedPaddingTop + (l?.getLineBaseline(0) ?: lineHeight) + gap else last + step
            while (y < bottom) { ruleYs.add(y); y += step }
            // above the first line (centred text), in step up to the top padding; none for text at the top
            var up = (ruleYs.firstOrNull() ?: y) - step
            while (up >= scrollY + paddingTop) { ruleYsAbove.add(up); up -= step }
            for (yy in ruleYsAbove + ruleYs) if (yy >= scrollY) c.drawLine(scrollX.toFloat(), yy.toFloat(), (scrollX + width).toFloat(), yy.toFloat(), rule)
            super.onDraw(c)
        }
        /**
         * 0.4.30: a centred text moves inside the view when the view's height changes (the keyboard opening or
         * closing), but the view itself stays put, so the editor kept its cursor / selection handles where the
         * text used to be (a stray teardrop below or above the cursor). Telling it that the content moved —
         * which is what the editor watches for, as after a scroll — places them on the text again.
         */
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (centred && h != oldh) onScrollChanged(scrollX, scrollY, scrollX, scrollY)
        }

        private var downX = 0f
        private var downY = 0f
        private var tapCandidate = false
        /**
         * 0.4.30: the tap that starts editing (the page is not focused yet) only puts the cursor where it was
         * tapped and opens the keyboard — no teardrop handle, which Android otherwise shows on every tap. Taps
         * while already editing, long presses (word selection) and drags (scrolling) are left to the EditText.
         */
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (!isEditableText || isFocused) return super.onTouchEvent(e)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; tapCandidate = true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) tapCandidate = false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    // (the system delay, not W.longPressMs: past it the EditText's own long press selects a word)
                    if (tapCandidate && e.eventTime - e.downTime < android.view.ViewConfiguration.getLongPressTimeout()) {
                        tapCandidate = false
                        val cancel = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel); cancel.recycle()
                        startEditingAt(e.x, e.y)
                        performClick()
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> tapCandidate = false
            }
            return super.onTouchEvent(e)
        }
        private val isEditableText: Boolean get() = isFocusableInTouchMode && keyListener != null

        /** Focus, cursor at the tapped point ([x], [y] in view coordinates) and the keyboard. */
        fun startEditingAt(x: Float, y: Float) {
            requestFocus()
            setSelection(getOffsetForPosition(x, y))
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(this, 0)
        }

        /** The rule's paint (tests: dotted, no margin). */
        val rulePaint: Paint get() = rule
        companion object { const val RULE_DOT_DP = 1.5f; const val RULE_GAP_DP = 2.5f }
    }
}
