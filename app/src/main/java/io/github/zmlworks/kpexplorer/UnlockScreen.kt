package io.github.zmlworks.kpexplorer

import android.view.Gravity
import android.view.View
import android.widget.TextView
import io.github.zmlworks.kpexplorer.sec.Vault
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
    val bio = bigButton(L.t("OS認証で解除", "Unlock with device lock")) { unlock() }
    val pwButton = bigButton(L.t("マスターパスワード", "Master password")) { showPassword(null) }
    val browse = bigButton(L.t("外部ファイルを閲覧", "Browse external file")) { error.text = ""; TempImport.browseExternal(a) }
    val export = bigButton(L.t("外部に書き出す", "Export")) { error.text = ""; Security.exportEncrypted(a) }
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
        val gap = W.dp(GAP_DP)
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
        if (!a.vault.hasStoredKey()) showPassword(L.t("指紋・画面ロック用の鍵がないため、マスターパスワードで開いてください。", "There is no fingerprint / screen lock key, so open with the master password."))
        else pwBox.visibility = View.GONE
    }

    private fun bigButton(label: String, onClick: () -> Unit): TextView = W.text(ctx, label, 17f, false, W.TEXT).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, LABEL_WEIGHT, false)
        gravity = Gravity.CENTER; background = SoftButton(); isClickable = true; isFocusable = true
        contentDescription = label; setPadding(W.dp(8), 0, W.dp(8), 0)
        setOnClickListener { onClick() }
    }

    companion object {
        const val BUTTON_DP = 64
        const val GAP_DP = 14
        /** Light (300); up to 0.4.35 bold. */
        const val LABEL_WEIGHT = 300
    }

    private fun panel() = W.vbox(ctx).apply { background = SoftButton(); setPadding(W.dp(12), W.dp(8), W.dp(12), W.dp(8)) }

    override fun onShown() {
        Session.takeLockSaveError()?.let {
            Dlg.message(ctx, L.t("保存エラー", "Save Error"), L.t("ロックする直前の変更を保存できませんでした。最後の変更は失われた可能性があります。\n$it", "The changes made just before locking could not be saved. The last change may have been lost.\n$it"), 'x')
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
        if (p.isEmpty()) { error.text = L.t("マスターパスワードを入力してください。", "Enter the master password."); return }
        pw.setText("")
        error.text = ""
        Security.unlockWithPassword(a, p, onError = { error.text = it }) { a.showExplorer() }
    }

    fun unlock() {
        error.text = ""
        if (!a.vault.hasStoredKey()) { showPassword(L.t("指紋・画面ロック用の鍵がないため、マスターパスワードで開いてください。", "There is no fingerprint / screen lock key, so open with the master password.")); return }
        Security.unlock(a, onError = { error.text = it }, onLost = { showPassword(L.t("$it\nマスターパスワードで開くと、もう一度登録します。", "$it\nOpening with the master password sets it up again.")) }) { a.showExplorer() }
    }
}
