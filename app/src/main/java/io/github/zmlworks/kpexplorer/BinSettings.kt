package io.github.zmlworks.kpexplorer

import android.view.View
import android.widget.LinearLayout
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.W

/**
 * 0.4.23: ごみ箱の設定 (main menu). Every setting is kept in the database file (KeePass "use recycle bin" flag,
 * KP Explorer keys in Meta CustomData), so it is the same on every device. Changes apply at once.
 */
object BinSettings {
    val DAYS = listOf(0, 7, 14, 30, 60, 90, 180, 365)

    fun daysLabel(n: Int) = if (n <= 0) L.t("しない", "Off") else L.t("$n 日後", "After $n days")

    fun show(a: MainActivity): ClassicDialog {
        val body = W.vbox(a)
        val d = ClassicDialog(a, L.t("ごみ箱の設定", "Recycle Bin Settings"), body, listOf(ClassicDialog.Btn(L.t("閉じる", "Close"), true) { true }), scroll = false)
        fill(a, body)
        d.show(); Dlg.last = d
        return d
    }

    private fun changed(a: MainActivity, body: LinearLayout) { a.explorer?.refresh(); fill(a, body) }

    private fun row(a: MainActivity, body: LinearLayout, text: String, button: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
        val r = W.hbox(a).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        r.addView(W.text(a, text, 15f).apply { this.tag = "$tag-text" }, W.lp(0, W.WRAP, 1f))
        val b = W.button(a, button) { onClick() }.apply { this.tag = tag; isEnabled = enabled; alpha = if (enabled) 1f else 0.45f }
        r.addView(b, W.lp(W.WRAP, W.WRAP, l = 8))
        body.addView(r, W.lp(W.MATCH, W.WRAP, t = 6))
    }

    fun fill(a: MainActivity, body: LinearLayout) {
        body.removeAllViews()
        val db = Session.db ?: return
        val bin = Tree.recycleBinId(db)?.let { Tree.findGroup(db, it) }
        val ro = Session.readOnlyTemp
        val name = bin?.name?.ifEmpty { L.t("(名前なし)", "(no name)") } ?: L.t("(なし)", "(none)")
        row(a, body, L.t("名前: ", "Name: ") + name, L.t("名前の変更…", "Rename…"), "bin-rename", bin != null && !ro) { rename(a, body) }
        val n = Tree.recycleBinChildCount(db)
        row(a, body, L.t("中身: $n 個", "Contains: $n"), L.t("空にする…", "Empty…"), "bin-empty", bin != null && n > 0 && !ro) {
            Dlg.confirm(a, a.getString(R.string.empty_recycle_bin), a.getString(R.string.confirm_empty_bin), '!') {
                Session.update { Tree.emptyRecycleBin(it) }; a.explorer?.selection = null; changed(a, body)
            }
        }
        val days = Tree.binAutoDeleteDays(db)
        row(a, body, L.t("日数で自動削除: ", "Auto-delete: ") + daysLabel(days), L.t("変更…", "Change…"), "bin-days", !ro) { pickDays(a, body) }
        body.addView(W.text(a, L.t("ごみ箱に入れてから指定の日数が過ぎた項目を、データベースを開いたときに完全に削除します。",
            "Items that have been in the bin longer than this are deleted permanently when the database is opened."), 13f, color = W.SHADOW),
            W.lp(W.MATCH, W.WRAP, t = 2))
        val once = W.check(a, L.t("一度で完全削除 (ごみ箱を使わない)", "Delete permanently at once (no recycle bin)"), !db.content.meta.recycleBinEnabled).apply {
            tag = "bin-once"; isEnabled = !ro
            setOnClickListener {
                Session.update { Tree.setRecycleBinEnabled(it, !isChecked, a.getString(R.string.recycle_bin)) }
                changed(a, body)
            }
        }
        body.addView(once, W.lp(W.MATCH, W.WRAP, t = 10))
        val hide = W.check(a, L.t("ごみ箱を表示しない (メニューの「ごみ箱を開く」で開けます)", "Hide the recycle bin (open it with \"Open Recycle Bin\" in the menu)"), Tree.binHidden(db)).apply {
            tag = "bin-hide"; isEnabled = !ro
            setOnClickListener { Session.update { Tree.setBinHidden(it, isChecked) }; changed(a, body) }
        }
        body.addView(hide, W.lp(W.MATCH, W.WRAP))
        // 0.4.29: the bin's place in the folder tree
        val last = W.check(a, L.t("階層表示でごみ箱をいつも一番下にする", "Always show the recycle bin last in the folder tree"), Tree.binLastInTree(db)).apply {
            tag = "bin-tree-last"; isEnabled = !ro
            setOnClickListener { Session.update { Tree.setBinLastInTree(it, isChecked) }; changed(a, body) }
        }
        body.addView(last, W.lp(W.MATCH, W.WRAP))
        body.addView(W.text(a, L.t("外すと、階層表示のごみ箱は一覧と同じ位置 (一覧でドラッグして動かせます) に出ます。",
            "When off, the folder tree shows the bin where it is in the list (drag it there to move it)."), 13f, color = W.SHADOW),
            W.lp(W.MATCH, W.WRAP, t = 2))
    }

    private fun rename(a: MainActivity, body: LinearLayout) {
        val db = Session.db ?: return
        val bin = Tree.recycleBinId(db)?.let { Tree.findGroup(db, it) } ?: return
        Dlg.prompt(a, L.t("ごみ箱の名前の変更", "Rename Recycle Bin"), L.t("新しい名前:", "New name:"), bin.name) { n ->
            val t = n.trim()
            if (t.isEmpty()) return@prompt false
            if (t != bin.name) { Session.update { Tree.renameGroup(it, bin.uuid, t) }; changed(a, body) }
            true
        }
    }

    private fun pickDays(a: MainActivity, body: LinearLayout) {
        val db = Session.db ?: return
        Dlg.choose(a, L.t("日数で自動削除", "Auto-delete"), DAYS.map { daysLabel(it) }, DAYS.indexOf(Tree.binAutoDeleteDays(db)).coerceAtLeast(0)) { i ->
            val cur = Session.db ?: return@choose
            val next = Tree.setBinAutoDeleteDays(cur, DAYS[i])
            val gone = Tree.recycleBinChildCount(next) - Tree.recycleBinChildCount(Tree.purgeOldInBin(next))
            if (gone <= 0) { Session.update { Tree.setBinAutoDeleteDays(it, DAYS[i]) }; changed(a, body); return@choose }
            Dlg.confirm(a, L.t("日数で自動削除", "Auto-delete"),
                L.t("ごみ箱の $gone 個の項目は ${DAYS[i]} 日より前に入れたものです。今すぐ完全に削除しますか？\nこの操作は元に戻せません。",
                    "$gone item(s) in the bin are older than ${DAYS[i]} days. Delete them permanently now?\nThis cannot be undone."), '!') {
                Session.update { Tree.purgeOldInBin(Tree.setBinAutoDeleteDays(it, DAYS[i])) }; a.explorer?.selection = null; changed(a, body)
            }
        }
    }
}
