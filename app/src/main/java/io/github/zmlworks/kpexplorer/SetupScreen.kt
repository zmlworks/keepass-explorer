package io.github.zmlworks.kpexplorer

import android.view.Gravity
import android.view.View
import android.widget.TextView
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

/**
 * First launch: choose the master password (twice). db.kdbx is a standard KDBX 4 with that password; if the
 * device has a screen lock, the derived key is also wrapped in the Android Keystore for fingerprint / PIN unlock.
 */
class SetupScreen(private val a: MainActivity) : MainActivity.Screen {
    override val desktop = true
    private val ctx = a
    private val root: View
    override val view: View get() = root
    val error: TextView = W.text(ctx, "", 14f, color = 0xFFC00000.toInt())
    val p1: android.widget.EditText
    val p2: android.widget.EditText

    init {
        val content = W.vbox(ctx)
        val split = W.hbox(ctx).apply { gravity = Gravity.TOP }
        split.addView(Desktop.wizardArt(ctx), W.lp(W.dp(112), W.MATCH))
        val body = W.vbox(ctx).apply { setPadding(W.dp(12), W.dp(10), W.dp(10), W.dp(10)) }
        body.addView(W.text(ctx, L.t("KeePass Explorer へようこそ", "Welcome to KeePass Explorer"), 19f, true), W.lp(W.MATCH, W.WRAP, b = 10))
        body.addView(W.text(ctx, L.t("パスワードや鍵、メモを、エクスプローラーのようなフォルダーで整理して保存します。\n\n", "Keeps your passwords, keys and memos organised in Explorer-style folders.\n\n") +
            L.t("マスターパスワードを決めてください。データは KeePass と同じ KDBX 形式で、このパスワードで暗号化されます。\n", "Choose a master password. The data is encrypted with it in KDBX format, the same as KeePass.\n") +
            L.t("普段は指紋や画面ロック (PIN など) で開けます。画面ロックを解除した場合などは、マスターパスワードで開きます。", "Usually you open it with your fingerprint or screen lock (PIN etc.); after the screen lock is removed, for example, you open it with the master password. ") +
            L.t("忘れると開けなくなるので、控えておいてください。", "If you forget it, the data cannot be opened, so write it down."), 14f).apply { setLineSpacing(0f, 1.15f) })
        val pr = Security.passwordPair(a, body, L.t("マスターパスワード:", "Master password:"), L.t("もう一度入力:", "Enter it again:"))
        p1 = pr.first; p2 = pr.second
        body.addView(error, W.lp(W.MATCH, W.WRAP, t = 8))
        split.addView(body, W.lp(0, W.WRAP, 1f))
        content.addView(split, W.lp())
        content.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 6))
        val btns = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(W.dp(8), W.dp(8), W.dp(8), W.dp(8)) }
        btns.addView(W.button(ctx, L.t("はじめる(S)", "Start(S)"), true) { start() }, W.lp(W.WRAP, W.WRAP))
        btns.addView(W.button(ctx, L.t("キャンセル", "Cancel")) { a.finish() }, W.lp(W.WRAP, W.WRAP, l = 8))
        content.addView(btns, W.lp())
        val cap = W.titleBar(ctx, L.t("KeePass Explorer セットアップ", "KeePass Explorer Setup"), Px.computer16, onClose = { a.finish() })
        root = Desktop.frame(ctx, W.window(ctx, cap, android.widget.ScrollView(ctx).apply { addView(content) }))
    }

    fun start() {
        error.text = ""
        val pw = Security.checkPair(a, L.t("セットアップ", "Setup"), p1, p2) ?: return
        p1.setText(""); p2.setText("")
        Security.firstRun(a, pw, onError = { error.text = it }) { a.showExplorer() }
    }
}
