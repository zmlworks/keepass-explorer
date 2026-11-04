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

/** Windows 2000-style "logon" box: fingerprint / OS screen lock (wrapped key), or the master password. */
class UnlockScreen(private val a: MainActivity) : MainActivity.Screen {
    override val desktop = true
    private val ctx = a
    private val root: View
    override val view: View get() = root
    val error: TextView = W.text(ctx, "", 14f, color = 0xFFC00000.toInt())
    private var autoPrompted = false
    private val info = W.text(ctx, "指紋、またはこの端末の画面ロック (PIN・パターン・パスワード) でロックを解除します。" +
        "マスターパスワードでも開けます。", 14f).apply { setLineSpacing(0f, 1.15f) }
    val pw = W.field(ctx, password = true).apply { isSingleLine = true }
    val pwBox = W.vbox(ctx)
    private val bio = W.button(ctx, "指紋・画面ロック(U)", true) { unlock() }

    init {
        val content = W.vbox(ctx)
        content.addView(banner(), W.lp(W.MATCH, W.dp(84)))
        content.addView(View(ctx).apply { setBackgroundColor(W.NAVY) }, W.lp(W.MATCH, W.dp(4)))
        val inner = W.vbox(ctx, 12)
        val top = W.hbox(ctx).apply { gravity = Gravity.TOP }
        top.addView(W.icon(ctx, Px.keyfile32), W.lp(W.WRAP, W.WRAP, r = 12))
        top.addView(info, W.lp(0, W.WRAP, 1f))
        inner.addView(top)
        pwBox.addView(W.text(ctx, "マスターパスワード:", 14f), W.lp(W.MATCH, W.WRAP))
        pwBox.addView(pw, W.lp(W.MATCH, W.WRAP, t = 3))
        pwBox.addView(W.button(ctx, "開く(O)", true) { openWithPassword() }.apply { minWidth = W.dp(96) },
            W.lp(W.WRAP, W.WRAP, t = 6).apply { gravity = Gravity.END })
        inner.addView(pwBox, W.lp(W.MATCH, W.WRAP, t = 8))
        inner.addView(error, W.lp(W.MATCH, W.WRAP, t = 10))
        val btns = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        btns.addView(bio, W.lp(W.WRAP, W.WRAP))
        btns.addView(W.button(ctx, "終了") { a.finish() }, W.lp(W.WRAP, W.WRAP, l = 6))
        inner.addView(btns, W.lp(W.MATCH, W.WRAP, t = 8))
        val alt = W.hbox(ctx).apply { gravity = Gravity.END }
        alt.addView(W.button(ctx, "マスターパスワードで開く...") { showPassword(null) }, W.lp(W.WRAP, W.WRAP))
        inner.addView(alt, W.lp(W.MATCH, W.WRAP, t = 6))
        if (!a.vault.hasStoredKey()) showPassword("指紋・画面ロック用の鍵がないため、マスターパスワードで開いてください。")
        else pwBox.visibility = View.GONE
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
        if (!autoPrompted && Vault.forceAvailableForTest == null && a.vault.hasStoredKey()) {
            autoPrompted = true
            root.post { unlock() }
        }
    }

    /** Show the master-password box ([why] = reason, e.g. the Keystore key is gone). */
    fun showPassword(why: String?) {
        pwBox.visibility = View.VISIBLE
        if (why != null) error.text = why
        pw.requestFocus()
    }

    fun openWithPassword() {
        val p = pw.text.toString()
        if (p.isEmpty()) { error.text = "マスターパスワードを入力してください。"; return }
        pw.setText("")
        error.text = ""
        Security.unlockWithPassword(a, p, onError = { error.text = it }) { a.showExplorer() }
    }

    fun unlock() {
        error.text = ""
        if (!a.vault.hasStoredKey()) { showPassword("指紋・画面ロック用の鍵がないため、マスターパスワードで開いてください。"); return }
        Security.unlock(a, onError = { error.text = it }, onLost = { showPassword("$it\nマスターパスワードで開くと、もう一度登録します。") }) { a.showExplorer() }
    }
}
