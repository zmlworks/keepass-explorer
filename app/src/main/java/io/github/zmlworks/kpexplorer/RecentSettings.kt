package io.github.zmlworks.kpexplorer

import android.content.Context
import android.widget.LinearLayout
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Recent
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.W
import java.util.UUID

/**
 * 最近の鍵の設定 (main menu; 0.4.38 「最近更新された鍵の設定」), like ごみ箱の設定, for the two virtual folders under "/":
 * 「最近更新した鍵」 and (0.4.39) 「最近使用した鍵」 — for each, whether it shows in the folder tree, how many keys it
 * lists, and a button that opens it (also while it is hidden); for the second also 使用履歴を消去. Showing and counts
 * are this device's settings; the lists come from the file (see [Recent]).
 */
object RecentSettings {
    const val TITLE_JA = "最近の鍵の設定"
    const val TITLE_EN = "Recent keys settings"
    val COUNTS = listOf(10, 20, 30, 50)

    /** A virtual folder. [id] stands for it in the back history (no group has it). */
    enum class Kind(val nameJa: String, val nameEn: String, val prefShow: String, val prefCount: String, val id: UUID) {
        CHANGED("最近更新した鍵", "Recently changed keys", "recent_show", "recent_count", UUID(0L, 0x0438L)),
        USED("最近使用した鍵", "Recently used keys", "used_show", "used_count", UUID(0L, 0x0439L));

        val label get() = L.t(nameJa, nameEn)

        /** Its keys in the file [db], in its order. */
        fun items(ctx: Context, db: KeePassDatabase): List<Recent.Item> =
            if (this == CHANGED) Recent.list(db, count(ctx, this)) else Recent.used(db, count(ctx, this))

        companion object { fun of(id: UUID?): Kind? = entries.firstOrNull { it.id == id } }
    }

    /** 最近更新した鍵 (the 0.4.38 names, used by tests and older code). */
    val NAME_JA get() = Kind.CHANGED.nameJa
    val NAME_EN get() = Kind.CHANGED.nameEn

    fun shown(ctx: Context, k: Kind = Kind.CHANGED) = App.prefs(ctx).getBoolean(k.prefShow, true)
    fun count(ctx: Context, k: Kind = Kind.CHANGED) = App.prefs(ctx).getInt(k.prefCount, Recent.LIMIT).takeIf { it in COUNTS } ?: Recent.LIMIT

    fun show(a: MainActivity): ClassicDialog {
        val body = W.vbox(a)
        lateinit var d: ClassicDialog
        d = ClassicDialog(a, L.t(TITLE_JA, TITLE_EN), body, listOf(ClassicDialog.Btn(L.t("閉じる", "Close"), true) { true }), scroll = true)
        fill(a, body, d)
        d.show(); Dlg.last = d
        return d
    }

    /** Show the list of [k] (also while hidden: then the tree shows it only while it is open, as the hidden bin). */
    fun open(a: MainActivity, k: Kind) { a.explorer?.openVirtual(k) }

    /** 使用履歴を消去, after asking. */
    fun askClearUsed(a: MainActivity, done: () -> Unit = {}): ClassicDialog =
        Dlg.confirm(a, L.t("使用履歴を消去", "Clear usage history"),
            L.t("「${Kind.USED.nameJa}」の履歴を消去しますか?\nすべての鍵の最終アクセス日時を作成日時に戻し、使用回数を 0 にします (データベースファイルに保存されます。更新日時と履歴は変わりません)。",
                "Clear the \"${Kind.USED.nameEn}\" history?\nEvery key's last access time goes back to its creation time and its use count to 0 (saved in the database file; modification times and History stay)."),
            '?', yes = L.t("消去", "Clear"), no = L.t("キャンセル", "Cancel")) {
            Session.update { Recent.clearUsed(it) }
            a.explorer?.refresh(); done()
        }

    private fun fill(a: MainActivity, body: LinearLayout, d: ClassicDialog) {
        body.removeAllViews()
        val p = App.prefs(a)
        for (k in Kind.entries) {
            val key = k.name.lowercase()
            body.addView(W.text(a, k.label, 15f, true), W.lp(W.MATCH, W.WRAP, t = if (body.childCount == 0) 0 else 16))
            val hide = W.check(a, L.t("フォルダーの階層に表示しない (下の「${k.nameJa}を見る」で開けます)", "Hide in the folder tree (open it with \"View ${k.nameEn.lowercase()}\" below)"), !shown(a, k)).apply {
                tag = "$key-hide"
                setOnClickListener { p.edit().putBoolean(k.prefShow, !isChecked).apply(); a.explorer?.refresh(); fill(a, body, d) }
            }
            body.addView(hide, W.lp(W.MATCH, W.WRAP, t = 4))
            val r = W.hbox(a).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            r.addView(W.text(a, L.t("表示する数: ${count(a, k)} 件", "Keys listed: ${count(a, k)}"), 15f).apply { tag = "$key-count-text" }, W.lp(0, W.WRAP, 1f))
            r.addView(W.button(a, L.t("変更", "Change")) {
                Dlg.choose(a, L.t("表示する数", "Keys listed"), COUNTS.map { L.t("$it 件", "$it") }, COUNTS.indexOf(count(a, k))) { i ->
                    p.edit().putInt(k.prefCount, COUNTS[i]).apply(); a.explorer?.refresh(); fill(a, body, d)
                }
            }.apply { tag = "$key-count" }, W.lp(W.WRAP, W.WRAP, l = 8))
            body.addView(r, W.lp(W.MATCH, W.WRAP, t = 6))
            body.addView(W.text(a, if (k == Kind.CHANGED)
                L.t("鍵の作成日時と最終更新日時の新しいほうで、新しい順に並べます。", "Newest first, by the later of each key's creation and last change.")
                else L.t("鍵のページを開いたとき・項目をコピーしたとき・編集したときに使用として記録し、新しい順に並べます。記録はデータベースファイルの中 (KeePass の最終アクセス日時と使用回数) にあり、KeePass など他のアプリの使用も反映されます。",
                    "Recorded when a key page is opened, a field is copied from it or it is edited; newest first. Kept in the database file (KeePass's last access time and use count), so use in KeePass and other apps shows too."),
                13f, color = W.SHADOW).apply { setLineSpacing(0f, 1.1f) }, W.lp(W.MATCH, W.WRAP, t = 4))
            val b = W.hbox(a).apply { gravity = android.view.Gravity.END }
            if (k == Kind.USED) b.addView(W.button(a, L.t("使用履歴を消去", "Clear history")) {
                askClearUsed(a) { fill(a, body, d) }
            }.apply { tag = "used-clear"; isEnabled = !Session.readOnlyTemp && Session.db?.let { Recent.anyUsed(it) } == true }, W.lp(W.WRAP, W.WRAP, r = 8))
            b.addView(W.button(a, L.t("${k.nameJa}を見る", "View ${k.nameEn.lowercase()}")) { d.dismiss(); open(a, k) }.apply { tag = "$key-open" }, W.lp(W.WRAP, W.WRAP))
            body.addView(b, W.lp(W.MATCH, W.WRAP, t = 6))
        }
        body.addView(W.text(a, L.t("どちらも、ごみ箱の中・独立メモ・履歴は含みません。", "Neither lists the recycle bin, independent memos or history."), 13f, color = W.SHADOW), W.lp(W.MATCH, W.WRAP, t = 14))
    }
}
