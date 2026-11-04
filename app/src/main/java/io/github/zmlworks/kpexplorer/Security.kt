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

    /** After a master key change: enroll the new key; if that is cancelled, drop the stale old one. */
    private fun reEnroll(a: MainActivity, done: () -> Unit) {
        val c = Session.compositeCopy() ?: run { a.vault.clear(); return done() }
        a.vault.enroll(a, c, object : Vault.Result {
            override fun ok(composite: ByteArray?) {
                Toast.makeText(a, "指紋認証を登録し直しました", Toast.LENGTH_SHORT).show(); done()
            }
            override fun fail(msg: String, invalidated: Boolean) {
                a.vault.clear(); App.prefs(a).edit().putBoolean("bio", false).apply()
                Dlg.message(a, "指紋認証", "指紋認証を登録し直せなかったため、無効にしました。" +
                    (if (msg.isNotEmpty()) "\n$msg" else ""), '!') { done() }
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
                        val old = Session.compositeCopy()
                        Session.update { Kdbx.withCredentials(it, nk) }
                        nk.fill(0)
                        val busy = Dlg.busy(a, "マスターパスワードの変更", "保存しています…")
                        // Report success only once the file with the new key is written and verified.
                        Session.afterSave({ Unit }) { r ->
                            busy.dismiss()
                            r.onSuccess {
                                old?.fill(0)
                                val hadPin = a.pins.hasPin()
                                a.pins.clear()   // the PIN blob wraps the old master key
                                if (hadPin) Toast.makeText(a, "PIN は解除されました。メニューから設定し直してください。", Toast.LENGTH_LONG).show()
                                val hadBio = a.vault.hasStoredKey()
                                Dlg.message(a, "マスターパスワードの変更", "マスターパスワードを変更しました。" +
                                    if (hadBio) "\n続けて指紋認証を登録し直します。" else "") {
                                    // the stored fingerprint key still wraps the old master key: replace it
                                    // (enroll keeps the old entry until the new one is stored), else drop it
                                    if (hadBio) reEnroll(a, done) else done()
                                }
                            }.onFailure { t ->
                                // the file still has the old key: keep it in memory too
                                if (old != null) { Session.update { Kdbx.withCredentials(it, old) }; old.fill(0) }
                                Dlg.message(a, "マスターパスワードの変更",
                                    "保存できなかったため、マスターパスワードは変更されていません。\n${t.message ?: t}", 'x') { done() }
                            }
                        }
                        true
                    }
                }
            },
            ClassicDialog.Btn("キャンセル"))).show()
    }

    /** Ask for a new PIN (twice) and store the composite key under it. [firstRun] offers "あとで". */
    fun setupPin(a: MainActivity, firstRun: Boolean = false, done: () -> Unit) {
        val col = W.vbox(a)
        col.addView(W.text(a, (if (firstRun) "ロック解除用の PIN を設定します (指紋認証が使えないときにも使えます)。\n" else "") +
            "4〜12 桁の数字。${io.github.zmlworks.kpexplorer.sec.PinVault.MAX_ATTEMPTS} 回間違えると PIN は無効になり、マスターパスワードが必要になります。", 14f)
            .apply { setLineSpacing(0f, 1.1f) })
        fun f(label: String): EditText {
            col.addView(W.text(a, label, 14f), W.lp(W.MATCH, W.WRAP, t = 8))
            return W.field(a, password = true).also {
                it.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                col.addView(it, W.lp(W.MATCH, W.WRAP, t = 3))
            }
        }
        val p1 = f("PIN:"); val p2 = f("PIN の確認入力:")
        ClassicDialog(a, "PIN の設定", col, listOf(
            ClassicDialog.Btn("OK", true) {
                val pin = p1.text.toString()
                when {
                    !io.github.zmlworks.kpexplorer.sec.PinVault.valid(pin) -> { Dlg.message(a, "PIN", "PIN は 4〜12 桁の数字で入力してください。", '!'); false }
                    pin != p2.text.toString() -> { Dlg.message(a, "PIN", "確認用の PIN が一致しません。", 'x'); false }
                    else -> {
                        val c = Session.compositeCopy()
                        if (c == null) { done(); return@Btn true }
                        val busy = Dlg.busy(a, "PIN の設定", "PIN を登録しています…")
                        Session.io.execute {
                            val r = runCatching { a.pins.setPin(pin, c) }
                            c.fill(0)
                            a.runOnUiThread {
                                busy.dismiss()
                                r.onSuccess { Toast.makeText(a, "PIN を設定しました", Toast.LENGTH_SHORT).show(); done() }
                                 .onFailure { Dlg.message(a, "PIN", "PIN を設定できませんでした。\n$it", 'x') { done() } }
                            }
                        }
                        true
                    }
                }
            },
            ClassicDialog.Btn(if (firstRun) "あとで" else "キャンセル") { done(); true })).show()
    }
}
