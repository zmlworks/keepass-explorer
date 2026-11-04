package io.github.zmlworks.kpexplorer

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.TextView
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

/** Windows 2000-style "logon" box: the OS screen lock (fingerprint or device PIN) is the only way in. */
class UnlockScreen(private val a: MainActivity) : MainActivity.Screen {
    override val desktop = true
    private val ctx = a
    private val root: View
    override val view: View get() = root
    val error: TextView = W.text(ctx, "", 14f, color = 0xFFC00000.toInt())
    private var autoPrompted = false

    init {
        val content = W.vbox(ctx)
        content.addView(banner(), W.lp(W.MATCH, W.dp(84)))
        content.addView(View(ctx).apply { setBackgroundColor(W.NAVY) }, W.lp(W.MATCH, W.dp(4)))
        val inner = W.vbox(ctx, 12)
        val top = W.hbox(ctx).apply { gravity = Gravity.TOP }
        top.addView(W.icon(ctx, Px.keyfile32), W.lp(W.WRAP, W.WRAP, r = 12))
        top.addView(W.text(ctx, "ロックを解除するには、指紋、またはこの端末の画面ロック (PIN・パターン・パスワード) を使ってください。", 14f).apply {
            setLineSpacing(0f, 1.15f)
        }, W.lp(0, W.WRAP, 1f))
        inner.addView(top)
        inner.addView(error, W.lp(W.MATCH, W.WRAP, t = 10))
        val btns = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        btns.addView(W.button(ctx, "ロック解除(U)", true) { unlock() }, W.lp(W.WRAP, W.WRAP))
        btns.addView(W.button(ctx, "終了") { a.finish() }, W.lp(W.WRAP, W.WRAP, l = 6))
        inner.addView(btns, W.lp(W.MATCH, W.WRAP, t = 8))
        content.addView(inner)
        val cap = W.titleBar(ctx, "KeePass Explorer へログオン", Px.key16, onClose = null)
        root = Desktop.frame(ctx, W.window(ctx, cap, content))
    }

    /** Win2000 logon banner. */
    private fun banner(): View = object : View(ctx) {
        override fun onDraw(c: Canvas) {
            val p = Paint()
            p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, 0xFF000060.toInt(), 0xFF3A6EA5.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.shader = null
            p.isAntiAlias = true
            p.color = W.HILITE
            p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            p.textSize = W.dp(26).toFloat()
            c.drawText("KeePass", W.dp(18).toFloat(), W.dp(44).toFloat(), p)
            val w = p.measureText("KeePass ")
            p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            p.textSize = W.dp(24).toFloat()
            c.drawText("Explorer", W.dp(18) + w, W.dp(44).toFloat(), p)
            p.typeface = Typeface.DEFAULT
            p.textSize = W.dp(12).toFloat()
            p.color = 0xFFC0D0FF.toInt()
            c.drawText("Classic Edition  —  KDBX Password Manager", W.dp(20).toFloat(), W.dp(68).toFloat(), p)
        }
    }

    override fun onShown() {
        Session.takeLockSaveError()?.let {
            Dlg.message(ctx, "保存エラー", "ロックする直前の変更を保存できませんでした。最後の変更は失われた可能性があります。\n$it", 'x')
        }
        if (!autoPrompted && Vault.forceAvailableForTest == null) {
            autoPrompted = true
            root.post { unlock() }
        }
    }

    fun unlock() {
        error.text = ""
        if (!a.vault.hasStoredKey()) { error.text = "鍵が見つかりません。アプリのデータを削除して、セットアップからやり直してください。"; return }
        Security.unlock(a, onError = { error.text = it }) { a.showExplorer() }
    }
}
