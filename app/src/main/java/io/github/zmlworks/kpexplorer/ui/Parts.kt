package io.github.zmlworks.kpexplorer.ui

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
        path.forEachIndexed { i, g ->
            val name = if (i == 0) "/" else g.name.ifEmpty { "(名前なし)" }
            val row = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL; tag = g.uuid; minimumHeight = W.dp(26) }
            row.setPadding(W.dp(20) * i, 0, 0, 0)
            if (i > 0) row.addView(W.text(ctx, "└", 15f), W.lp(W.WRAP, W.WRAP))
            row.addView(W.icon(ctx, Px.folder16, maxOf(1, W.iconScale() - 2)).apply { setPadding(W.dp(2), 0, W.dp(3), 0) }, W.lp(W.WRAP, W.WRAP))
            row.addView(W.text(ctx, name, 15f, bold = i == path.size - 1).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END },
                W.lp(W.WRAP, W.WRAP))
            if (showCounts) {
                row.addView(W.text(ctx, " (${g.groups.size},${g.entries.size})", 13f, color = W.SHADOW).apply { isSingleLine = true; tag = "count" },
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
        foot.addView(W.text(ctx, "← 戻る", 17f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON); isClickable = true; contentDescription = "戻る"
            setOnClickListener { onBack() }
        }, W.lp(0, W.dp(56), 1f, r = 6))
        val sv = W.hbox(ctx).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.DEFAULT_BUTTON); isClickable = true; contentDescription = "保存"
            setOnClickListener { onSave() }
        }
        sv.addView(ImageView(ctx).apply { setImageDrawable(Glyph(Glyph.Kind.FLOPPY)) }, W.lp(W.dp(24), W.dp(24), r = 8))
        sv.addView(W.text(ctx, "保存", 17f, true), W.lp(W.WRAP, W.WRAP))
        foot.addView(sv, W.lp(0, W.dp(56), 1f, l = 6))
        return foot
    }

    class AppBar(val bar: LinearLayout, val title: TextView)


    /**
     * Key detail title bar (0.3.13): navy gradient; physically centered title; flat × only on the right
     * (no ⋮ — actions live on fields / 「欄を編集」 / bottom secondary row).
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
            contentDescription = "閉じる"; tooltipText = "閉じる"
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
    fun appBar(ctx: Context, title: String, onBack: () -> Unit, titleView: TextView? = null): AppBar {
        val bar = W.hbox(ctx)
        bar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(W.accent1(), W.accent2()))
        bar.setPadding(W.dp(3), W.dp(3), W.dp(3), W.dp(3))
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = W.text(ctx, "←", 20f, true).apply {
            background = W.Bevel(W.Style.BUTTON); gravity = Gravity.CENTER; contentDescription = "戻る"
            isClickable = true; setOnClickListener { onBack() }
        }
        bar.addView(back, W.lp(W.dp(44), W.dp(38), r = 6))
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
     * coordinates, so the rules scroll together with the text. A red margin line runs down the left side.
     */
    class RuledText(ctx: Context, editable: Boolean) : EditText(ctx) {
        private val rule = Paint().apply { color = 0xFF9EB8E0.toInt(); strokeWidth = maxOf(1f, W.px.toFloat()) }
        private val margin = Paint().apply { color = 0xFFE89090.toInt(); strokeWidth = maxOf(1f, W.px.toFloat()) }
        private val r = android.graphics.Rect()
        /** Rule positions drawn last (content coordinates) — exposed for tests. */
        val ruleYs = ArrayList<Int>()
        init {
            setBackgroundColor(0xFFFFFFF4.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(W.TEXT)
            gravity = Gravity.TOP or Gravity.START
            setPadding(W.dp(22), W.dp(8), W.dp(8), W.dp(8))
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
        override fun onDraw(c: Canvas) {
            ruleYs.clear()
            val gap = W.dp(4)                       // rule sits a little below the baseline (under descenders of Latin text)
            val bottom = scrollY + height           // visible bottom in content coordinates
            val l = layout
            var last = -1
            var step = lineHeight
            if (l != null) {
                for (i in 0 until lineCount) {
                    val base = getLineBounds(i, r)  // baseline incl. padding, in content coordinates
                    val y = base + gap
                    ruleYs.add(y); last = y
                    if (i > 0) step = maxOf(1, l.getLineBaseline(i) - l.getLineBaseline(i - 1))
                }
            }
            var y = if (last < 0) extendedPaddingTop + (l?.getLineBaseline(0) ?: lineHeight) + gap else last + step
            while (y < bottom) { ruleYs.add(y); y += step }
            for (yy in ruleYs) if (yy >= scrollY) c.drawLine(scrollX.toFloat(), yy.toFloat(), (scrollX + width).toFloat(), yy.toFloat(), rule)
            val mx = (scrollX + W.dp(16)).toFloat()
            c.drawLine(mx, scrollY.toFloat(), mx, bottom.toFloat(), margin)
            super.onDraw(c)
        }
    }
}
