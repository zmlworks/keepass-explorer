package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.FolderTree
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The Explorer window: caption, menu bar, toolbar, address bar, icon/list view and status bar. */
class ExplorerScreen(private val a: MainActivity) : MainActivity.Screen {
    private val ctx = a
    var current: UUID = Tree.root(db()).uuid
        private set
    private val history = ArrayList<UUID>()
    var selection: UUID? = null
    var cut: UUID? = null
    var search: String? = null
        private set

    private val root = W.vbox(ctx)
    override val view: View get() = root
    private lateinit var caption: W.Caption
    private lateinit var addressText: TextView
    private lateinit var addressIcon: ImageView
    private val content = FrameLayout(ctx)
    private lateinit var statusLeft: TextView
    private lateinit var statusRight: TextView
    private val tools = HashMap<String, View>()

    /** One cell / row of the current view. */
    sealed class Row {
        abstract val id: UUID
        data class F(val g: Group, val isBin: Boolean) : Row() { override val id get() = g.uuid }
        data class E(val e: Entry, val where: Group?) : Row() { override val id get() = e.uuid }
    }
    var rows: List<Row> = emptyList()
        private set
    var iconMode = true
        private set

    private fun db(): KeePassDatabase = Session.db ?: throw IllegalStateException("locked")

    init {
        val last = App.prefs(ctx).getString("lastGroup", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (last != null && Tree.findGroup(db(), last) != null) current = last
        build()
        Session.listener = { a.runOnUiThread { updateStatus() } }
    }

    // ------------------------------------------------------------------ layout

    private fun build() {
        caption = W.titleBar(ctx, "", Px.folder16, onMin = { a.moveTaskToBack(true) }, onMax = null, onClose = { a.lock() })
        root.addView(caption.bar, W.lp())

        // menu bar
        val menubar = W.hbox(ctx).apply { setPadding(W.dp(2), W.dp(1), W.dp(2), W.dp(1)) }
        fun menu(label: String, items: () -> List<Menus.Item>) {
            val t = W.text(ctx, label, 15f).apply {
                background = W.Bevel(W.Style.FLAT_TOOL)
                setPadding(W.dp(9), W.dp(8), W.dp(9), W.dp(8))
                isClickable = true
            }
            t.setOnClickListener { Menus.dropDown(t, items()) }
            menubar.addView(t)
        }
        menu("ファイル(F)") { fileMenu() }
        menu("編集(E)") { editMenu() }
        menu("表示(V)") { viewMenu() }
        menu("ツール(T)") { toolsMenu() }
        menu("ヘルプ(H)") { helpMenu() }
        root.addView(menubar, W.lp())
        root.addView(W.hsep(ctx))

        // toolbar
        val tb = W.hbox(ctx).apply { setPadding(W.dp(2), W.dp(2), W.dp(2), W.dp(2)) }
        fun tool(key: String, bmp: Bitmap, tip: String, f: () -> Unit) {
            val v = ImageView(ctx).apply {
                setImageDrawable(Px.drawable(ctx, bmp, W.iconScale()))
                scaleType = ImageView.ScaleType.CENTER
                background = W.Bevel(W.Style.FLAT_TOOL)
                contentDescription = tip
                tooltipText = tip
                setOnClickListener { f() }
            }
            tools[key] = v
            tb.addView(v, W.lp(W.dp(46), W.dp(42)))
        }
        tool("back", Px.back16, "戻る") { goBack() }
        tool("up", Px.up16, "上へ") { goUp() }
        tb.addView(W.vsep(ctx))
        tool("cut", Px.cut16, "切り取り") { selection?.let { cut(it) } }
        tool("paste", Px.paste16, "貼り付け") { paste(current) }
        tool("delete", Px.delete16, "削除") { selection?.let { askDelete(it) } }
        tb.addView(W.vsep(ctx))
        tool("newFolder", Px.newFolder16, "新しいフォルダー") { newFolder() }
        tool("newFile", Px.newFile16, "新しいエントリ") { newEntryMenu(tools["newFile"]!!) }
        tb.addView(W.vsep(ctx))
        tool("backup", Px.floppy16, "バックアップを作成") { quickBackup() }
        tool("find", Px.find16, "検索") { askSearch() }
        tool("lock", Px.lock16, "ロック") { a.lock() }
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tb) }
        root.addView(hs, W.lp())
        root.addView(W.hsep(ctx))

