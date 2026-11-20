package io.github.zmlworks.kpexplorer

import android.view.Gravity
import android.view.View
import android.widget.TextView
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.db.FileInfo
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.DotLogo
import io.github.zmlworks.kpexplorer.ui.SoftButton
import io.github.zmlworks.kpexplorer.ui.W

/**
 * Unlock screen. 0.4.32: the user's design — the teal backdrop with the dotted "KeePass" / "Explorer" ([DotLogo]) and a
 * centred stack of light grey buttons, 70 % of the width: OS認証で解除 (fingerprint / screen lock, the wrapped key),
 * マスターパスワード (opens the password box under it), 終了. Errors appear in a panel under the buttons.
 * 0.4.35: 外部ファイルを閲覧 (another KDBX, read-only, with its own password; this database stays locked) and
 * 外部に書き出す (this database's file copied as it is, still encrypted) before 終了, as in the user's sample.
 * 0.4.36: the five buttons evenly spaced (no group gaps), taller ([BUTTON_DP]), their labels in a light weight.
 * 0.4.37: by the database's protection — master password: as before; not encrypted: one 開く button (nothing to
 * check); OS keystore: OS認証で解除 only (there is no password). 外部に書き出す lists the database and internal
 * backups (strong ones only).
 * 0.4.38 (the user's design): the same five buttons, closer together, in every protection mode; one that cannot be
 * used there shows grey text (a tap says why, nothing else). For a database that is not encrypted the first one is
 * 開く (暗号化なし). 外部に書き出す follows セキュリティの設定 「認証なしで外部に書き出す際の強度」.
 * (Up to 0.4.31 a Windows 2000-style logon window on the teal desktop.)
 */
/** [prompt] = ask for the fingerprint / screen lock as soon as it is shown (not when coming back from a read-only view). */
class UnlockScreen(private val a: MainActivity, prompt: Boolean = true) : MainActivity.Screen {
    override val desktop = true
    override val backdrop: Int get() = DotLogo.TEAL
    private val ctx = a
    private val root: View
    override val view: View get() = root
    val error: TextView = W.text(ctx, "", 14f, color = 0xFFC00000.toInt())
    private var autoPrompted = !prompt
    val pw = W.field(ctx, password = true).apply { isSingleLine = true }
    val pwBox = W.vbox(ctx)
    /** The backdrop (tests). */
    val logo = DotLogo(ctx)
    /** The stack of buttons and panels (tests). */
    val stack = W.vbox(ctx)
    /** The database's protection when the screen was made. */
    val protection = Security.protection(a)
    /** Why a button cannot be used now (it is drawn grey; a tap shows this). */
    private val why = HashMap<View, String>()
    /** The unlock button: OS認証で解除, or (not encrypted) 開く (暗号化なし). */
    val bio = bigButton(if (protection == FileInfo.Protection.NONE) (if (Security.noneGate(a)) L.t("指紋認証で開く (暗号化なし)", "Open with fingerprint (not encrypted)") else L.t("開く (暗号化なし)", "Open (not encrypted)")) else L.t("OS認証で解除", "Unlock with device lock")) {
        if (protection == FileInfo.Protection.NONE) { error.text = ""; Security.openUnencrypted(a, onError = { error.text = it }) { a.showExplorer() } } else unlock()
    }
    val pwButton = bigButton(L.t("マスターパスワード", "Master password")) { showPassword(null) }
    val browse = bigButton(L.t("外部ファイルを閲覧", "Browse external file")) { error.text = ""; TempImport.browseExternal(a) }
    val export = bigButton(L.t("外部に書き出す", "Export")) { error.text = ""; Security.exportFromLaunch(a) }
    val exit = bigButton(L.t("終了", "Exit")) { a.finish() }
    private val errorPanel = panel()

