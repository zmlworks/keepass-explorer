package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.zmlworks.kpexplorer.L
import io.github.zmlworks.kpexplorer.sec.PasswordStrength

/**
 * 0.4.37: live password strength under a new-password field (setup, master password change, export password):
 * a sunken bar filled in five steps (red → green) and "強度: …". Strong = [PasswordStrength.STRONG_LOG10].
 */
class StrengthMeter(ctx: Context, private val field: EditText) : LinearLayout(ctx) {
    var result: PasswordStrength.Result? = null
        private set
    val bar = Bar(ctx)
    val label: TextView = W.text(ctx, "", 13f)

    init {
        orientation = VERTICAL
        tag = TAG
        addView(bar, W.lp(W.MATCH, W.dp(12)))
        addView(label, W.lp(W.MATCH, W.WRAP, t = 2))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = update()
        })
        update()
    }

    fun update() {
        val t = field.text
        result = if (t.isNullOrEmpty()) null else PasswordStrength.judge(t)
        val r = result
        label.text = if (r == null) L.t("強度: (未入力)", "Strength: (empty)")
            else L.t("強度: ${r.label}", "Strength: ${r.label}") + if (r.strong) "" else L.t(" — 認証なしの書き出しには「強い」以上が必要です", " — export without unlocking needs Strong or better")
        label.setTextColor(if (r == null) GREY else TEXT_COLORS[r.level])
        bar.invalidate()
    }

    inner class Bar(ctx: Context) : View(ctx) {
        private val p = Paint()
        private val frame = W.Bevel(W.Style.FIELD)
        override fun onDraw(c: Canvas) {
            frame.setBounds(0, 0, width, height); frame.draw(c)
            val r = result ?: return
            val inset = 2 * W.px
            val w = (width - 2 * inset) * (r.level + 1) / 5
            p.color = COLORS[r.level]
            c.drawRect(inset.toFloat(), inset.toFloat(), (inset + w).toFloat(), (height - inset).toFloat(), p)
        }
    }

    companion object {
        const val TAG = "strength-meter"
        val COLORS = intArrayOf(0xFFC00000.toInt(), 0xFFE06000.toInt(), 0xFFE0C000.toInt(), 0xFF80B000.toInt(), 0xFF008000.toInt())
        /** Darker versions for the label (readable on the grey window face). */
        val TEXT_COLORS = intArrayOf(0xFFB00000.toInt(), 0xFF903800.toInt(), 0xFF705000.toInt(), 0xFF3C5A00.toInt(), 0xFF005A00.toInt())
        private const val GREY = 0xFF606060.toInt()
    }
}
