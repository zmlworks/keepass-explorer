package io.github.zmlworks.kpexplorer

import android.graphics.Typeface
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

object Help {
    const val VERSION = "0.3.0"

    fun about(a: MainActivity) {
        val col = W.vbox(a)
        val head = W.hbox(a)
        head.addView(W.icon(a, Px.computer32), W.lp(W.WRAP, W.WRAP, r = 12))
        val t = W.vbox(a)
        t.addView(W.text(a, "KeePass Explorer", 18f, true))
        t.addView(W.text(a, "バージョン $VERSION (試作版)", 14f), W.lp(W.MATCH, W.WRAP, t = 4))
        head.addView(t)
        col.addView(head)
        col.addView(W.hsep(a), W.lp(W.MATCH, 4 * W.px, t = 10, b = 10))
        col.addView(W.text(a, "KeePass (KDBX) 形式のパスワード／メモ帳。\nデータはこの端末の中だけに保存され、通信は一切しません。\n\n" +
            "© 2026 zmlworks — MIT License\nKDBX: kotpass (MIT) ほか。詳しくは「ライセンス」を参照。", 14f).apply { setLineSpacing(0f, 1.15f) })
        ClassicDialog(a, "バージョン情報", col, listOf(
            ClassicDialog.Btn("ライセンス...") { licenses(a); false },
            ClassicDialog.Btn("OK", true))).show()
    }

    fun licenses(a: MainActivity) {
        val text = runCatching { a.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText() }.getOrDefault("(not found)")
        val tv = W.text(a, text, 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        ClassicDialog(a, "ライセンス", tv, listOf(ClassicDialog.Btn("閉じる", true)), scroll = true).show()
    }

    fun usage(a: MainActivity) {
        Dlg.message(a, "使い方",
            "・フォルダー = KeePass のグループ、鍵とメモ = エントリです。\n" +
            "・タップで選択して下の欄に内容を表示、ダブルタップで開く、長押しでメニュー。\n" +
            "・長押ししたまま指を動かすと移動できます。フォルダーの上で離すとその中へ、項目の前後で離すと並べ替え、上の階層表示の行で離すと上の階層へ。\n" +
            "・並び順はフォルダーごとに保存されます (KDBX の CustomData)。\n" +
            "・鍵の画面の「≡」から、カスタム欄の追加、欄の表示・順番、ワンタイムパスワード (TOTP) を設定できます。\n" +
            "・フォルダー操作は自動保存、鍵とメモは「保存」で保存します。", 'i')
    }

    fun dbInfo(a: MainActivity) {
        val d = Session.db ?: return
        val size = a.store.dbFile.length()
        Dlg.message(a, "データベースの情報",
            "名前: ${d.content.meta.name.ifEmpty { d.content.group.name }}\n" +
            "形式: ${Kdbx.describe(d)}\n" +
            "エントリ: ${Tree.countEntries(d.content.group)} 個\n" +
            "ファイル: ${a.store.dbFile.name} (${size / 1024} KB)\n" +
            "作成元: ${d.content.meta.generator}\n" +
            "ごみ箱: ${if (d.content.meta.recycleBinEnabled) "有効" else "無効"}", 'i')
    }
}
