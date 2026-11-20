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
import io.github.zmlworks.kpexplorer.sec.PasswordStrength
import io.github.zmlworks.kpexplorer.db.FileInfo
import io.github.zmlworks.kpexplorer.ui.StrengthMeter
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
        }, discardLost = protection(a) != FileInfo.Protection.KEYSTORE)   // 0.4.37: that key is the database's only one
    }

    /**
     * Master password → open; then (screen lock available) wrap the key again for fingerprint / PIN unlock.
     * 0.4.37: the typed password's strength verdict is recorded for the database (only the verdict).
     */
    fun unlockWithPassword(a: MainActivity, password: String, onError: (String) -> Unit, onOk: () -> Unit) {
        val k = Kdbx.compositeKey(password)
        val info = PasswordStrength.info(password)
        openStored(a, k, { k.fill(0); onError(it) }, {
            a.store.setInfo(info)
            if (!a.vault.hasStoredKey() && a.vault.canWrap())
                a.vault.wrap(a, k) { err -> k.fill(0); if (!err.isNullOrEmpty()) toast(a, L.t("指紋・画面ロックの登録に失敗: $err", "Could not set up fingerprint / screen lock: $err")) }
            else k.fill(0)
            onOk()
        }, keep = true)
    }

    /**
     * 0.4.37 「暗号化せずに使う」: the database's key is the empty password, so opening needs nothing. 0.4.42: unless
     * 「開くときに指紋認証を求める」 ([noneGate]): first the OS prompt (fingerprint, or the device PIN / pattern /
     * password). With no screen lock left on the device it opens with a note, so nobody is locked out of a file that
     * is not encrypted anyway.
     */
    fun openUnencrypted(a: MainActivity, onError: (String) -> Unit, onOk: () -> Unit) {
        if (!noneGate(a)) { openStored(a, Kdbx.compositeKey(""), onError, onOk); return }
        if (!a.vault.isAvailable()) {
            toast(a, L.t("この端末に指紋・画面ロックがないため、確認なしで開きます。", "This device has no fingerprint or screen lock, so it opens without the check."))
            openStored(a, Kdbx.compositeKey(""), onError, onOk); return
        }
        a.vault.confirm(a, L.t("KeePass Explorer を開く", "Open KeePass Explorer"), L.t("指紋または画面ロックで確認します", "Confirm with your fingerprint or screen lock")) { err ->
            when (err) {
                null -> openStored(a, Kdbx.compositeKey(""), onError, onOk)
                "" -> onError(L.t("確認がキャンセルされました。", "The check was cancelled."))
                else -> onError(err)
            }
        }
    }

    /** 0.4.42: 「開くときに指紋認証を求める」 (a device setting) applies: only to a database that is not encrypted. */
    const val PREF_NONE_GATE = "none_bio_gate"
    fun noneGate(a: MainActivity) = protection(a) == FileInfo.Protection.NONE && App.prefs(a).getBoolean(PREF_NONE_GATE, false)

    /** Turns the check on after one successful prompt (so it is known to work here), or off. [done] gets the new state. */
    fun setNoneGate(a: MainActivity, on: Boolean, done: (Boolean) -> Unit) {
        if (!on) { App.prefs(a).edit().putBoolean(PREF_NONE_GATE, false).apply(); done(false); return }
        if (!a.vault.isAvailable()) {
            Dlg.message(a, L.t("指紋認証", "Fingerprint"), L.t("この端末には指紋・画面ロック (PIN・パターン・パスワード) が設定されていないため使えません。端末の設定で設定してから選んでください。",
                "This device has no fingerprint or screen lock (PIN, pattern, password), so this cannot be used. Set one in the device settings first."), 'i')
            done(false); return
        }
        a.vault.confirm(a, L.t("開くときに指紋認証を求める", "Ask for the fingerprint when opening"), L.t("確認できたら、次から開くたびに求めます", "Once confirmed, it is asked every time you open")) { err ->
            if (err == null) App.prefs(a).edit().putBoolean(PREF_NONE_GATE, true).apply()
            else if (err.isNotEmpty()) Dlg.message(a, L.t("指紋認証", "Fingerprint"), err, 'x')
            done(err == null)
        }
    }

    /** The app database's protection (0.4.37; databases made before are [FileInfo.Protection.PASSWORD]). */
    fun protection(a: MainActivity): FileInfo.Protection = a.store.info().protection

    /** New empty database (root folder without a name = "/"), standard KDBX 4 keyed by [composite]. */
    fun newDatabase(composite: ByteArray, light: Boolean = false): KeePassDatabase =
        Kdbx.create("KeePass Explorer", composite, rootName = "", light = light)

    /** Write the new database and open it (I/O thread), record [info]; [composite] is NOT wiped. */
    private fun create(a: MainActivity, composite: ByteArray, light: Boolean, info: FileInfo, onError: (String) -> Unit, onOk: () -> Unit) {
        val busy = Dlg.busy(a, L.t("セットアップ", "Setup"), L.t("データベースを作成しています", "Creating the database"))
        Session.io.execute {
            val r = runCatching { newDatabase(composite, light).also { a.store.write(Kdbx.encodeVerified(it, keepSalt = true)) } }
            a.runOnUiThread {
                busy.dismiss()
                r.onSuccess { a.store.setInfo(info); opened(a, it); onOk() }
                 .onFailure { onError(L.t("作成できませんでした。\n$it", "Could not create it.\n$it")) }
            }
        }
    }

    /** First run, master password: create the database with [password]; then offer fingerprint / screen-lock unlock (wrap). */
    fun firstRun(a: MainActivity, password: String, onError: (String) -> Unit, onOk: () -> Unit) {
        val composite = Kdbx.compositeKey(password)
        val info = PasswordStrength.info(password)
        create(a, composite, false, info, { composite.fill(0); onError(it) }) {
            if (a.vault.canWrap())
                a.vault.wrap(a, composite) { err -> composite.fill(0); if (!err.isNullOrEmpty()) toast(a, L.t("指紋・画面ロックの登録に失敗: $err", "Could not set up fingerprint / screen lock: $err")); onOk() }
            else { composite.fill(0); onOk() }
        }
    }

    /**
     * 0.4.37 first run 「暗号化せずに使う」: the same KDBX code path, keyed by the empty password (what KeePass calls an
     * empty master password), with light key derivation — there is nothing to protect with stretching. Anyone who can
     * read the file can open it; the app labels it as not encrypted.
     */
    fun firstRunUnencrypted(a: MainActivity, onError: (String) -> Unit, onOk: () -> Unit) {
        val composite = Kdbx.compositeKey("")
        create(a, composite, true, FileInfo(FileInfo.Protection.NONE, FileInfo.Strength.UNKNOWN), { composite.fill(0); onError(it) }) { composite.fill(0); onOk() }
    }

    /**
     * 0.4.37 first run 「OSのkeystoreを利用する」: 32 random bytes are the database key. They are wrapped by a new
     * Android Keystore key that needs the screen lock (one OS prompt) BEFORE the database is written, so a cancelled
     * or failed prompt leaves nothing behind. The key exists nowhere else: removing the screen lock, a factory reset
     * or another device makes the database impossible to open.
     */
    fun firstRunKeystore(a: MainActivity, onError: (String) -> Unit, onOk: () -> Unit) {
        if (!a.vault.isAvailable()) { onError(KEYSTORE_NEEDS_LOCK()); return }
        val composite = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        a.vault.wrap(a, composite) { err ->
            if (err != null) {
                composite.fill(0)
                onError(if (err.isEmpty()) L.t("画面ロックの確認がキャンセルされました。", "The screen lock check was cancelled.") else L.t("キーストアに鍵を保存できませんでした: $err", "Could not store the key in the keystore: $err"))
                return@wrap
            }
            create(a, composite, true, FileInfo(FileInfo.Protection.KEYSTORE, FileInfo.Strength.UNKNOWN), { composite.fill(0); a.vault.clear(); onError(it) }) { composite.fill(0); onOk() }
        }
    }

    @Suppress("FunctionName")
    fun KEYSTORE_NEEDS_LOCK() = L.t("この端末には画面ロック (PIN・パターン・パスワード、または指紋) が設定されていないため、この方式は使えません。端末の設定で画面ロックを設定してから選んでください。",
        "This device has no screen lock (PIN, pattern, password or fingerprint), so this option cannot be used. Set a screen lock in the device settings first.")

    /**
     * Menu: new master password (twice). The file is re-encrypted (new Argon2 salt) and the wrapped key replaced.
     * 0.4.33: then the internal backups that open with the current password are encrypted again for the new one
     * ([io.github.zmlworks.kpexplorer.db.Store.rekeyBackups]; others are left as they are), unless the user ticks
     * 「内部バックアップのパスワードを書き換えない」 (after a confirmation). Backups are only touched after the database
     * itself was saved with the new password.
     */
    fun changeMasterPassword(a: MainActivity): ClassicDialog {
        val mode = protection(a)
        val setting = mode != FileInfo.Protection.PASSWORD   // 0.4.37: no password yet (not encrypted / OS keystore)
        val title = if (setting) L.t("マスターパスワードの設定", "Set Master Password") else L.t("マスターパスワードの変更", "Change Master Password")
        val col = W.vbox(a)
        if (setting) col.addView(W.text(a, (if (mode == FileInfo.Protection.NONE) L.t("今は暗号化されていません。", "The database is not encrypted now. ")
                else L.t("今はOSのkeystoreの鍵だけで保護されています。", "It is protected only by the OS keystore key now. ")) +
                L.t("マスターパスワードを設定すると、マスターパスワードで暗号化した方式に切り替わります (指紋・画面ロックでも開けます)。",
                    "Setting a master password switches it to master-password encryption (it also opens with the fingerprint / screen lock)."), 14f)
                .apply { setLineSpacing(0f, 1.1f) }, W.lp(W.MATCH, W.WRAP, b = 8))
        col.addView(W.text(a, if (setting) L.t("内部バックアップも新しいパスワードに書き換えます", "Internal backups are rewritten for the new password too")
            else L.t("現在のパスワードで開けられる内部バックアップも新しいパスワードに書き換えます",
            "Internal backups that open with the current password are rewritten for the new password too"), 14f).apply { setLineSpacing(0f, 1.1f) },
            W.lp(W.MATCH, W.WRAP, b = 8))
        val (p1, p2) = passwordPair(a, col, L.t("新しいパスワード", "New password"), L.t("新しいパスワードの確認", "Confirm new password"), meter = true)
        // 0.4.38: 「内部バックアップのパスワードを書き換える」, ticked by default (up to 0.4.37 the opposite 「…書き換えない」);
        // unticking it asks first
        val rewrite = W.check(a, L.t("内部バックアップのパスワードを書き換える", "Change the password of internal backups too"), true)
        rewrite.tag = "rewrite-backups"
        var sure = false
        rewrite.setOnCheckedChangeListener { box, on ->
            if (on || sure) { sure = false; return@setOnCheckedChangeListener }
            box.isChecked = true   // unticked only after the warning is accepted
            Dlg.confirm(a, title, L.t("バックアップを開くのに古いパスワードの入力が必要になります", "Opening the backups will need the old password"), '!',
                yes = "OK", no = L.t("キャンセル", "Cancel")) { sure = true; box.isChecked = false }
        }
        if (!setting) col.addView(rewrite, W.lp(W.MATCH, W.WRAP, t = 2))   // (backups of a key nobody can type: always rewritten)
        return ClassicDialog(a, title, col, listOf(
            ClassicDialog.Btn(if (setting) L.t("設定", "Set") else L.t("変更", "Change"), true) {
                val pw = checkPair(a, title, p1, p2) ?: return@Btn false
                confirmWeak(a, title, pw) { applyNewPassword(a, title, pw, rekey = setting || rewrite.isChecked) }
                true
            },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel")))).also { it.show() }
    }

    /** A weak new password is allowed, after saying what it means (0.4.37). [starting] = the first-run wording. */
    fun confirmWeak(a: MainActivity, title: String, pw: String, starting: Boolean = false, go: () -> Unit) {
        if (PasswordStrength.judge(pw).strong) { go(); return }
        Dlg.confirm(a, title, L.t("このパスワードは弱いと判定されました。推測されやすく、ファイルを盗まれると総当たりで開かれるおそれがあります。また、認証なしの書き出し (起動画面の「外部に書き出す」) はできません。\n\n", "This password was judged weak: it is easy to guess, and a stolen file could be opened by brute force. Export without unlocking (launch screen) will not be possible either.\n\n") +
            if (starting) L.t("このパスワードで始めますか?", "Start with this password?") else L.t("このパスワードで続けますか?", "Continue with this password?"), '!',
            yes = if (starting) L.t("はじめる", "Start") else L.t("続ける", "Continue"), no = L.t("戻る", "Back")) { go() }
    }

    private fun applyNewPassword(a: MainActivity, title: String, pw: String, rekey: Boolean) {
        val old = Session.compositeCopy() ?: return
        val k = Kdbx.compositeKey(pw)
        val before = a.store.info()
        val info = PasswordStrength.info(pw)
        Session.update { Kdbx.withHeader(Kdbx.withCredentials(it, k), light = false) }
        // recorded before the save: if the app dies in between, the unlock screen still offers both ways in
        a.store.setInfo(info)
        val busy = Dlg.busy(a, title, L.t("暗号化し直しています", "Re-encrypting"))
        Session.afterSave({ if (rekey) a.store.rekeyBackups(old, k, info) else null }) { res ->
            old.fill(0)
            busy.dismiss()
            res.onSuccess { r ->
                Dlg.message(a, title, changedMessage(r), 'i')
                if (a.vault.canWrap())
                    a.vault.wrap(a, k) { err -> k.fill(0); if (!err.isNullOrEmpty()) toast(a, L.t("指紋・画面ロックの登録に失敗: $err", "Could not set up fingerprint / screen lock: $err")) }
                else { a.vault.clear(); k.fill(0) }
            }.onFailure { a.store.setInfo(before); k.fill(0); Dlg.message(a, title, L.t("保存できませんでした。\n${it.message ?: it}", "Could not save.\n${it.message ?: it}"), 'x') }
        }
    }

    /** The report after a master password change: how many internal backups were rewritten / left alone. */
    fun changedMessage(r: io.github.zmlworks.kpexplorer.db.Store.Rekey?): String {
        val head = L.t("マスターパスワードを変更しました。", "The master password was changed.")
        if (r == null) return head + "\n" + L.t("内部バックアップはそのままです (開くには古いパスワードが必要です)。", "Internal backups were left as they were (they need the old password).")
        return head + "\n" + rewrittenMessage(r)
    }

    /** How many internal backups [Store.rekeyBackups] rewrote / left alone (0.4.38: also after an import). */
    fun rewrittenMessage(r: io.github.zmlworks.kpexplorer.db.Store.Rekey): String {
        val sb = StringBuilder()
        sb.append(L.t("内部バックアップ: ${r.rewritten.size} 件を新しいパスワードに書き換えました。", "Internal backups: ${r.rewritten.size} rewritten for the new password."))
        if (r.skipped.isNotEmpty()) sb.append("\n").append(L.t("${r.skipped.size} 件は現在のパスワードで開けないため、そのままです。", "${r.skipped.size} do not open with the current password and were left as they were."))
        if (r.failed.isNotEmpty()) sb.append("\n").append(L.t("${r.failed.size} 件は書き換えられませんでした (元のまま): ", "${r.failed.size} could not be rewritten (unchanged): ") + r.failed.joinToString(", "))
        return sb.toString()
    }

    private fun toast(a: MainActivity, s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()

    /** Two password fields + "show" check box. */
    /** 0.4.37 [meter]: a live [StrengthMeter] under them (new master passwords, export passwords). */
    fun passwordPair(a: MainActivity, col: android.widget.LinearLayout, first: String = L.t("パスワード:", "Password:"), second: String = L.t("パスワードの確認入力:", "Confirm password:"), meter: Boolean = false): Pair<EditText, EditText> {
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
        if (meter) col.addView(StrengthMeter(a, p1), W.lp(W.MATCH, W.WRAP, t = 6))
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
            // 0.4.36: both buttons one fixed width (up to 0.4.35 the window's full width); 0.4.38: centred, notes too
            col.addView(W.button(a, label) { d.dismiss(); f() }.apply { this.tag = tag },
                W.lp(W.dp(SAVE_BUTTON_DP), W.WRAP, t = if (col.childCount == 0) 0 else 12).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
            col.addView(W.text(a, note, 14f).apply { setLineSpacing(0f, 1.1f); gravity = android.view.Gravity.CENTER_HORIZONTAL }, W.lp(W.MATCH, W.WRAP, t = 4))
        }
        choice(L.t("アプリ内に保存", "Save in app"), "save-internal",
            L.t("$name として内部にバックアップします", "Backs up inside the app as $name")) { confirmBackup(a, onInternal) }   // 0.4.36: asks first
        choice(L.t("外部に書き出す", "Export"), "save-export",
            L.t("保存先を選び、パスワード付きKDBXファイルを書き出します", "Choose a location and write a password-protected KDBX file")) {
            askExportPassword(a, "keepass-explorer-${LocalDate.now()}.kdbx") { Session.db }
        }
        d = ClassicDialog(a, L.t("保存", "Save"), col, listOf(ClassicDialog.Btn(L.t("キャンセル", "Cancel"))))
        d.show()
        return d
    }

    /** Width of the two buttons of the Save window. */
    const val SAVE_BUTTON_DP = 168

    /**
     * 0.4.33: menu 「現在のバックアップを作成」 and the backup list's 「今すぐ作成」 ask first, naming the file;
     * 0.4.36: so does アプリ内に保存 in the Save window.
     */
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
        val (p1, p2) = passwordPair(a, pwBox, meter = true)
        col.addView(pwBox, W.lp(W.MATCH, W.WRAP, l = 12))
        rg.setOnCheckedChangeListener { _, id -> pwBox.visibility = if (id == other.id) View.VISIBLE else View.GONE }
        same.isChecked = true; pwBox.visibility = View.GONE
        // 0.4.37: a database without a master password (not encrypted / OS keystore) has no "same password" to keep —
        // the copy must get one, or it would leave the device unencrypted / unopenable
        if (protection(a) != FileInfo.Protection.PASSWORD) {
            same.visibility = View.GONE; other.isChecked = true; pwBox.visibility = View.VISIBLE
            col.addView(W.text(a, L.t("このデータベースにはマスターパスワードがないため、書き出すファイルのパスワードを決めてください。", "This database has no master password, so choose a password for the exported file."), 13f)
                .apply { tag = "export-needs-password"; setLineSpacing(0f, 1.1f) }, 1, W.lp(W.MATCH, W.WRAP, t = 6))
        }
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

    // ---------------------------------------------------------------- 0.4.37: export from the launch screen

    /**
     * セキュリティの設定 「認証なしで外部に書き出す際の強度」 (0.4.38): the estimated guesses (power of ten) a file's
     * master password needs before the launch screen may write it out; 0 = 無効 (default). Replaces 0.4.37's on / off
     * 「起動画面からの書き出し」 (on → 強い, off → 無効), migrated on first read.
     */
    const val PREF_EXPORT_MIN = "export_min_log10"
    /** 0.4.37's on / off setting (read once for the migration, then removed). */
    const val PREF_LAUNCH_EXPORT_OLD = "launch_export"
    /**
     * 0.4.40: more levels, the strength meter's words with their powers of ten — 無効 / とても強い 10^14 / 強い 10^12 /
     * もう少し 10^10 / やや弱い 10^8 / 弱い 10^5 / すべて ([EXPORT_ANY]: every file with a master password, also one not
     * judged yet) — and any other power of ten typed in (「数値で指定」, [EXPORT_MIN_N]..[EXPORT_MAX_N]).
     */
    const val EXPORT_ANY = FileInfo.ANY
    const val EXPORT_MIN_N = 1
    const val EXPORT_MAX_N = 40
    val EXPORT_LEVELS = listOf(0, PasswordStrength.VERY_STRONG_LOG10.toInt(), PasswordStrength.STRONG_LOG10.toInt(), 10, 8, 5, EXPORT_ANY)

    /** The strength words of a power of ten (as the strength meter has them). */
    fun strengthWord(n: Int) = when {
        n >= PasswordStrength.VERY_STRONG_LOG10 -> L.t("とても強い", "Very strong")
        n >= PasswordStrength.STRONG_LOG10 -> L.t("強い", "Strong")
        n >= 10 -> L.t("もう少し", "Almost")
        n >= 8 -> L.t("やや弱い", "Fair")
        n >= 5 -> L.t("弱い", "Weak")
        else -> L.t("とても弱い", "Very weak")
    }

    fun exportLevelLabel(n: Int) = when {
        n == 0 -> L.t("無効", "Off")
        n == EXPORT_ANY -> L.t("すべて (強度を問わない)", "All (any strength)")
        n in EXPORT_LEVELS -> L.t("${strengthWord(n)} (10^${n}以上)", "${strengthWord(n)} (10^$n or more)")
        else -> L.t("10^${n}以上 (${strengthWord(n)})", "10^$n or more (${strengthWord(n)})")
    }

    /**
     * How long 10^[n] guesses take on average (half of them) at about 10^4 Argon2id guesses per second per GPU (see
     * PasswordStrength): "GPU 1 台で約 3 年、100 台で約 12 日".
     */
    fun crackTime(n: Int): String {
        fun span(sec: Double): String = when {
            sec < 60 -> L.t("1 分未満", "under a minute")
            sec < 3600 -> L.t("約 ${(sec / 60).toInt()} 分", "about ${(sec / 60).toInt()} minutes")
            sec < 86400 -> L.t("約 ${(sec / 3600).toInt()} 時間", "about ${(sec / 3600).toInt()} hours")
            sec < 86400 * 365.0 -> L.t("約 ${(sec / 86400).toInt()} 日", "about ${(sec / 86400).toInt()} days")
            sec < 86400 * 365.0 * 1e6 -> L.t("約 ${"%,d".format((sec / 86400 / 365).toLong())} 年", "about ${"%,d".format((sec / 86400 / 365).toLong())} years")
            else -> L.t("100 万年以上", "over a million years")
        }
        val one = Math.pow(10.0, n.toDouble()) / 2 / 1e4
        return L.t("GPU 1 台で${span(one)}、100 台で${span(one / 100)}", "${span(one)} on one GPU, ${span(one / 100)} on 100")
    }

    fun exportMinLog10(a: android.content.Context): Int {
        val p = App.prefs(a)
        if (!p.contains(PREF_EXPORT_MIN) && p.contains(PREF_LAUNCH_EXPORT_OLD)) {
            val v = if (p.getBoolean(PREF_LAUNCH_EXPORT_OLD, false)) PasswordStrength.STRONG_LOG10.toInt() else 0
            p.edit().putInt(PREF_EXPORT_MIN, v).remove(PREF_LAUNCH_EXPORT_OLD).apply()
            return v
        }
        return p.getInt(PREF_EXPORT_MIN, 0)
    }

    fun setExportMinLog10(a: android.content.Context, n: Int) { App.prefs(a).edit().putInt(PREF_EXPORT_MIN, n).remove(PREF_LAUNCH_EXPORT_OLD).apply() }

    fun launchExportEnabled(a: MainActivity) = exportMinLog10(a) != 0

    /** A file the launch screen can offer: the app database or an internal backup, with its record. */
    class ExportItem(val file: File, val label: String, val info: FileInfo, val main: Boolean, val minLog10: Int) {
        val exportable get() = info.exportableAt(minLog10)
        /** File name offered in the system's save screen. */
        val outName get() = if (main) "keepass-explorer-${LocalDate.now()}.kdbx" else "keepass-explorer-${file.nameWithoutExtension}.kdbx"
    }

    /** The database first, then the internal backups (newest first). */
    fun launchExportItems(a: MainActivity): List<ExportItem> {
        val out = ArrayList<ExportItem>()
        val min = exportMinLog10(a)
        if (a.store.exists()) out.add(ExportItem(a.store.dbFile, L.t("現在のデータベース", "Current database"), a.store.info(), true, min))
        for (f in a.store.backups()) out.add(ExportItem(f, f.name, a.store.info(f), false, min))
        return out
    }

    /** Why a file cannot be written out from the launch screen (null = it can). */
    fun notExportableReason(i: FileInfo, minLog10: Int): String? = when {
        minLog10 == 0 -> L.t("認証なしの書き出しは無効です (セキュリティの設定)", "Export without unlocking is off (Security settings)")
        i.protection == FileInfo.Protection.NONE -> L.t("暗号化されていないため書き出せません", "Not encrypted, so it cannot be exported")
        i.protection == FileInfo.Protection.KEYSTORE -> L.t("OSのkeystoreの鍵で保護されているため書き出せません (他の端末では開けません)", "Protected by the OS keystore key, so it cannot be exported (it would not open anywhere else)")
        i.strength == FileInfo.Strength.UNKNOWN -> L.t("パスワードの強度が未判定のため書き出せません (一度マスターパスワードを入力して開くと判定されます)", "The password strength is not known yet, so it cannot be exported (it is judged when you open it by typing the master password)")
        !i.exportableAt(minLog10) -> L.t("マスターパスワードの強度が設定 (${exportLevelLabel(minLog10)}) に届かないため書き出せません", "The master password is weaker than the setting (${exportLevelLabel(minLog10)}), so it cannot be exported")
        else -> null
    }

    /**
     * Launch screen (nothing unlocked) 外部に書き出す: pick the database or an internal backup; only files with a strong
     * master password can be picked, the others are greyed with the reason. The chosen file is copied as it is —
     * still encrypted, opened with its own master password in KeePass / KeePassXC etc. — to a place chosen in the
     * system's "save" screen, after checking the KDBX signature (and finishing pending saves).
     */
    fun exportFromLaunch(a: MainActivity): ClassicDialog {
        val title = L.t("外部に書き出す", "Export")
        val items = launchExportItems(a)
        val col = W.vbox(a)
        col.addView(W.text(a, L.t("書き出すファイルを選んでください。暗号化したまま書き出し、そのファイルのマスターパスワードで KeePass や KeePassXC などで開けます。マスターパスワードの強度が「${exportLevelLabel(exportMinLog10(a))}」のファイルだけ選べます (セキュリティの設定)。",
            "Choose the file to export. It is written still encrypted and opens with its own master password in KeePass, KeePassXC etc. Only files whose master password reaches \"${exportLevelLabel(exportMinLog10(a))}\" can be chosen (Security settings)."), 14f)
            .apply { setLineSpacing(0f, 1.1f) }, W.lp(W.MATCH, W.WRAP, b = 6))
        val radios = ArrayList<android.widget.RadioButton>()
        var picked: ExportItem? = null
        val fmt = L.dateTime(seconds = false)
        for ((n, it) in items.withIndex()) {
            val r = W.radio(a, it.label).apply {
                tag = "export-$n"; isEnabled = it.exportable
                if (!it.exportable) setTextColor(GREYED)
            }
            radios.add(r)
            r.setOnClickListener { _ -> if (!r.isEnabled) { r.isChecked = false; return@setOnClickListener }; radios.forEach { o -> o.isChecked = o === r }; picked = it }
            col.addView(r, W.lp(W.MATCH, W.WRAP, t = if (n == 0) 0 else 2))
            val time = fmt.format(java.time.Instant.ofEpochMilli(it.file.lastModified()))
            val note = notExportableReason(it.info, it.minLog10)?.let { why -> "$time — $why" } ?: time
            col.addView(W.text(a, note, 12f, color = if (it.exportable) W.TEXT else GREYED_NOTE).apply { tag = "export-note-$n"; setPadding(W.dp(34), 0, 0, 0) }, W.lp(W.MATCH, W.WRAP))
        }
        items.indexOfFirst { it.exportable }.takeIf { it >= 0 }?.let { radios[it].isChecked = true; picked = items[it] }
        if (picked == null) col.addView(W.text(a, L.t("書き出せるファイルがありません。アプリを開いてマスターパスワードをより強いものに変更すると、書き出せるようになります。",
            "There is no file that can be exported. Open the app and change the master password to a stronger one to make it exportable."), 14f, color = 0xFFB00000.toInt())
            .apply { tag = "export-none"; setLineSpacing(0f, 1.1f) }, W.lp(W.MATCH, W.WRAP, t = 8))
        val d = ClassicDialog(a, title, col, listOf(
            ClassicDialog.Btn(L.t("保存先を選ぶ", "Choose location"), true) { picked?.let { writeAsIs(a, title, it) }; picked != null },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel"))), scroll = true)
        d.buttonViews.first().isEnabled = picked != null
        if (picked == null) d.buttonViews.first().setTextColor(GREYED)
        d.show()
        return d
    }

    private const val GREYED = 0xFF808080.toInt()
    private const val GREYED_NOTE = 0xFF6A6A6A.toInt()

    /** Copy [item]'s file byte for byte to a document the user creates. */
    private fun writeAsIs(a: MainActivity, title: String, item: ExportItem) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, item.outName)
        }
        a.startForResult(i) { rc, data ->
            val uri: Uri = data?.data ?: return@startForResult
            if (rc != Activity.RESULT_OK) return@startForResult
            val busy = Dlg.busy(a, title, L.t("書き出しています", "Exporting"))
            Session.afterSave({
                // checked again: the record could have changed since the list was shown
                check((if (item.main) a.store.info() else a.store.info(item.file)).exportableAt(exportMinLog10(a))) { L.t("このファイルは書き出せません", "This file cannot be exported") }
                val bytes = item.file.readBytes()
                check(Kdbx.isKdbx(bytes)) { L.t("データベースのファイルが壊れています", "The database file is damaged") }
                a.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            }) { r ->
                busy.dismiss()
                r.onSuccess { Toast.makeText(a, L.t("書き出しました", "Exported"), Toast.LENGTH_SHORT).show() }
                 .onFailure { Dlg.message(a, title, L.t("書き出せませんでした。\n${it.message ?: it}", "Could not export.\n${it.message ?: it}"), 'x') }
            }
        }
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
