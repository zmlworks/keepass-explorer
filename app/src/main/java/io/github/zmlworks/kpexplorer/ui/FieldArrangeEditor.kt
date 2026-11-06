package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.zmlworks.kpexplorer.KeyFields
import io.github.zmlworks.kpexplorer.db.Order
import kotlin.math.abs

/**
 * Drag-to-reorder field arrangement (鍵「欄の編集」).
 *
 * Rows above 「非表示」 are visible on the detail page; rows below are hidden.
 * Crossing the line toggles visibility only — values stay in the entry
 * ([Order.CD_FIELDS] with a "-" prefix). [Order.F_TITLE] cannot be hidden.
 * Custom fields may be hidden the same way; × removes a custom from the layout
 * (host clears its value on OK). Drag starts from the ☰ handle to avoid
 * conflicting with scroll.
 */
class FieldArrangeEditor(
    private val ctx: Context,
    initial: List<Pair<String, Boolean>>,
    private val onChanged: () -> Unit = {},
) : LinearLayout(ctx) {
    val shown: MutableList<String> = ArrayList()
    val hidden: MutableList<String> = ArrayList()
    val removedCustoms: MutableSet<String> = linkedSetOf()

    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val scroll = ScrollView(ctx).apply { W.classicScrollbars(this); isFillViewport = true }
    private var dragKey: String? = null
    private var dragStartY = 0f
    private val rowStep get() = W.dp(44)

    init {
        orientation = VERTICAL
        load(initial)
        addView(W.text(ctx,
            "☰ を上下ドラッグで並べ替え。線より下＝非表示 (内容は残ります)。タイトルは非表示にできません。カスタム欄の × で欄自体を削除。",
            13f).apply { setLineSpacing(0f, 1.1f) }, W.lp(W.MATCH, W.WRAP, b = 8))
        scroll.addView(list)
        addView(scroll, W.lp(W.MATCH, 0, 1f))
        render()
    }

    private fun load(src: List<Pair<String, Boolean>>) {
        shown.clear(); hidden.clear()
        shown.add(Order.F_TITLE)
        val seen = hashSetOf(Order.F_TITLE)
        for ((k, v) in src) {
            if (k == Order.F_TITLE || k in seen) continue
            seen.add(k)
            if (v) shown.add(k) else hidden.add(k)
        }
    }

    fun snapshot(): List<Pair<String, Boolean>> =
        shown.map { it to true } + hidden.map { it to false }

    fun render() {
        list.removeAllViews()
        shown.forEachIndexed { i, k -> list.addView(fieldRow(k, true, i, false), W.lp(W.MATCH, W.WRAP, b = 2)) }
        list.addView(dividerRow(), W.lp(W.MATCH, W.WRAP, t = 6, b = 6))
        if (hidden.isEmpty()) {
            list.addView(W.text(ctx, "(非表示の欄はここに並びます)", 12f, color = W.SHADOW)
                .apply { setPadding(W.dp(8), W.dp(4), W.dp(8), W.dp(4)) }, W.lp(W.MATCH, W.WRAP))
        } else {
            hidden.forEachIndexed { i, k -> list.addView(fieldRow(k, false, i, true), W.lp(W.MATCH, W.WRAP, b = 2)) }
        }
        onChanged()
    }

    private fun dividerRow(): View {
        val row = W.hbox(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(W.dp(4), W.dp(8), W.dp(4), W.dp(8))
            setBackgroundColor(0xFFE8E4DC.toInt())
            tag = "divider"
        }
        row.addView(View(ctx).apply { setBackgroundColor(W.NAVY) }, W.lp(0, W.dp(2), 1f))
        row.addView(W.text(ctx, " 非表示 ", 13f, true, W.NAVY), W.lp(W.WRAP, W.WRAP))
        row.addView(View(ctx).apply { setBackgroundColor(W.NAVY) }, W.lp(0, W.dp(2), 1f))
        return row
    }

    private fun fieldRow(k: String, vis: Boolean, index: Int, inHidden: Boolean): LinearLayout {
        val row = W.hbox(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = W.dp(44)
            background = W.Bevel(W.Style.THIN_RAISED, if (vis) W.FACE else 0xFFD0D0D0.toInt())
            setPadding(W.dp(4), W.dp(4), W.dp(4), W.dp(4))
            tag = k
        }
        val handle = ImageView(ctx).apply {
            setImageDrawable(Glyph(Glyph.Kind.MORE, W.SHADOW))
            contentDescription = "並べ替え"
            minimumWidth = W.dp(40); minimumHeight = W.dp(40)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(W.dp(8), W.dp(8), W.dp(8), W.dp(8))
        }
        handle.setOnTouchListener { _, e -> onHandleTouch(k, inHidden, e) }
        row.addView(handle, W.lp(W.dp(40), W.dp(40), r = 4))
        val label = KeyFields.label(k).removeSuffix(":")
        row.addView(W.text(ctx, if (vis) label else "($label)", 15f, k == Order.F_TITLE).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }, W.lp(0, W.WRAP, 1f))
        if (k == Order.F_TITLE) {
            row.addView(W.text(ctx, "必須", 11f, color = W.SHADOW), W.lp(W.WRAP, W.WRAP, r = 4))
        } else if (k !in Order.DEFAULT_LAYOUT) {
            row.addView(W.text(ctx, "×", 18f, true).apply {
                gravity = Gravity.CENTER; contentDescription = "欄を削除"
                minimumWidth = W.dp(40); minimumHeight = W.dp(40)
                isClickable = true
                setOnClickListener {
                    shown.remove(k); hidden.remove(k); removedCustoms.add(k); render()
                }
            }, W.lp(W.dp(40), W.dp(40)))
        }
        return row
    }

    private fun onHandleTouch(key: String, inHidden: Boolean, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragKey = key; dragStartY = e.rawY
                scroll.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragKey != key) return true
                val dy = e.rawY - dragStartY
                if (abs(dy) < rowStep * 0.4f) return true
                val dir = if (dy > 0) 1 else -1
                if (step(key, inHidden, dir)) {
                    dragStartY = e.rawY
                    render()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragKey = null
                scroll.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    /** One step up (-1) or down (+1). Returns true if the arrangement changed. */
    fun step(key: String, currentlyHidden: Boolean, dir: Int): Boolean {
        // Title stays pinned at the top of the visible section (cannot hide / reorder).
        if (key == Order.F_TITLE) return false
        if (!currentlyHidden) {
            val i = shown.indexOf(key)
            if (i < 0) return false
            val j = i + dir
            if (dir < 0) {
                if (j < 0) return false
                // keep Title pinned at index 0
                val dest = if (shown.getOrNull(0) == Order.F_TITLE) maxOf(1, j) else j
                if (dest == i) return false
                shown.add(dest, shown.removeAt(i))
                return true
            }
            // dir > 0
            if (j < shown.size) {
                shown.add(j, shown.removeAt(i))
                return true
            }
            // past end of shown → into hidden (head)
            shown.removeAt(i)
            hidden.add(0, key)
            return true
        } else {
            val i = hidden.indexOf(key)
            if (i < 0) return false
            val j = i + dir
            if (dir > 0) {
                if (j >= hidden.size) return false
                hidden.add(j, hidden.removeAt(i))
                return true
            }
            // dir < 0
            if (j >= 0) {
                hidden.add(j, hidden.removeAt(i))
                return true
            }
            // past start of hidden → end of shown
            hidden.removeAt(i)
            shown.add(key)
            return true
        }
    }

    /** Tests / screenshots: move [key] into shown or hidden at [index] within that zone. */
    fun place(key: String, visible: Boolean, index: Int) {
        if (key == Order.F_TITLE && !visible) return
        shown.remove(key); hidden.remove(key)
        if (key == Order.F_TITLE) {
            shown.add(index.coerceIn(0, shown.size), key)
        } else if (visible) {
            shown.add(index.coerceIn(0, shown.size), key)
        } else {
            hidden.add(index.coerceIn(0, hidden.size), key)
        }
        // Title stays in shown
        if (Order.F_TITLE !in shown) {
            hidden.remove(Order.F_TITLE)
            shown.add(0, Order.F_TITLE)
        }
        render()
    }
}
