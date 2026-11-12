package io.github.zmlworks.kpexplorer

import android.graphics.Typeface
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

object Help {
    const val VERSION = "0.4.13"

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
            "© 2026 zmlworks — MIT License\nKDBX: kotpass (MIT) ほか。\nSome icons by Yusuke Kamiyamane. Licensed under a Creative Commons Attribution 3.0 License.\n詳しくは「ライセンス」を参照。", 14f).apply { setLineSpacing(0f, 1.15f) })
        ClassicDialog(a, "バージョン情報", col, listOf(
            ClassicDialog.Btn("ライセンス...") { licenses(a); false },
            ClassicDialog.Btn("戻る操作ログ") { backLog(a); false },
            ClassicDialog.Btn("OK", true))).show()
    }

    /** 0.4.7 diagnostic: Back/keyboard/edit event names + times only. Never field names or contents. */
    fun backLog(a: MainActivity) {
        val log = io.github.zmlworks.kpexplorer.ui.BackTrace.dump().ifEmpty { "(記録なし)" }
        val tv = W.text(a, "鍵の編集中に OS の戻るを押した時の順序（イベント名と時刻のみ。入力内容は含みません）\n\n$log", 12f)
        tv.setTextIsSelectable(true); tv.typeface = Typeface.MONOSPACE
        ClassicDialog(a, "戻る操作ログ", tv, listOf(
            ClassicDialog.Btn("コピー") {
                (a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("back-log", log)); true },
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
            "・フォルダーはタップで開きます。鍵・メモはタップで選択 (下に内容を表示)、選択中の項目をもう一度タップで開きます。下の簡易表示も、1 回目のタップで選択 (点線の枠)、もう一度タップで開きます (Pass のタップは表示切替のまま)。\n" +
            "・一覧はどこをなぞってもスクロールします (スクロールバーはありません)。項目を長押しするとメニュー。メニューが出たまま指を 8mm ほど動かすとメニューが閉じて移動 (ドラッグ) になります。フォルダーの上で離すとその中へ、項目の前後で離すと並べ替え、上の階層表示の行で離すとそのフォルダーへ。動かさずに離すとメニューはそのまま。アイコン表示・詳細表示とも同じです。\n" +
            "・詳細表示の列の幅は、見出しの境目 (つまみ) を左右にドラッグして変えられます (覚えておきます。何もない所の長押しメニュー「列の幅を元に戻す」)。\n" +
            "・ロック解除は指紋・画面ロック (PIN など)、またはマスターパスワード。メニューからマスターパスワードを変更できます。\n" +
            "・メインの「メニュー」→「履歴」で、選択中の鍵／メモの保存履歴 (最大10件、読取専用) を見られます。\n" +
            "・ツールバーの「保存」: アプリ内に日付.kdbx で保存、または パスワードを付けて外部に書き出し (同じマスターパスワードか別のパスワード。KeePass / KeePassXC で開けます)。\n" +
            "・新しい鍵: 作成専用の入力画面 (すぐ編集・生成ボタンあり。コピー操作なし)。空欄のまま作成した欄は最初から削除扱い (あとで「項目の編集」から戻せます)。作成後は通常の鍵画面へ。\n" +
            "・鍵の画面: タイトル中央＋右端 ×。「項目の編集」「項目を追加」。Passはタップで表示。URL は左スワイプで確認後ブラウザで開く（右スワイプはコピー）。Tag はタップで編集。欄を長押し (~0.3秒) で編集、他を触ると下書きへ反映（DB は「保存」で確定）。OTP は OTP欄の長押し。削除は一覧メニュー（ごみ箱へは確認なし／完全削除は確認）。項目の編集は横三線つまみと「この下は非表示」(スクロールバーなし)。\n" +
            "・鍵・メモの長押しメニューは「名前の変更」「切り取り」「フォルダーを指定して移動」「一つ上の階層へ移動」(親フォルダの先頭へ)「削除」。メニューは項目に重ならない位置に出ます。切り取った項目は薄く表示され、移動先の何もない所の長押し (またはフォルダーの長押し) で「貼り付け」。やめるときは「切り取りの取り消し」。コピーは鍵の画面で欄をスワイプ。\n" +
            "・検索: 上の欄に文字を入力するかタグを押すと、すぐ下に結果が出ます (タグは複数でAND)。\n・削除: 鍵・メモ・フォルダーはごみ箱へ (確認なし)。ごみ箱の中で削除すると完全に削除します (確認あり)。ごみ箱の「元に戻す」は削除前のフォルダーへ戻します (無くなっていればルートへ)。\n" +
            "・ごみ箱を空にする: ごみ箱のアイコン、または上の階層表示のごみ箱を長押し。\n" +
            "・並び順はフォルダーごとに保存されます (KDBX の CustomData)。\n" +
            "・不足している標準欄は「項目の編集」の非表示側からドラッグで戻せます。ワンタイムパスワード (OTP) は OTP 欄の長押しで設定・変更・削除 (未設定の鍵にも「未設定 (長押しで設定)」の欄があります)。新しい鍵の画面でも入力できます。KeePass 2 (TimeOtp-*) や旧 KeePassXC (TOTP Seed) 形式の OTP も表示します。\n" +
            "・フォルダー操作は自動保存。鍵とメモの欄編集は画面内の下書きのみで、「保存」で初めてデータベースへ反映します。ごみ箱はルート直下に固定です。", 'i')
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
            "削除: ごみ箱へ / ごみ箱内は完全削除", 'i')
    }
}
