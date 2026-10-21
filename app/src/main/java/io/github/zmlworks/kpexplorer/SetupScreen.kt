package io.github.zmlworks.kpexplorer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

/** First-launch wizard: create a new database, or import (copy) an existing .kdbx into app storage. */
class SetupScreen(private val a: MainActivity) : MainActivity.Screen {
    override val desktop = true
    private val ctx = a
    private val body = FrameLayout(ctx)
    private val root: View
    override val view: View get() = root
    var page = 0
        private set
    var importMode = false
    private var importBytes: ByteArray? = null
    private var importName: String? = null

    private val nameField = W.field(ctx).apply { setText("パスワード") }
    private val pw1 = W.field(ctx, password = true)
    private val pw2 = W.field(ctx, password = true)
    private val pwImport = W.field(ctx, password = true)
    private val bio: CheckBox = W.check(ctx, "指紋認証でロックを解除できるようにする", true)
    private lateinit var back: TextView
    private lateinit var next: TextView
    private lateinit var fileLabel: TextView

    init {
        val content = W.vbox(ctx)
        val split = W.hbox(ctx).apply { gravity = Gravity.TOP }
        split.addView(Desktop.wizardArt(ctx), W.lp(W.dp(112), W.MATCH))
        body.setPadding(W.dp(12), W.dp(10), W.dp(10), W.dp(10))
        split.addView(body, W.lp(0, W.WRAP, 1f))
        content.addView(split, W.lp())
        content.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 6))
        val btns = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(W.dp(8), W.dp(8), W.dp(8), W.dp(8)) }
        back = W.button(ctx, "< 戻る(B)") { show(0) }
        next = W.button(ctx, "次へ(N) >", true) { onNext() }
        btns.addView(back, W.lp(W.WRAP, W.WRAP))
        btns.addView(next, W.lp(W.WRAP, W.WRAP))
        btns.addView(W.button(ctx, "キャンセル") { a.finish() }, W.lp(W.WRAP, W.WRAP, l = 8))
        content.addView(btns, W.lp())
        val cap = W.titleBar(ctx, "KeePass Explorer セットアップ ウィザード", Px.computer16, onClose = { a.finish() })
        root = Desktop.frame(ctx, W.window(ctx, cap, content))
        if (!a.vault.isAvailable()) { bio.isChecked = false; bio.isEnabled = false; bio.text = "指紋認証 (この端末では使えません)" ; W.setEnabledLook(bio, false) }
        show(0)
    }

    fun show(p: Int) {
        page = p
        body.removeAllViews()
        listOf(nameField, pw1, pw2, pwImport, bio).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        body.addView(if (p == 0) welcome() else if (importMode) importPage() else createPage())
        W.setEnabledLook(back, p > 0)
        next.text = if (p == 0) "次へ(N) >" else "完了"
    }

    private fun t(s: String, sp: Float = 14f, bold: Boolean = false) = W.text(ctx, s, sp, bold).apply { setLineSpacing(0f, 1.15f) }

    private fun welcome(): View {
        val col = W.vbox(ctx)
        col.addView(t("KeePass Explorer へようこそ", 18f, true))
        col.addView(t("このウィザードでは、パスワードとメモを保存するデータベース (KeePass 形式 .kdbx) を準備します。\n\n" +
            "データベースはアプリ専用の領域に 1 つだけ保存され、マスターパスワードで暗号化されます。"), W.lp(W.MATCH, W.WRAP, t = 10))
        col.addView(t("どちらを行いますか?"), W.lp(W.MATCH, W.WRAP, t = 14))
        val rg = RadioGroup(ctx)
        val r1 = W.radio(ctx, "新しいデータベースを作成する", !importMode).apply { id = View.generateViewId() }
        val r2 = W.radio(ctx, "既存の KeePass ファイル (.kdbx) をインポートする", importMode).apply { id = View.generateViewId() }
        rg.addView(r1); rg.addView(r2)
        rg.setOnCheckedChangeListener { _, id -> importMode = id == r2.id }
        col.addView(rg, W.lp(W.MATCH, W.WRAP, t = 6))
        return col
    }

    private fun label(s: String) = t(s).also { }

    private fun createPage(): View {
        val col = W.vbox(ctx)
        col.addView(t("新しいデータベース", 16f, true))
        col.addView(t("マスターパスワードを決めてください。忘れると復元できません。"), W.lp(W.MATCH, W.WRAP, t = 6))
        col.addView(label("データベース名(D):"), W.lp(W.MATCH, W.WRAP, t = 10))
        col.addView(nameField, W.lp(W.MATCH, W.WRAP, t = 3))
        col.addView(label("マスターパスワード(P):"), W.lp(W.MATCH, W.WRAP, t = 8))
        col.addView(pw1, W.lp(W.MATCH, W.WRAP, t = 3))
        col.addView(label("確認のためもう一度(C):"), W.lp(W.MATCH, W.WRAP, t = 8))
        col.addView(pw2, W.lp(W.MATCH, W.WRAP, t = 3))
        col.addView(bio, W.lp(W.MATCH, W.WRAP, t = 8))
        col.addView(t("形式: KDBX 4 / AES-256 / Argon2id", 12f).apply { setTextColor(W.SHADOW) }, W.lp(W.MATCH, W.WRAP, t = 4))
        return col
    }

    private fun importPage(): View {
        val col = W.vbox(ctx)
        col.addView(t("既存のファイルのインポート", 16f, true))
        col.addView(t("選んだファイルはアプリ内にコピーされます (元のファイルは変更しません)。マスターパスワードだけのファイルに対応しています。"), W.lp(W.MATCH, W.WRAP, t = 6))
        val row = W.hbox(ctx)
        fileLabel = W.text(ctx, importName ?: "(ファイルが選ばれていません)", 14f).apply {
            background = W.Bevel(W.Style.FIELD); setPadding(W.dp(6), W.dp(8), W.dp(6), W.dp(8)); isSingleLine = true
        }
        row.addView(fileLabel, W.lp(0, W.WRAP, 1f))
        row.addView(W.button(ctx, "参照(R)...") { pick() }.apply { minWidth = W.dp(70) }, W.lp(W.WRAP, W.WRAP, l = 6))
        col.addView(row, W.lp(W.MATCH, W.WRAP, t = 10))
        col.addView(label("マスターパスワード(P):"), W.lp(W.MATCH, W.WRAP, t = 10))
        col.addView(pwImport, W.lp(W.MATCH, W.WRAP, t = 3))
        col.addView(bio, W.lp(W.MATCH, W.WRAP, t = 8))
        return col
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        a.startForResult(i) { rc, data ->
            val uri: Uri = data?.data ?: return@startForResult
            if (rc != Activity.RESULT_OK) return@startForResult
            try {
                var name = "database.kdbx"
                a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
                val bytes = a.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                require(bytes.size >= 12) { "file too small" }
                importBytes = bytes; importName = name
                fileLabel.text = "$name (${bytes.size / 1024} KB)"
            } catch (t: Throwable) {
                Dlg.message(ctx, "インポート", "ファイルを読み込めませんでした。\n$t", 'x')
            }
        }
    }

    private fun onNext() {
        if (page == 0) { show(1); return }
        if (importMode) doImport() else doCreate()
    }

    private fun doCreate() {
        val name = nameField.text.toString().trim().ifEmpty { "パスワード" }
        if (pw1.text.isEmpty()) { Dlg.message(ctx, "セットアップ", "マスターパスワードを入力してください。", '!'); return }
        if (pw1.text.toString() != pw2.text.toString()) { Dlg.message(ctx, "セットアップ", "確認用のパスワードが一致しません。", 'x'); return }
        val key = Kdbx.compositeKey(pw1.text)
        pw1.setText(""); pw2.setText("")
        val busy = Dlg.busy(ctx, "セットアップ", "データベースを作成しています…")
        Session.io.execute {
            val r = runCatching {
                val db = Kdbx.create(name, key)
                a.store.write(Kdbx.encodeVerified(db))
                db
            }
            key.fill(0)
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess { db -> Security.opened(a, db); finishSetup() }
                 .onFailure { Dlg.message(ctx, "セットアップ", "作成できませんでした。\n$it", 'x') }
            }
        }
    }

    private fun doImport() {
        val bytes = importBytes ?: run { Dlg.message(ctx, "インポート", "まず「参照...」でファイルを選んでください。", '!'); return }
        val key = Kdbx.compositeKey(pwImport.text)
        val busy = Dlg.busy(ctx, "インポート", "ファイルを開いています…")
        Session.io.execute {
            val r = runCatching {
                var db = Kdbx.open(bytes, key)
                val twofish = Kdbx.isTwofish(db)
                if (twofish) {
                    db = Kdbx.convertToAes(db)
                    a.store.write(Kdbx.encodeVerified(db))
                } else a.store.write(bytes)
                db to twofish
            }
            key.fill(0)
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess { (db, twofish) ->
                    pwImport.setText("")
                    importBytes = null
                    Security.opened(a, db)
                    if (twofish) Dlg.message(ctx, "インポート", "Twofish で暗号化されたファイルだったため、アプリ内のコピーは AES-256 で保存しました。", 'i') { finishSetup() }
                    else finishSetup()
                }.onFailure { t ->
                    Dlg.message(ctx, "インポート", if (t is Kdbx.WrongPasswordException)
                        "開けませんでした。パスワードが違うか、キーファイルが必要なファイルです。" else "開けませんでした。\n$t", 'x')
                }
            }
        }
    }

    private fun finishSetup() {
        if (bio.isChecked && bio.isEnabled) Security.enroll(a) { a.showExplorer() } else a.showExplorer()
    }

    override fun onBack(): Boolean { if (page > 0) { show(0); return true }; return false }
}
