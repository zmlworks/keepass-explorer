package io.github.zmlworks.kpexplorer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import app.keemobile.kotpass.database.modifiers.modifyContent
import io.github.zmlworks.kpexplorer.db.Order
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
        val busy = Dlg.busy(a, L.t("ログオン", "Log On"), L.t("データベースを開いています…", "Opening the database…"))
        Session.io.execute {
            val r = runCatching { Kdbx.open(a.store.read(), composite) }
            if (!keep) composite.fill(0)
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess { db -> opened(a, db); onOk() }
                 .onFailure { t -> onError(if (t is Kdbx.WrongPasswordException) L.t("マスターパスワードが違います。", "Wrong master password.") else L.t("データベースを開けませんでした。\n$t", "Could not open the database.\n$t")) }
            }
        }
    }

    /** Session start; recycle bin set up when the database uses one, old bin items auto-deleted when set (localized bin name, stable UUID). */
    fun opened(a: MainActivity, db: KeePassDatabase) {
        Session.open(db)
        val binName = a.getString(R.string.recycle_bin)
        Session.update { Tree.purgeOldInBin(Tree.ensureRecycleBinAtRoot(it, binName)) }   // 0.4.23: + auto-delete by days
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
                a.vault.wrap(a, k) { err -> k.fill(0); if (!err.isNullOrEmpty()) toast(a, L.t("指紋・画面ロックの登録に失敗: $err", "Could not set up fingerprint / screen lock: $err")) }
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
        val busy = Dlg.busy(a, L.t("セットアップ", "Setup"), L.t("データベースを作成しています…", "Creating the database…"))
        Session.io.execute {
            val r = runCatching { newDatabase(composite).also { a.store.write(Kdbx.encodeVerified(it, keepSalt = true)) } }
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess {
                    opened(a, it)
                    if (a.vault.isAvailable() && Vault.forceAvailableForTest == null)
                        a.vault.wrap(a, composite) { err -> composite.fill(0); if (!err.isNullOrEmpty()) toast(a, L.t("指紋・画面ロックの登録に失敗: $err", "Could not set up fingerprint / screen lock: $err")); onOk() }
                    else { composite.fill(0); onOk() }
                }.onFailure { composite.fill(0); onError(L.t("作成できませんでした。\n$it", "Could not create it.\n$it")) }
            }
        }
    }

    /** Menu: new master password (twice). The file is re-encrypted (new Argon2 salt) and the wrapped key replaced. */
    fun changeMasterPassword(a: MainActivity): ClassicDialog {
        val col = W.vbox(a)
        col.addView(W.text(a, L.t("新しいマスターパスワードを 2 回入力してください。アプリ内に保存済みの日付ファイルは、", "Enter the new master password twice. Dated files already saved inside the app ") +
            L.t("それぞれ作成時のマスターパスワードのままです。", "keep the master password they were made with."), 14f).apply { setLineSpacing(0f, 1.1f) })
        val (p1, p2) = passwordPair(a, col)
        return ClassicDialog(a, L.t("マスターパスワードの変更", "Change Master Password"), col, listOf(
            ClassicDialog.Btn(L.t("変更", "Change"), true) {
                val pw = checkPair(a, L.t("マスターパスワードの変更", "Change Master Password"), p1, p2) ?: return@Btn false
                val k = Kdbx.compositeKey(pw)
                Session.update { Kdbx.withHeader(Kdbx.withCredentials(it, k), light = false) }
                val busy = Dlg.busy(a, L.t("マスターパスワードの変更", "Change Master Password"), L.t("暗号化し直しています…", "Re-encrypting…"))
                Session.afterSave({ Unit }) { res ->
                    busy.dismiss()
                    res.onSuccess {
                        toast(a, L.t("マスターパスワードを変更しました", "Master password changed"))
                        if (a.vault.isAvailable() && Vault.forceAvailableForTest == null)
                            a.vault.wrap(a, k) { err -> k.fill(0); if (!err.isNullOrEmpty()) toast(a, L.t("指紋・画面ロックの登録に失敗: $err", "Could not set up fingerprint / screen lock: $err")) }
                        else { a.vault.clear(); k.fill(0) }
                    }.onFailure { k.fill(0); Dlg.message(a, L.t("マスターパスワードの変更", "Change Master Password"), L.t("保存できませんでした。\n${it.message ?: it}", "Could not save.\n${it.message ?: it}"), 'x') }
                }
                true
            },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel")))).also { it.show() }
    }

    private fun toast(a: MainActivity, s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()

    /** Two password fields + "show" check box. */
    fun passwordPair(a: MainActivity, col: android.widget.LinearLayout, first: String = L.t("パスワード:", "Password:"), second: String = L.t("パスワードの確認入力:", "Confirm password:")): Pair<EditText, EditText> {
        fun f(label: String): EditText {
            col.addView(W.text(a, label, 14f), W.lp(W.MATCH, W.WRAP, t = 8))
            return W.field(a, password = true).also { col.addView(it, W.lp(W.MATCH, W.WRAP, t = 3)) }
        }
        val p1 = f(first); val p2 = f(second)
        val show = W.check(a, L.t("パスワードを表示する", "Show password"), false)
        show.setOnCheckedChangeListener { _, on ->
            val t = InputType.TYPE_CLASS_TEXT or if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            p1.inputType = t; p2.inputType = t
        }
        col.addView(show, W.lp(W.MATCH, W.WRAP, t = 6))
        return p1 to p2
    }

    /** Password from the pair, or null after telling the user what is wrong. */
    fun checkPair(a: MainActivity, title: String, p1: EditText, p2: EditText): String? = when {
        p1.text.isEmpty() -> { Dlg.message(a, title, L.t("パスワードを入力してください。", "Enter a password."), '!'); null }
        p1.text.toString() != p2.text.toString() -> { Dlg.message(a, title, L.t("確認用のパスワードが一致しません。", "The passwords do not match."), 'x'); null }
        else -> p1.text.toString()
    }

    // ---------------------------------------------------------------- save dialog / export

    /** Toolbar 保存: internal dated copy, or password-protected export outside the app (SAF). */
    fun saveDialog(a: MainActivity, onInternal: (File) -> Unit = {}): ClassicDialog {
        val col = W.vbox(a)
        col.addView(W.text(a, L.t("保存方法を選んでください。", "Choose how to save."), 15f, true), W.lp(W.MATCH, W.WRAP, b = 8))
        col.addView(W.text(a, L.t("・アプリ内に保存: backups/${LocalDate.now()}.kdbx として、アプリの中に日付のコピーを作ります (同じ日は _2, _3…)。\n", "• Save in app: makes a dated copy inside the app as backups/${LocalDate.now()}.kdbx (_2, _3… on the same day).\n") +
            L.t("・外部に書き出す: 保存先 (ダウンロード、クラウドなど) を選び、パスワードを付けた KDBX として書き出します。KeePass / KeePassXC で開けます。", "• Export: choose a place (Downloads, cloud storage…) and write a password-protected KDBX there. Opens in KeePass / KeePassXC."), 14f)
            .apply { setLineSpacing(0f, 1.15f) })
        return ClassicDialog(a, L.t("保存", "Save"), col, listOf(
            ClassicDialog.Btn(L.t("アプリ内に保存", "Save in app"), true) { internalBackup(a, onInternal); true },
            ClassicDialog.Btn(L.t("外部に書き出す...", "Export...")) { askExportPassword(a, "keepass-explorer-${LocalDate.now()}.kdbx") { Session.db }; true },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel")))).also { it.show() }
    }

    fun internalBackup(a: MainActivity, done: (File) -> Unit = {}) {
        val busy = Dlg.busy(a, L.t("保存", "Save"), L.t("保存しています…", "Saving…"))
        Session.afterSave({ a.store.backup() }) { res ->
            busy.dismiss()
            res.onSuccess { f -> Toast.makeText(a, L.t("保存しました: backups/${f.name}", "Saved: backups/${f.name}"), Toast.LENGTH_SHORT).show(); done(f) }
               .onFailure { Dlg.message(a, L.t("保存", "Save"), L.t("保存できませんでした。\n${it.message ?: it}", "Could not save.\n${it.message ?: it}"), 'x') }
        }
    }

    /**
     * Export: choose "same master password" (current key, fresh salt) or "a different password" (twice),
     * then SAF "create document" → standard KDBX 4 that KeePass / KeePassXC open.
     */
    fun askExportPassword(a: MainActivity, fileName: String, source: () -> KeePassDatabase?): ClassicDialog {
        val col = W.vbox(a)
        col.addView(W.text(a, L.t("書き出すファイルのパスワードを選んでください。KeePass / KeePassXC などで開くときに必要です。", "Choose the password for the exported file. It is needed to open it in KeePass / KeePassXC etc."), 14f)
            .apply { setLineSpacing(0f, 1.1f) })
        val rg = android.widget.RadioGroup(a)
        val same = android.widget.RadioButton(a).apply { id = View.generateViewId(); text = L.t("今のマスターパスワードと同じ", "Same as the current master password"); textSize = 15f; setTextColor(W.TEXT) }
        val other = android.widget.RadioButton(a).apply { id = View.generateViewId(); text = L.t("別のパスワードを入力する", "Enter another password"); textSize = 15f; setTextColor(W.TEXT) }
        rg.addView(same); rg.addView(other)
        col.addView(rg, W.lp(W.MATCH, W.WRAP, t = 8))
        val pwBox = W.vbox(a)
        val (p1, p2) = passwordPair(a, pwBox)
        col.addView(pwBox, W.lp(W.MATCH, W.WRAP, l = 12))
        rg.setOnCheckedChangeListener { _, id -> pwBox.visibility = if (id == other.id) View.VISIBLE else View.GONE }
        same.isChecked = true; pwBox.visibility = View.GONE
        return ClassicDialog(a, L.t("外部に書き出す", "Export"), col, listOf(
            ClassicDialog.Btn(L.t("保存先を選ぶ...", "Choose location..."), true) {
                if (same.isChecked) { pickAndWrite(a, fileName, null, source); true }
                else {
                    val pw = checkPair(a, L.t("外部に書き出す", "Export"), p1, p2) ?: return@Btn false
                    pickAndWrite(a, fileName, pw, source); p1.setText(""); p2.setText(""); true
                }
            },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel")))).also { it.show() }
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
            val busy = Dlg.busy(a, L.t("外部に書き出す", "Export"), L.t("暗号化して書き出しています…", "Encrypting and exporting…"))
            Session.io.execute {
                val r = runCatching {
                    val db = source() ?: error(L.t("データベースが開かれていません", "No database is open"))
                    val out = if (password == null) Kdbx.withHeader(db, light = false) else Kdbx.exportCopy(db, password)
                    val bytes = Kdbx.encodeVerified(out)
                    a.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                }
                a.runOnUiThread {
                    busy.dismiss()
                    r.onSuccess { Toast.makeText(a, L.t("書き出しました", "Exported"), Toast.LENGTH_SHORT).show() }
                     .onFailure { Dlg.message(a, L.t("外部に書き出す", "Export"), L.t("書き出せませんでした。\n${it.message ?: it}", "Could not export.\n${it.message ?: it}"), 'x') }
                }
            }
        }
    }
}
