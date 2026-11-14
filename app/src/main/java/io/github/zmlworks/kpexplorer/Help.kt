package io.github.zmlworks.kpexplorer

import android.graphics.Typeface
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

object Help {
    const val VERSION = "0.4.21"

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
        ClassicDialog(a, L.t("バージョン情報", "About"), col, listOf(
            ClassicDialog.Btn(L.t("ライセンス...", "Licenses...")) { licenses(a); false },
            ClassicDialog.Btn(L.t("戻る操作ログ", "Back key log")) { backLog(a); false },
            ClassicDialog.Btn("OK", true))).show()
    }

    /** 0.4.7 diagnostic: Back/keyboard/edit event names + times only. Never field names or contents. */
    fun backLog(a: MainActivity) {
        val log = io.github.zmlworks.kpexplorer.ui.BackTrace.dump().ifEmpty { L.t("(記録なし)", "(nothing recorded)") }
        val tv = W.text(a, L.t("鍵の編集中に OS の戻るを押した時の順序（イベント名と時刻のみ。入力内容は含みません）\n\n$log", "Order of events when the OS Back was pressed while editing a key (event names and times only; nothing you typed)\n\n$log"), 12f)
        tv.setTextIsSelectable(true); tv.typeface = Typeface.MONOSPACE
        ClassicDialog(a, L.t("戻る操作ログ", "Back key log"), tv, listOf(
            ClassicDialog.Btn(L.t("コピー", "Copy")) {
                (a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("back-log", log)); true },
            ClassicDialog.Btn("OK", true))).show()
    }

    fun licenses(a: MainActivity) {
        val text = runCatching { a.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText() }.getOrDefault("(not found)")
        val tv = W.text(a, text, 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        ClassicDialog(a, L.t("ライセンス", "Licenses"), tv, listOf(ClassicDialog.Btn(L.t("閉じる", "Close"), true)), scroll = true).show()
    }

    fun usage(a: MainActivity) {
        Dlg.message(a, L.t("使い方", "How to Use"),
            L.t("・フォルダー = KeePass のグループ、鍵とメモ = エントリです。\n", "• Folders = KeePass groups; keys and memos = entries.\n") +
            L.t("・フォルダー・鍵・メモ (ごみ箱も) は、タップで選択 (下に内容を表示)、選択中の項目をもう一度タップで開きます。上の階層表示のフォルダーは 1 回のタップで移動します。下の簡易表示も、1 回目のタップで選択 (点線の枠)、もう一度タップで開きます (Pass のタップは表示切替のまま)。\n", "• Folders, keys and memos (and the Recycle Bin): tap to select (its contents appear below), tap the selected item again to open it. Folders in the path bar at the top open with one tap. In the preview pane below too, the first tap selects (dotted frame) and a second tap opens (tapping Pass still shows / hides it).\n") +
            L.t("・一覧はどこをなぞってもスクロールします (スクロールバーはありません)。項目を長押しするとメニュー。メニューが出たまま指を 8mm ほど動かすとメニューが閉じて移動 (ドラッグ) になります。フォルダーの真ん中 (幅や高さの中央 4 割) で離すとその中へ、項目の前後で離すと並べ替え、上の階層表示の行で離すとそのフォルダーへ。動かさずに離すとメニューはそのまま。アイコン表示・詳細表示とも同じです。詳細表示では、名前の列に触れて動かすと長押しなしですぐドラッグになります (ほかの列はスクロール)。\n", "• The list scrolls wherever you swipe (there is no scroll bar). Long-press an item for its menu. Move your finger about 8 mm while the menu is open to close it and start moving (dragging). Release in the middle of a folder (the central 40 % of its width or height) to put it inside, before or after an item to reorder, or on a row of the path bar to move it to that folder. Release without moving and the menu stays. Same in icon and details view. In details view, touching the name column and moving starts a drag right away without a long-press (the other columns scroll).\n") +
            L.t("・詳細表示の列の幅は、見出しの境目 (つまみ) を左右にドラッグして変えられます (覚えておきます。何もない所の長押しメニュー「列の幅を元に戻す」)。\n", "• In details view, drag the borders (handles) between the column headers to change the column widths (remembered; long-press an empty spot → \"Reset column widths\").\n") +
            L.t("・ロック解除は指紋・画面ロック (PIN など)、またはマスターパスワード。メニューからマスターパスワードを変更できます。メニューの「言語」で表示の言語 (端末に合わせる / 日本語 / English)、「日付」で日付の並び (年/月/日・月/日/年・日/月/年) を選べます。\n", "• Unlock with your fingerprint, the screen lock (PIN etc.) or the master password. The master password can be changed from the menu. In the menu, \"Language\" sets the display language (system / 日本語 / English) and \"Date\" sets the date order (Y/M/D, M/D/Y, D/M/Y).\n") +
            L.t("・鍵の画面の Last Modified の右「- 履歴」で、保存履歴を見られます (件数はデータベースの設定 HistoryMaxItems どおり。新しく作ったデータベースは KeePass と同じ 10 件)。履歴が残るのは中身 (欄の値・OTP・タグ) を変えて保存したときだけで、欄の表示／非表示・並べ替え・空の欄の追加では残りません。履歴には変わった欄だけを変更前の値で残します (タイトルは目印として常に残し、履歴画面では変わったときだけ表示。メモ欄は前後 1 文字付きの差分)。空だった欄に書き足しただけの保存は、タイトルだけの履歴になり、履歴画面では「項目の追加」と表示します。履歴は 1 件ずつ、または「全て削除」で消せます (毎回確認あり)。\n", "• On a key page, \"- History\" to the right of Last Modified shows the saved history (as many versions as the database setting HistoryMaxItems allows; new databases use 10, like KeePass). History is kept only when you save a change to the contents (field values, OTP, tags); showing / hiding, reordering or adding empty fields does not add history. Each history version keeps only the changed fields, with their previous values (the title is always kept as a marker but shown only when it changed; notes are stored as a diff with 1 character of context). A save that only fills in empty or new fields leaves a copy with just the title, shown as \"Fields added\". Delete history one version at a time or with \"Delete all\" (always confirmed).\n") +
            L.t("・メモの画面: タイトルは太字で表示します (変えるときはファイルの長押しメニュー「名前の変更」)。右端の「- 差分」で履歴を表示します。このアプリのメモの履歴には、本文の変わった所だけを「- 変更前」「+ 変更後」(前後 1 文字付き、↵ は改行) で残します。他のアプリで作られたメモは「- 履歴」で、鍵と同じ履歴を表示します。\n", "• Memo page: the title is shown in bold (rename it with \"Rename\" in the item’s long-press menu). \"- Changes\" at the right edge shows the history. For memos made by this app, the history keeps only the changed parts of the text as \"- before\" / \"+ after\" (with 1 character of context; ↵ = line break). Memos made by other apps show \"- History\", the same history as keys.\n") +
            L.t("・ツールバーの「保存」: アプリ内に日付.kdbx で保存、または パスワードを付けて外部に書き出し (同じマスターパスワードか別のパスワード。KeePass / KeePassXC で開けます)。\n", "• \"Save\" in the toolbar: save a dated .kdbx inside the app, or export it with a password (the same master password or another one; opens in KeePass / KeePassXC).\n") +
            L.t("・新しい鍵: 作成専用の入力画面 (すぐ編集・生成ボタンあり。コピー操作なし)。空欄のまま作成した欄は最初から削除扱い (あとで「項目の編集」から戻せます)。作成後は通常の鍵画面へ。\n", "• New key: a page just for creating (fields are editable at once, with a Generate button; no copying). Fields left empty start out deleted (bring them back later with \"Edit fields\"). After creating, the normal key page opens.\n") +
            L.t("・鍵の画面: タイトル中央＋右端 ×。「項目の編集」「カスタム項目の追加」。下のボタンは、変更が無いときは長い [OK] (閉じる)、何か変更すると「戻る」「保存」。Passはタップで表示。URL は左スワイプで確認後ブラウザで開く（「example.com/…」のように https:// が無ければ付けて開く。右スワイプはコピー）。Tag はタップで編集。欄を長押し (~0.3秒) で編集、他を触ると下書きへ反映（DB は「保存」で確定）。OTP は OTP欄の長押し。削除は一覧メニュー（ごみ箱へは確認なし／完全削除は確認）。項目の編集は横三線つまみと「この下は非表示」(スクロールバーなし)。\n", "• Key page: title in the centre, × at the right. \"Edit fields\", \"Add custom field\". The bottom button is a long [OK] (close) while nothing has changed, and \"Back\" / \"Save\" after a change. Tap Pass to show it. Swipe a URL left to open it in the browser after confirming (https:// is added when missing, as in \"example.com/…\"; swipe right to copy). Tap Tag to edit it. Long-press a field (~0.3 s) to edit; touching anything else puts the edit in the draft (the database changes on \"Save\"). Long-press the OTP field for OTP. Delete from the list menu (to the Recycle Bin without asking; permanent delete asks). Edit fields uses the ≡ handles and \"Hidden below\" (no scroll bar).\n") +
            L.t("・長押しメニューの一番上は「表示: 詳細」(詳細表示では「表示: アイコン」「列の幅を元に戻す」)。鍵・メモの長押しメニューは「名前の変更」「切り取り」「フォルダーを指定して移動」「一つ上の階層へ移動」(親フォルダの先頭へ)「削除」。メニューは項目に重ならない位置に出ます。切り取った項目は薄く表示され、移動先の何もない所の長押し (またはフォルダーの長押し) で「貼り付け」。やめるときは「切り取りの取り消し」。コピーは鍵の画面で欄をスワイプ。\n", "• The top of the long-press menu is \"View: Details\" (in details view \"View: Icons\" and \"Reset column widths\"). The long-press menu of keys and memos has \"Rename\", \"Cut\", \"Move to folder\", \"Move up one level\" (to the top of the parent folder) and \"Delete\". Menus appear where they do not cover the item. Cut items are shown faded; long-press an empty spot at the destination (or a folder) and choose \"Paste\". To give up, choose \"Cancel cut\". To copy, swipe a field on the key page.\n") +
            L.t("・検索: 上の欄に文字を入力するかタグを押すと、すぐ下に結果が出ます (タグは複数でAND)。\n・削除: 鍵・メモ・フォルダーはごみ箱へ (確認なし)。ごみ箱の中で削除すると完全に削除します (確認あり)。ごみ箱の「元に戻す」は削除前のフォルダーへ戻します (無くなっていればルートへ)。\n", "• Search: type in the box at the top or tap tags; results appear right below (several tags = AND).\n• Delete: keys, memos and folders go to the Recycle Bin (no confirmation). Deleting inside the Recycle Bin deletes permanently (confirmed). \"Restore\" in the Recycle Bin puts an item back in its previous folder (or the root if that folder is gone).\n") +
            L.t("・ごみ箱を空にする: ごみ箱のアイコン、または上の階層表示のごみ箱を長押し。\n", "• Empty the Recycle Bin: long-press the Recycle Bin icon, or the Recycle Bin in the path bar.\n") +
            L.t("・並び順はフォルダーごとに保存されます (KDBX の CustomData)。\n", "• The order is saved for each folder (in KDBX CustomData).\n") +
            L.t("・不足している標準欄は「項目の編集」の非表示側からドラッグで戻せます。ワンタイムパスワード (OTP) は OTP 欄の長押しで設定・変更・削除 (未設定の鍵にも「未設定 (長押しで設定)」の欄があります)。新しい鍵の画面でも入力できます。KeePass 2 (TimeOtp-*) や旧 KeePassXC (TOTP Seed) 形式の OTP も表示します。\n", "• Missing standard fields can be dragged back from the hidden side of \"Edit fields\". The one-time password (OTP) is set up, changed or removed by long-pressing the OTP field (keys without one also have a \"Not set (long-press to set)\" field). It can also be entered on the new key page. OTPs in KeePass 2 (TimeOtp-*) and old KeePassXC (TOTP Seed) format are shown too.\n") +
            L.t("・フォルダー操作は自動保存。鍵とメモの欄編集は画面内の下書きのみで、「保存」で初めてデータベースへ反映します。ごみ箱はルート直下に固定です (長押ししたままドラッグで、ルートの中の並び順だけ変えられます)。", "• Folder actions are saved automatically. Field edits on keys and memos stay in the page’s draft until you press \"Save\". The Recycle Bin stays directly under the root (long-press and drag to change only its place in the root’s order)."), 'i')
    }

    fun dbInfo(a: MainActivity) {
        val d = Session.db ?: return
        val size = a.store.dbFile.length()
        Dlg.message(a, L.t("データベースの情報", "Database Properties"),
            L.t("名前: ${d.content.meta.name.ifEmpty { d.content.group.name }}\n", "Name: ${d.content.meta.name.ifEmpty { d.content.group.name }}\n") +
            L.t("形式: ${Kdbx.describe(d)}\n", "Format: ${Kdbx.describe(d)}\n") +
            L.t("エントリ: ${Tree.countEntries(d.content.group)} 個\n", "Entries: ${Tree.countEntries(d.content.group)}\n") +
            L.t("ファイル: ${a.store.dbFile.name} (${size / 1024} KB)\n", "File: ${a.store.dbFile.name} (${size / 1024} KB)\n") +
            L.t("作成元: ${d.content.meta.generator}\n", "Generator: ${d.content.meta.generator}\n") +
            L.t("削除: ごみ箱へ / ごみ箱内は完全削除", "Delete: to the Recycle Bin / permanently inside the Recycle Bin"), 'i')
    }
}
