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
        val busy = Dlg.busy(a, L.t("ログオン", "Log On"), L.t("データベースを開いています", "Opening the database"))
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
        val busy = Dlg.busy(a, L.t("セットアップ", "Setup"), L.t("データベースを作成しています", "Creating the database"))
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

    /**
     * Menu: new master password (twice). The file is re-encrypted (new Argon2 salt) and the wrapped key replaced.
     * 0.4.33: then the internal backups that open with the current password are encrypted again for the new one
     * ([io.github.zmlworks.kpexplorer.db.Store.rekeyBackups]; others are left as they are), unless the user ticks
     * 「内部バックアップのパスワードを書き換えない」 (after a confirmation). Backups are only touched after the database
     * itself was saved with the new password.
     */
    fun changeMasterPassword(a: MainActivity): ClassicDialog {
        val title = L.t("マスターパスワードの変更", "Change Master Password")
        val col = W.vbox(a)
        col.addView(W.text(a, L.t("現在のパスワードで開けられる内部バックアップも新しいパスワードに書き換えます",
            "Internal backups that open with the current password are rewritten for the new password too"), 14f).apply { setLineSpacing(0f, 1.1f) },
            W.lp(W.MATCH, W.WRAP, b = 8))
        val (p1, p2) = passwordPair(a, col, L.t("新しいパスワード", "New password"), L.t("新しいパスワードの確認", "Confirm new password"))
        val keep = W.check(a, L.t("内部バックアップのパスワードを書き換えない", "Do not change the password of internal backups"), false)
        keep.tag = "keep-backups"
        var sure = false
        keep.setOnCheckedChangeListener { box, on ->
            if (!on || sure) { sure = false; return@setOnCheckedChangeListener }
            box.isChecked = false   // ticked only after the warning is accepted
            Dlg.confirm(a, title, L.t("バックアップを開くのに古いパスワードの入力が必要になります", "Opening the backups will need the old password"), '!',
                yes = "OK", no = L.t("キャンセル", "Cancel")) { sure = true; box.isChecked = true }
        }
        col.addView(keep, W.lp(W.MATCH, W.WRAP, t = 2))
        return ClassicDialog(a, title, col, listOf(
            ClassicDialog.Btn(L.t("変更", "Change"), true) {
                val pw = checkPair(a, title, p1, p2) ?: return@Btn false
                val old = Session.compositeCopy() ?: return@Btn false
                val rekey = !keep.isChecked
                val k = Kdbx.compositeKey(pw)
                Session.update { Kdbx.withHeader(Kdbx.withCredentials(it, k), light = false) }
                val busy = Dlg.busy(a, title, L.t("暗号化し直しています", "Re-encrypting"))
                Session.afterSave({ if (rekey) a.store.rekeyBackups(old, k) else null }) { res ->
                    old.fill(0)
                    busy.dismiss()
                    res.onSuccess { r ->
                        Dlg.message(a, title, changedMessage(r), 'i')
                        if (a.vault.isAvailable() && Vault.forceAvailableForTest == null)
                            a.vault.wrap(a, k) { err -> k.fill(0); if (!err.isNullOrEmpty()) toast(a, L.t("指紋・画面ロックの登録に失敗: $err", "Could not set up fingerprint / screen lock: $err")) }
                        else { a.vault.clear(); k.fill(0) }
                    }.onFailure { k.fill(0); Dlg.message(a, title, L.t("保存できませんでした。\n${it.message ?: it}", "Could not save.\n${it.message ?: it}"), 'x') }
                }
                true
            },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel")))).also { it.show() }
    }

    /** The report after a master password change: how many internal backups were rewritten / left alone. */
    fun changedMessage(r: io.github.zmlworks.kpexplorer.db.Store.Rekey?): String {
        val head = L.t("マスターパスワードを変更しました。", "The master password was changed.")
        if (r == null) return head + "\n" + L.t("内部バックアップはそのままです (開くには古いパスワードが必要です)。", "Internal backups were left as they were (they need the old password).")
        val sb = StringBuilder(head).append("\n")
        sb.append(L.t("内部バックアップ: ${r.rewritten.size} 件を新しいパスワードに書き換えました。", "Internal backups: ${r.rewritten.size} rewritten for the new password."))
        if (r.skipped.isNotEmpty()) sb.append("\n").append(L.t("${r.skipped.size} 件は現在のパスワードで開けないため、そのままです。", "${r.skipped.size} do not open with the current password and were left as they were."))
        if (r.failed.isNotEmpty()) sb.append("\n").append(L.t("${r.failed.size} 件は書き換えられませんでした (元のまま): ", "${r.failed.size} could not be rewritten (unchanged): ") + r.failed.joinToString(", "))
        return sb.toString()
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

    /**
     * Toolbar 保存 (0.4.33 wording): [アプリ内に保存] makes the dated internal backup whose name is shown under it,
     * [外部に書き出す] the password-protected export (SAF); [キャンセル] at the bottom.
     */
    fun saveDialog(a: MainActivity, onInternal: (File) -> Unit = {}): ClassicDialog {
        val col = W.vbox(a)
        lateinit var d: ClassicDialog
        val name = a.store.nextBackupName()
        fun choice(label: String, tag: String, note: String, f: () -> Unit) {
            col.addView(W.button(a, label) { d.dismiss(); f() }.apply { this.tag = tag }, W.lp(W.MATCH, W.WRAP, t = if (col.childCount == 0) 0 else 12))
            col.addView(W.text(a, note, 14f).apply { setLineSpacing(0f, 1.1f); setPadding(W.dp(10), 0, 0, 0) }, W.lp(W.MATCH, W.WRAP, t = 4))
        }
        choice(L.t("アプリ内に保存", "Save in app"), "save-internal",
            L.t("$name として内部にバックアップします", "Backs up inside the app as $name")) { internalBackup(a, onInternal) }
        choice(L.t("外部に書き出す", "Export"), "save-export",
            L.t("保存先を選び、パスワード付きKDBXファイルを書き出します", "Choose a location and write a password-protected KDBX file")) {
            askExportPassword(a, "keepass-explorer-${LocalDate.now()}.kdbx") { Session.db }
        }
        d = ClassicDialog(a, L.t("保存", "Save"), col, listOf(ClassicDialog.Btn(L.t("キャンセル", "Cancel"))))
        d.show()
        return d
    }

    /** 0.4.33: menu 「現在のバックアップを作成」 and the backup list's 「今すぐ作成」 ask first, naming the file. */
    fun confirmBackup(a: MainActivity, done: (File) -> Unit = {}): ClassicDialog {
        val name = a.store.nextBackupName()
        return Dlg.confirm(a, L.t("現在のバックアップを作成", "Back up the current database"),
            L.t("現在のデータベースを $name として内部にバックアップしますか?", "Back up the current database inside the app as $name?"), '?',
            yes = L.t("作成", "Create"), no = L.t("キャンセル", "Cancel")) { internalBackup(a, done) }
    }

    fun internalBackup(a: MainActivity, done: (File) -> Unit = {}) {
        val busy = Dlg.busy(a, L.t("保存", "Save"), L.t("保存しています", "Saving"))
        Session.afterSave({ a.store.backup() }) { res ->
            busy.dismiss()
            res.onSuccess { f -> Toast.makeText(a, L.t("内部にバックアップしました: ${f.name}", "Backed up inside the app: ${f.name}"), Toast.LENGTH_SHORT).show(); done(f) }
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
            ClassicDialog.Btn(L.t("保存先を選ぶ", "Choose location"), true) {
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
            val busy = Dlg.busy(a, L.t("外部に書き出す", "Export"), L.t("暗号化して書き出しています", "Encrypting and exporting"))
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
