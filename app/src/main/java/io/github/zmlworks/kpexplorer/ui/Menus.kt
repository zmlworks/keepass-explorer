package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** Classic pop-up menus (menu bar drop-downs and right-click style context menus). */
object Menus {
    class Item(val label: String, val enabled: Boolean = true, val checked: Boolean = false,
               val shortcut: String = "", val sep: Boolean = false, val action: () -> Unit = {})

    fun sep() = Item("", sep = true)

    /** For tests/screenshots: the content view of the last menu shown. */
    var lastView: View? = null
    var current: PopupWindow? = null

    fun build(ctx: Context, items: List<Item>, onPick: (Item) -> Unit): View {
        val list = W.vbox(ctx)
        list.setPadding(2 * W.px + W.dp(1), 2 * W.px + W.dp(2), 2 * W.px + W.dp(1), 2 * W.px + W.dp(2))
        for (it in items) {
            if (it.sep) {
                list.addView(View(ctx).apply { background = W.Etched(true) }, W.lp(W.MATCH, W.dp(8), l = 2, r = 2))
                continue
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = W.dp(42)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(W.NAVY))
                    addState(intArrayOf(), ColorDrawable(0))
                }
                isClickable = it.enabled
            }
            val colors = W.colors(android.R.attr.state_pressed to W.HILITE, 0 to W.TEXT)
            val mark = W.text(ctx, if (it.checked) "✓" else "", 15f, true).apply {
                gravity = Gravity.CENTER; setTextColor(colors); isDuplicateParentStateEnabled = true
            }
            row.addView(mark, W.lp(W.dp(28), W.WRAP))
            val label = W.text(ctx, it.label, 15f).apply { setTextColor(colors); isDuplicateParentStateEnabled = true }
            row.addView(label, W.lp(0, W.WRAP, 1f))
            if (it.shortcut.isNotEmpty()) row.addView(W.text(ctx, it.shortcut, 14f).apply {
                setTextColor(colors); isDuplicateParentStateEnabled = true
            }, W.lp(W.WRAP, W.WRAP, l = 16))
            row.addView(View(ctx), W.lp(W.dp(20), 1))
            if (!it.enabled) { W.setEnabledLook(label, false); W.setEnabledLook(mark, false) }
            else row.setOnClickListener { _ -> onPick(it) }
            list.addView(row, W.lp(W.MATCH, W.WRAP))
        }
        val scroll = ScrollView(ctx).apply { isFillViewport = true; background = W.Bevel(W.Style.RAISED) }
        W.classicScrollbars(scroll)
        scroll.addView(list)
        return scroll
    }

    private fun popup(anchor: View, items: List<Item>, minWidthDp: Int): Pair<PopupWindow, View> {
        val ctx = anchor.context
        lateinit var pw: PopupWindow
        val v = build(ctx, items) { item ->
            pw.dismiss()
            item.action()
        }
        val dm = ctx.resources.displayMetrics
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels - W.dp(16), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels - W.dp(48), View.MeasureSpec.AT_MOST))
        val w = maxOf(v.measuredWidth, W.dp(minWidthDp)).coerceAtMost(dm.widthPixels - W.dp(16))
        pw = PopupWindow(v, w, minOf(v.measuredHeight, dm.heightPixels - W.dp(64)), true)
        pw.setBackgroundDrawable(ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.elevation = 0f
        lastView = v
        current = pw
        return pw to v
    }

    /** Drop-down under [anchor] (menu bar). */
    fun dropDown(anchor: View, items: List<Item>, minWidthDp: Int = 220): PopupWindow {
        val (pw, _) = popup(anchor, items, minWidthDp)
        pw.showAsDropDown(anchor, 0, 0)
        return pw
    }

    /** Context menu at a screen position (x, y in window coordinates). */
    fun at(anchor: View, x: Int, y: Int, items: List<Item>, minWidthDp: Int = 230): PopupWindow {
        val (pw, v) = popup(anchor, items, minWidthDp)
        val dm = anchor.context.resources.displayMetrics
        val px = x.coerceAtMost(dm.widthPixels - pw.width - W.dp(4)).coerceAtLeast(W.dp(4))
        val py = y.coerceAtMost(dm.heightPixels - pw.height - W.dp(4)).coerceAtLeast(W.dp(24))
        pw.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, px, py)
        return pw
    }

    fun dismiss() { current?.dismiss(); current = null }
}
