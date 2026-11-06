package io.github.zmlworks.kpexplorer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import app.keemobile.kotpass.database.modifiers.modifyContent
import io.github.zmlworks.kpexplorer.db.Tree
import android.widget.EditText
import android.widget.Toast
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.W
import java.io.File
import java.time.LocalDate

/**
 * Master password (KDBX standard) + optional Keystore-wrapped key for everyday OS-lock unlock, first-run
 * creation, changing the master password, and the export dialog.
 */
object Security {
    /** Decrypt the app database with [composite] on the I/O thread. [composite] is wiped unless [keep]. */
    fun openStored(a: MainActivity, composite: ByteArray, onError: (String) -> Unit, onOk: () -> Unit, keep: Boolean = false) {
        val busy = Dlg.busy(a, "ログオン", "データベースを開いています…")
        Session.io.execute {
            val r = runCatching { Kdbx.open(a.store.read(), composite) }
            if (!keep) composite.fill(0)
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess { db -> opened(a, db); onOk() }
                 .onFailure { t -> onError(if (t is Kdbx.WrongPasswordException) "マスターパスワードが違います。" else "データベースを開けませんでした。\n$t") }
            }
        }
    }

    /** Session start; HistoryMaxItems + recycle bin enabled (localized bin name, stable UUID). */
    fun opened(a: MainActivity, db: KeePassDatabase) {
        Session.open(db)
        val binName = a.getString(R.string.recycle_bin)
        Session.update { Tree.ensureRecycleBin(it, binName) }
    }

    /** OS prompt → wrapped composite key → open. [onLost]: the wrapped key is unusable, ask for the master password. */
    fun unlock(a: MainActivity, onError: (String) -> Unit, onLost: (String) -> Unit, onOk: () -> Unit) {
        a.vault.unlock(a, object : Vault.Result {
            override fun ok(composite: ByteArray) = openStored(a, composite, onError, onOk)
            override fun fail(msg: String) = onError(msg)
            override fun lost(msg: String) = onLost(msg)
        })
    }

    /** Master password → open; then (screen lock available) wrap the key again for fingerprint / PIN unlock. */
    fun unlockWithPassword(a: MainActivity, password: String, onError: (String) -> Unit, onOk: () -> Unit) {
        val k = Kdbx.compositeKey(password)
        openStored(a, k, { k.fill(0); onError(it) }, {
            if (!a.vault.hasStoredKey() && a.vault.isAvailable() && Vault.forceAvailableForTest == null)
                a.vault.wrap(a, k) { err -> k.fill(0); if (!err.isNullOrEmpty()) toast(a, "指紋・画面ロックの登録に失敗: $err") }
            else k.fill(0)
            onOk()
        }, keep = true)
    }

    /** New empty database (root folder without a name = "/"), standard KDBX 4 with the master password. */
    fun newDatabase(composite: ByteArray): KeePassDatabase =
        Kdbx.create("KeePass Explorer", composite, rootName = "")

    /** First run: create db.kdbx with [password]; then offer fingerprint / screen-lock unlock (wrap). */
    fun firstRun(a: MainActivity, password: String, onError: (String) -> Unit, onOk: () -> Unit) {
        val composite = Kdbx.compositeKey(password)
        val busy = Dlg.busy(a, "セットアップ", "データベースを作成しています…")
        Session.io.execute {
            val r = runCatching { newDatabase(composite).also { a.store.write(Kdbx.encodeVerified(it, keepSalt = true)) } }
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess {
                    opened(a, it)
                    if (a.vault.isAvailable() && Vault.forceAvailableForTest == null)
                        a.vault.wrap(a, composite) { err -> composite.fill(0); if (!err.isNullOrEmpty()) toast(a, "指紋・画面ロックの登録に失敗: $err"); onOk() }
                    else { composite.fill(0); onOk() }
                }.onFailure { composite.fill(0); onError("作成できませんでした。\n$it") }
            }
        }
    }

    /** Menu: new master password (twice). The file is re-encrypted (new Argon2 salt) and the wrapped key replaced. */
    fun changeMasterPassword(a: MainActivity): ClassicDialog {
        val col = W.vbox(a)
        col.addView(W.text(a, "新しいマスターパスワードを 2 回入力してください。アプリ内に保存済みの日付ファイルは、" +
            "それぞれ作成時のマスターパスワードのままです。", 14f).apply { setLineSpacing(0f, 1.1f) })
        val (p1, p2) = passwordPair(a, col)
        return ClassicDialog(a, "マスターパスワードの変更", col, listOf(
            ClassicDialog.Btn("変更", true) {
                val pw = checkPair(a, "マスターパスワードの変更", p1, p2) ?: return@Btn false
                val k = Kdbx.compositeKey(pw)
                Session.update { Kdbx.withHeader(Kdbx.withCredentials(it, k), light = false) }
                val busy = Dlg.busy(a, "マスターパスワードの変更", "暗号化し直しています…")
                Session.afterSave({ Unit }) { res ->
                    busy.dismiss()
                    res.onSuccess {
                        toast(a, "マスターパスワードを変更しました")
                        if (a.vault.isAvailable() && Vault.forceAvailableForTest == null)
                            a.vault.wrap(a, k) { err -> k.fill(0); if (!err.isNullOrEmpty()) toast(a, "指紋・画面ロックの登録に失敗: $err") }
                        else { a.vault.clear(); k.fill(0) }
                    }.onFailure { k.fill(0); Dlg.message(a, "マスターパスワードの変更", "保存できませんでした。\n${it.message ?: it}", 'x') }
                }
                true
            },
            ClassicDialog.Btn("キャンセル"))).also { it.show() }
    }

    private fun toast(a: MainActivity, s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()

    /** Two password fields + "show" check box. */
    fun passwordPair(a: MainActivity, col: android.widget.LinearLayout, first: String = "パスワード:", second: String = "パスワードの確認入力:"): Pair<EditText, EditText> {
        fun f(label: String): EditText {
            col.addView(W.text(a, label, 14f), W.lp(W.MATCH, W.WRAP, t = 8))
            return W.field(a, password = true).also { col.addView(it, W.lp(W.MATCH, W.WRAP, t = 3)) }
        }
        val p1 = f(first); val p2 = f(second)
        val show = W.check(a, "パスワードを表示する", false)
        show.setOnCheckedChangeListener { _, on ->
            val t = InputType.TYPE_CLASS_TEXT or if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            p1.inputType = t; p2.inputType = t
        }
        col.addView(show, W.lp(W.MATCH, W.WRAP, t = 6))
        return p1 to p2
    }

    /** Password from the pair, or null after telling the user what is wrong. */
    fun checkPair(a: MainActivity, title: String, p1: EditText, p2: EditText): String? = when {
        p1.text.isEmpty() -> { Dlg.message(a, title, "パスワードを入力してください。", '!'); null }
        p1.text.toString() != p2.text.toString() -> { Dlg.message(a, title, "確認用のパスワードが一致しません。", 'x'); null }
        else -> p1.text.toString()
    }

    // ---------------------------------------------------------------- save dialog / export

    /** Toolbar 保存: internal dated copy, or password-protected export outside the app (SAF). */
    fun saveDialog(a: MainActivity, onInternal: (File) -> Unit = {}): ClassicDialog {
        val col = W.vbox(a)
        col.addView(W.text(a, "保存方法を選んでください。", 15f, true), W.lp(W.MATCH, W.WRAP, b = 8))
        col.addView(W.text(a, "・アプリ内に保存: backups/${LocalDate.now()}.kdbx として、アプリの中に日付のコピーを作ります (同じ日は _2, _3…)。\n" +
            "・外部に書き出す: 保存先 (ダウンロード、クラウドなど) を選び、パスワードを付けた KDBX として書き出します。KeePass / KeePassXC で開けます。", 14f)
            .apply { setLineSpacing(0f, 1.15f) })
        return ClassicDialog(a, "保存", col, listOf(
            ClassicDialog.Btn("アプリ内に保存", true) { internalBackup(a, onInternal); true },
            ClassicDialog.Btn("外部に書き出す...") { askExportPassword(a, "keepass-explorer-${LocalDate.now()}.kdbx") { Session.db }; true },
            ClassicDialog.Btn("キャンセル"))).also { it.show() }
    }

    fun internalBackup(a: MainActivity, done: (File) -> Unit = {}) {
        val busy = Dlg.busy(a, "保存", "保存しています…")
        Session.afterSave({ a.store.backup() }) { res ->
            busy.dismiss()
            res.onSuccess { f -> Toast.makeText(a, "保存しました: backups/${f.name}", Toast.LENGTH_SHORT).show(); done(f) }
               .onFailure { Dlg.message(a, "保存", "保存できませんでした。\n${it.message ?: it}", 'x') }
        }
    }

    /**
     * Export: choose "same master password" (current key, fresh salt) or "a different password" (twice),
     * then SAF "create document" → standard KDBX 4 that KeePass / KeePassXC open.
     */
    fun askExportPassword(a: MainActivity, fileName: String, source: () -> KeePassDatabase?): ClassicDialog {
        val col = W.vbox(a)
        col.addView(W.text(a, "書き出すファイルのパスワードを選んでください。KeePass / KeePassXC などで開くときに必要です。", 14f)
            .apply { setLineSpacing(0f, 1.1f) })
        val rg = android.widget.RadioGroup(a)
        val same = android.widget.RadioButton(a).apply { id = View.generateViewId(); text = "今のマスターパスワードと同じ"; textSize = 15f; setTextColor(W.TEXT) }
        val other = android.widget.RadioButton(a).apply { id = View.generateViewId(); text = "別のパスワードを入力する"; textSize = 15f; setTextColor(W.TEXT) }
        rg.addView(same); rg.addView(other)
        col.addView(rg, W.lp(W.MATCH, W.WRAP, t = 8))
        val pwBox = W.vbox(a)
        val (p1, p2) = passwordPair(a, pwBox)
        col.addView(pwBox, W.lp(W.MATCH, W.WRAP, l = 12))
        rg.setOnCheckedChangeListener { _, id -> pwBox.visibility = if (id == other.id) View.VISIBLE else View.GONE }
        same.isChecked = true; pwBox.visibility = View.GONE
        return ClassicDialog(a, "外部に書き出す", col, listOf(
            ClassicDialog.Btn("保存先を選ぶ...", true) {
                if (same.isChecked) { pickAndWrite(a, fileName, null, source); true }
                else {
                    val pw = checkPair(a, "外部に書き出す", p1, p2) ?: return@Btn false
                    pickAndWrite(a, fileName, pw, source); p1.setText(""); p2.setText(""); true
                }
            },
            ClassicDialog.Btn("キャンセル"))).also { it.show() }
    }

    /** [password] null = keep the database's own credentials (same master password). */
    private fun pickAndWrite(a: MainActivity, fileName: String, password: String?, source: () -> KeePassDatabase?) {
        Session.flush()
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, fileName)
        }
        a.startForResult(i) { rc, data ->
            val uri: Uri = data?.data ?: return@startForResult
            if (rc != Activity.RESULT_OK) return@startForResult
            val busy = Dlg.busy(a, "外部に書き出す", "暗号化して書き出しています…")
            Session.io.execute {
                val r = runCatching {
                    val db = source() ?: error("データベースが開かれていません")
                    val out = if (password == null) Kdbx.withHeader(db, light = false) else Kdbx.exportCopy(db, password)
                    val bytes = Kdbx.encodeVerified(out)
                    a.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                }
                a.runOnUiThread {
                    busy.dismiss()
                    r.onSuccess { Toast.makeText(a, "書き出しました", Toast.LENGTH_SHORT).show() }
                     .onFailure { Dlg.message(a, "外部に書き出す", "書き出せませんでした。\n${it.message ?: it}", 'x') }
                }
            }
        }
    }
}
