package io.github.zmlworks.kpexplorer

import android.widget.LinearLayout
import io.github.zmlworks.kpexplorer.db.FileInfo
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.W

/**
 * 0.4.38: セキュリティの設定 (main menu), the user's design: how the current database is protected and how strong its
 * master password is, the caution that follows from that, and 「認証なしで外部に書き出す際の強度」 (a drop-down:
 * 無効 / 強い 10^12 / とても強い 10^14; replaces 0.4.37's on / off 起動画面からの書き出し; 0.4.40: もう少し 10^10, やや弱い
 * 10^8, 弱い 10^5, すべて, and any 10^n typed in, a warning before anything below 強い). The settings that used to sit in
 * the menu live here too: screen protection, auto-lock, the internal backup list. Changes apply at once.
 */
object SecuritySettings {
    const val TITLE_JA = "セキュリティの設定"

    fun show(a: MainActivity): ClassicDialog {
        val body = W.vbox(a)
        val d = ClassicDialog(a, L.t(TITLE_JA, "Security Settings"), body, listOf(ClassicDialog.Btn(L.t("閉じる", "Close"), true) { true }), scroll = true)
        fill(a, body, d)
        d.show(); Dlg.last = d
        return d
    }

    /** Protection and strength of a record, in words ("マスターパスワード / 強い (推定 10^19 回)"). */
    fun describe(i: FileInfo): Pair<String, String> {
        val mode = when (i.protection) {
            FileInfo.Protection.PASSWORD -> L.t("マスターパスワード", "Master password")
            FileInfo.Protection.NONE -> L.t("暗号化なし", "Not encrypted")
            FileInfo.Protection.KEYSTORE -> L.t("OSのkeystoreの鍵", "OS keystore key")
        }
        val n = i.guessesLog10
        val strength = when {
            i.protection != FileInfo.Protection.PASSWORD -> L.t("— (パスワードなし)", "— (no password)")
            i.strength == FileInfo.Strength.UNKNOWN -> L.t("未判定", "Not judged yet")
            n != null && i.log10 != null -> Security.strengthWord(n)   // 0.4.40: the meter's words (やや弱い, もう少し ...)
            n != null && n >= io.github.zmlworks.kpexplorer.sec.PasswordStrength.VERY_STRONG_LOG10 -> L.t("とても強い", "Very strong")
            i.strength == FileInfo.Strength.STRONG -> L.t("強い", "Strong")
            else -> L.t("弱い", "Weak")
        } + if (n != null && i.log10 != null) L.t(" (推測に約 10^$n 回)", " (about 10^$n guesses)") else ""
        return mode to strength
    }

    /** The caution that follows from the protection and strength. */
    fun caution(i: FileInfo): String = when {
        i.protection == FileInfo.Protection.NONE -> L.t("暗号化されていません。守っているのは Android のアプリ専用の保存領域だけで、端末の root 化や解析ツール、OS の乗っ取りがあると、すべての内容が読まれます。メニューの「マスターパスワードの設定」で暗号化できます。",
            "Not encrypted. Only Android's app-private storage protects it: a rooted device, forensic tools or a compromised OS expose everything. Set a master password (menu) to encrypt it.")
        i.protection == FileInfo.Protection.KEYSTORE -> L.t("鍵は OS のキーストアにしかありません。画面ロックを解除・無効にする、端末を初期化する、機種を変えると鍵が永久に消え、データベースと内部バックアップは二度と開けなくなります。マスターパスワードを設定すると、この心配はなくなります。",
            "The key exists only in the OS keystore. Removing or disabling the screen lock, resetting the device or changing devices destroys it for good, and the database and its internal backups can never be opened again. Setting a master password removes this risk.")
        i.strength == FileInfo.Strength.UNKNOWN -> L.t("強度はまだ判定されていません。一度マスターパスワードを入力して開くと判定されます。",
            "The strength is not known yet: it is judged when you open the database by typing the master password.")
        i.strength == FileInfo.Strength.WEAK -> L.t("マスターパスワードが弱いため、ファイルを持ち出されると総当たりで開かれるおそれがあります。長く、いろいろな文字を混ぜたパスワードに変えてください。",
            "The master password is weak: a copied file could be opened by brute force. Change it to a longer password mixing kinds of characters.")
        else -> L.t("マスターパスワードを忘れると開けなくなります。控えておいてください。", "If you forget the master password the data cannot be opened, so write it down.")
    }

    private fun heading(a: MainActivity, body: LinearLayout, s: String) =
        body.addView(W.text(a, s, 15f, true), W.lp(W.MATCH, W.WRAP, t = if (body.childCount == 0) 0 else 14))

