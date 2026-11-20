package io.github.zmlworks.kpexplorer

import android.content.Context
import android.widget.LinearLayout
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Recent
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.W
import java.util.UUID

/**
 * 0.4.40: 機能フォルダ — the recycle bin and the three virtual folders (最近の更新, 最近使用,
 * 頻繁に使用; see [Recent]) — and the user's 「機能フォルダの設定」 window, which replaces 0.4.38's ごみ箱の設定 and
 * 0.4.39's 最近の鍵の設定. The main menu opens each folder (also a hidden one) and this window.
 *
 * Where things are kept: the bin's settings in the database file (KeePass's recycle-bin flag, KP Explorer keys in Meta
 * CustomData); the virtual folders' place in "/" in the root's own order (see Order.order); whether each one shows,
 * how many it lists (its long-press menu) and 「ファイルを開くたびにデータベースを更新する」 on this device.
 */
object FeatureFolders {
    const val TITLE_JA = "機能フォルダの設定"
    const val TITLE_EN = "Special folder settings"
    val COUNTS = listOf(10, 20, 30, 50)
    /** 「ファイルを開くたびにデータベースを更新する」 (off by default): see [recordUse]. */
    const val PREF_SAVE_ON_OPEN = "usage_save_on_open"

    /** A virtual folder. [id] stands for it in the back history and in the root's order (no group has it). */
    enum class Kind(val nameJa: String, val nameEn: String, val prefShow: String, val prefCount: String, val id: UUID) {
        // 0.4.41 names (0.4.40: 最近更新されたファイル, 最近利用したファイル, よく利用するファイル)
        CHANGED("最近の更新", "Recently updated", "recent_show", "recent_count", Recent.CHANGED_ID),
        USED("最近使用", "Recently used", "used_show", "used_count", Recent.USED_ID),
        FREQUENT("頻繁に使用", "Frequently used", "frequent_show", "frequent_count", Recent.FREQUENT_ID);

        val label get() = L.t(nameJa, nameEn)
        /** Its list comes from the use record (KeePass LastAccessTime / UsageCount). */
        val usage get() = this != CHANGED

        /** Its entries in the file [db], in its order. */
        fun items(ctx: Context, db: KeePassDatabase): List<Recent.Item> = when (this) {
            CHANGED -> Recent.list(db, count(ctx, this))
            USED -> Recent.used(db, count(ctx, this))
            FREQUENT -> Recent.frequent(db, count(ctx, this))
        }

        companion object { fun of(id: UUID?): Kind? = entries.firstOrNull { it.id == id } }
    }

    fun shown(ctx: Context, k: Kind) = App.prefs(ctx).getBoolean(k.prefShow, true)
    fun count(ctx: Context, k: Kind) = App.prefs(ctx).getInt(k.prefCount, Recent.LIMIT).takeIf { it in COUNTS } ?: Recent.LIMIT
    fun saveOnOpen(ctx: Context) = App.prefs(ctx).getBoolean(PREF_SAVE_ON_OPEN, false)

    /**
     * A use of entry [id] (its page opened, a field copied): KeePass LastAccessTime / UsageCount ([Recent.markUsed]).
     * With 「ファイルを開くたびにデータベースを更新する」 on, a change like any other (saved a moment later); off (the
     * default), only in memory — the lists show it at once and the next save of an edit writes it; locking or leaving
     * the app puts it in the encrypted side file (0.4.42, [Session.recordUse], [io.github.zmlworks.kpexplorer.db.UsageLog]),
     * merged back at the next unlock. Nothing while a file is browsed read-only.
     */
    fun recordUse(ctx: Context, id: UUID) {
        if (Session.readOnlyTemp || !Session.isOpen) return
        if (saveOnOpen(ctx)) Session.update { Recent.markUsed(it, id) } else Session.recordUse(id)
    }

    fun binId(db: KeePassDatabase): UUID? = Tree.recycleBinId(db)?.takeIf { Tree.findGroup(db, it) != null }