        // address bar
        val addr = W.hbox(ctx).apply { setPadding(W.dp(6), W.dp(3), W.dp(4), W.dp(5)) }
        addr.addView(W.text(ctx, "アドレス(D)", 14f), W.lp(W.WRAP, W.WRAP, r = 6))
        val combo = W.hbox(ctx).apply { background = W.Bevel(W.Style.FIELD); setPadding(3 * W.px + W.dp(2), 2 * W.px, 2 * W.px, 2 * W.px) }
        addressIcon = W.icon(ctx, Px.folder16, maxOf(1, W.iconScale() - 1))
        combo.addView(addressIcon, W.lp(W.WRAP, W.WRAP, r = 6))
        addressText = W.text(ctx, "", 15f).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.START }
        combo.addView(addressText, W.lp(0, W.WRAP, 1f))
        val arrow = View(ctx).apply {
            background = W.Bevel(W.Style.BUTTON); foreground = W.Glyph('v'); isClickable = true
            setOnClickListener { browseFolders() }
        }
        combo.addView(arrow, W.lp(W.dp(26), W.dp(30)))
        combo.setOnClickListener { browseFolders() }
        addr.addView(combo, W.lp(0, W.WRAP, 1f))
        root.addView(addr, W.lp())

        content.background = W.Bevel(W.Style.FIELD)
        content.setPadding(2 * W.px, 2 * W.px, 2 * W.px, 2 * W.px)
        root.addView(content, W.lp(W.MATCH, 0, 1f, l = 2, r = 2))

        // status bar
        val sb = W.hbox(ctx).apply { setPadding(W.dp(2), W.dp(3), W.dp(2), W.dp(2)) }
        statusLeft = W.text(ctx, "", 13f).apply {
            background = W.Bevel(W.Style.THIN_SUNKEN); setPadding(W.dp(6), W.dp(5), W.dp(6), W.dp(5)); isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        statusRight = W.text(ctx, "", 13f).apply {
            background = W.Bevel(W.Style.THIN_SUNKEN); setPadding(W.dp(6), W.dp(5), W.dp(6), W.dp(5)); isSingleLine = true
            setOnClickListener { Session.lastError?.let { Dlg.message(ctx, "保存エラー", it, 'x') } }
        }
        sb.addView(statusLeft, W.lp(0, W.WRAP, 1f))
        sb.addView(statusRight, W.lp(W.dp(110), W.WRAP, l = 3))
        root.addView(sb, W.lp())
    }

    // ------------------------------------------------------------------ refresh

    fun currentGroup(): Group = Tree.findGroup(db(), current) ?: Tree.root(db()).also { current = it.uuid }

    fun refresh() {
        if (!Session.isOpen) return
        val d = db()
        val g = currentGroup()
        val bin = Tree.recycleBinId(d)
        val q = search
        if (q != null) {
            rows = Tree.search(d, q).map { Row.E(it.second, it.first) }
            iconMode = false
            caption.title.text = "検索結果 - KeePass Explorer"
            addressText.text = "検索結果: $q"
            addressIcon.setImageDrawable(Px.drawable(ctx, Px.find16, maxOf(1, W.iconScale() - 1)))
        } else {
            rows = g.groups.map { Row.F(it, it.uuid == bin) } + g.entries.map { Row.E(it, null) }
            iconMode = when (Tree.viewMode(g)) {
                "icons" -> true
                "list" -> false
                else -> g.groups.isNotEmpty()
            }
            val path = Tree.path(Tree.root(d), g.uuid)
            caption.title.text = "${g.name.ifEmpty { "(名前なし)" }} - KeePass Explorer"
            addressText.text = path.joinToString("\\") { it.name.ifEmpty { "(名前なし)" } } + if (path.size == 1) "\\" else ""
            addressIcon.setImageDrawable(Px.drawable(ctx, when {
                path.size == 1 -> Px.computer16
                g.uuid == bin -> Px.bin16
                else -> Px.folder16
            }, maxOf(1, W.iconScale() - 1)))
            App.prefs(ctx).edit().putString("lastGroup", g.uuid.toString()).apply()
        }
        if (selection != null && rows.none { it.id == selection }) selection = null
        if (cut != null && Tree.parentOf(Tree.root(d), cut!!) == null) cut = null
        content.removeAllViews()
        content.addView(if (iconMode) buildGrid() else buildList(), FrameLayout.LayoutParams(W.MATCH, W.MATCH))
        updateTools()
        updateStatus()
    }

    private fun updateTools() {
        val d = db()
        fun en(k: String, on: Boolean) {
            val v = tools[k] ?: return
            v.isEnabled = on
            v.alpha = if (on) 1f else 0.45f
            (v as ImageView).colorFilter = if (on) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val sel = selection
        val isRoot = current == Tree.root(d).uuid && search == null
        en("back", history.isNotEmpty() || search != null)
        en("up", !isRoot)
        en("cut", sel != null && sel != Tree.recycleBinId(d))
        en("paste", cut != null && search == null)
        en("delete", sel != null)
        en("newFolder", search == null)
        en("newFile", search == null)
    }

    fun updateStatus() {
        if (!Session.isOpen) return
        val sel = rows.firstOrNull { it.id == selection }
        statusLeft.text = when {
            search != null -> "${rows.size} 件見つかりました"
            sel != null -> "「${label(sel)}」を選択" + (if (cut != null) "  (切り取り中)" else "")
            else -> "${rows.size} 個のオブジェクト" + (if (cut != null) "  (切り取り中)" else "")
        }
        statusRight.text = when (Session.saveState) {
            Session.SaveState.SAVED -> "保存済み"
            Session.SaveState.PENDING -> "未保存…"
            Session.SaveState.SAVING -> "保存中…"
            Session.SaveState.ERROR -> "⚠ 保存エラー"
        }
    }

    fun label(r: Row): String = when (r) {
        is Row.F -> r.g.name.ifEmpty { "(名前なし)" }
        is Row.E -> Tree.title(r.e).ifEmpty { "(無題)" }
    }

    private fun bmpFor(r: Row, large: Boolean): Bitmap {
        val b = when (r) {
            is Row.F -> if (r.isBin) (if (large) Px.bin32 else Px.bin16) else (if (large) Px.folder32 else Px.folder16)
            is Row.E -> if (Tree.password(r.e).isNotEmpty()) (if (large) Px.keyfile32 else Px.keyfile16)
                        else (if (large) Px.file32 else Px.file16)
        }
        return when {
            r.id == selection -> Px.selected(b)
            r.id == cut -> Px.ghost(b)
            else -> b
        }
    }

    // ------------------------------------------------------------------ views

    private fun attachGestures(v: AbsListView) {
        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val pos = v.pointToPosition(e.x.toInt(), e.y.toInt())
                val r = rowAt(v, pos)
                if (r == null) { selection = null; refreshSelection(v) } else { selection = r.id; refreshSelection(v); open(r) }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                val pos = v.pointToPosition(e.x.toInt(), e.y.toInt())
                val r = rowAt(v, pos)
                val loc = IntArray(2); v.getLocationInWindow(loc)
                val x = loc[0] + e.x.toInt(); val y = loc[1] + e.y.toInt()
                if (r == null) { selection = null; refreshSelection(v); Menus.at(v, x, y, backgroundMenu()) }
                else { selection = r.id; refreshSelection(v); Menus.at(v, x, y, itemMenu(r)) }
            }
        })
        v.setOnTouchListener { _, e -> gd.onTouchEvent(e); false }
    }

    private fun rowAt(v: AbsListView, pos: Int): Row? {
        if (pos == AbsListView.INVALID_POSITION) return null
        val p = if (v is ListView) pos - v.headerViewsCount else pos
        return rows.getOrNull(p)
    }

    private fun refreshSelection(v: AbsListView) {
        (v.adapter as? BaseAdapter)?.notifyDataSetChanged()
        ((v as? ListView)?.adapter as? android.widget.HeaderViewListAdapter)?.wrappedAdapter?.let { (it as BaseAdapter).notifyDataSetChanged() }
        updateTools(); updateStatus()
    }

    private fun buildGrid(): View {
        val grid = GridView(ctx)
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = W.dp(100)
        grid.stretchMode = GridView.STRETCH_SPACING_UNIFORM
        grid.verticalSpacing = W.dp(6)
        grid.setPadding(W.dp(4), W.dp(8), W.dp(4), W.dp(8))
        grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(0)
        grid.setBackgroundColor(W.WINDOW)
        W.classicScrollbars(grid)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val r = rows[p]
                val cell = W.vbox(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(W.dp(2), W.dp(4), W.dp(2), W.dp(4)) }
                cell.addView(ImageView(ctx).apply {
                    setImageDrawable(Px.drawable(ctx, bmpFor(r, true), W.iconScale()))
                    scaleType = ImageView.ScaleType.CENTER
                }, W.lp(W.WRAP, W.WRAP))
                val sel = r.id == selection
                cell.addView(W.text(ctx, label(r), 14f, color = if (sel) W.HILITE else W.TEXT).apply {
                    gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setPadding(W.dp(3), W.dp(1), W.dp(3), W.dp(2))
                    if (sel) setBackgroundColor(W.NAVY)
                }, W.lp(W.WRAP, W.WRAP, t = 4))
                cell.layoutParams = AbsListView.LayoutParams(W.dp(100), W.dp(104))
                return cell
            }
        }
        attachGestures(grid)
        if (rows.isEmpty()) return empty(grid)
        return grid
    }

    private fun empty(v: View): View {
        val f = FrameLayout(ctx)
        f.addView(v)
        f.addView(W.text(ctx, if (search != null) "見つかりませんでした。" else "このフォルダーは空です。", 14f, color = W.SHADOW).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, FrameLayout.LayoutParams(W.MATCH, W.WRAP).apply { topMargin = W.dp(64) })
        return f
    }

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault())

    private fun buildList(): View {
        val col = W.vbox(ctx).apply { setBackgroundColor(W.WINDOW) }
        val header = W.hbox(ctx)
        fun h(s: String, w: Int, weight: Float) = header.addView(W.text(ctx, s, 13f).apply {
            background = W.Bevel(W.Style.HEADER); setPadding(W.dp(6), W.dp(6), W.dp(4), W.dp(6)); isSingleLine = true
        }, W.lp(w, W.WRAP, weight))
        val inSearch = search != null
        h("名前", 0, 1.25f)
        h(if (inSearch) "場所" else "ユーザー名", 0, 0.9f)
        h("更新日時", W.dp(94), 0f)
        col.addView(header, W.lp())
        val list = ListView(ctx)
        list.divider = null
        list.selector = android.graphics.drawable.ColorDrawable(0)
        W.classicScrollbars(list)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val r = rows[p]
                val row = W.hbox(ctx).apply { setPadding(W.dp(4), 0, 0, 0); minimumHeight = W.dp(40) }
                row.addView(ImageView(ctx).apply {
                    setImageDrawable(Px.drawable(ctx, bmpFor(r, false), W.iconScale()))
                }, W.lp(W.WRAP, W.WRAP, r = 4))
                val sel = r.id == selection
                val nameBox = FrameLayout(ctx)
                nameBox.addView(W.text(ctx, label(r), 15f, color = if (sel) W.HILITE else W.TEXT).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setPadding(W.dp(3), W.dp(2), W.dp(3), W.dp(2))
                    if (sel) setBackgroundColor(W.NAVY)
                }, FrameLayout.LayoutParams(W.WRAP, W.WRAP, Gravity.CENTER_VERTICAL))
                row.addView(nameBox, W.lp(0, W.WRAP, 1.25f))
                val second = when (r) {
                    is Row.E -> if (inSearch) r.where?.name.orEmpty() else Tree.userName(r.e)
                    is Row.F -> "フォルダー"
                }
                row.addView(W.text(ctx, second, 14f, color = if (inSearch) W.SHADOW else W.TEXT).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(W.dp(6), 0, W.dp(2), 0)
                }, W.lp(0, W.WRAP, 0.9f))
                val t = when (r) { is Row.E -> r.e.times?.lastModificationTime; is Row.F -> r.g.times?.lastModificationTime }
                row.addView(W.text(ctx, t?.let { dateFmt.format(it) } ?: "", 13f).apply {
                    isSingleLine = true; setPadding(W.dp(6), 0, 0, 0)
                }, W.lp(W.dp(94), W.WRAP))
                return row
            }
        }
        attachGestures(list)
        col.addView(if (rows.isEmpty()) empty(list) else list, W.lp(W.MATCH, 0, 1f))
        return col
    }

    // ------------------------------------------------------------------ navigation

    fun navigate(id: UUID, push: Boolean = true) {
        if (search == null && id == current) return
        if (push) { history.add(if (search != null) current else current); if (history.size > 50) history.removeAt(0) }
        search = null
        current = id
        selection = null
        refresh()
    }

    fun goUp() {
        if (search != null) { search = null; refresh(); return }
        val p = Tree.parentOf(Tree.root(db()), current) ?: return
        val from = current
        navigate(p.uuid)
        selection = from; refresh()
    }

    fun goBack() {
        if (search != null) { search = null; refresh(); return }
        val prev = history.removeLastOrNull() ?: return
        if (Tree.findGroup(db(), prev) != null) navigate(prev, push = false) else goBack()
    }

    override fun onBack(): Boolean {
        if (search != null) { search = null; refresh(); return true }
        if (current != Tree.root(db()).uuid) { goUp(); return true }
        return false
    }

    fun open(r: Row) = when (r) {
        is Row.F -> navigate(r.g.uuid)
        is Row.E -> a.show(EditorScreen(a, r.e.uuid, null, false))
    }

    fun searchFor(q: String) {
        search = q.trim().ifEmpty { null }
        selection = null
        refresh()
    }

    private fun browseFolders() {
        val d = db()
        var dlg: ClassicDialog? = null
        val tree = FolderTree(ctx, Tree.root(d), current, Tree.recycleBinId(d),
            onToggle = { id, exp -> Session.update { Tree.setExpanded(it, id, exp) } },
            onSelect = { id -> dlg?.dismiss(); navigate(id) })
        dlg = ClassicDialog(ctx, "フォルダーの参照", tree, listOf(ClassicDialog.Btn("閉じる")), scroll = true)
        dlg.show()
    }

    // ------------------------------------------------------------------ menus

    private fun fileMenu() = listOf(
        Menus.Item("新しいフォルダー", search == null) { newFolder() },
        Menus.Item("新しいメモ", search == null) { newEntry(memo = true) },
        Menus.Item("新しいログイン情報", search == null) { newEntry(memo = false) },
        Menus.sep(),
        Menus.Item("バックアップを作成", shortcut = "1-タップ") { quickBackup() },
        Menus.Item("バックアップの一覧...") { a.show(BackupScreen(a)) },
        Menus.sep(),
        Menus.Item("プロパティ") { folderProperties(currentGroup()) },
        Menus.sep(),
        Menus.Item("ロック") { a.lock() },
        Menus.Item("閉じる") { Session.lock(); a.explorer = null; a.finish() }
    )

    private fun editMenu(): List<Menus.Item> {
        val sel = rows.firstOrNull { it.id == selection }
        return listOf(
            Menus.Item("切り取り", sel != null) { sel?.let { cut(it.id) } },
            Menus.Item("貼り付け", cut != null && search == null) { paste(current) },
            Menus.Item("削除", sel != null) { sel?.let { askDelete(it.id) } },
            Menus.Item("名前の変更", sel != null) { sel?.let { rename(it) } },
            Menus.sep(),
            Menus.Item("上へ移動", sel != null && search == null) { sel?.let { moveBy(it.id, -1) } },
            Menus.Item("下へ移動", sel != null && search == null) { sel?.let { moveBy(it.id, +1) } },
            Menus.Item("フォルダーへ移動...", sel != null) { sel?.let { moveToDialog(it.id) } },
            Menus.sep(),
            Menus.Item("ユーザー名をコピー", sel is Row.E && Tree.userName(sel.e).isNotEmpty()) { (sel as? Row.E)?.let { copyUser(it.e) } },
            Menus.Item("パスワードをコピー", sel is Row.E && Tree.password(sel.e).isNotEmpty()) { (sel as? Row.E)?.let { copyPassword(it.e) } },
            Menus.sep(),
            Menus.Item("検索...") { askSearch() }
        )
    }

    private fun viewMenu(): List<Menus.Item> {
        val g = currentGroup()
        val m = Tree.viewMode(g)
        return listOf(
            Menus.Item("大きいアイコン", search == null, checked = m == "icons") { setView("icons") },
            Menus.Item("一覧", search == null, checked = m == "list") { setView("list") },
            Menus.Item("自動 (サブフォルダーがあればアイコン)", search == null, checked = m == null) { setView(null) },
            Menus.sep(),
            Menus.Item("フォルダーの参照...") { browseFolders() },
            Menus.Item("最新の情報に更新") { refresh() }
        )
    }

    private fun setView(mode: String?) {
        Session.update { Tree.setViewMode(it, current, mode) }
        refresh()
    }

    private fun toolsMenu(): List<Menus.Item> {
        val p = App.prefs(ctx)
        val lockMin = a.autoLockMinutes()
        val bin = Tree.recycleBinId(db())?.let { Tree.findGroup(db(), it) }
        return listOf(
            Menus.Item("検索...") { askSearch() },
            Menus.Item("ごみ箱を空にする", bin != null && (bin.groups.isNotEmpty() || bin.entries.isNotEmpty())) { askEmptyBin() },
            Menus.sep(),
            Menus.Item("マスターパスワードの変更...") { Security.changePassword(a) { refresh() } },
            Menus.Item(if (a.vault.hasStoredKey()) "指紋認証を無効にする" else "指紋認証を有効にする...", a.vault.isAvailable() || a.vault.hasStoredKey()) {
                Security.toggleBiometric(a) { updateStatus() }
            },
            Menus.Item("画面を保護 (スクリーンショット禁止)", checked = p.getBoolean("secure", true)) {
                p.edit().putBoolean("secure", !p.getBoolean("secure", true)).apply(); a.applySecure()
            },
            Menus.sep(),
            Menus.Item("自動ロック: アプリを離れたらすぐ", checked = lockMin == 0) { p.edit().putInt("autolock", 0).apply() },
            Menus.Item("自動ロック: 1 分後", checked = lockMin == 1) { p.edit().putInt("autolock", 1).apply() },
            Menus.Item("自動ロック: 5 分後", checked = lockMin == 5) { p.edit().putInt("autolock", 5).apply() },
            Menus.Item("自動ロック: 30 分後", checked = lockMin == 30) { p.edit().putInt("autolock", 30).apply() },
            Menus.Item("自動ロック: しない", checked = lockMin < 0) { p.edit().putInt("autolock", -1).apply() }
        )
    }

    private fun helpMenu() = listOf(
        Menus.Item("使い方") { Help.usage(a) },
        Menus.Item("データベースの情報") { Help.dbInfo(a) },
        Menus.sep(),
        Menus.Item("バージョン情報") { Help.about(a) }
    )

    fun itemMenu(r: Row): List<Menus.Item> {
        val d = db()
        val inSearch = search != null
        val common = listOf(
            Menus.sep(),
            Menus.Item("切り取り", !(r is Row.F && r.isBin)) { cut(r.id) },
            Menus.Item("削除") { askDelete(r.id) },
            Menus.Item("名前の変更", !(r is Row.F && r.isBin)) { rename(r) },
            Menus.sep(),
            Menus.Item("上へ移動", !inSearch && Tree.indexInParent(d, r.id) > 0) { moveBy(r.id, -1) },
            Menus.Item("下へ移動", !inSearch && Tree.indexInParent(d, r.id) < Tree.siblingCount(d, r.id) - 1) { moveBy(r.id, +1) },
            Menus.Item("先頭へ移動", !inSearch && Tree.indexInParent(d, r.id) > 0) { moveTo(r.id, 0) },
            Menus.Item("末尾へ移動", !inSearch && Tree.indexInParent(d, r.id) < Tree.siblingCount(d, r.id) - 1) { moveTo(r.id, Int.MAX_VALUE) },
            Menus.Item("フォルダーへ移動...", !(r is Row.F && r.isBin)) { moveToDialog(r.id) },
            Menus.sep()
        )
        return when (r) {
            is Row.E -> listOf(
                Menus.Item("開く") { open(r) },
                Menus.Item("ユーザー名をコピー", Tree.userName(r.e).isNotEmpty()) { copyUser(r.e) },
                Menus.Item("パスワードをコピー", Tree.password(r.e).isNotEmpty()) { copyPassword(r.e) },
            ) + (if (inSearch) listOf(Menus.Item("このエントリのフォルダーを開く") { r.where?.let { navigate(it.uuid); selection = r.id; refresh() } }) else emptyList()) +
                common + Menus.Item("プロパティ") { a.show(EditorScreen(a, r.e.uuid, null, false, startTab = 1)) }
            is Row.F -> listOf(
                Menus.Item("開く") { open(r) },
                Menus.Item("貼り付け", cut != null && cut != r.id) { paste(r.g.uuid) },
            ) + (if (r.isBin) listOf(Menus.Item("ごみ箱を空にする", r.g.groups.isNotEmpty() || r.g.entries.isNotEmpty()) { askEmptyBin() }) else emptyList()) +
                common + Menus.Item("プロパティ") { folderProperties(r.g) }
        }
    }

    fun backgroundMenu(): List<Menus.Item> {
        val m = Tree.viewMode(currentGroup())
        val ns = search == null
        return listOf(
            Menus.Item("表示: 大きいアイコン", ns, checked = m == "icons") { setView("icons") },
            Menus.Item("表示: 一覧", ns, checked = m == "list") { setView("list") },
            Menus.Item("表示: 自動", ns, checked = m == null) { setView(null) },
            Menus.sep(),
            Menus.Item("貼り付け", cut != null && ns) { paste(current) },
            Menus.sep(),
            Menus.Item("新しいフォルダー", ns) { newFolder() },
            Menus.Item("新しいメモ", ns) { newEntry(true) },
            Menus.Item("新しいログイン情報", ns) { newEntry(false) },
            Menus.sep(),
            Menus.Item("プロパティ") { folderProperties(currentGroup()) }
        )
    }

    private fun newEntryMenu(anchor: View) {
        Menus.dropDown(anchor, listOf(
            Menus.Item("テキスト文書 (メモ)") { newEntry(true) },
            Menus.Item("ログイン情報 (ユーザー名・パスワード)") { newEntry(false) }
        ))
    }

    // ------------------------------------------------------------------ operations

    fun newFolder() {
        val g = currentGroup()
        val name = Tree.uniqueName(g.groups.map { it.name }, "新しいフォルダー")
        Dlg.prompt(ctx, "新しいフォルダー", "フォルダー名:", name) { n ->
            val nn = n.trim().ifEmpty { name }
            val ng = Tree.newGroup(nn)
            Session.update { Tree.addGroup(it, current, ng) }
            selection = ng.uuid
            refresh()
            true
        }
    }

    fun newEntry(memo: Boolean) = a.show(EditorScreen(a, null, current, memo))

    fun rename(r: Row) {
        Dlg.prompt(ctx, "名前の変更", "新しい名前:", label(r).let { if (it == "(無題)" || it == "(名前なし)") "" else it }) { n ->
            when (r) {
                is Row.F -> Session.update { Tree.renameGroup(it, r.g.uuid, n) }
                is Row.E -> Session.update { Tree.updateEntry(it, r.e.uuid, Tree.fieldsOf(r.e).copy(title = n)) }
            }
            refresh(); true
        }
    }

    fun cut(id: UUID) {
        cut = id
        refresh()
        Toast.makeText(ctx, "切り取りました。移動先のフォルダーで「貼り付け」を選んでください。", Toast.LENGTH_SHORT).show()
    }

    fun paste(target: UUID) {
        val id = cut ?: return
        try {
            Session.update { Tree.move(it, id, target) }
            cut = null
            selection = id
            refresh()
        } catch (e: Tree.TreeException) {
            Dlg.message(ctx, "フォルダーの移動エラー", "移動できません: 移動先のフォルダーは、移動元のフォルダーのサブフォルダーです。", 'x')
        }
    }

    fun moveBy(id: UUID, delta: Int) = moveTo(id, Tree.indexInParent(db(), id) + delta)

    fun moveTo(id: UUID, index: Int) {
        Session.update { Tree.reorder(it, id, index) }
        selection = id
        refresh()
    }

    fun moveToDialog(id: UUID) {
        val d = db()
        var target: UUID? = null
        val tree = FolderTree(ctx, Tree.root(d), null, Tree.recycleBinId(d), disabled = setOf(id),
            onToggle = { gid, exp -> Session.update { Tree.setExpanded(it, gid, exp) } },
            onSelect = { target = it })
        val name = rows.firstOrNull { it.id == id }?.let { label(it) } ?: ""
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, "「$name」の移動先を選んでください。", 15f), W.lp(W.MATCH, W.WRAP, b = 8))
        col.addView(FrameLayout(ctx).apply { background = W.Bevel(W.Style.FIELD); setPadding(2 * W.px, 2 * W.px, 2 * W.px, 2 * W.px); addView(tree) })
        ClassicDialog(ctx, "フォルダーへ移動", col, listOf(
            ClassicDialog.Btn("移動", true) {
                val t = target ?: return@Btn false
                try { Session.update { Tree.move(it, id, t) }; refresh(); true }
                catch (e: Tree.TreeException) {
                    Dlg.message(ctx, "フォルダーの移動エラー", "移動できません: 移動先のフォルダーは、移動元のフォルダーのサブフォルダーです。", 'x'); false
                }
            },
            ClassicDialog.Btn("キャンセル")), scroll = true).show()
    }

    fun askDelete(id: UUID) {
        val d = db()
        val r = rows.firstOrNull { it.id == id } ?: return
        val toBin = d.content.meta.recycleBinEnabled && !Tree.isInRecycleBin(d, id)
        val what = if (r is Row.F) "フォルダー「${label(r)}」とその内容" else "「${label(r)}」"
        val msg = if (toBin) "$what をごみ箱に移動しますか?" else "$what を完全に削除しますか?\nこの操作は元に戻せません。"
        Dlg.confirm(ctx, if (r is Row.F) "フォルダーの削除の確認" else "エントリの削除の確認", msg, if (toBin) '?' else '!') {
            Session.update { Tree.delete(it, id).first }
            if (cut == id) cut = null
            selection = null
            refresh()
        }
    }

    private fun askEmptyBin() {
        Dlg.confirm(ctx, "ごみ箱を空にする", "ごみ箱の中身をすべて完全に削除しますか?\nこの操作は元に戻せません。", '!') {
            Session.update { Tree.emptyRecycleBin(it) }
            refresh()
        }
    }

    fun copyUser(e: Entry) {
        Clip.copy(ctx, "ユーザー名", Tree.userName(e), false)
        Toast.makeText(ctx, "ユーザー名をコピーしました", Toast.LENGTH_SHORT).show()
    }

    fun copyPassword(e: Entry) {
        Clip.copy(ctx, "パスワード", Tree.password(e), true)
        Toast.makeText(ctx, "パスワードをコピーしました (${Clip.CLEAR_MS / 1000} 秒後に消去)", Toast.LENGTH_SHORT).show()
    }

    private fun askSearch() {
        Dlg.prompt(ctx, "検索", "探す文字列 (タイトル・ユーザー名・URL・メモ・タグ。#タグ で完全一致):", search ?: "", ok = "検索開始") { q ->
            searchFor(q); true
        }
    }

    fun quickBackup() {
        val busy = Dlg.busy(ctx, "バックアップ", "バックアップを作成しています…")
        Session.flush()
        Session.io.execute {
            val res = runCatching { a.store.backup() }
            a.runOnUiThread {
                busy.dismiss()
                res.onSuccess { f ->
                    statusLeft.text = "バックアップを作成しました: ${f.name}"
                    Toast.makeText(ctx, "バックアップ: ${f.name}", Toast.LENGTH_SHORT).show()
                }.onFailure { Dlg.message(ctx, "バックアップ", "バックアップを作成できませんでした。\n$it", 'x') }
            }
        }
    }

    private fun folderProperties(g: Group) {
        val d = db()
        val f = DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm:ss").withZone(ZoneId.systemDefault())
        val t = g.times
        val path = Tree.path(Tree.root(d), g.uuid).joinToString("\\") { it.name }
        val body = W.vbox(ctx)
        val head = W.hbox(ctx)
        head.addView(W.icon(ctx, if (g.uuid == Tree.recycleBinId(d)) Px.bin32 else Px.folder32), W.lp(W.WRAP, W.WRAP, r = 12))
        head.addView(W.text(ctx, g.name, 16f, true))
        body.addView(head)
        body.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 8, b = 8))
        fun kv(k: String, v: String) {
            val r = W.hbox(ctx).apply { gravity = Gravity.TOP }
            r.addView(W.text(ctx, k, 14f), W.lp(W.dp(96), W.WRAP))
            r.addView(W.text(ctx, v, 14f).apply { setTextIsSelectable(true) }, W.lp(0, W.WRAP, 1f))
            body.addView(r, W.lp(W.MATCH, W.WRAP, b = 6))
        }
        kv("種類:", "フォルダー (KeePass グループ)")
        kv("場所:", path)
        kv("内容:", "フォルダー ${g.groups.size}、エントリ ${g.entries.size} (全体で ${Tree.countEntries(g)} エントリ)")
        body.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 4, b = 8))
        kv("作成日時:", t?.creationTime?.let { f.format(it) } ?: "-")
        kv("更新日時:", t?.lastModificationTime?.let { f.format(it) } ?: "-")
        kv("場所の変更:", t?.locationChanged?.let { f.format(it) } ?: "-")
        kv("展開状態:", if (g.expanded) "展開 (IsExpanded)" else "折りたたみ")
        kv("UUID:", g.uuid.toString())
        ClassicDialog(ctx, "${g.name} のプロパティ", body, listOf(ClassicDialog.Btn("OK", true)), scroll = true).show()
    }
}
