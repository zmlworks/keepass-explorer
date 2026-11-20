package io.github.zmlworks.kpexplorer

import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Internal backups (app storage): list, create, password-protected export (SAF), restore, delete.
 * 0.4.33: titled 「内部バックアップ」 without an icon, no 場所 row, the columns sort by a tap on their header
 * (again = the other way; remembered), 「今すぐ作成」 asks first, 閲覧 comes back here when it ends.
 * 0.4.41: back in the main menu; only 今すぐ作成 / 閲覧 / エクスポート on top (復元, 名前の変更, 削除 in the long-press
 * menu); the column widths follow a drag on the header borders ([Columns]); a 強度 column, marked "*" when the launch
 * screen could write it out without unlocking (was: green text) and "#" when it opens with the current database's key
 * ([checkKeys], remembered in its [io.github.zmlworks.kpexplorer.db.FileInfo.keyCheck]).
 */
class BackupScreen(private val a: MainActivity, select: String? = null) : MainActivity.Screen {
    private val ctx = a
    private val root = W.vbox(ctx).apply { setBackgroundColor(W.FACE) }
    override val view: View get() = root
    var files: List<File> = emptyList()
        private set
    var selected: File? = null
    private val listHolder = FrameLayout(ctx)
    private val legend = W.text(ctx, "", 12f).apply { tag = "backup-legend"; setPadding(W.dp(6), W.dp(4), W.dp(6), W.dp(6)) }
    private val cols = io.github.zmlworks.kpexplorer.ui.Columns(ctx, PREF_COLS, intArrayOf(0, 60, 120), 96, intArrayOf(80, 40, 64, 48))
    private var adapter: BaseAdapter? = null
    /** Backups whose key check runs now. */
    private val checking = HashSet<String>()
    private val fmt: DateTimeFormatter get() = L.dateTime(false)

    enum class SortBy(val key: String) { NAME("name"), SIZE("size"), DATE("date") }
    var sortBy: SortBy = SortBy.DATE
        private set
    var ascending = false
        private set

