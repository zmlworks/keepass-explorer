package io.github.zmlworks.kpexplorer

import android.graphics.Typeface
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

object Help {
    const val VERSION = "0.3.2"

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
            "・フォルダーはタップで開きます。鍵・メモはタップで選択 (下に内容を表示)、選択中の項目をもう一度タップで開きます。\n" +
            "・項目に触れたまま指を少し動かすと移動 (ドラッグ) になります。フォルダーの上で離すとその中へ、項目の前後で離すと並べ替え、上の階層表示の行で離すと上の階層へ。\n" +
            "・一覧のスクロールは、何もない所か右端のスクロールバーの帯をなぞります。動かさずに長押しするとメニュー。\n" +
            "・ロック解除は端末の画面ロック (指紋・顔・PIN など) を使います。\n" +
            "・ツールバーの「保存」: アプリ内に日付.kdbx で保存、または パスワードを付けて外部に書き出し (KeePass / KeePassXC で開けます)。\n" +
            "・鍵の画面: 欄を横にスワイプでコピー、長押しで編集、パスワード欄はタップで表示／非表示 (最初は ******)。\n" +
            "・検索: 文字とタグ (複数選ぶとすべてを持つもの) で絞り込めます。\n" +
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
