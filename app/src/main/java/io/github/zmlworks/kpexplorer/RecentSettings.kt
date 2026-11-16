package io.github.zmlworks.kpexplorer

import android.content.Context
import android.widget.LinearLayout
import io.github.zmlworks.kpexplorer.db.Recent
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.W

/**
 * 0.4.38: 最近更新された鍵の設定 (main menu), like ごみ箱の設定: whether the pseudo folder 「最近更新した鍵」 shows under
 * "/" in the folder tree, how many keys it lists, and 「最近更新した鍵を見る」 (which works while it is hidden, too).
 * Kept in this device's settings (the list itself is worked out from the file, nothing is stored there).
 */
object RecentSettings {
    const val NAME_JA = "最近更新した鍵"
    const val NAME_EN = "Recently changed keys"
    const val PREF_SHOW = "recent_show"
    const val PREF_COUNT = "recent_count"
    val COUNTS = listOf(10, 20, 30, 50)

    fun shown(ctx: Context) = App.prefs(ctx).getBoolean(PREF_SHOW, true)
    fun count(ctx: Context) = App.prefs(ctx).getInt(PREF_COUNT, Recent.LIMIT).takeIf { it in COUNTS } ?: Recent.LIMIT

    fun show(a: MainActivity): ClassicDialog {
        val body = W.vbox(a)
        val d = ClassicDialog(a, L.t("最近更新された鍵の設定", "Recently Changed Keys Settings"), body, listOf(
            ClassicDialog.Btn(L.t("最近更新した鍵を見る", "View recently changed keys")) { open(a); true },
            ClassicDialog.Btn(L.t("閉じる", "Close"), true) { true }))
        fill(a, body)
        d.show(); Dlg.last = d
        return d
    }

    /** Show the list (also while hidden: then the tree shows it only while it is open, as the hidden bin). */
    fun open(a: MainActivity) { a.explorer?.openRecent() }

    private fun fill(a: MainActivity, body: LinearLayout) {
        body.removeAllViews()
        val p = App.prefs(a)
        val hide = W.check(a, L.t("「$NAME_JA」をフォルダーの階層に表示しない (この画面の「最近更新した鍵を見る」で開けます)", "Hide \"$NAME_EN\" in the folder tree (open it with \"View recently changed keys\" here)"), !shown(a)).apply {
            tag = "recent-hide"
            setOnClickListener { p.edit().putBoolean(PREF_SHOW, !isChecked).apply(); a.explorer?.refresh(); fill(a, body) }
        }
        body.addView(hide, W.lp(W.MATCH, W.WRAP))
        val r = W.hbox(a).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        r.addView(W.text(a, L.t("表示する数: ${count(a)} 件", "Keys listed: ${count(a)}"), 15f).apply { tag = "recent-count-text" }, W.lp(0, W.WRAP, 1f))
        r.addView(W.button(a, L.t("変更", "Change")) {
            Dlg.choose(a, L.t("表示する数", "Keys listed"), COUNTS.map { L.t("$it 件", "$it") }, COUNTS.indexOf(count(a))) { i ->
                p.edit().putInt(PREF_COUNT, COUNTS[i]).apply(); a.explorer?.refresh(); fill(a, body)
            }
        }.apply { tag = "recent-count" }, W.lp(W.WRAP, W.WRAP, l = 8))
        body.addView(r, W.lp(W.MATCH, W.WRAP, t = 8))
        body.addView(W.text(a, L.t("鍵の作成日時と最終更新日時の新しいほうで、新しい順に並べます。ごみ箱の中・独立メモ・履歴は含みません。",
            "Newest first, by the later of each key's creation and last change. Not the recycle bin, independent memos or history."), 13f, color = W.SHADOW)
            .apply { setLineSpacing(0f, 1.1f) }, W.lp(W.MATCH, W.WRAP, t = 4))
    }
}
