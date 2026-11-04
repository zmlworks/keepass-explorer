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
     *   メイン
     *     └フォルダ１
     *         └フォルダ２
     * Every line is a [View] tagged with the group uuid (drop target / tap to navigate).
     */
    fun pathTree(ctx: Context, path: List<Group>, onTap: ((Group) -> Unit)? = null, lines: MutableList<View>? = null): LinearLayout {
        val box = W.vbox(ctx).apply { setPadding(W.dp(6), W.dp(3), W.dp(6), W.dp(4)) }
        path.forEachIndexed { i, g ->
            val name = g.name.ifEmpty { "(名前なし)" }
            val row = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL; tag = g.uuid; minimumHeight = W.dp(26) }
            row.setPadding(if (i == 0) 0 else W.dp(10) + W.dp(18) * (i - 1), 0, 0, 0)
            if (i > 0) row.addView(W.text(ctx, "└", 15f), W.lp(W.WRAP, W.WRAP))
            row.addView(W.icon(ctx, Px.folder16, maxOf(1, W.iconScale() - 2)).apply { setPadding(W.dp(2), 0, W.dp(3), 0) }, W.lp(W.WRAP, W.WRAP))
            row.addView(W.text(ctx, name, 15f, bold = i == path.size - 1).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END },
                W.lp(0, W.WRAP, 1f))
            if (onTap != null) { row.isClickable = true; row.background = W.Bevel(W.Style.NONE); row.setOnClickListener { onTap(g) } }
            box.addView(row, W.lp(W.MATCH, W.WRAP))
            lines?.add(row)
        }
        return box
    }

    class AppBar(val bar: LinearLayout, val title: TextView)

    /** Navy gradient bar: big "←" back button, title, optional right-side tool buttons. */
    fun appBar(ctx: Context, title: String, onBack: () -> Unit, titleView: TextView? = null): AppBar {
        val bar = W.hbox(ctx)
        bar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(W.NAVY, W.TITLE2))
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

    /** Notebook paper: light-blue rule under every text line, red margin. */
    class RuledText(ctx: Context, editable: Boolean) : EditText(ctx) {
        private val p = Paint().apply { color = 0xFF9EB8E0.toInt(); strokeWidth = maxOf(1f, W.px.toFloat()) }
        private val margin = Paint().apply { color = 0xFFE89090.toInt(); strokeWidth = maxOf(1f, W.px.toFloat()) }
        init {
            setBackgroundColor(0xFFFFFFF4.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(W.TEXT)
            gravity = Gravity.TOP or Gravity.START
            setPadding(W.dp(22), W.dp(8), W.dp(8), W.dp(8))
            setLineSpacing(W.dp(8).toFloat(), 1f)
            isSingleLine = false
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            if (!editable) { isFocusable = false; isCursorVisible = false; keyListener = null; setTextIsSelectable(true) }
        }
        override fun onDraw(c: Canvas) {
            val lh = lineHeight
            val base = paddingTop + (layout?.getLineBaseline(0) ?: (lh - W.dp(8))) - paddingTop
            val h = maxOf(height, (layout?.height ?: 0) + paddingTop + paddingBottom) + scrollY
            var y = paddingTop + base + W.dp(4)
            while (y < h) { c.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), p); y += lh }
            c.drawLine(W.dp(16).toFloat(), 0f, W.dp(16).toFloat(), h.toFloat(), margin)
            super.onDraw(c)
        }
    }
}