    private fun note(a: MainActivity, body: LinearLayout, s: String, tag: String? = null) =
        body.addView(W.text(a, s, 13f, color = W.SHADOW).apply { this.tag = tag; setLineSpacing(0f, 1.1f) }, W.lp(W.MATCH, W.WRAP, t = 2))

    fun fill(a: MainActivity, body: LinearLayout, d: ClassicDialog) {
        body.removeAllViews()
        val info = a.store.info()
        val (mode, strength) = describe(info)
        heading(a, body, L.t("現在のデータベース", "Current database"))
        body.addView(W.text(a, L.t("保護: ", "Protection: ") + mode, 14f).apply { tag = "sec-mode" }, W.lp(W.MATCH, W.WRAP, t = 4))
        body.addView(W.text(a, L.t("強度: ", "Strength: ") + strength, 14f).apply { tag = "sec-strength" }, W.lp(W.MATCH, W.WRAP, t = 2))
        val warn = info.protection != FileInfo.Protection.PASSWORD || info.strength != FileInfo.Strength.STRONG
        val row = W.hbox(a).apply { gravity = android.view.Gravity.TOP }
        row.addView(W.icon(a, io.github.zmlworks.kpexplorer.ui.Px.msgIcon(if (warn) '!' else 'i')), W.lp(W.WRAP, W.WRAP, r = 10, t = 2))
        row.addView(W.text(a, caution(info), 14f).apply { tag = "sec-caution"; setLineSpacing(0f, 1.1f) }, W.lp(0, W.WRAP, 1f))
        body.addView(row, W.lp(W.MATCH, W.WRAP, t = 8))

        heading(a, body, L.t("認証なしで外部に書き出す際の強度", "Strength needed to export without unlocking"))
        val cur = Security.exportMinLog10(a)
        val combo = W.combo(a, Security.exportLevelLabel(cur)) { anchor ->
            // 0.4.40: finer levels, 「すべて」, and any power of ten
            val levels = Security.EXPORT_LEVELS.let { l -> if (cur in l) l else (l + cur).sortedWith(compareBy<Int> { it != 0 }.thenByDescending { if (it == Security.EXPORT_ANY) Int.MIN_VALUE else it }) }
            Menus.dropDown(anchor, levels.map { n ->
                Menus.Item(Security.exportLevelLabel(n), checked = n == Security.exportMinLog10(a)) { setLevel(a, n) { fill(a, body, d) } }
            } + listOf(Menus.sep(), Menus.Item(L.t("数値で指定 (10^n)", "Enter a power of ten (10^n)")) { askLevel(a) { fill(a, body, d) } }), minWidthDp = 260)
        }
        combo.box.tag = "sec-export-level"
        body.addView(combo.box, W.lp(W.MATCH, W.WRAP, t = 6))
        note(a, body, when {
            cur == 0 -> L.t("起動画面の「外部に書き出す」は使えません。", "The launch screen's Export cannot be used.")
            else -> (if (cur == Security.EXPORT_ANY)
                L.t("マスターパスワードのあるデータベースと内部バックアップを、強度を問わず (未判定でも)、ロックを解除しないで起動画面から暗号化したまま書き出せます (内部バックアップの一覧では緑の文字)。",
                    "The database and internal backups with a master password can be exported, still encrypted, from the launch screen without unlocking, whatever its strength (also not judged yet) (green in the internal backup list).")
                else L.t("マスターパスワードの推定が 10^$cur 回以上のデータベースと内部バックアップを、ロックを解除しないで起動画面から暗号化したまま書き出せます (内部バックアップの一覧では緑の文字)。10^$cur 回の推測は、平均で${Security.crackTime(cur)}かかる見積もりです。",
                    "The database and internal backups whose master password estimate reaches 10^$cur guesses can be exported, still encrypted, from the launch screen without unlocking (green in the internal backup list). 10^$cur guesses take, on average, ${Security.crackTime(cur)}.")) +
                L.t("端末を手にした人なら誰でも暗号化されたファイルを持ち出せる点に注意してください。", " Note that anyone holding the device could then take the encrypted file away.")
        }, "sec-export-note")
        note(a, body, if (info.exportableAt(cur)) L.t("現在のデータベース: 起動画面から書き出せます", "Current database: can be exported from the launch screen")
            else L.t("現在のデータベース: 起動画面からは書き出せません", "Current database: cannot be exported from the launch screen"), "sec-export-current")

        heading(a, body, L.t("画面とロック", "Screen and lock"))
        val p = App.prefs(a)
        val secure = W.check(a, a.getString(R.string.menu_secure_screen), p.getBoolean("secure", true)).apply {
            tag = "sec-secure"
            setOnClickListener { p.edit().putBoolean("secure", isChecked).apply(); a.applySecure() }
        }
        body.addView(secure, W.lp(W.MATCH, W.WRAP, t = 4))
        val lockRow = W.hbox(a).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        lockRow.addView(W.text(a, a.getString(autoLockLabel(a)), 14f).apply { tag = "sec-autolock-text" }, W.lp(0, W.WRAP, 1f))
        lockRow.addView(W.button(a, L.t("変更", "Change")) { a.explorer?.pickAutoLock { fill(a, body, d) } }.apply { tag = "sec-autolock" }, W.lp(W.WRAP, W.WRAP, l = 8))
        body.addView(lockRow, W.lp(W.MATCH, W.WRAP, t = 4))

        heading(a, body, L.t("内部バックアップ", "Internal backups"))
        val n = a.store.backups().size
        val bRow = W.hbox(a).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        bRow.addView(W.text(a, L.t("$n 個", "$n file(s)"), 14f), W.lp(0, W.WRAP, 1f))
        bRow.addView(W.button(a, L.t("一覧を開く", "Open the list")) { d.dismiss(); a.show(BackupScreen(a)) }.apply { tag = "sec-backups"; isEnabled = !Session.readOnlyTemp }, W.lp(W.WRAP, W.WRAP, l = 8))
        body.addView(bRow, W.lp(W.MATCH, W.WRAP, t = 4))
    }

