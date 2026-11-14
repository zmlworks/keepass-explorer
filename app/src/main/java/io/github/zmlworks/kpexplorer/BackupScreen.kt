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
import android.widget.Toast
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Internal backups (app storage): list, create, password-protected export (SAF), restore, delete. */
class BackupScreen(private val a: MainActivity) : MainActivity.Screen {
    private val ctx = a
    private val root = W.vbox(ctx)
    override val view: View get() = root
    var files: List<File> = emptyList()
        private set
    var selected: File? = null
    private val listHolder = FrameLayout(ctx)
    private val fmt: DateTimeFormatter get() = L.dateTime(false)

    init {
        val cap = W.titleBar(ctx, L.t("バックアップ - KeePass Explorer", "Backup - KeePass Explorer"), Px.floppy16, onClose = { a.showExplorer() })
        root.addView(cap.bar, W.lp())
        val tb = W.hbox(ctx).apply { setPadding(W.dp(4), W.dp(4), W.dp(4), W.dp(4)) }
        fun b(s: String, f: () -> Unit) = tb.addView(W.button(ctx, s) { f() }.apply { minWidth = W.dp(64) }, W.lp(W.WRAP, W.WRAP, r = 4))
        b(L.t("今すぐ作成", "Create now")) { create() }
        b(ctx.getString(R.string.browse_backup)) { selected?.let { TempImport.browseBackupFile(a, it) } ?: need() }
        b(L.t("外部に書き出す...", "Export...")) { selected?.let { export(it) } ?: need() }
        b(L.t("復元...", "Restore...")) { selected?.let { restore(it) } ?: need() }
        b(L.t("名前の変更...", "Rename...")) { selected?.let { rename(it) } ?: need() }
        b(L.t("削除", "Delete")) { selected?.let { delete(it) } ?: need() }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tb) }, W.lp())
        val addr = W.hbox(ctx).apply { setPadding(W.dp(6), W.dp(2), W.dp(4), W.dp(5)) }
        addr.addView(W.text(ctx, L.t("場所", "Location"), 14f), W.lp(W.WRAP, W.WRAP, r = 6))
        addr.addView(W.text(ctx, displayPath(), 14f).apply {
            background = W.Bevel(W.Style.FIELD); setPadding(W.dp(6), W.dp(7), W.dp(6), W.dp(7)); isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
        }, W.lp(0, W.WRAP, 1f))
        root.addView(addr, W.lp())
        listHolder.background = W.Bevel(W.Style.FIELD)
        listHolder.setPadding(2 * W.px, 2 * W.px, 2 * W.px, 2 * W.px)
        root.addView(listHolder, W.lp(W.MATCH, 0, 1f, l = 2, r = 2))
        reload()
    }

    private fun displayPath(): String {
        return L.t("アプリ内 / backups", "In app / backups")
    }

    private fun need() = Dlg.message(ctx, L.t("バックアップ", "Backup"), L.t("先に一覧からバックアップを選んでください。", "Select a backup in the list first."), 'i')

    fun reload() {
        files = a.store.backups()
        if (selected != null && files.none { it.name == selected!!.name }) selected = null
        listHolder.removeAllViews()
        val col = W.vbox(ctx).apply { setBackgroundColor(W.WINDOW) }
        val header = W.hbox(ctx)
        fun h(s: String, w: Int, weight: Float) = header.addView(W.text(ctx, s, 13f).apply {
            background = W.Bevel(W.Style.HEADER); setPadding(W.dp(6), W.dp(6), W.dp(4), W.dp(6))
        }, W.lp(w, W.WRAP, weight))
        h(L.t("名前", "Name"), 0, 1f); h(L.t("サイズ", "Size"), W.dp(70), 0f); h(L.t("更新日時", "Modified"), W.dp(122), 0f)
        col.addView(header)
        val list = ListView(ctx)
        list.divider = null
        list.selector = android.graphics.drawable.ColorDrawable(0)
        W.classicScrollbars(list)
        val adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(p: Int) = files[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, c: View?, parent: ViewGroup?): View {
                val f = files[p]
                val sel = f.name == selected?.name
                val row = W.hbox(ctx).apply { setPadding(W.dp(4), 0, 0, 0); minimumHeight = W.dp(40) }
                row.addView(ImageView(ctx).apply {
                    setImageDrawable(Px.drawable(ctx, if (sel) Px.selected(Px.floppy16) else Px.floppy16, W.iconScale()))
                }, W.lp(W.WRAP, W.WRAP, r = 4))
                val nb = FrameLayout(ctx)
                nb.addView(W.text(ctx, f.name, 15f, color = if (sel) W.HILITE else W.TEXT).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(W.dp(3), W.dp(2), W.dp(3), W.dp(2))
                    if (sel) setBackgroundColor(W.NAVY)
                }, FrameLayout.LayoutParams(W.WRAP, W.WRAP, Gravity.CENTER_VERTICAL))
                row.addView(nb, W.lp(0, W.WRAP, 1f))
                row.addView(W.text(ctx, "${(f.length() + 1023) / 1024} KB", 14f).apply { gravity = Gravity.END; setPadding(0, 0, W.dp(6), 0) }, W.lp(W.dp(70), W.WRAP))
                row.addView(W.text(ctx, fmt.format(Instant.ofEpochMilli(f.lastModified())), 13f).apply { setPadding(W.dp(6), 0, 0, 0) }, W.lp(W.dp(122), W.WRAP))
                return row
            }
        }
        list.adapter = adapter
        // 0.4.31: the long press is the app's own ([W.longPressMs]), not GestureDetector's system delay
        val long = io.github.zmlworks.kpexplorer.ui.LongPress(list) { x, y ->
            files.getOrNull(list.pointToPosition(x.toInt(), y.toInt()))?.let { f ->
                selected = f; adapter.notifyDataSetChanged(); updateStatus()
                val loc = IntArray(2); list.getLocationInWindow(loc)
                Menus.at(list, loc[0] + x.toInt(), loc[1] + y.toInt(), menuFor(f))
            }
        }
        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (long.fired) return true
                val pos = list.pointToPosition(e.x.toInt(), e.y.toInt())
                selected = files.getOrNull(pos); adapter.notifyDataSetChanged(); updateStatus(); return true
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
    }

    fun menuFor(f: File) = listOf(
        Menus.Item(ctx.getString(R.string.browse_backup)) { TempImport.browseBackupFile(a, f) },
        Menus.Item(L.t("外部に書き出す (パスワード付き)...", "Export (with password)...")) { export(f) },
        Menus.sep(),
        Menus.Item(L.t("このバックアップから復元...", "Restore from this backup...")) { restore(f) },
        Menus.Item(ctx.getString(R.string.import_menu)) {
            TempImport.confirmAndImport(a, f.readBytes(), f.name)
        },
        Menus.sep(),
        Menus.Item(L.t("名前の変更...", "Rename...")) { rename(f) },
        Menus.Item(L.t("削除", "Delete")) { delete(f) }
    )

    private fun updateStatus() {}

    fun create() {
        val busy = Dlg.busy(ctx, L.t("バックアップ", "Backup"), L.t("保存してからバックアップしています…", "Saving, then backing up…"))
        Session.afterSave({ a.store.backup() }) { r ->
            busy.dismiss()
            r.onSuccess { f -> selected = f; reload(); Toast.makeText(ctx, L.t("バックアップ: ${f.name}", "Backup: ${f.name}"), Toast.LENGTH_SHORT).show() }
             .onFailure { Dlg.message(ctx, L.t("バックアップ", "Backup"), L.t("作成できませんでした。\n${it.message ?: it}", "Could not create it.\n${it.message ?: it}"), 'x') }
        }
    }

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
            val busy = Dlg.busy(ctx, L.t("復元", "Restore"), L.t("バックアップを確認しています…", "Checking the backup…"))
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
            f.delete(); selected = null; reload()
        }
    }

    override fun onBack(): Boolean { a.showExplorer(); return true }
}