    /** Menu ごみ箱 / the three folders: open it (also while it is hidden: then it shows only while open). */
    fun openBin(a: MainActivity) { Session.db?.let { binId(it) }?.let { a.explorer?.navigate(it) } }
    fun open(a: MainActivity, k: Kind) { a.explorer?.openVirtual(k) }

    /** 表示する数 of [k] (its long-press menu). */
    fun pickCount(a: MainActivity, k: Kind, done: () -> Unit = {}): ClassicDialog =
        Dlg.choose(a, L.t("「${k.nameJa}」に表示する数", "Files listed in \"${k.nameEn}\""), COUNTS.map { L.t("$it 件", "$it") }, COUNTS.indexOf(count(a, k))) { i ->
            App.prefs(a).edit().putInt(k.prefCount, COUNTS[i]).apply(); a.explorer?.refresh(); done()
        }

    /** 利用履歴を消去, after asking: empties 最近使用 and 頻繁に使用. */
    fun askClearUsed(a: MainActivity, done: () -> Unit = {}): ClassicDialog =
        Dlg.confirm(a, L.t("利用履歴を消去", "Clear usage history"),
            L.t("「${Kind.USED.nameJa}」と「${Kind.FREQUENT.nameJa}」の履歴を消去しますか?\nすべての鍵とメモの最終アクセス日時を作成日時に戻し、使用回数を 0 にします (データベースファイルに保存されます。更新日時と履歴は変わりません)。",
                "Clear the history of \"${Kind.USED.nameEn}\" and \"${Kind.FREQUENT.nameEn}\"?\nEvery key's and memo's last access time goes back to its creation time and its use count to 0 (saved in the database file; modification times and History stay)."),
            '?', yes = L.t("消去", "Clear"), no = L.t("キャンセル", "Cancel")) {
            Session.update { Recent.clearUsed(it) }
            a.explorer?.refresh(); done()
        }

    fun show(a: MainActivity): ClassicDialog {
        val body = W.vbox(a)
        val d = ClassicDialog(a, L.t(TITLE_JA, TITLE_EN), body, listOf(ClassicDialog.Btn(L.t("閉じる", "Close"), true) { true }), scroll = true)
        fill(a, body)
        d.show(); Dlg.last = d
        return d
    }

    private fun changed(a: MainActivity, body: LinearLayout) { a.explorer?.refresh(); fill(a, body) }

    private fun row(a: MainActivity, body: LinearLayout, text: String, button: String, tag: String, enabled: Boolean, indent: Int = 0, onClick: () -> Unit) {
        val r = W.hbox(a).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        r.addView(W.text(a, text, 15f).apply { this.tag = "$tag-text" }, W.lp(0, W.WRAP, 1f, l = indent))
        val b = W.button(a, button) { onClick() }.apply { this.tag = tag; isEnabled = enabled; alpha = if (enabled) 1f else 0.45f }
        r.addView(b, W.lp(W.WRAP, W.WRAP, l = 8))
        body.addView(r, W.lp(W.MATCH, W.WRAP, t = 6))
    }

    private fun check(a: MainActivity, body: LinearLayout, text: String, on: Boolean, tag: String, enabled: Boolean, grey: Boolean = false, f: (Boolean) -> Unit) {
        val c = W.check(a, text, on).apply {
            this.tag = tag; isEnabled = enabled
            if (grey) setTextColor(W.SHADOW)
            setOnClickListener { f(isChecked) }
        }
        body.addView(c, W.lp(W.MATCH, W.WRAP))
    }

    fun daysLabel(n: Int) = if (n <= 0) L.t("しない", "Off") else L.t("$n 日後", "After $n days")
    val DAYS = listOf(0, 7, 14, 30, 60, 90, 180, 365)

