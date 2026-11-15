package io.github.zmlworks.kpexplorer

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import io.github.zmlworks.kpexplorer.db.FileInfo
import io.github.zmlworks.kpexplorer.sec.PasswordStrength
import io.github.zmlworks.kpexplorer.ui.DotLogo
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.StrengthMeter
import io.github.zmlworks.kpexplorer.ui.W

/**
 * First launch. 0.4.37, the user's design: a window 「ようこそ」 on the launch screen's teal (no taskbar / スタート any
 * more) with three choices; picking one replaces everything under them:
 *  - マスターパスワードを設定して始める (推奨, default): password twice, パスワードを表示する, the live strength meter,
 *    はじめる. KDBX with the master password; fingerprint / PIN everyday unlock when the device has a screen lock.
 *  - 暗号化せずに使う: the warning, はじめる. KDBX keyed by the empty password — labelled "not encrypted".
 *  - OSのkeystoreを利用する: the warning (the screen lock is the key's life), はじめる; unavailable without a screen lock.
 * The choice is recorded as the database's [FileInfo.Protection] (db/files.properties).
 */
class SetupScreen(private val a: MainActivity) : MainActivity.Screen {
    override val desktop = true
    override val backdrop: Int get() = DotLogo.TEAL
    private val ctx = a
    private val root: View
    override val view: View get() = root
    val error: TextView = W.text(ctx, "", 14f, color = 0xFFC00000.toInt())
    val rPassword: RadioButton = W.radio(ctx, L.t("マスターパスワードを設定して始める(推奨)", "Start with a master password (recommended)")).apply { tag = "mode-password" }
    val rNone: RadioButton = W.radio(ctx, L.t("暗号化せずに使う", "Use without encryption")).apply { tag = "mode-none" }
    val rKeystore: RadioButton = W.radio(ctx, L.t("OSのkeystoreを利用する", "Use the OS keystore")).apply { tag = "mode-keystore" }
    /** Everything under the radio buttons (rebuilt when the choice changes). */
    val panel: LinearLayout = W.vbox(ctx)
    var mode = FileInfo.Protection.PASSWORD
        private set
    var p1: android.widget.EditText? = null; private set
    var p2: android.widget.EditText? = null; private set
    var meter: StrengthMeter? = null; private set
    /** はじめる of the current panel. */
    lateinit var start: TextView; private set