    /** Below 「強い」 (10^12) or 「すべて」: asks first, with the estimate; then applies [n]. */
    fun setLevel(a: MainActivity, n: Int, done: () -> Unit) {
        val weak = n == Security.EXPORT_ANY || (n in 1 until io.github.zmlworks.kpexplorer.sec.PasswordStrength.STRONG_LOG10.toInt())
        if (!weak) { Security.setExportMinLog10(a, n); done(); return }
        Dlg.confirm(a, L.t("認証なしで外部に書き出す際の強度", "Strength needed to export without unlocking"),
            (if (n == Security.EXPORT_ANY) L.t("「すべて」にすると、マスターパスワードが弱くても (未判定でも)、端末を手にした人が誰でもロックを解除しないでファイルを持ち出せます。弱いパスワードのファイルは、持ち出されると総当たりで開かれるおそれがあります。",
                "With \"All\", anyone holding the device can take the file away without unlocking, even with a weak master password (or one not judged yet). A file with a weak password could then be opened by brute force.")
            else L.t("「${Security.exportLevelLabel(n)}」は「強い」(10^12) より弱い設定です。10^$n 回の推測は、平均で${Security.crackTime(n)}の見積もりで、持ち出されたファイルが総当たりで開かれるおそれがあります。",
                "\"${Security.exportLevelLabel(n)}\" is weaker than \"Strong\" (10^12). 10^$n guesses take, on average, ${Security.crackTime(n)}, so a file taken away could be opened by brute force.")) +
                L.t("\nこの設定にしますか?", "\nUse this setting?"), '!', yes = L.t("設定する", "Set"), no = L.t("キャンセル", "Cancel")) {
            Security.setExportMinLog10(a, n); done()
        }
    }

    /** 「数値で指定」: a power of ten typed in ([Security.EXPORT_MIN_N]..[Security.EXPORT_MAX_N]). */
    fun askLevel(a: MainActivity, done: () -> Unit): ClassicDialog {
        val cur = Security.exportMinLog10(a).takeIf { it > 0 } ?: io.github.zmlworks.kpexplorer.sec.PasswordStrength.STRONG_LOG10.toInt()
        return Dlg.prompt(a, L.t("認証なしで外部に書き出す際の強度", "Strength needed to export without unlocking"),
            L.t("推測回数 10^n の n (${Security.EXPORT_MIN_N} から ${Security.EXPORT_MAX_N}):", "n of 10^n guesses (${Security.EXPORT_MIN_N} to ${Security.EXPORT_MAX_N}):"), cur.toString()) { t ->
            val n = t.trim().toIntOrNull()?.takeIf { it in Security.EXPORT_MIN_N..Security.EXPORT_MAX_N } ?: return@prompt false
            setLevel(a, n, done); true
        }
    }

    fun autoLockLabel(a: MainActivity): Int = a.autoLockMinutes().let { when { it == 0 -> R.string.menu_autolock_now; it < 0 -> R.string.menu_autolock_never; else -> R.string.menu_autolock_5 } }
}
