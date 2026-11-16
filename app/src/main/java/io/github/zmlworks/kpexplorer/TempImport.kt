package io.github.zmlworks.kpexplorer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.Toast
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.sec.PasswordStrength
import io.github.zmlworks.kpexplorer.db.FileInfo
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.W
import java.io.File

/**
 * Temporary read-only browse (external SAF / internal backup) and Import (replace primary
 * after forced timestamped backup).
 *
 * Same-password attempt: uses [Session.compositeCopy] — the salt-independent SHA-256²(password)
 * composite. Compatible KDF on the candidate then derives the file key. Different master
 * password (or corrupt file) fails → password prompt. Never stores plaintext password.
 */
object TempImport {

    // ------------------------------------------------------------------ temp browse

    /** Also from the unlock screen (0.4.35): the app's own database stays locked; closing the view locks again. */
    fun browseExternal(a: MainActivity) {
        if (Session.readOnlyTemp) {
            Dlg.message(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_no_edit), 'i')
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        a.startForResult(i) { rc, data ->
            if (rc != Activity.RESULT_OK) return@startForResult
            val uri = data?.data ?: return@startForResult
            val name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "external.kdbx"
            openBytesAsTemp(a, readUri(a, uri), name)
        }
    }

    /** [back] (0.4.33): what to show when the read-only view ends (the backup list), instead of the main folder. */
    fun browseBackupFile(a: MainActivity, f: File, back: ((MainActivity) -> Unit)? = null) {
        if (Session.readOnlyTemp) {
            Dlg.message(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_no_edit), 'i')
            return
        }
        openBytesAsTemp(a, f.readBytes(), f.name, back, f)
    }

    /** Where [exitTemp] goes back to (set when the read-only view starts; null = the main folder). */
    private var afterExit: ((MainActivity) -> Unit)? = null

    private fun readUri(a: MainActivity, uri: Uri): ByteArray =
        a.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    /** [backup] (0.4.37): the internal backup being opened, whose password verdict is recorded when it opens. */
    private fun openBytesAsTemp(a: MainActivity, bytes: ByteArray, label: String, back: ((MainActivity) -> Unit)? = null, backup: File? = null) {
        if (bytes.isEmpty()) {
            Dlg.message(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_invalid_file), 'x')
            return
        }
        // 0.4.35: locked (opened from the unlock screen) — no current password to try, ask for this file's
        val composite = Session.compositeCopy() ?: run { promptPasswordForTemp(a, bytes, label, back, backup); return }
        val busy = Dlg.busy(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_trying_same))
        Session.io.execute {
            val tried = try {
                runCatching { Kdbx.open(bytes, composite) }
            } finally {
                composite.fill(0)
            }
            a.runOnUiThread {
                busy.dismiss()
                tried.onSuccess { db ->
                    // same key as the database: same password, so the database's record applies
                    if (backup != null && a.store.info(backup) == FileInfo.LEGACY) a.store.setInfo(backup, a.store.info())
                    enterTemp(a, db, label, back)
                }
                    .onFailure { err ->
                        if (err is Kdbx.WrongPasswordException || compositeFailedAsWrong(err)) {
                            promptPasswordForTemp(a, bytes, label, back, backup)
                        } else {
                            Dlg.message(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_invalid_file), 'x')
                        }
                    }
            }
        }
    }

    private fun compositeFailedAsWrong(t: Throwable): Boolean =
        t is Kdbx.WrongPasswordException || t.message?.contains("password", true) == true

    private fun promptPasswordForTemp(a: MainActivity, bytes: ByteArray, label: String, back: ((MainActivity) -> Unit)?, backup: File? = null) {
        val col = W.vbox(a)
        val ask = if (Session.isOpen) a.getString(R.string.temp_need_password)
            else L.t("「$label」のパスワードを入力してください。アプリのデータベースはロックしたまま、閲覧専用で開きます。",
                "Enter the password of \"$label\". It opens read-only; the app's own database stays locked.")
        col.addView(W.text(a, ask, 14f).apply { setLineSpacing(0f, 1.1f) })
        col.addView(W.text(a, a.getString(R.string.password_label), 14f), W.lp(W.MATCH, W.WRAP, t = 8))
        val field = W.field(a, password = true).apply { tag = "temp-password" }
        col.addView(field, W.lp(W.MATCH, W.WRAP, t = 3))
        val show = W.check(a, a.getString(R.string.show_password), false)
        show.setOnCheckedChangeListener { _, on ->
            field.inputType = InputType.TYPE_CLASS_TEXT or
                if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(show, W.lp(W.MATCH, W.WRAP, t = 6))
        ClassicDialog(a, a.getString(R.string.temp_open_title), col, listOf(
            ClassicDialog.Btn(a.getString(R.string.open), true) {
                val pw = field.text?.toString().orEmpty()
                if (pw.isEmpty()) {
                    Dlg.message(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_need_password), '!')
                    return@Btn false
                }
                tryOpenWithPassword(a, bytes, label, pw, back, backup)
                field.setText("")
                true
            },
            ClassicDialog.Btn(a.getString(R.string.cancel))
        )).show()
    }

    private fun tryOpenWithPassword(a: MainActivity, bytes: ByteArray, label: String, password: String, back: ((MainActivity) -> Unit)?, backup: File? = null) {
        val busy = Dlg.busy(a, a.getString(R.string.temp_open_title), a.getString(R.string.opening_db))
        Session.io.execute {
            val key = Kdbx.compositeKey(password)
            val r = runCatching { Kdbx.open(bytes, key) }
            key.fill(0)
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess {
                    // 0.4.37: an internal backup opened with a typed password gets that password's verdict
                    if (backup != null) a.store.setInfo(backup, PasswordStrength.info(password))
                    enterTemp(a, it, label, back)
                }
                    .onFailure { t ->
                        val msg = if (t is Kdbx.WrongPasswordException)
                            a.getString(R.string.temp_wrong_password)
                        else a.getString(R.string.temp_invalid_file)
                        Dlg.message(a, a.getString(R.string.temp_open_title), msg, 'x')
                    }
            }
        }
    }

    private fun enterTemp(a: MainActivity, db: KeePassDatabase, label: String, back: ((MainActivity) -> Unit)?) {
        // Flush pending primary saves first so stash is consistent; do not touch primary file after.
        Session.flush()
        Session.afterSave({ Unit }) { res ->
            res.onFailure {
                Dlg.message(a, a.getString(R.string.temp_open_title), a.getString(R.string.cannot_open_db, it.message ?: it), 'x')
                return@afterSave
            }
            try {
                Session.beginTempView(db, label)
            } catch (t: Throwable) {
                Dlg.message(a, a.getString(R.string.temp_open_title), t.message ?: t.toString(), 'x')
                return@afterSave
            }
            afterExit = back
            a.explorer = null
            a.showExplorer()
        }
    }

    fun exitTemp(a: MainActivity) {
        if (!Session.readOnlyTemp) return
        Session.exitTempView()
        a.explorer = null
        val back = afterExit; afterExit = null
        when {
            back != null -> back(a)
            Session.isOpen -> a.showExplorer()
            else -> a.show(UnlockScreen(a, prompt = false))   // 0.4.35: opened from the unlock screen → back to it, still locked
        }
    }

    // ------------------------------------------------------------------ import (replace primary)

    fun showImportMenu(a: MainActivity) {
        if (Session.readOnlyTemp) {
            Dlg.message(a, a.getString(R.string.import_title), a.getString(R.string.temp_no_edit), 'i')
            return
        }
        val col = W.vbox(a)
        col.addView(W.text(a, a.getString(R.string.import_explain), 14f).apply { setLineSpacing(0f, 1.15f) })
        // 0.4.38: 外部ファイルを閲覧 left the menu; it is here, as "look without importing"
        lateinit var d: ClassicDialog
        val r = W.hbox(a).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        r.addView(W.text(a, L.t("取り込まずに中身を見るだけなら:", "To look without importing:"), 14f), W.lp(0, W.WRAP, 1f))
        r.addView(W.button(a, a.getString(R.string.browse_external)) { d.dismiss(); browseExternal(a) }.apply { tag = "import-browse" }, W.lp(W.WRAP, W.WRAP, l = 8))
        col.addView(r, W.lp(W.MATCH, W.WRAP, t = 12))
        d = ClassicDialog(a, a.getString(R.string.import_title), col, listOf(
            ClassicDialog.Btn(a.getString(R.string.import_from_external), true) {
                pickExternalForImport(a); true
            },
            ClassicDialog.Btn(a.getString(R.string.import_from_backup)) {
                pickBackupForImport(a); true
            },
            ClassicDialog.Btn(a.getString(R.string.cancel))
        ))
        d.show()
    }

    private fun pickExternalForImport(a: MainActivity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        a.startForResult(i) { rc, data ->
            if (rc != Activity.RESULT_OK) return@startForResult
            val uri = data?.data ?: return@startForResult
            val name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "import.kdbx"
            confirmAndImport(a, readUri(a, uri), name)
        }
    }

    private fun pickBackupForImport(a: MainActivity) {
        val files = a.store.backups()
        if (files.isEmpty()) {
            Dlg.message(a, a.getString(R.string.import_title), a.getString(R.string.backup_need_select), 'i')
            return
        }
        val col = W.vbox(a)
        files.take(40).forEach { f ->
            col.addView(W.button(a, f.name) {
                confirmAndImport(a, f.readBytes(), f.name)
            }, W.lp(W.MATCH, W.WRAP, t = 4))
        }
        ClassicDialog(a, a.getString(R.string.import_from_backup), col, listOf(
            ClassicDialog.Btn(a.getString(R.string.cancel))
        ), scroll = true).show()
    }

    fun confirmAndImport(a: MainActivity, bytes: ByteArray, sourceLabel: String) {
        if (bytes.isEmpty()) {
            Dlg.message(a, a.getString(R.string.import_title), a.getString(R.string.import_invalid), 'x')
            return
        }
        // Preview backup name that will be used (approx; actual may get _2 suffix)
        val previewName = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".kdbx"
        Dlg.confirm(
            a,
            a.getString(R.string.import_confirm_title),
            a.getString(R.string.import_confirm_body, previewName, sourceLabel),
            '!'
        ) {
            unlockCandidateThenReplace(a, bytes, sourceLabel)
        }
    }

    private fun unlockCandidateThenReplace(a: MainActivity, bytes: ByteArray, sourceLabel: String) {
        val busy = Dlg.busy(a, a.getString(R.string.import_title), a.getString(R.string.temp_trying_same))
        Session.io.execute {
            val composite = Session.compositeCopy()
            val tried = if (composite != null) {
                try { runCatching { Kdbx.open(bytes, composite) } } finally { composite.fill(0) }
            } else Result.failure(IllegalStateException("no composite"))
            a.runOnUiThread {
                busy.dismiss()
                tried.onSuccess { db -> performReplace(a, bytes, db) }
                    .onFailure {
                        promptPasswordForImport(a, bytes, sourceLabel)
                    }
            }
        }
    }

    private fun promptPasswordForImport(a: MainActivity, bytes: ByteArray, sourceLabel: String) {
        val col = W.vbox(a)
        col.addView(W.text(a, a.getString(R.string.temp_need_password), 14f).apply { setLineSpacing(0f, 1.1f) })
        col.addView(W.text(a, a.getString(R.string.password_label), 14f), W.lp(W.MATCH, W.WRAP, t = 8))
        val field = W.field(a, password = true)
        col.addView(field, W.lp(W.MATCH, W.WRAP, t = 3))
        ClassicDialog(a, a.getString(R.string.import_title), col, listOf(
            ClassicDialog.Btn(a.getString(R.string.import_menu).removeSuffix("").removeSuffix(""), true) {
                val pw = field.text?.toString().orEmpty()
                if (pw.isEmpty()) {
                    Dlg.message(a, a.getString(R.string.import_title), a.getString(R.string.temp_need_password), '!')
                    return@Btn false
                }
                val busy = Dlg.busy(a, a.getString(R.string.import_title), a.getString(R.string.opening_db))
                Session.io.execute {
                    val key = Kdbx.compositeKey(pw)
                    val r = runCatching { Kdbx.open(bytes, key) }
                    // Keep key for rewrap after success — wipe on failure paths inside
                    a.runOnUiThread {
                        busy.dismiss()
                        r.onSuccess { db ->
                            performReplace(a, bytes, db, keyForWrap = key, info = PasswordStrength.info(pw))
                        }.onFailure { t ->
                            key.fill(0)
                            val msg = if (t is Kdbx.WrongPasswordException)
                                a.getString(R.string.import_wrong_password)
                            else a.getString(R.string.import_invalid)
                            Dlg.message(a, a.getString(R.string.import_title), msg, 'x')
                        }
                    }
                }
                field.setText("")
                true
            },
            ClassicDialog.Btn(a.getString(R.string.cancel)) {
                Toast.makeText(a, a.getString(R.string.import_cancelled), Toast.LENGTH_SHORT).show()
                true
            }
        )).show()
    }

    /**
     * Order: flush primary → timestamped backup (abort if fail) → validate bytes already opened as [candidate]
     * → write primary → rewrap Keystore with candidate credentials → Security.opened.
     * [keyForWrap] is the candidate composite (caller transfers ownership); wiped after wrap attempt.
     */
    private fun performReplace(
        a: MainActivity,
        bytes: ByteArray,
        candidate: KeePassDatabase,
        keyForWrap: ByteArray? = null,
        info: FileInfo? = null
    ) {
        val busy = Dlg.busy(a, a.getString(R.string.import_title), a.getString(R.string.import_backing_up))
        // 0.4.38: a file opened with a typed password has another key: the backups made with the current one can follow
        val oldKey = if (keyForWrap != null) Session.compositeCopy() else null
        val newKey = keyForWrap?.copyOf()
        Session.afterSave({
            // 1) Forced dated backup of CURRENT primary (must succeed)
            val backup = try {
                a.store.backupTimestamped()
            } catch (t: Throwable) {
                throw BackupFailed(t)
            }
            // 2) Atomic replace
            a.store.replacePrimary(bytes)
            // 0.4.37: a file opened with a typed password is a master-password database with that verdict;
            // one that opened with the current key keeps the current record (same key, same protection)
            if (info != null) a.store.setInfo(info)
            backup
        }) { res ->
            busy.dismiss()
            res.onFailure { t ->
                keyForWrap?.fill(0); oldKey?.fill(0); newKey?.fill(0)
                val msg = when (t) {
                    is BackupFailed -> a.getString(R.string.import_backup_failed)
                    is Session.SaveFailedException -> a.getString(R.string.import_backup_failed) + "\n" + (t.message ?: "")
                    else -> a.getString(R.string.import_invalid) + "\n" + (t.message ?: t.toString())
                }
                Dlg.message(a, a.getString(R.string.import_title), msg, 'x')
            }.onSuccess { backup ->
                // Open candidate as primary; rewrap vault with its composite when it differs. 0.4.37: the
                // same-key path keeps the stored wrapped key as it is (still valid; for an OS-keystore database
                // replacing it would risk its only key on a cancelled prompt).
                val wrapKey = keyForWrap
                Security.opened(a, candidate)
                if (wrapKey != null && a.vault.canWrap()) {
                    a.vault.wrap(a, wrapKey) { err ->
                        wrapKey.fill(0)
                        if (!err.isNullOrEmpty()) {
                            Toast.makeText(a, err, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    wrapKey?.fill(0)
                }
                a.explorer = null
                a.showExplorer()
                Dlg.message(
                    a,
                    a.getString(R.string.import_title),
                    a.getString(R.string.import_ok) + "\nbackups/${backup.name}",
                    'i'
                ).onGone.add {   // OK or ×: then the question about the backups
                    if (oldKey != null && newKey != null && info != null) askRewriteBackups(a, oldKey, newKey, info)
                    else { oldKey?.fill(0); newKey?.fill(0) }
                }
            }
        }
    }

    /**
     * 0.4.38: after importing a file opened with its own (typed) password, offer to rewrite the internal backups that
     * open with the previous key for the imported file's password, as far as possible (others are left as they are).
     * Both keys are wiped when done.
     */
    fun askRewriteBackups(a: MainActivity, oldKey: ByteArray, newKey: ByteArray, info: FileInfo) {
        val title = a.getString(R.string.import_title)
        val wipe = { oldKey.fill(0); newKey.fill(0) }
        if (a.store.backups().isEmpty()) { wipe(); return }
        var rewriting = false
        val d = Dlg.confirm(a, title, L.t("内部バックアップも、取り込んだデータベースのパスワードに書き換えますか?\n(今までのパスワードで開けるものだけを書き換えます。書き換えないと、それらを開くには今までのパスワードが必要です)",
            "Rewrite the internal backups for the imported database's password too?\n(Only those that open with the previous password are rewritten. If not, opening them needs the previous password.)"), '?',
            yes = L.t("書き換える", "Rewrite"), no = L.t("書き換えない", "Keep")) {
            rewriting = true
            val busy = Dlg.busy(a, title, L.t("暗号化し直しています", "Re-encrypting"))
            Session.afterSave({ a.store.rekeyBackups(oldKey, newKey, info) }) { r ->
                wipe(); busy.dismiss()
                r.onSuccess { rk -> Dlg.message(a, title, Security.rewrittenMessage(rk), 'i') }
                 .onFailure { Dlg.message(a, title, L.t("書き換えられませんでした。\n${it.message ?: it}", "Could not rewrite them.\n${it.message ?: it}"), 'x') }
            }
        }
        d.onGone.add { if (!rewriting) wipe() }   // 書き換えない / closed: the keys go now
    }

    private class BackupFailed(cause: Throwable) : Exception(cause.message, cause)
}
