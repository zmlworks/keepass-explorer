package io.github.zmlworks.kpexplorer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.Toast
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.sec.Vault
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

    fun browseBackupFile(a: MainActivity, f: File) {
        if (Session.readOnlyTemp) {
            Dlg.message(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_no_edit), 'i')
            return
        }
        openBytesAsTemp(a, f.readBytes(), f.name)
    }

    private fun readUri(a: MainActivity, uri: Uri): ByteArray =
        a.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun openBytesAsTemp(a: MainActivity, bytes: ByteArray, label: String) {
        if (bytes.isEmpty()) {
            Dlg.message(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_invalid_file), 'x')
            return
        }
        val busy = Dlg.busy(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_trying_same))
        Session.io.execute {
            val composite = Session.compositeCopy()
            val tried = if (composite != null) {
                try {
                    runCatching { Kdbx.open(bytes, composite) }
                } finally {
                    composite.fill(0)
                }
            } else Result.failure(IllegalStateException("no composite"))
            a.runOnUiThread {
                busy.dismiss()
                tried.onSuccess { db -> enterTemp(a, db, label) }
                    .onFailure { err ->
                        if (err is Kdbx.WrongPasswordException || compositeFailedAsWrong(err)) {
                            promptPasswordForTemp(a, bytes, label)
                        } else {
                            Dlg.message(a, a.getString(R.string.temp_open_title), a.getString(R.string.temp_invalid_file), 'x')
                        }
                    }
            }
        }
    }

    private fun compositeFailedAsWrong(t: Throwable): Boolean =
        t is Kdbx.WrongPasswordException || t.message?.contains("password", true) == true

    private fun promptPasswordForTemp(a: MainActivity, bytes: ByteArray, label: String) {
        val col = W.vbox(a)
        col.addView(W.text(a, a.getString(R.string.temp_need_password), 14f).apply { setLineSpacing(0f, 1.1f) })
        col.addView(W.text(a, a.getString(R.string.password_label), 14f), W.lp(W.MATCH, W.WRAP, t = 8))
        val field = W.field(a, password = true)
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
                tryOpenWithPassword(a, bytes, label, pw)
                field.setText("")
                true
            },
            ClassicDialog.Btn(a.getString(R.string.cancel))
        )).show()
    }

    private fun tryOpenWithPassword(a: MainActivity, bytes: ByteArray, label: String, password: String) {
        val busy = Dlg.busy(a, a.getString(R.string.temp_open_title), a.getString(R.string.opening_db))
        Session.io.execute {
            val key = Kdbx.compositeKey(password)
            val r = runCatching { Kdbx.open(bytes, key) }
            key.fill(0)
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess { enterTemp(a, it, label) }
                    .onFailure { t ->
                        val msg = if (t is Kdbx.WrongPasswordException)
                            a.getString(R.string.temp_wrong_password)
                        else a.getString(R.string.temp_invalid_file)
                        Dlg.message(a, a.getString(R.string.temp_open_title), msg, 'x')
                    }
            }
        }
    }

    private fun enterTemp(a: MainActivity, db: KeePassDatabase, label: String) {
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
            a.explorer = null
            a.showExplorer()
        }
    }

    fun exitTemp(a: MainActivity) {
        if (!Session.readOnlyTemp) return
        Session.exitTempView()
        a.explorer = null
        a.showExplorer()
    }

    // ------------------------------------------------------------------ import (replace primary)

    fun showImportMenu(a: MainActivity) {
        if (Session.readOnlyTemp) {
            Dlg.message(a, a.getString(R.string.import_title), a.getString(R.string.temp_no_edit), 'i')
            return
        }
        val col = W.vbox(a)
        col.addView(W.text(a, a.getString(R.string.import_explain), 14f).apply { setLineSpacing(0f, 1.15f) })
        ClassicDialog(a, a.getString(R.string.import_title), col, listOf(
            ClassicDialog.Btn(a.getString(R.string.import_from_external), true) {
                pickExternalForImport(a); true
            },
            ClassicDialog.Btn(a.getString(R.string.import_from_backup)) {
                pickBackupForImport(a); true
            },
            ClassicDialog.Btn(a.getString(R.string.cancel))
        )).show()
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
            ClassicDialog.Btn(a.getString(R.string.import_menu).removeSuffix("…").removeSuffix("..."), true) {
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
                            performReplace(a, bytes, db, keyForWrap = key)
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
        keyForWrap: ByteArray? = null
    ) {
        val busy = Dlg.busy(a, a.getString(R.string.import_title), a.getString(R.string.import_backing_up))
        Session.afterSave({
            // 1) Forced dated backup of CURRENT primary (must succeed)
            val backup = try {
                a.store.backupTimestamped()
            } catch (t: Throwable) {
                throw BackupFailed(t)
            }
            // 2) Atomic replace
            a.store.replacePrimary(bytes)
            backup
        }) { res ->
            busy.dismiss()
            res.onFailure { t ->
                keyForWrap?.fill(0)
                val msg = when (t) {
                    is BackupFailed -> a.getString(R.string.import_backup_failed)
                    is Session.SaveFailedException -> a.getString(R.string.import_backup_failed) + "\n" + (t.message ?: "")
                    else -> a.getString(R.string.import_invalid) + "\n" + (t.message ?: t.toString())
                }
                Dlg.message(a, a.getString(R.string.import_title), msg, 'x')
            }.onSuccess { backup ->
                // Open candidate as primary; rewrap vault with its composite
                val wrapKey = keyForWrap ?: run {
                    // Same-password path: composite of candidate equals what Session had;
                    // take from candidate credentials.
                    candidate.credentials.passphrase?.getBinary()?.copyOf()
                }
                Security.opened(a, candidate)
                if (wrapKey != null && a.vault.isAvailable() && Vault.forceAvailableForTest == null) {
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
                )
            }
        }
    }

    private class BackupFailed(cause: Throwable) : Exception(cause.message, cause)
}
