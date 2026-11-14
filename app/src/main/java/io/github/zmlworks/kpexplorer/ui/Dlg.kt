package io.github.zmlworks.kpexplorer.ui

import io.github.zmlworks.kpexplorer.L
import io.github.zmlworks.kpexplorer.R

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Classic dialog boxes: gray window, navy caption, OK/Cancel at the bottom right.
 * [scroll]: a tall window (80 % of the screen) whose body scrolls. [fit] (0.4.28, messages): the window wraps its
 * body and grows up to the screen; a body taller than that scrolls, with the buttons kept on screen. Up to 0.4.27
 * a message was never scrollable, so a long one (使い方) ran off the screen.
 */
class ClassicDialog(ctx: Context, title: String, body: View, buttons: List<Btn>, scroll: Boolean = false, fit: Boolean = false) :
    Dialog(ctx, android.R.style.Theme_Material_Light_Dialog_NoActionBar) {

    class Btn(val label: String, val default: Boolean = false, val onClick: () -> Boolean = { true })

    val frame: LinearLayout
    val buttonViews = ArrayList<TextView>()
    /** The scroll view around the body ([scroll] or [fit]), else null. */
    var bodyScroll: ScrollView? = null
        private set

    init {
        val content = W.vbox(ctx)
        content.setPadding(W.dp(12), W.dp(14), W.dp(12), W.dp(12))
        if (scroll || fit) {
            val sv = ScrollView(ctx); W.classicScrollbars(sv); sv.addView(body); bodyScroll = sv
            sv.scrollBarStyle = View.SCROLLBARS_INSIDE_INSET   // 0.4.28: the bar beside the text, not over its last letters
            content.addView(sv, W.lp(W.MATCH, 0, 1f))
        } else content.addView(body, W.lp(W.MATCH, W.WRAP))
        if (buttons.isNotEmpty()) {
            val row = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            for (b in buttons) {
                val v = W.button(ctx, b.label, b.default) { if (b.onClick()) dismiss() }
                buttonViews.add(v)
                row.addView(v, W.lp(W.WRAP, W.WRAP, l = 8))
            }
            content.addView(row, W.lp(W.MATCH, W.WRAP, t = 14))
        }
        val cap = W.titleBar(ctx, title, null, onClose = { cancel() })
        frame = W.window(ctx, cap, content, bodyWeight = scroll || fit)
        setContentView(frame)
        window?.setBackgroundDrawable(ColorDrawable(0))
        window?.setDimAmount(0f)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val dm = ctx.resources.displayMetrics
        window?.setLayout(minOf(dm.widthPixels - W.dp(24), W.dp(460)),
            if (scroll) (dm.heightPixels * 0.8f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnShowListener { Dlg.last = this }
        setOnDismissListener { if (Dlg.last === this) Dlg.last = null }
    }
}

object Dlg {
    var last: ClassicDialog? = null

    private fun msgBody(ctx: Context, icon: Char?, msg: CharSequence, extra: View? = null): View {
        val row = W.hbox(ctx).apply { gravity = Gravity.TOP }
        if (icon != null) row.addView(W.icon(ctx, Px.msgIcon(icon)), W.lp(W.WRAP, W.WRAP, r = 14))
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, msg, 15f).apply { setLineSpacing(0f, 1.15f) }, W.lp(W.MATCH, W.WRAP, t = 4))
        if (extra != null) col.addView(extra, W.lp(W.MATCH, W.WRAP, t = 10))
        row.addView(col, W.lp(0, W.WRAP, 1f))
        return row
    }

    fun message(ctx: Context, title: String, msg: CharSequence, icon: Char? = 'i', onOk: () -> Unit = {}): ClassicDialog =
        ClassicDialog(ctx, title, msgBody(ctx, icon, msg), listOf(ClassicDialog.Btn("OK", true) { onOk(); true }), fit = true)
            .also { it.show() }

    fun confirm(ctx: Context, title: String, msg: CharSequence, icon: Char = '?', yes: String? = null, no: String? = null,
                onYes: () -> Unit): ClassicDialog {
        val y = yes ?: ctx.getString(R.string.yes)
        val n = no ?: ctx.getString(R.string.no)
        return ClassicDialog(ctx, title, msgBody(ctx, icon, msg), listOf(
            ClassicDialog.Btn(y, true) { onYes(); true },
            ClassicDialog.Btn(n) { true }), fit = true).also { it.show(); last = it }
    }

    fun prompt(ctx: Context, title: String, label: String, initial: String, ok: String = "OK",
               password: Boolean = false, onOk: (String) -> Boolean): ClassicDialog {
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, label, 15f), W.lp(W.MATCH, W.WRAP))
        val f: EditText = W.field(ctx, password = password).apply { setText(initial); selectAll() }
        col.addView(f, W.lp(W.MATCH, W.WRAP, t = 8))
        val d = ClassicDialog(ctx, title, col, listOf(
            ClassicDialog.Btn(ok, true) { onOk(f.text.toString()) },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel")) { true }))
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        f.requestFocus()
        Handler(Looper.getMainLooper()).postDelayed({
            ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(f, 0)
        }, 150)
        return d
    }

    /** 0.4.23: one-of-N choice (radio buttons); tapping an option applies it and closes the box. */
    fun choose(ctx: Context, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): ClassicDialog {
        val rg = android.widget.RadioGroup(ctx)
        lateinit var d: ClassicDialog
        options.forEachIndexed { i, s ->
            val r = W.radio(ctx, s, i == selected).apply { id = View.generateViewId(); tag = "pick-$i" }
            r.setOnClickListener { d.dismiss(); if (i != selected) onPick(i) }
            rg.addView(r, W.lp(W.MATCH, W.WRAP))
        }
        d = ClassicDialog(ctx, title, rg, listOf(ClassicDialog.Btn(L.t("キャンセル", "Cancel")) { true }))
        d.show(); last = d
        return d
    }

    /** Non-cancelable "working…" box with a classic block progress bar. */
    fun busy(ctx: Context, title: String, msg: String): ClassicDialog {
        val col = W.vbox(ctx)
        col.addView(msgBody(ctx, null, msg))
        col.addView(BlockBar(ctx), W.lp(W.MATCH, W.dp(24), t = 12))
        return ClassicDialog(ctx, title, col, emptyList()).also { it.setCancelable(false); it.show() }
    }

    /** Animated marquee of navy blocks in a sunken well. */
    class BlockBar(ctx: Context) : View(ctx) {
        private val p = Paint().apply { color = W.NAVY }
        private var t = 0
        private val tick = object : Runnable { override fun run() { t++; invalidate(); postDelayed(this, 120) } }
        init { background = W.Bevel(W.Style.SUNKEN) }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
        override fun onDraw(c: Canvas) {
            val pad = 3 * W.px; val bw = W.dp(8); val gap = W.dp(2)
            val n = (width - 2 * pad) / (bw + gap)
            val start = t % (n + 6) - 5
            for (i in 0 until 5) {
                val k = start + i
                if (k < 0 || k >= n) continue
                val x = pad + k * (bw + gap)
                c.drawRect(x.toFloat(), pad.toFloat(), (x + bw).toFloat(), (height - pad).toFloat(), p)
            }
        }
    }
}
