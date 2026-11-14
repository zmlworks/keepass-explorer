package io.github.zmlworks.kpexplorer

import android.graphics.Typeface
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

object Help {
    const val VERSION = "0.4.34"

    fun about(a: MainActivity) {
        val col = W.vbox(a)
        val head = W.hbox(a)
        head.addView(W.icon(a, Px.computer32), W.lp(W.WRAP, W.WRAP, r = 12))
        val t = W.vbox(a)
        t.addView(W.text(a, "KeePass Explorer", 18f, true))
        t.addView(W.text(a, L.t("バージョン $VERSION (試作版)", "Version $VERSION (preview)"), 14f), W.lp(W.MATCH, W.WRAP, t = 4))
        head.addView(t)
        col.addView(head)
        col.addView(W.hsep(a), W.lp(W.MATCH, 4 * W.px, t = 10, b = 10))
        col.addView(W.text(a, L.t("KeePass (KDBX) 形式のパスワード／メモ帳。\nデータはこの端末の中だけに保存され、通信は一切しません。\n\n", "A password / memo book in KeePass (KDBX) format.\nData is stored only on this device; the app never uses the network.\n\n") +
            L.t("© 2026 zmlworks — MIT License\nKDBX: kotpass (MIT) ほか。\nSome icons by Yusuke Kamiyamane. Licensed under a Creative Commons Attribution 3.0 License.\n詳しくは「ライセンス」を参照。", "© 2026 zmlworks — MIT License\nKDBX: kotpass (MIT) and others.\nSome icons by Yusuke Kamiyamane. Licensed under a Creative Commons Attribution 3.0 License.\nSee \"Licenses\" for details."), 14f).apply { setLineSpacing(0f, 1.15f) })
        // 0.4.33: titled About in both languages; the Back-key diagnostic log (0.4.7) is gone
        ClassicDialog(a, "About", col, listOf(
            ClassicDialog.Btn(L.t("ライセンス", "Licenses")) { licenses(a); false },
            ClassicDialog.Btn("OK", true))).show()
    }

    fun licenses(a: MainActivity) {
        val text = runCatching { a.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText() }.getOrDefault("(not found)")
        val tv = W.text(a, text, 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        ClassicDialog(a, L.t("ライセンス", "Licenses"), tv, listOf(ClassicDialog.Btn(L.t("閉じる", "Close"), true)), scroll = true).show()
    }

    fun dbInfo(a: MainActivity) {
        val d = Session.db ?: return
        val size = a.store.dbFile.length()
        Dlg.message(a, L.t("データベースのプロパティ", "Database Properties"),
            L.t("名前: ${d.content.meta.name.ifEmpty { d.content.group.name }}\n", "Name: ${d.content.meta.name.ifEmpty { d.content.group.name }}\n") +
            L.t("形式: ${Kdbx.describe(d)}\n", "Format: ${Kdbx.describe(d)}\n") +
            L.t("エントリ: ${Tree.countEntries(d.content.group)} 個\n", "Entries: ${Tree.countEntries(d.content.group)}\n") +
            L.t("ファイル: ${a.store.dbFile.name} (${size / 1024} KB)\n", "File: ${a.store.dbFile.name} (${size / 1024} KB)\n") +
            L.t("作成元: ${d.content.meta.generator}\n", "Generator: ${d.content.meta.generator}\n") +
            L.t("削除: ごみ箱へ / ごみ箱内は完全削除", "Delete: to the Recycle Bin / permanently inside the Recycle Bin"), 'i')
    }
}
