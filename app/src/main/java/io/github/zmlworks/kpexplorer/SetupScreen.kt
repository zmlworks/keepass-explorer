package io.github.zmlworks.kpexplorer

import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.TextView
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

/**
 * First launch: no master password. The database key is random and protected by the OS screen lock
 * (fingerprint or the device PIN / pattern / password). Opening an existing .kdbx is not offered (undecided).
 */
class SetupScreen(private val a: MainActivity) : MainActivity.Screen {
    override val desktop = true
    private val ctx = a
    private val root: View
    override val view: View get() = root
    val error: TextView = W.text(ctx, "", 14f, color = 0xFFC00000.toInt())
    private val secure = a.vault.isAvailable()

    init {
        val content = W.vbox(ctx)
        val split = W.hbox(ctx).apply { gravity = Gravity.TOP }
        split.addView(Desktop.wizardArt(ctx), W.lp(W.dp(112), W.MATCH))
        val body = W.vbox(ctx).apply { setPadding(W.dp(12), W.dp(10), W.dp(10), W.dp(10)) }
        body.addView(W.text(ctx, "KeePass Explorer へようこそ", 19f, true), W.lp(W.MATCH, W.WRAP, b = 10))
        body.addView(W.text(ctx, "パスワードや鍵、メモを、エクスプローラーのようなフォルダーで整理して保存します。\n\n" +
            "ロックの解除には、この端末の画面ロック (指紋、または画面ロックの PIN・パターン・パスワード) を使います。" +
            "アプリ独自のマスターパスワードはありません。\n\n" +
            "データは KDBX 形式で、Android キーストアに保管したランダムな鍵で暗号化されます。" +
            "外部に書き出すときだけ、パスワードを付けて KeePass / KeePassXC で開ける形式にします。", 14f).apply { setLineSpacing(0f, 1.15f) })
        if (!secure) body.addView(W.text(ctx, "\nこの端末には画面ロックが設定されていません。先に「設定」で画面ロック (PIN など) を設定してください。", 14f, true,
            0xFFC00000.toInt()))
        body.addView(error, W.lp(W.MATCH, W.WRAP, t = 8))
        split.addView(body, W.lp(0, W.WRAP, 1f))
        content.addView(split, W.lp())
        content.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 6))
        val btns = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(W.dp(8), W.dp(8), W.dp(8), W.dp(8)) }
        if (secure) btns.addView(W.button(ctx, "はじめる(S)", true) { start() }, W.lp(W.WRAP, W.WRAP))
        else btns.addView(W.button(ctx, "画面ロックの設定...", true) {
            runCatching { a.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        }, W.lp(W.WRAP, W.WRAP))
        btns.addView(W.button(ctx, "キャンセル") { a.finish() }, W.lp(W.WRAP, W.WRAP, l = 8))
        content.addView(btns, W.lp())
        val cap = W.titleBar(ctx, "KeePass Explorer セットアップ", Px.computer16, onClose = { a.finish() })
        root = Desktop.frame(ctx, W.window(ctx, cap, content))
    }

    fun start() {
        error.text = ""
        Security.firstRun(a, onError = { error.text = it }) { a.showExplorer() }
    }

    override fun onShown() { if (!secure && a.vault.isAvailable()) a.route() }
}
