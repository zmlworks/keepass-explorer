package io.github.zmlworks.kpexplorer

import android.widget.EditText
import android.widget.Toast
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.W

/** Opening the database and master-key management (password ↔ Keystore). */
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
                 .onFailure { t ->
                     onError(if (t is Kdbx.WrongPasswordException) "パスワードが正しくありません。" else "データベースを開けませんでした。\n$t")
                 }
            }
        }
    }

    fun opened(a: MainActivity, db: KeePassDatabase) {
        Session.open(db)
        App.prefs(a).edit().putString("dbName", db.content.meta.name.ifEmpty { db.content.group.name }).apply()
    }

    /** Offer / perform fingerprint enrollment for the open database. */
    fun enroll(a: MainActivity, then: () -> Unit) {
        val c = Session.compositeCopy() ?: return then()
        a.vault.enroll(a, c, object : Vault.Result {
            override fun ok(composite: ByteArray?) {
                App.prefs(a).edit().putBoolean("bio", true).apply()
                Toast.makeText(a, "指紋認証を有効にしました", Toast.LENGTH_SHORT).show()
                then()
            }
            override fun fail(msg: String, invalidated: Boolean) {
                if (msg.isNotEmpty()) Dlg.message(a, "指紋認証", "指紋認証を有効にできませんでした。\n$msg", '!') { then() } else then()
            }
        })
    }

    fun toggleBiometric(a: MainActivity, done: () -> Unit) {
        if (a.vault.hasStoredKey()) {
            Dlg.confirm(a, "指紋認証", "指紋認証を無効にしますか?\nキーストアの鍵を削除し、次回からはマスターパスワードで解除します。") {
                a.vault.clear(); App.prefs(a).edit().putBoolean("bio", false).apply(); done()
            }
        } else enroll(a, done)
    }

    fun changePassword(a: MainActivity, done: () -> Unit) {
        val col = W.vbox(a)
        fun f(label: String): EditText {
            col.addView(W.text(a, label, 14f), W.lp(W.MATCH, W.WRAP, t = 6))
            return W.field(a, password = true).also { col.addView(it, W.lp(W.MATCH, W.WRAP, t = 3)) }
        }
        val cur = f("現在のパスワード:")
        val n1 = f("新しいパスワード:")
        val n2 = f("新しいパスワードの確認入力:")
        ClassicDialog(a, "マスターパスワードの変更", col, listOf(
            ClassicDialog.Btn("OK", true) {
                val have = Session.compositeCopy() ?: return@Btn true
                val chk = Kdbx.compositeKey(cur.text)
                val okCur = chk.contentEquals(have)
                chk.fill(0); have.fill(0)
                when {
                    !okCur -> { Dlg.message(a, "マスターパスワードの変更", "現在のパスワードが正しくありません。", 'x'); false }
                    n1.text.toString() != n2.text.toString() -> { Dlg.message(a, "マスターパスワードの変更", "新しいパスワードが一致しません。", 'x'); false }
                    n1.text.isEmpty() -> { Dlg.message(a, "マスターパスワードの変更", "新しいパスワードを入力してください。", '!'); false }
                    else -> {
                        val nk = Kdbx.compositeKey(n1.text)
                        Session.update { Kdbx.withCredentials(it, nk) }
                        nk.fill(0)
                        Session.flush()
                        val hadBio = a.vault.hasStoredKey()
                        a.vault.clear()
                        Dlg.message(a, "マスターパスワードの変更", "マスターパスワードを変更しました。" +
                            if (hadBio) "\n続けて指紋認証を登録し直します。" else "") { if (hadBio) enroll(a, done) else done() }
                        true
                    }
                }
            },
            ClassicDialog.Btn("キャンセル"))).show()
    }
}
