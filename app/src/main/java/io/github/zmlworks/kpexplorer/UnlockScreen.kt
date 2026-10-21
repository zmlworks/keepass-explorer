package io.github.zmlworks.kpexplorer

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

/** Windows 2000-style "logon" box: fingerprint first, master password as fallback. */
class UnlockScreen(private val a: MainActivity) : MainActivity.Screen {
    override val desktop = true
    private val ctx = a
    private val root: View
    override val view: View get() = root
    val password = W.field(ctx, password = true)
    private val error: TextView = W.text(ctx, "", 14f, color = 0xFFC00000.toInt())
    private var autoPrompted = false
    private val options = W.vbox(ctx)

    init {
        val content = W.vbox(ctx)
        content.addView(banner(), W.lp(W.MATCH, W.dp(84)))
        content.addView(View(ctx).apply { setBackgroundColor(W.NAVY) }, W.lp(W.MATCH, W.dp(4)))
        val inner = W.vbox(ctx, 12)
        val top = W.hbox(ctx).apply { gravity = Gravity.TOP }
        top.addView(W.icon(ctx, Px.keyfile32), W.lp(W.WRAP, W.WRAP, r = 12))
        top.addView(W.text(ctx, "データベースのロックを解除するには、指紋認証を使うか、マスターパスワードを入力してください。", 14f).apply {
            setLineSpacing(0f, 1.15f)
        }, W.lp(0, W.WRAP, 1f))
        inner.addView(top)
        fun row(label: String, v: View) {
            val r = W.hbox(ctx)
            r.addView(W.text(ctx, label, 14f), W.lp(W.dp(108), W.WRAP))
            r.addView(v, W.lp(0, W.WRAP, 1f))
            inner.addView(r, W.lp(W.MATCH, W.WRAP, t = 10))
        }
        val dbName = App.prefs(ctx).getString("dbName", null) ?: "database.kdbx"
        row("データベース(D):", W.text(ctx, dbName, 15f).apply {
            background = W.Bevel(W.Style.SUNKEN, W.FACE); setPadding(W.dp(6), W.dp(8), W.dp(6), W.dp(8)); isSingleLine = true
        })
        row("パスワード(P):", password)
        password.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        password.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
        }
        inner.addView(error, W.lp(W.MATCH, W.WRAP, t = 6))
        val btns = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        btns.addView(W.button(ctx, "OK", true) { submit() }, W.lp(W.WRAP, W.WRAP))
        if (a.vault.hasStoredKey()) btns.addView(W.button(ctx, "指紋で解除") { biometric() }, W.lp(W.WRAP, W.WRAP, l = 6))
        btns.addView(W.button(ctx, "オプション >>") { toggleOptions() }.apply { minWidth = W.dp(70) }, W.lp(W.WRAP, W.WRAP, l = 6))
        inner.addView(btns, W.lp(W.MATCH, W.WRAP, t = 8))
        options.visibility = View.GONE
        options.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 10, b = 6))
        options.addView(W.text(ctx, "別の .kdbx に置き換える場合は、現在のデータベースをバックアップしてから最初のセットアップに戻ります。", 13f).apply {
            setLineSpacing(0f, 1.1f)
        })
        options.addView(W.button(ctx, "データベースを置き換える...") { replaceDb() }, W.lp(W.WRAP, W.WRAP, t = 8))
        inner.addView(options)
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

    private fun toggleOptions() { options.visibility = if (options.visibility == View.GONE) View.VISIBLE else View.GONE }

    override fun onShown() {
        if (!autoPrompted && a.vault.hasStoredKey() && Vault.forceAvailableForTest == null) {
            autoPrompted = true
            password.post { biometric() }
        }
    }

    fun submit() {
        if (password.text.isEmpty()) { error.text = "パスワードを入力してください。"; return }
        val key = io.github.zmlworks.kpexplorer.db.Kdbx.compositeKey(password.text)
        error.text = ""
        Security.openStored(a, key, onError = { msg -> error.text = msg; password.selectAll() }) {
            password.setText("")
            val p = App.prefs(ctx)
            if (p.getBoolean("bio", false) && !a.vault.hasStoredKey() && a.vault.isAvailable())
                Dlg.confirm(ctx, "指紋認証", "指紋認証の鍵が無効になっています。もう一度登録しますか?") { Security.enroll(a) { a.showExplorer() } }
                    .setOnDismissListener { if (a.screen !is ExplorerScreen) a.showExplorer() }
            else a.showExplorer()
        }
    }

    private fun biometric() {
        a.vault.unlock(a, object : Vault.Result {
            override fun ok(composite: ByteArray?) {
                if (composite == null) return
                Security.openStored(a, composite, onError = { msg ->
                    error.text = if (msg.startsWith("パスワード")) "保存された鍵ではデータベースを開けませんでした。パスワードを入力してください。" else msg
                }) { a.showExplorer() }
            }
            override fun fail(msg: String, invalidated: Boolean) {
                if (msg.isNotEmpty()) error.text = msg
                if (invalidated) a.vault.clear()
            }
        })
    }

    private fun replaceDb() {
        Dlg.confirm(ctx, "データベースの置き換え", "現在のデータベースをバックアップしてから削除し、セットアップに戻りますか?", '!') {
            val r = runCatching { a.store.backup() }
            r.onSuccess { f ->
                a.store.delete(); a.vault.clear()
                App.prefs(ctx).edit().remove("dbName").remove("lastGroup").apply()
                Dlg.message(ctx, "データベースの置き換え", "バックアップ ${f.name} を作成しました。\nバックアップはアプリの backups フォルダーに残ります。") { a.route() }
            }.onFailure { Dlg.message(ctx, "データベースの置き換え", "バックアップできなかったため中止しました。\n$it", 'x') }
        }
    }
}
