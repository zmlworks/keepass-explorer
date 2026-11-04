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
        body.addView(W.text(ctx, "KeePass Explorer へようこそ", 19f, true), W.lp(W.MATCH, W.WRAP, b = 10))
        body.addView(W.text(ctx, "パスワードや鍵、メモを、エクスプローラーのようなフォルダーで整理して保存します。\n\n" +
            "マスターパスワードを決めてください。データは KeePass と同じ KDBX 形式で、このパスワードで暗号化されます。\n" +
            "普段は指紋や画面ロック (PIN など) で開けます。画面ロックを解除した場合などは、マスターパスワードで開きます。" +
            "忘れると開けなくなるので、控えておいてください。", 14f).apply { setLineSpacing(0f, 1.15f) })
        val pr = Security.passwordPair(a, body, "マスターパスワード:", "もう一度入力:")
        p1 = pr.first; p2 = pr.second
        body.addView(error, W.lp(W.MATCH, W.WRAP, t = 8))
        split.addView(body, W.lp(0, W.WRAP, 1f))
        content.addView(split, W.lp())
        content.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 6))
        val btns = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(W.dp(8), W.dp(8), W.dp(8), W.dp(8)) }
        btns.addView(W.button(ctx, "はじめる(S)", true) { start() }, W.lp(W.WRAP, W.WRAP))
        btns.addView(W.button(ctx, "キャンセル") { a.finish() }, W.lp(W.WRAP, W.WRAP, l = 8))
        content.addView(btns, W.lp())
        val cap = W.titleBar(ctx, "KeePass Explorer セットアップ", Px.computer16, onClose = { a.finish() })
        root = Desktop.frame(ctx, W.window(ctx, cap, android.widget.ScrollView(ctx).apply { addView(content) }))
    }

    fun start() {
        error.text = ""
        val pw = Security.checkPair(a, "セットアップ", p1, p2) ?: return
        p1.setText(""); p2.setText("")
        Security.firstRun(a, pw, onError = { error.text = it }) { a.showExplorer() }
    }
}
