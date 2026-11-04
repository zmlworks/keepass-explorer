package io.github.zmlworks.kpexplorer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.InputType
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
 * Opening the database with the Keystore-held random key (OS screen lock), first-run creation, and the
 * password-protected export (the only place where the user types a password).
 */
object Security {
    /** Decrypt the app database with [composite] (wiped afterwards) on the I/O thread. */
    fun openStored(a: MainActivity, composite: ByteArray, onError: (String) -> Unit, onOk: () -> Unit) {
        val busy = Dlg.busy(a, "ログオン", "データベースを開いています…")
        Session.io.execute {
            val r = runCatching { Kdbx.open(a.store.read(), composite) }
            composite.fill(0)
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess { db -> opened(a, db); onOk() }
                 .onFailure { t -> onError(if (t is Kdbx.WrongPasswordException) "鍵が一致しないため、データベースを開けません。" else "データベースを開けませんでした。\n$t") }
            }
        }
    }

    fun opened(a: MainActivity, db: KeePassDatabase) { Session.open(db) }

    /** OS prompt → key → open. */
    fun unlock(a: MainActivity, onError: (String) -> Unit, onOk: () -> Unit) {
        a.vault.unlock(a, object : Vault.Result {
            override fun ok(composite: ByteArray) = openStored(a, composite, onError, onOk)
            override fun fail(msg: String) = onError(msg)
        })
    }

    /** New empty database (root folder without a name = "/"), encrypted with a fresh random key. */
    fun newDatabase(composite: ByteArray): KeePassDatabase =
        Kdbx.create("KeePass Explorer", composite, rootName = "", light = true)

    /** First run: OS prompt wraps a new random key; create and write the empty database. */
    fun firstRun(a: MainActivity, onError: (String) -> Unit, onOk: () -> Unit) {
        a.vault.create(a, object : Vault.Result {
            override fun ok(composite: ByteArray) {
                val busy = Dlg.busy(a, "セットアップ", "データベースを作成しています…")
                Session.io.execute {
                    val r = runCatching { newDatabase(composite).also { a.store.write(Kdbx.encodeVerified(it)) } }
                    composite.fill(0)
                    a.runOnUiThread {
                        busy.dismiss()
                        r.onSuccess { opened(a, it); onOk() }.onFailure { a.vault.clear(); onError("作成できませんでした。\n$it") }
                    }
                }
            }
            override fun fail(msg: String) = onError(msg)
        })
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

    /** Password (twice) → SAF "create document" → standard KDBX encrypted with that password. */
    fun askExportPassword(a: MainActivity, fileName: String, source: () -> KeePassDatabase?): ClassicDialog {
        val col = W.vbox(a)
        col.addView(W.text(a, "書き出すファイルのマスターパスワードを入力してください。KeePass / KeePassXC などで開くときに必要です。\n" +
            "忘れると開けなくなります。", 14f).apply { setLineSpacing(0f, 1.1f) })
        fun f(label: String): EditText {
            col.addView(W.text(a, label, 14f), W.lp(W.MATCH, W.WRAP, t = 8))
            return W.field(a, password = true).also { col.addView(it, W.lp(W.MATCH, W.WRAP, t = 3)) }
        }
        val p1 = f("パスワード:"); val p2 = f("パスワードの確認入力:")
        val show = W.check(a, "パスワードを表示する", false)
        show.setOnCheckedChangeListener { _, on ->
            val t = InputType.TYPE_CLASS_TEXT or if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            p1.inputType = t; p2.inputType = t
        }
        col.addView(show, W.lp(W.MATCH, W.WRAP, t = 6))
        return ClassicDialog(a, "外部に書き出す", col, listOf(
            ClassicDialog.Btn("保存先を選ぶ...", true) {
                when {
                    p1.text.isEmpty() -> { Dlg.message(a, "外部に書き出す", "パスワードを入力してください。", '!'); false }
                    p1.text.toString() != p2.text.toString() -> { Dlg.message(a, "外部に書き出す", "確認用のパスワードが一致しません。", 'x'); false }
                    else -> { pickAndWrite(a, fileName, p1.text.toString(), source); p1.setText(""); p2.setText(""); true }
                }
            },
            ClassicDialog.Btn("キャンセル"))).also { it.show() }
    }

    private fun pickAndWrite(a: MainActivity, fileName: String, password: String, source: () -> KeePassDatabase?) {
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
                    val bytes = Kdbx.encodeVerified(Kdbx.exportCopy(db, password))
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