    /** The window's content, the user's layout exactly (plus one line when the file does not use a recycle bin). */
    fun fill(a: MainActivity, body: LinearLayout) {
        body.removeAllViews()
        val db = Session.db ?: return
        val p = App.prefs(a)
        val bin = Tree.recycleBinId(db)?.let { Tree.findGroup(db, it) }
        val ro = Session.readOnlyTemp
        // a file whose KeePass setting is "no recycle bin" (0.4.23–0.4.39: 一度で完全削除): say so, and offer the bin
        if (!db.content.meta.recycleBinEnabled) row(a, body, L.t("ごみ箱を使わない設定です (削除するとすぐに完全に削除)", "This file uses no recycle bin (deleting is permanent)"),
            L.t("使う", "Use"), "bin-enable", !ro) {
            Session.update { Tree.setRecycleBinEnabled(it, true, a.getString(R.string.recycle_bin)) }; changed(a, body)
        }
        val name = bin?.name?.ifEmpty { L.t("(名前なし)", "(no name)") } ?: L.t("(なし)", "(none)")
        row(a, body, L.t("ごみ箱の名前: ", "Recycle bin name: ") + name, L.t("変更", "Change"), "bin-rename", bin != null && !ro) { rename(a, body) }
        val n = Tree.recycleBinChildCount(db)
        row(a, body, L.t("$n 個の項目", "$n item(s)"), a.getString(R.string.empty_recycle_bin), "bin-empty", bin != null && n > 0 && !ro, indent = 16) {
            Dlg.confirm(a, a.getString(R.string.empty_recycle_bin), a.getString(R.string.confirm_empty_bin), '!') {
                Session.update { Tree.emptyRecycleBin(it) }; a.explorer?.selection = null; changed(a, body)
            }
        }
        row(a, body, L.t("自動削除: ", "Auto-delete: ") + daysLabel(Tree.binAutoDeleteDays(db)), L.t("変更", "Change"), "bin-days", !ro) { pickDays(a, body) }
        check(a, body, L.t("ごみ箱を表示する", "Show the recycle bin"), !Tree.binHidden(db), "bin-show", !ro) { on ->
            Session.update { Tree.setBinHidden(it, !on) }; changed(a, body)
        }
        check(a, body, L.t("ごみ箱をフォルダツリーで一番下にする", "Show the recycle bin last in the folder tree"), Tree.binLastInTree(db), "bin-tree-last", !ro) { on ->
            Session.update { Tree.setBinLastInTree(it, on) }; changed(a, body)
        }
        body.addView(W.hsep(a), W.lp(W.MATCH, 4 * W.px, t = 10, b = 6))
        for (k in Kind.entries) check(a, body, L.t("「${k.nameJa}」を表示する", "Show \"${k.nameEn}\""), shown(a, k), "${k.name.lowercase()}-show", true) { on ->
            p.edit().putBoolean(k.prefShow, on).apply(); changed(a, body)
        }
        check(a, body, L.t("ファイルを開くたびにデータベースを更新する", "Update the database every time a file is opened"), saveOnOpen(a), "save-on-open", true, grey = true) { on ->
            p.edit().putBoolean(PREF_SAVE_ON_OPEN, on).apply()
            // turning it on writes what was recorded quietly so far
            if (on && Session.hasQuietUnsaved) Session.flushAll()
            fill(a, body)
        }
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
        Dlg.choose(a, L.t("自動削除", "Auto-delete"), DAYS.map { daysLabel(it) }, DAYS.indexOf(Tree.binAutoDeleteDays(db)).coerceAtLeast(0)) { i ->
            val cur = Session.db ?: return@choose
            val next = Tree.setBinAutoDeleteDays(cur, DAYS[i])
            val gone = Tree.recycleBinChildCount(next) - Tree.recycleBinChildCount(Tree.purgeOldInBin(next))
            if (gone <= 0) { Session.update { Tree.setBinAutoDeleteDays(it, DAYS[i]) }; changed(a, body); return@choose }
            Dlg.confirm(a, L.t("自動削除", "Auto-delete"),
                L.t("ごみ箱の $gone 個の項目は ${DAYS[i]} 日より前に入れたものです。今すぐ完全に削除しますか？\nこの操作は元に戻せません。",
                    "$gone item(s) in the bin are older than ${DAYS[i]} days. Delete them permanently now?\nThis cannot be undone."), '!') {
                Session.update { Tree.purgeOldInBin(Tree.setBinAutoDeleteDays(it, DAYS[i])) }; a.explorer?.selection = null; changed(a, body)
            }
        }
    }
}