    init {
        App.prefs(ctx).getString(PREF_SORT, null)?.split('-')?.let { p ->
            SortBy.entries.firstOrNull { it.key == p.getOrNull(0) }?.let { sortBy = it; ascending = p.getOrNull(1) == "asc" }
        }
        val cap = W.titleBar(ctx, L.t("内部バックアップ", "Internal backups"), null, onClose = { a.showExplorer() })
        root.addView(cap.bar, W.lp())
        val tb = W.hbox(ctx).apply { setPadding(W.dp(4), W.dp(4), W.dp(4), W.dp(4)) }
        fun b(s: String, f: () -> Unit) = tb.addView(W.button(ctx, s) { f() }.apply { minWidth = W.dp(64) }, W.lp(W.WRAP, W.WRAP, r = 4))
        b(L.t("今すぐ作成", "Create now")) { Security.confirmBackup(a) { f -> selected = f; reload() } }
        b(L.t("閲覧", "Browse")) { selected?.let { browse(it) } ?: need() }
        b(L.t("エクスポート", "Export")) { selected?.let { export(it) } ?: need() }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tb) }, W.lp())
        listHolder.background = W.Bevel(W.Style.FIELD)
        listHolder.setPadding(2 * W.px, 2 * W.px, 2 * W.px, 2 * W.px)
        root.addView(listHolder, W.lp(W.MATCH, 0, 1f, l = 2, r = 2))
        root.addView(legend, W.lp())
        if (select != null) selected = a.store.backupFile(select)
        reload()
    }

    /** 閲覧 (read-only); when it ends the app comes back to this list with the file selected. */
    private fun browse(f: File) {
        val name = f.name
        TempImport.browseBackupFile(a, f) { act -> act.show(BackupScreen(act, name)) }
    }

    /** Tap on a column header: sort by it, or the other way when it already sorts the list (remembered). */
    fun sortOn(by: SortBy) {
        ascending = if (by == sortBy) !ascending else by != SortBy.DATE   // a new column: names / sizes up, dates newest first
        sortBy = by
        App.prefs(ctx).edit().putString(PREF_SORT, by.key + "-" + if (ascending) "asc" else "desc").apply()
        reload()
    }

    private fun sorted(list: List<File>): List<File> {
        val byName = compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val c = when (sortBy) {
            SortBy.NAME -> byName
            SortBy.SIZE -> compareBy<File> { it.length() }.then(byName)
            SortBy.DATE -> compareBy<File> { it.lastModified() }.then(byName)
        }
        return list.sortedWith(if (ascending) c else c.reversed())
    }

    private fun need() = Dlg.message(ctx, L.t("バックアップ", "Backup"), L.t("先に一覧からバックアップを選んでください。", "Select a backup in the list first."), 'i')

    /** Width the columns share (the list pane minus its bevel). */
    private fun avail(): Int {
        val w = if (listHolder.width > 0) listHolder.width - listHolder.paddingLeft - listHolder.paddingRight
            else ctx.resources.displayMetrics.widthPixels - W.dp(4) - 4 * W.px
        return w.coerceAtLeast(W.dp(200))
    }

    /** The 強度 cell: "*" = exportable without unlocking, "#" = opens with the current key, then the verdict. */
    fun strengthText(f: File): String {
        val i = a.store.info(f)
        return marks(f) + if (i.protection != io.github.zmlworks.kpexplorer.db.FileInfo.Protection.PASSWORD) L.t("パスワードなし", "No password")
            else SecuritySettings.strengthLabel(i)
    }

    fun marks(f: File): String {
        val i = a.store.info(f)
        val token = Session.db?.takeIf { !Session.readOnlyTemp }?.let { Kdbx.keyToken(it) }
        return (if (i.exportableAt(Security.exportMinLog10(ctx))) "*" else "") + (if (token != null && i.sameKey(token) == true) "#" else "")
    }

    private fun updateLegend() {
        legend.text = L.t("* 認証なしで書き出せる (セキュリティの設定)　# 今のデータベースと同じパスワード", "* can be exported without unlocking (Security settings)   # same password as the current database") +
            if (checking.isEmpty()) "" else L.t("\nパスワードを確認しています (残り ${checking.size} 個)", "\nChecking the passwords (${checking.size} left)")
    }

    fun reload() {
        files = sorted(a.store.backups())
        if (selected != null && files.none { it.name == selected!!.name }) selected = null
        listHolder.removeAllViews()
        cols.load(avail())
        val col = W.vbox(ctx).apply { setBackgroundColor(W.WINDOW) }
        var ad: BaseAdapter? = null
        val header = cols.header(::avail) { ad?.notifyDataSetChanged() }
        fun h(s: String, by: SortBy?, w: Int, tag: String) = header.addView(W.text(ctx, s + if (by != null && by == sortBy) (if (ascending) " ▲" else " ▼") else "", 13f).apply {
            background = W.Bevel(W.Style.HEADER); setPadding(W.dp(6), W.dp(10), W.dp(10), W.dp(10))
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; this.tag = tag
            if (w > 0) foreground = io.github.zmlworks.kpexplorer.ui.HeaderGrip()
            if (by != null) { isClickable = true; setOnClickListener { sortOn(by) } }
        }, if (w > 0) W.lp(w, W.WRAP) else W.lp(0, W.WRAP, 1f))
        h(L.t("名前", "Name"), SortBy.NAME, cols.widths[0], "sort-name"); h(L.t("サイズ", "Size"), SortBy.SIZE, cols.widths[1], "sort-size")
        h(L.t("更新日時", "Modified"), SortBy.DATE, cols.widths[2], "sort-date"); h(L.t("強度", "Strength"), null, 0, "col-strength")
        header.contentDescription = L.t("列の見出し (境目をドラッグで幅を変更)", "Column headers (drag a border to resize)")
        col.addView(header)
        val list = ListView(ctx)
        list.divider = null
        list.selector = android.graphics.drawable.ColorDrawable(0)
        W.classicScrollbars(list)
        fun cell(s: String, size: Float, end: Boolean = false) = W.text(ctx, s, size).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(W.dp(6), 0, W.dp(6), 0); if (end) gravity = Gravity.END }
        ad = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(p: Int) = files[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, c: View?, parent: ViewGroup?): View {
                val f = files[p]
                val sel = f.name == selected?.name
                // 0.4.36: white / pale blue rows, as in the folder tree
                val row = W.hbox(ctx).apply { minimumHeight = W.dp(40); setBackgroundColor(stripe(p)) }
                val nc = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(W.dp(4), 0, 0, 0) }
                nc.addView(ImageView(ctx).apply {
                    setImageDrawable(Px.drawable(ctx, if (sel) Px.selected(Px.floppy16) else Px.floppy16, W.iconScale()))
                }, W.lp(W.WRAP, W.WRAP, r = 4))
                val nb = FrameLayout(ctx)
                nb.addView(W.text(ctx, f.name, 15f, color = if (sel) W.HILITE else W.TEXT).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(W.dp(3), W.dp(2), W.dp(3), W.dp(2))
                    if (sel) setBackgroundColor(W.NAVY)
                }, FrameLayout.LayoutParams(W.WRAP, W.WRAP, Gravity.CENTER_VERTICAL))
                nc.addView(nb, W.lp(0, W.WRAP, 1f))
                row.addView(nc, W.lp(cols.widths[0], W.WRAP))
                row.addView(cell("${(f.length() + 1023) / 1024} KB", 14f, end = true), W.lp(cols.widths[1], W.WRAP))
                row.addView(cell(fmt.format(Instant.ofEpochMilli(f.lastModified())), 13f), W.lp(cols.widths[2], W.WRAP))
                row.addView(cell(strengthText(f), 13f).apply { tag = "strength-" + f.name }, W.lp(0, W.WRAP, 1f))
                return row
            }
        }
        adapter = ad
        list.adapter = ad
        // 0.4.31: the long press is the app's own ([W.longPressMs]), not GestureDetector's system delay
        val long = io.github.zmlworks.kpexplorer.ui.LongPress(list) { x, y ->
            files.getOrNull(list.pointToPosition(x.toInt(), y.toInt()))?.let { f ->
                selected = f; ad?.notifyDataSetChanged(); updateStatus()
                val loc = IntArray(2); list.getLocationInWindow(loc)
                Menus.at(list, loc[0] + x.toInt(), loc[1] + y.toInt(), menuFor(f))
            }
        }
        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (long.fired) return true
                val pos = list.pointToPosition(e.x.toInt(), e.y.toInt())
                selected = files.getOrNull(pos); ad?.notifyDataSetChanged(); updateStatus(); return true
            }
        }).also { it.setIsLongpressEnabled(false) }
        list.setOnTouchListener { _, e -> long.onTouch(e); gd.onTouchEvent(e); false }
        if (files.isEmpty()) {
            val f = FrameLayout(ctx); f.addView(list)
            f.addView(W.text(ctx, L.t("バックアップはまだありません。\n「今すぐ作成」またはツールバーの「保存」で作成できます。", "No backups yet.\nCreate one with \"Create now\" or with \"Save\" in the toolbar."), 14f, color = W.SHADOW).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(W.MATCH, W.WRAP).apply { topMargin = W.dp(60); leftMargin = W.dp(16); rightMargin = W.dp(16) })
            col.addView(f, W.lp(W.MATCH, 0, 1f))
        } else col.addView(list, W.lp(W.MATCH, 0, 1f))
        listHolder.addView(col)
        updateStatus()
        updateLegend()
        checkKeys()
    }

    /**
     * 0.4.41: the "#" mark. Each backup not yet checked against the current key ([Kdbx.keyToken]) is opened with it on
     * a background thread, one at a time ([Kdbx.opensWith]; a backup made from this database shares its KDF salt, so
     * the key derivation of the unlock is reused; another salt costs one derivation, not kept). The answer goes into
     * the backup's record, so the page does it once per backup and key. Not while browsing another file read-only.
     */
    private fun checkKeys() {
        if (Session.readOnlyTemp) return
        val db = Session.db ?: return
        val token = Kdbx.keyToken(db)
        val todo = files.filter { it.name !in checking && a.store.info(it).sameKey(token) == null }
        if (todo.isEmpty()) return
        val key = Session.compositeCopy() ?: return
        checking.addAll(todo.map { it.name }); updateLegend()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        CHECKER.execute {
            try {
                for (f in todo) {
                    val same = runCatching { Kdbx.opensWith(f.readBytes(), key) }.getOrDefault(false)
                    runCatching { a.store.setKeyCheck(f, token, same) }
                    main.post { checking.remove(f.name); adapter?.notifyDataSetChanged(); updateLegend() }
                }
            } finally { key.fill(0) }
        }
    }

    /** Row background of list row [p]: white, pale blue, white … */
    fun stripe(p: Int): Int = if (p % 2 == 0) W.WINDOW else io.github.zmlworks.kpexplorer.ui.STRIPE

    /** 0.4.33: 名前の変更 first. */
    fun menuFor(f: File) = listOf(
        Menus.Item(L.t("名前の変更", "Rename")) { rename(f) },
        Menus.sep(),
        Menus.Item(ctx.getString(R.string.browse_backup)) { browse(f) },
        Menus.Item(L.t("外部に書き出す", "Export")) { export(f) },
        Menus.sep(),
        Menus.Item(L.t("このバックアップから復元", "Restore from this backup")) { restore(f) },
        Menus.Item(ctx.getString(R.string.import_menu)) {
            TempImport.confirmAndImport(a, f.readBytes(), f.name)
        },
        Menus.sep(),
        Menus.Item(L.t("削除", "Delete")) { delete(f) }
    )

    private fun updateStatus() {}

    /** Decrypt the internal copy with the current key, then write it out under a password the user chooses. */
    private fun export(f: File) {
        Security.askExportPassword(a, "keepass-explorer-${f.nameWithoutExtension}.kdbx") {
            val key = Session.compositeCopy() ?: return@askExportPassword null
            try { Kdbx.open(f.readBytes(), key) } finally { key.fill(0) }
        }
    }

    private fun restore(f: File) {
        Dlg.confirm(ctx, L.t("バックアップから復元", "Restore from backup"), L.t("現在のデータベースを「${f.name}」の内容で置き換えますか?\n\n置き換える前に、現在の内容も自動でバックアップします。", "Replace the current database with the contents of \"${f.name}\"?\n\nThe current contents are backed up automatically first."), '!') {
            val key = Session.compositeCopy() ?: return@confirm
            val busy = Dlg.busy(ctx, L.t("復元", "Restore"), L.t("バックアップを確認しています", "Checking the backup"))
            Session.afterSave({
                try {
                    val bytes = f.readBytes()
                    val db = Kdbx.open(bytes, key)
                    val safety = a.store.backup()
                    a.store.write(bytes)
                    db to safety
                } finally { key.fill(0) }
            }) { r ->
                key.fill(0)
                busy.dismiss()
                r.onSuccess { (db, safety) ->
                    Security.opened(a, db)
                    a.explorer = null
                    reload()
                    Dlg.message(ctx, L.t("復元", "Restore"), L.t("復元しました。\n(復元前の内容は ${safety.name} に保存しました)", "Restored.\n(The previous contents were saved as ${safety.name})"), 'i')
                }.onFailure { t ->
                    Dlg.message(ctx, L.t("復元", "Restore"), when (t) {
                        is Kdbx.WrongPasswordException -> L.t("このファイルは今のマスターパスワードでは開けないため、復元できません (変更前のパスワードで作られたファイルです)。", "This file cannot be opened with the current master password, so it cannot be restored (it was made with an earlier password).")
                        is Session.SaveFailedException -> L.t("現在の変更を保存できなかったため、復元を中止しました。\n${t.message}", "The current changes could not be saved, so the restore was cancelled.\n${t.message}")
                        else -> L.t("復元できませんでした。\n$t", "Could not restore.\n$t")
                    }, 'x')
                }
            }
        }
    }

    /** 0.4.10: rename (".kdbx" kept, no separators, no clash with another backup). */
    fun rename(f: File): io.github.zmlworks.kpexplorer.ui.ClassicDialog =
        Dlg.prompt(ctx, L.t("名前の変更", "Rename"), L.t("新しい名前 (.kdbx は自動で付きます):", "New name (.kdbx is added automatically):"), f.nameWithoutExtension) { input ->
            try {
                val nf = a.store.renameBackup(f, input)
                selected = nf; reload(); true
            } catch (e: io.github.zmlworks.kpexplorer.db.Store.RenameException) {
                Dlg.message(ctx, L.t("名前の変更", "Rename"), e.message ?: L.t("名前を変更できませんでした。", "Could not rename it."), '!'); false
            }
        }

    private fun delete(f: File) {
        Dlg.confirm(ctx, L.t("ファイルの削除の確認", "Confirm File Delete"), L.t("「${f.name}」を削除しますか?", "Delete \"${f.name}\"?"), '!') {
            a.store.deleteBackup(f); selected = null; reload()
        }
    }

    override fun onBack(): Boolean { a.showExplorer(); return true }

    companion object {
        const val PREF_SORT = "backup_sort"
        const val PREF_COLS = "backup_cols"
        /** 0.4.41: the "#" checks, one file at a time, off the main thread (not the save thread). */
        val CHECKER: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "kpx-keycheck").apply { isDaemon = true } }
    }
}