    init {
        val body = W.vbox(ctx).apply { setPadding(W.dp(12), W.dp(10), W.dp(12), W.dp(12)) }
        body.addView(W.text(ctx, L.t("パスワードや鍵、メモを、エクスプローラーのようなフォルダーで整理して保存します。データは KeePass と同じ KDBX 形式です。\n始め方を選んでください。",
            "Keeps your passwords, keys and memos organised in Explorer-style folders, in KDBX format, the same as KeePass.\nChoose how to start."), 14f)
            .apply { setLineSpacing(0f, 1.15f) }, W.lp(W.MATCH, W.WRAP, b = 6))
        val radios = listOf(rPassword to FileInfo.Protection.PASSWORD, rNone to FileInfo.Protection.NONE, rKeystore to FileInfo.Protection.KEYSTORE)
        for ((r, m) in radios) {
            r.setOnClickListener { choose(m) }
            body.addView(r, W.lp(W.MATCH, W.WRAP))
        }
        body.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 6, b = 4))
        body.addView(panel, W.lp(W.MATCH, W.WRAP))
        val cap = W.titleBar(ctx, L.t("ようこそ", "Welcome"), Px.computer16, onClose = { a.finish() })
        val win = W.window(ctx, cap, body)
        val f = android.widget.FrameLayout(ctx).apply { setPadding(W.dp(10), W.dp(16), W.dp(10), W.dp(16)) }
        f.addView(win, android.widget.FrameLayout.LayoutParams(W.MATCH, W.WRAP, Gravity.CENTER))
        root = android.widget.ScrollView(ctx).apply { isFillViewport = true; setBackgroundColor(DotLogo.TEAL); addView(f) }
        choose(FileInfo.Protection.PASSWORD)
        PasswordStrength.warmUp()
    }

    /** Pick a choice: radio buttons and the panel under them. */
    fun choose(m: FileInfo.Protection) {
        mode = m
        rPassword.isChecked = m == FileInfo.Protection.PASSWORD
        rNone.isChecked = m == FileInfo.Protection.NONE
        rKeystore.isChecked = m == FileInfo.Protection.KEYSTORE
        error.text = ""
        p1?.setText(""); p2?.setText("")
        p1 = null; p2 = null; meter = null
        panel.removeAllViews()
        when (m) {
            FileInfo.Protection.PASSWORD -> {
                note(L.t("マスターパスワードで暗号化します。普段は指紋や画面ロック (PIN など) で開けます。画面ロックを解除した場合などは、マスターパスワードで開きます。忘れると開けなくなるので、控えておいてください。",
                    "The data is encrypted with the master password. Usually you open it with your fingerprint or screen lock (PIN etc.); after the screen lock is removed, for example, you open it with the master password. If you forget it, the data cannot be opened, so write it down."), null)
                val pr = Security.passwordPair(a, panel, L.t("マスターパスワード:", "Master password:"), L.t("もう一度入力:", "Enter it again:"), meter = true)
                p1 = pr.first; p2 = pr.second
                meter = panel.findViewWithTag(StrengthMeter.TAG)
            }
            FileInfo.Protection.NONE -> note(L.t("データは暗号化されません。守っているのは Android のアプリ専用の保存領域だけです。端末の root 化や解析ツールによる抜き取り、OS の乗っ取り (マルウェアなど) があると、すべての内容が読まれます。バックアップや書き出したファイルも暗号化されません (書き出すときはパスワードを決めて暗号化します)。",
                "The data is not encrypted. Only Android's app-private storage protects it: a rooted device, forensic extraction or a compromised OS (malware etc.) exposes everything. Internal backups are not encrypted either (an export is encrypted with a password you choose)."), '!')
            FileInfo.Protection.KEYSTORE -> {
                note(L.t("アプリが作ったランダムな鍵でデータを暗号化し、その鍵を画面ロックが必要な Android のキーストアで保護します。開くときは指紋や画面ロック (PIN など) を使います。パスワードはありません。\n\n" +
                    "画面ロックを解除・無効にしたり、端末を初期化したり、機種を変更したりすると、復号の鍵は永久に消え、このデータベースと内部バックアップは二度と開けなくなります。端末の中の安全性は OS とその画面ロックに依存します。",
                    "The data is encrypted with a random key made by the app, and that key is protected by the Android keystore, which needs the screen lock. You open it with your fingerprint or screen lock (PIN etc.); there is no password.\n\n" +
                    "If the screen lock is removed or disabled, the device is reset or you change devices, the decryption key is gone for good and this database and its internal backups can never be opened again. Its safety on the device depends on the OS and its screen lock."), '!')
                if (!a.vault.isAvailable()) note(Security.KEYSTORE_NEEDS_LOCK(), 'x').also { it.tag = "keystore-no-lock" }
            }
        }
        panel.addView(error, W.lp(W.MATCH, W.WRAP, t = 8))
        start = W.button(ctx, L.t("はじめる(S)", "Start(S)"), true) { start() }.apply { tag = "setup-start"; minWidth = W.dp(112) }
        if (m == FileInfo.Protection.KEYSTORE && !a.vault.isAvailable()) { start.isEnabled = false; start.setTextColor(0xFF808080.toInt()) }
        panel.addView(start, W.lp(W.WRAP, W.WRAP, t = 8).apply { gravity = Gravity.END })
    }

    private fun note(text: String, icon: Char?): View {
        val row = W.hbox(ctx).apply { gravity = Gravity.TOP }
        if (icon != null) row.addView(W.icon(ctx, Px.msgIcon(icon)), W.lp(W.WRAP, W.WRAP, r = 10, t = 2))
        row.addView(W.text(ctx, text, 14f).apply { setLineSpacing(0f, 1.15f) }, W.lp(0, W.WRAP, 1f))
        panel.addView(row, W.lp(W.MATCH, W.WRAP, t = if (panel.childCount == 0) 0 else 8))
        return row
    }

    fun start() {
        error.text = ""
        val title = L.t("セットアップ", "Setup")
        val done = { a.showExplorer() }
        val fail = { e: String -> error.text = e }
        when (mode) {
            FileInfo.Protection.PASSWORD -> {
                val f1 = p1 ?: return; val f2 = p2 ?: return
                val pw = Security.checkPair(a, title, f1, f2) ?: return
                Security.confirmWeak(a, title, pw, starting = true) { f1.setText(""); f2.setText(""); Security.firstRun(a, pw, fail, done) }
            }
            FileInfo.Protection.NONE -> Security.firstRunUnencrypted(a, fail, done)
            FileInfo.Protection.KEYSTORE -> Security.firstRunKeystore(a, fail, done)
        }
    }
}
