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
    private val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault())

    init {
        val cap = W.titleBar(ctx, "バックアップ - KeePass Explorer", Px.floppy16, onClose = { a.showExplorer() })
        root.addView(cap.bar, W.lp())
        val tb = W.hbox(ctx).apply { setPadding(W.dp(4), W.dp(4), W.dp(4), W.dp(4)) }
        fun b(s: String, f: () -> Unit) = tb.addView(W.button(ctx, s) { f() }.apply { minWidth = W.dp(64) }, W.lp(W.WRAP, W.WRAP, r = 4))
        b("今すぐ作成") { create() }
        b(ctx.getString(R.string.browse_backup)) { selected?.let { TempImport.browseBackupFile(a, it) } ?: need() }
        b("外部に書き出す...") { selected?.let { export(it) } ?: need() }
        b("復元...") { selected?.let { restore(it) } ?: need() }
        b("名前の変更...") { selected?.let { rename(it) } ?: need() }
        b("削除") { selected?.let { delete(it) } ?: need() }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tb) }, W.lp())
        val addr = W.hbox(ctx).apply { setPadding(W.dp(6), W.dp(2), W.dp(4), W.dp(5)) }
        addr.addView(W.text(ctx, "場所", 14f), W.lp(W.WRAP, W.WRAP, r = 6))
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
        return "アプリ内 / backups"
    }

    private fun need() = Dlg.message(ctx, "バックアップ", "先に一覧からバックアップを選んでください。", 'i')

    fun reload() {
        files = a.store.backups()
        if (selected != null && files.none { it.name == selected!!.name }) selected = null
        listHolder.removeAllViews()
        val col = W.vbox(ctx).apply { setBackgroundColor(W.WINDOW) }
        val header = W.hbox(ctx)
        fun h(s: String, w: Int, weight: Float) = header.addView(W.text(ctx, s, 13f).apply {
            background = W.Bevel(W.Style.HEADER); setPadding(W.dp(6), W.dp(6), W.dp(4), W.dp(6))
        }, W.lp(w, W.WRAP, weight))
        h("名前", 0, 1f); h("サイズ", W.dp(70), 0f); h("更新日時", W.dp(122), 0f)
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
        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val pos = list.pointToPosition(e.x.toInt(), e.y.toInt())
                selected = files.getOrNull(pos); adapter.notifyDataSetChanged(); updateStatus(); return true
            }
            override fun onLongPress(e: MotionEvent) {
                val pos = list.pointToPosition(e.x.toInt(), e.y.toInt())
                val f = files.getOrNull(pos) ?: return
                selected = f; adapter.notifyDataSetChanged(); updateStatus()
                val loc = IntArray(2); list.getLocationInWindow(loc)
                Menus.at(list, loc[0] + e.x.toInt(), loc[1] + e.y.toInt(), menuFor(f))
            }
        })
        list.setOnTouchListener { _, e -> gd.onTouchEvent(e); false }
        if (files.isEmpty()) {
            val f = FrameLayout(ctx); f.addView(list)
            f.addView(W.text(ctx, "バックアップはまだありません。\n「今すぐ作成」またはツールバーの「保存」で作成できます。", 14f, color = W.SHADOW).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(W.MATCH, W.WRAP).apply { topMargin = W.dp(60); leftMargin = W.dp(16); rightMargin = W.dp(16) })
            col.addView(f, W.lp(W.MATCH, 0, 1f))
        } else col.addView(list, W.lp(W.MATCH, 0, 1f))
        listHolder.addView(col)
        updateStatus()
    }

    fun menuFor(f: File) = listOf(
        Menus.Item(ctx.getString(R.string.browse_backup)) { TempImport.browseBackupFile(a, f) },
        Menus.Item("外部に書き出す (パスワード付き)...") { export(f) },
        Menus.sep(),
        Menus.Item("このバックアップから復元...") { restore(f) },
        Menus.Item(ctx.getString(R.string.import_menu)) {
            TempImport.confirmAndImport(a, f.readBytes(), f.name)
        },
        Menus.sep(),
        Menus.Item("名前の変更...") { rename(f) },
        Menus.Item("削除") { delete(f) }
    )

    private fun updateStatus() {}

    fun create() {
        val busy = Dlg.busy(ctx, "バックアップ", "保存してからバックアップしています…")
        Session.afterSave({ a.store.backup() }) { r ->
            busy.dismiss()
            r.onSuccess { f -> selected = f; reload(); Toast.makeText(ctx, "バックアップ: ${f.name}", Toast.LENGTH_SHORT).show() }
             .onFailure { Dlg.message(ctx, "バックアップ", "作成できませんでした。\n${it.message ?: it}", 'x') }
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
        Dlg.confirm(ctx, "バックアップから復元", "現在のデータベースを「${f.name}」の内容で置き換えますか?\n\n置き換える前に、現在の内容も自動でバックアップします。", '!') {
            val key = Session.compositeCopy() ?: return@confirm
            val busy = Dlg.busy(ctx, "復元", "バックアップを確認しています…")
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
                    Dlg.message(ctx, "復元", "復元しました。\n(復元前の内容は ${safety.name} に保存しました)", 'i')
                }.onFailure { t ->
                    Dlg.message(ctx, "復元", when (t) {
                        is Kdbx.WrongPasswordException -> "このファイルは今のマスターパスワードでは開けないため、復元できません (変更前のパスワードで作られたファイルです)。"
                        is Session.SaveFailedException -> "現在の変更を保存できなかったため、復元を中止しました。\n${t.message}"
                        else -> "復元できませんでした。\n$t"
                    }, 'x')
                }
            }
        }
    }

    /** 0.4.10: rename (".kdbx" kept, no separators, no clash with another backup). */
    fun rename(f: File): io.github.zmlworks.kpexplorer.ui.ClassicDialog =
        Dlg.prompt(ctx, "名前の変更", "新しい名前 (.kdbx は自動で付きます):", f.nameWithoutExtension) { input ->
            try {
                val nf = a.store.renameBackup(f, input)
                selected = nf; reload(); true
            } catch (e: io.github.zmlworks.kpexplorer.db.Store.RenameException) {
                Dlg.message(ctx, "名前の変更", e.message ?: "名前を変更できませんでした。", '!'); false
            }
        }

    private fun delete(f: File) {
        Dlg.confirm(ctx, "ファイルの削除の確認", "「${f.name}」を削除しますか?", '!') {
            f.delete(); selected = null; reload()
        }
    }

    override fun onBack(): Boolean { a.showExplorer(); return true }
}