    init {
        pwBox.background = SoftButton(); pwBox.setPadding(W.dp(12), W.dp(10), W.dp(12), W.dp(10))
        pwBox.addView(W.text(ctx, L.t("マスターパスワード:", "Master password:"), 14f), W.lp(W.MATCH, W.WRAP))
        pwBox.addView(pw, W.lp(W.MATCH, W.WRAP, t = 3))
        pwBox.addView(W.button(ctx, L.t("開く(O)", "Open(O)"), true) { openWithPassword() }.apply { minWidth = W.dp(96) },
            W.lp(W.WRAP, W.WRAP, t = 6).apply { gravity = Gravity.END })
        errorPanel.addView(error, W.lp(W.MATCH, W.WRAP))
        errorPanel.visibility = View.GONE
        error.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { errorPanel.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
        })
        val gap = GAP_DP   // (dp: W.lp converts; up to 0.4.37 it was converted twice, 14 → 42 dp on the screen)
        stack.addView(bio, W.lp(W.MATCH, W.dp(BUTTON_DP)))
        stack.addView(pwButton, W.lp(W.MATCH, W.dp(BUTTON_DP), t = gap))
        stack.addView(pwBox, W.lp(W.MATCH, W.WRAP, t = gap))
        stack.addView(browse, W.lp(W.MATCH, W.dp(BUTTON_DP), t = gap))
        stack.addView(export, W.lp(W.MATCH, W.dp(BUTTON_DP), t = gap))
        stack.addView(exit, W.lp(W.MATCH, W.dp(BUTTON_DP), t = gap))
        stack.addView(errorPanel, W.lp(W.MATCH, W.WRAP, t = gap))
        // 70 % of the width, in the middle of the screen (scrolls when the keyboard leaves too little room)
        val across = W.hbox(ctx).apply { gravity = Gravity.CENTER; weightSum = 1f; setPadding(0, W.dp(24), 0, W.dp(24)) }
        across.addView(stack, W.lp(0, W.WRAP, 0.7f))
        val sv = android.widget.ScrollView(ctx).apply { isFillViewport = true; addView(across) }
        root = android.widget.FrameLayout(ctx).apply {
            addView(logo, android.widget.FrameLayout.LayoutParams(W.MATCH, W.MATCH))
            addView(sv, android.widget.FrameLayout.LayoutParams(W.MATCH, W.MATCH))
        }
        if (protection == FileInfo.Protection.KEYSTORE && !a.vault.hasStoredKey()) error.text = KEY_GONE()
        if (protection == FileInfo.Protection.PASSWORD && !a.vault.hasStoredKey()) showPassword(L.t("指紋・画面ロック用の鍵がないため、マスターパスワードで開いてください。", "There is no fingerprint / screen lock key, so open with the master password."))
        else pwBox.visibility = View.GONE
        updateStates()
    }

    /** Which buttons can be used in this mode / state (the others grey, with the reason). */
    fun updateStates() {
        fun set(v: TextView, reason: String?) {
            if (reason == null) why.remove(v) else why[v] = reason
            v.setTextColor(if (reason == null) W.TEXT else GREY)
        }
        set(bio, when {
            protection == FileInfo.Protection.NONE -> null
            !a.vault.hasStoredKey() -> if (protection == FileInfo.Protection.KEYSTORE) KEY_GONE()
                else L.t("指紋・画面ロック用の鍵がありません。マスターパスワードで開いてください (開くと登録し直します)。", "There is no fingerprint / screen lock key. Open with the master password (that sets it up again).")
            else -> null
        })
        set(pwButton, when (protection) {
            FileInfo.Protection.NONE -> L.t("暗号化していないため、マスターパスワードはありません。", "Not encrypted, so there is no master password.")
            FileInfo.Protection.KEYSTORE -> L.t("OSのkeystoreの鍵で保護しているため、マスターパスワードはありません。", "Protected by the OS keystore key, so there is no master password.")
            FileInfo.Protection.PASSWORD -> null
        })
        set(export, when {
            !Security.launchExportEnabled(a) -> L.t("認証なしの書き出しは無効です (アプリを開いて「セキュリティの設定」で変えられます)。", "Export without unlocking is off (change it in Security settings in the app).")
            Security.launchExportItems(a).none { it.exportable } -> L.t("書き出せるファイルがありません (マスターパスワードの強度がセキュリティの設定に届きません)。", "There is no file that can be exported (no master password reaches the Security setting).")
            else -> null
        })
    }

    /** The button can be used now (not grey). */
    fun usable(v: View) = v !in why

    @Suppress("FunctionName")
    private fun KEY_GONE() = L.t("OSのkeystoreの鍵がなくなっています (画面ロックの解除・変更、端末の初期化など)。この方式のデータベースは、もう開けません。",
        "The OS keystore key is gone (screen lock removed or changed, device reset, etc.). A database protected this way can no longer be opened.")

    private fun bigButton(label: String, onClick: () -> Unit): TextView = W.text(ctx, label, 17f, false, W.TEXT).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, LABEL_WEIGHT, false)
        gravity = Gravity.CENTER; background = SoftButton(); isClickable = true; isFocusable = true
        contentDescription = label; setPadding(W.dp(8), 0, W.dp(8), 0)
        setOnClickListener { v -> why[v]?.let { this@UnlockScreen.error.text = it } ?: onClick() }
    }

    companion object {
        const val BUTTON_DP = 64
        /** 0.4.38: closer — 12 dp between buttons (up to 0.4.37 effectively 42 dp). */
        const val GAP_DP = 12
        /** Text of a button that cannot be used now. */
        const val GREY = 0xFF8C8C8C.toInt()
        /** Light (300); up to 0.4.35 bold. */
        const val LABEL_WEIGHT = 300
    }

    private fun panel() = W.vbox(ctx).apply { background = SoftButton(); setPadding(W.dp(12), W.dp(8), W.dp(12), W.dp(8)) }

    override fun onShown() {
        Session.takeLockSaveError()?.let {
            Dlg.message(ctx, L.t("保存エラー", "Save Error"), L.t("ロックする直前の変更を保存できませんでした。最後の変更は失われた可能性があります。\n$it", "The changes made just before locking could not be saved. The last change may have been lost.\n$it"), 'x')
        }
        if (!autoPrompted && Vault.forceAvailableForTest == null && a.vault.hasStoredKey() && protection != FileInfo.Protection.NONE) {
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
        if (p.isEmpty()) { error.text = L.t("マスターパスワードを入力してください。", "Enter the master password."); return }
        pw.setText("")
        error.text = ""
        Security.unlockWithPassword(a, p, onError = { error.text = it }) { a.showExplorer() }
    }

    fun unlock() {
        error.text = ""
        if (protection == FileInfo.Protection.KEYSTORE) {
            if (!a.vault.hasStoredKey()) { error.text = KEY_GONE(); return }
            Security.unlock(a, onError = { error.text = it }, onLost = { error.text = "$it\n" + KEY_GONE() }) { a.showExplorer() }
            return
        }
        if (!a.vault.hasStoredKey()) { showPassword(L.t("指紋・画面ロック用の鍵がないため、マスターパスワードで開いてください。", "There is no fingerprint / screen lock key, so open with the master password.")); return }
        Security.unlock(a, onError = { error.text = it }, onLost = { showPassword(L.t("$it\nマスターパスワードで開くと、もう一度登録します。", "$it\nOpening with the master password sets it up again.")) }) { a.showExplorer() }
    }
}
