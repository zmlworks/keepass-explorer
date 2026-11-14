package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.FolderTree
import io.github.zmlworks.kpexplorer.ui.Ico
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.Parts
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W
import java.util.UUID
import kotlin.math.abs

/**
 * Main window: navy title bar, toolbar (戻る / 上へ / 表示 / 新しいフォルダ / 新しい鍵 / 新しいメモ / 保存 / 検索 / メニュー),
 * "└" path tree, item pane (5-column icons or details) and, once something is selected, a lower preview pane.
 * Folder: tap = open. Key / memo: tap = select + preview, tap the selected item again = open.
 * Long-press an item then move = drag; scroll from a short swipe, empty space, or the scrollbar strip on the right.
 * Long press without moving = context menu.
 */
class ExplorerScreen(private val a: MainActivity) : MainActivity.Screen {
    private val ctx = a
    var current: UUID = Tree.root(db()).uuid
        private set
    private val history = ArrayList<UUID>()
    var selection: UUID? = null
    var cut: UUID? = null
    var search: String? = null
        private set
    /** 0.4.23: 「下部ファイルをすべて表示」 — every key / memo below [current] is listed (tree long-press menu). */
    var flat = false
        private set

    private val frame = FrameLayout(ctx)
    private val root = W.vbox(ctx).apply { setBackgroundColor(W.FACE) }
    override val view: View get() = frame
    lateinit var caption: W.Caption
        private set
    private val pathBox = FrameLayout(ctx)
    /** 0.4.23: shown under the path tree in 「下部ファイルをすべて表示」; tap = back to the folder view. */
    private val flatBand = W.text(ctx, "", 14f, true, W.NAVY).apply {
        tag = "flat-band"; setPadding(W.dp(8), W.dp(5), W.dp(8), W.dp(5)); setBackgroundColor(W.FACE); visibility = View.GONE
        contentDescription = L.t("フォルダーの表示に戻す", "Back to the folder view"); isClickable = true
        setOnClickListener { exitFlat() }
    }
    private val tempBanner = W.hbox(ctx).apply {
        setPadding(W.dp(8), W.dp(6), W.dp(8), W.dp(6))
        setBackgroundColor(W.BAND_SEPIA)
        visibility = View.GONE
        gravity = Gravity.CENTER_VERTICAL
    }
    private val tempBannerText = W.text(ctx, "", 14f, true, W.HILITE).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE }
    /** Inline search panel (text + tag toggles); results are the list below, updated live. */
    val searchPanel = W.vbox(ctx, 6).apply { background = W.Bevel(W.Style.RAISED) }
    lateinit var searchField: android.widget.EditText
        private set
    private val searchTagHolder = FrameLayout(ctx)
    private val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveRun = Runnable { applyLiveSearch() }
    val pathLines = ArrayList<View>()
    private val content = FrameLayout(ctx)
    private val preview = PreviewBox(ctx) { onPreviewTap() }.apply {
        orientation = LinearLayout.VERTICAL; setPadding(W.dp(8), W.dp(8), W.dp(8), W.dp(8))
    }
    /** 0.4.11: the item whose preview was tapped once (next tap on the preview opens it). */
    var previewActive: UUID? = null
        private set

    private lateinit var previewScroll: PreviewScroll
    private lateinit var split: View
    private var shownSaveError: String? = null
    private val tools = HashMap<String, View>()
    private var listView: AbsListView? = null
    /** Folder, search, flat and view mode of the list now shown (0.4.27: same = keep the scroll position). */
    private var shownKey: List<Any?>? = null
    private var shownEmpty = false
    private val ghost = ImageView(ctx).apply { visibility = View.GONE; alpha = 0.75f }

    companion object {
        const val LIVE_MS = 150L
        const val PREF_COL_NAME = "detailColNameDp"; const val PREF_COL_USER = "detailColUserDp"
        const val MIN_NAME_DP = 64; const val MIN_USER_DP = 40; const val MIN_DATE_DP = 28
        const val DRAG_START_MM = 8f
        /** 0.4.20: a drop goes into a folder only between these fractions of the cell (width in icons, height in details). */
        const val INTO_FROM = 0.30f
        const val INTO_TO = 0.70f
        /** 0.4.13: preview frame dashes (longer than 0.4.12's 1px dots). */
        const val DASH_ON_DP = 4f
        const val DASH_OFF_DP = 3f
        /** 0.4.11 used 0xFFE8EEF8; 0.4.12 a touch deeper. */
        const val PREVIEW_SELECTED_BG = 0xFFDDE6F4.toInt()
    }

    var rows: List<Order.Node> = emptyList()
        private set
    val iconMode get() = App.prefs(ctx).getString("view", "icons") == "icons" && search == null

    private fun db(): KeePassDatabase = Session.db ?: throw IllegalStateException("locked")

    init {
        val last = App.prefs(ctx).getString("lastGroup", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (last != null && Tree.findGroup(db(), last) != null) current = last
        build()
        Session.listener = { a.runOnUiThread { updateStatus() } }
    }

    // ------------------------------------------------------------------ layout

    private fun build() {
        caption = W.titleBar(ctx, "", Px.key16, onMin = { a.moveTaskToBack(true) }, onClose = { a.lock() })
        // no caption bar (full window); the caption object only keeps the window title for accessibility / tests

        val tb = W.hbox(ctx).apply { setPadding(W.dp(1), W.dp(2), W.dp(1), W.dp(2)) }
        val dm = ctx.resources.displayMetrics
        val bw = maxOf(W.dp(40), (dm.widthPixels - W.dp(24)) / 8)
        fun tool(key: String, bmp: Bitmap, label: String, f: () -> Unit) {
            val v = W.vbox(ctx).apply {
                gravity = Gravity.CENTER
                background = W.Bevel(W.Style.FLAT_TOOL); contentDescription = label; tooltipText = label
                isClickable = true; setOnClickListener { f() }
                setPadding(0, W.dp(3), 0, W.dp(2))
            }
            v.addView(ImageView(ctx).apply { setImageDrawable(Px.drawable(ctx, bmp, maxOf(1, W.iconScale() - 1))); scaleType = ImageView.ScaleType.CENTER })
            v.addView(W.text(ctx, label, 10f).apply { isSingleLine = true; gravity = Gravity.CENTER }, W.lp(W.WRAP, W.WRAP, t = 1))
            tools[key] = v
            tb.addView(v, W.lp(bw, W.dp(50)))
        }
        fun sep() = tb.addView(View(ctx).apply { background = W.Bevel(W.Style.THIN_SUNKEN) }, W.lp(2 * W.px + W.px, W.dp(40), l = 3, r = 3))
        tool("up", Px.up16, L.t("上へ", "Up")) { goUp() }
        tool("view", if (iconMode) Ico.viewDetails16 else Ico.viewIcons16, L.t("表示", "View")) { toggleView() }
        sep()
        tool("newFolder", Px.newFolder16, L.t("フォルダ", "Folder")) { askNewFolder() }
        tool("newKey", Ico.newKey16, L.t("鍵", "Key")) { newKey() }
        tool("newMemo", Ico.newMemo16, L.t("メモ", "Memo")) { askNewMemo() }
        sep()
        tool("save", Px.floppy16, L.t("保存", "Save")) { Security.saveDialog(a) }
        tool("menu", Ico.menu16, L.t("メニュー", "Menu")) { Menus.dropDown(tools["menu"]!!, mainMenu()) }
        tool("find", Px.find16, L.t("検索", "Search")) { if (search == null) openSearch() else closeSearch() }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tb) }, W.lp())
        root.addView(W.hsep(ctx))
        tempBanner.removeAllViews()
        tempBanner.addView(tempBannerText, W.lp(0, W.WRAP, 1f))
        tempBanner.addView(W.text(ctx, ctx.getString(R.string.temp_exit), 13f, true, W.HILITE).apply {
            setPadding(W.dp(8), W.dp(4), W.dp(8), W.dp(4))
            background = W.Bevel(W.Style.BUTTON)
            isClickable = true
            setOnClickListener { TempImport.exitTemp(a) }
        }, W.lp(W.WRAP, W.WRAP, l = 6))
        root.addView(tempBanner, W.lp())

        searchPanel.visibility = View.GONE
        root.addView(searchPanel, W.lp(W.MATCH, W.WRAP, l = 2, r = 2, t = 2))
        pathBox.background = W.Bevel(W.Style.FIELD)
        root.addView(pathBox, W.lp(W.MATCH, W.WRAP, l = 2, r = 2, t = 2, b = 2))
        root.addView(flatBand, W.lp(W.MATCH, W.WRAP, l = 2, r = 2, b = 2))

        content.background = W.Bevel(W.Style.FIELD)
        content.setPadding(2 * W.px, 2 * W.px, 2 * W.px, 2 * W.px)
        root.addView(content, W.lp(W.MATCH, 0, 1.15f, l = 2, r = 2))
        content.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot && selection != null) content.post { ensureSelectionVisible() }
        }

        // splitter (drag to resize the two panes)
        split = View(ctx).apply { background = W.Bevel(W.Style.RAISED) }
        root.addView(split, W.lp(W.MATCH, W.dp(9), l = 2, r = 2))
        var startY = 0f; var startW = 0f
        split.setOnTouchListener { _, e ->
            val lpC = content.layoutParams as LinearLayout.LayoutParams
            val lpP = previewScroll.layoutParams as LinearLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startW = lpC.weight }
                MotionEvent.ACTION_MOVE -> {
                    val total = (content.height + previewScroll.height).coerceAtLeast(1)
                    val sum = lpC.weight + lpP.weight
                    val w = (startW + (e.rawY - startY) / total * sum).coerceIn(0.25f * sum, 0.85f * sum)
                    lpC.weight = w; lpP.weight = sum - w; root.requestLayout()
                }
            }
            true
        }

        previewScroll = PreviewScroll(ctx).apply { background = W.Bevel(W.Style.FIELD); W.classicScrollbars(this); isFillViewport = true }
        previewScroll.addView(preview)
        preview.setBackgroundColor(W.WINDOW)
        root.addView(previewScroll, W.lp(W.MATCH, 0, 0.85f, l = 2, r = 2))

        frame.addView(root, FrameLayout.LayoutParams(W.MATCH, W.MATCH))
        frame.addView(ghost, FrameLayout.LayoutParams(W.WRAP, W.WRAP))
    }

    // ------------------------------------------------------------------ refresh

    fun currentGroup(): Group = Tree.findGroup(db(), current) ?: Tree.root(db()).also { current = it.uuid }

    fun refresh() {
        if (!Session.isOpen) return
        val d = db()
        val g = currentGroup()
        val q = search
        val path = Tree.path(Tree.root(d), g.uuid)
        // 0.4.27: up to 0.4.26 every refresh built a new grid / list (and path tree), which starts at the top, so a
        // reorder or a drop scrolled back to the top. Now the same folder / search / view keeps its list view and
        // only reloads the rows (the adapters read [rows]); the rebuilt path tree gets its old scroll offset back.
        val key = listOf<Any?>(g.uuid, q, flat, iconMode, L.dateOrder, W.iconScale(), L.t("ja", "en"))
        val same = key == shownKey
        val keepTreeY = if (same) (pathBox.getChildAt(0) as? ScrollView)?.scrollY else null
        // 0.4.31: the path tree's offset before this refresh (null = no tree shown, e.g. search results)
        val treeY = (pathBox.getChildAt(0) as? ScrollView)?.scrollY
        shownKey = key
        pathBox.removeAllViews(); pathLines.clear()
        if (q != null) {
            rows = Tree.search(d, q).map { Order.Node.E(it.second) }
            caption.title.text = L.t("検索結果 - KeePass Explorer", "Search Results - KeePass Explorer")
            val ws = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val tg = ws.filter { it.startsWith("#") && it.length > 1 }.map { it.substring(1) }
            val tx = ws.filterNot { it.startsWith("#") && it.length > 1 }
            val desc = listOfNotNull(tx.takeIf { it.isNotEmpty() }?.let { L.t("文字: ${it.joinToString(" ")}", "Text: ${it.joinToString(" ")}") },
                tg.takeIf { it.isNotEmpty() }?.let { L.t("タグ: ${it.joinToString(" + ")}", "Tags: ${it.joinToString(" + ")}") }).joinToString("  ")
            // 0.4.27: a 「検索結果」 header above the results on pale blue (the tree's stripe), with the count once searching
            pathBox.addView(W.text(ctx, if (q.isEmpty()) L.t("検索結果", "Search results") else L.t("検索結果 ${rows.size} 件  $desc", "Search results: ${rows.size}  $desc"), 15f, true).apply {
                tag = "search-head"; setBackgroundColor(io.github.zmlworks.kpexplorer.ui.STRIPE); setPadding(W.dp(8), W.dp(6), W.dp(8), W.dp(6)) }, W.lp(W.MATCH, W.WRAP))
        } else {
            val hidden = Tree.hiddenGroups(d, g.uuid)
            val bin = Tree.recycleBinId(d)
            rows = if (flat) Order.subtreeEntries(g, if (bin != null && g.uuid != bin) setOf(bin) else emptySet())
                else Order.children(g).filter { it.uuid !in hidden }
            caption.title.text = if (flat) L.t("${groupLabel(g)} 以下すべて - KeePass Explorer", "All under ${groupLabel(g)} - KeePass Explorer")
                else "${groupLabel(g)} - KeePass Explorer"
            val tree = FolderTree(ctx, Tree.root(d), g.uuid, Tree.recycleBinId(d), hidden = hidden,
                onToggle = { gid, exp -> if (!Session.readOnlyTemp) Session.update { Tree.setExpanded(it, gid, exp) } else tempExpanded[gid] = exp },
                onSelect = { }, showCounts = true, current = g.uuid, onJump = { navigate(it) }, rowDp = 32,
                onLongPress = { gid, x, y -> pathLongPress(gid, x, y) }, cushion = { cushionTap() }, striped = true, binLast = Tree.binLastInTree(d), toggleSelected = !flat,
                expandedOverride = (if (Session.readOnlyTemp) tempExpanded else emptyMap()) + (if (same) folderTree?.expandedState().orEmpty() else emptyMap()),
                revealCurrent = !same)
            folderTree = tree
            pathLines.addAll(tree.rows)
            tree.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(p: View?, c: View?) { pathLines.clear(); pathLines.addAll(tree.rows) }
                override fun onChildViewRemoved(p: View?, c: View?) {}
            })
            val treeBox = MaxScroll(ctx, (ctx.resources.displayMetrics.heightPixels * 0.3f).toInt()).apply {
                W.classicScrollbars(this); addView(tree) }
            pathBox.addView(treeBox)
            // placed in its first layout, before it is drawn (a post could show one frame at the top first)
            treeBox.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, orr: Int, ob: Int) {
                    v.removeOnLayoutChangeListener(this)
                    placeTree(treeBox, tree, g.uuid, keepTreeY ?: treeY, keep = keepTreeY != null)
                }
            })
            flatBand.visibility = if (flat) View.VISIBLE else View.GONE
            if (flat) flatBand.text = L.t("「${groupLabel(g)}」以下の鍵・メモをすべて表示中 (${rows.size} 件)  ×", "All keys and memos under \"${groupLabel(g)}\" (${rows.size})  ×")
            App.prefs(ctx).edit().putString("lastGroup", g.uuid.toString()).apply()
        }
        searchPanel.visibility = if (q != null) View.VISIBLE else View.GONE
        if (q != null) flatBand.visibility = View.GONE
        if (selection != null && rows.none { it.uuid == selection }) selection = null
        if (cut != null && Tree.parentOf(Tree.root(d), cut!!) == null) cut = null
        val lv = listView
        // (an empty folder shows a note instead of the list, so a change to or from empty builds it again)
        if (same && rows.isEmpty() == shownEmpty && lv != null && generateSequence(lv.parent) { it.parent }.any { it === content }) notifyList()
        else {
            content.removeAllViews()
            content.addView(if (iconMode) buildGrid() else buildList(), FrameLayout.LayoutParams(W.MATCH, W.MATCH))
        }
        shownEmpty = rows.isEmpty()
        (tools["view"] as? LinearLayout)?.getChildAt(0)?.let {
            (it as ImageView).setImageDrawable(Px.drawable(ctx, if (iconMode) Ico.viewDetails16 else Ico.viewIcons16, maxOf(1, W.iconScale() - 1)))
        }
        updateTools()
        updatePreview()
        updateStatus()
    }

    private fun updateTools() {
        val d = db()
        fun en(k: String, on: Boolean) {
            val v = tools[k] as? LinearLayout ?: return
            v.isEnabled = on
            v.alpha = if (on) 1f else 0.45f
            (v.getChildAt(0) as ImageView).colorFilter = if (on) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val ro = Session.readOnlyTemp
        val isRoot = current == Tree.root(d).uuid && search == null
        en("back", history.isNotEmpty() || search != null || flat)
        en("up", !isRoot || flat)
        en("newFolder", search == null && !flat && !ro); en("newKey", search == null && !ro); en("newMemo", search == null && !ro)
        en("save", !ro)
        // Temp banner
        if (ro) {
            tempBanner.visibility = View.VISIBLE
            tempBanner.setBackgroundColor(W.BAND_SEPIA)
            tempBannerText.text = ctx.getString(R.string.read_only_banner, Session.tempSourceLabel)
        } else {
            tempBanner.visibility = View.GONE
        }
    }

    /** No status bar any more: only a save error is surfaced (once per distinct error). */
    fun updateStatus() {
        if (!Session.isOpen) return
        val err = Session.lastError
        if (Session.saveState == Session.SaveState.ERROR && err != null && err != shownSaveError) {
            shownSaveError = err
            Dlg.message(ctx, L.t("保存エラー", "Save Error"), L.t("変更を保存できませんでした。\n$err", "Could not save the changes.\n$err"), 'x')
        }
    }

    /** The root folder has no name and is shown as "/". */
    fun groupLabel(g: Group): String = if (g.uuid == Tree.root(db()).uuid) "/" else g.name.ifEmpty { L.t("(名前なし)", "(no name)") }

    fun label(r: Order.Node): String = r.name.ifEmpty { if (r is Order.Node.F) L.t("(名前なし)", "(no name)") else L.t("(無題)", "(Untitled)") }

    private fun isBin(r: Order.Node): Boolean {
        val d = db()
        val bin = Tree.recycleBinId(d) ?: return false
        return r.uuid == bin || Tree.isInRecycleBin(d, r.uuid)
    }
    private fun isRecycleBinFolder(r: Order.Node): Boolean {
        val bin = Tree.recycleBinId(db()) ?: return false
        return r.uuid == bin
    }

    var folderTree: FolderTree? = null
        private set

    /**
     * 0.4.10: long press in the path tree. Only the recycle bin has a menu there (the same one as its icon in
     * the list: 開く / ごみ箱を空にする); other folders keep tap-to-open on release.
     */
    /**
     * 0.4.23: long press on a folder of the path tree: 開く (= one tap) and 下部ファイルをすべて表示; the recycle bin
     * also offers 空にする.
     */
    /** 0.4.31: folder-tree open / closed state of a read-only browse (not written to that file), by folder. */
    private val tempExpanded = HashMap<UUID, Boolean>()

    /**
     * Scroll of a rebuilt path tree. [y] = the old tree's offset: kept as it was ([keep]: same folder), or (0.4.31)
     * kept and moved only as far as needed to show the current folder [cur]. Up to 0.4.30 every change of folder
     * scrolled the current folder up to the top, so a folder tapped in the tree jumped away from the finger.
     * No old tree (first show, after a search): the current folder near the top, as before.
     */
    private fun placeTree(sv: ScrollView, tree: FolderTree, cur: UUID, y: Int?, keep: Boolean) {
        val row = tree.rows.firstOrNull { it.tag == cur }
        if (y == null) { row?.let { sv.scrollTo(0, maxOf(0, it.top - W.dp(32))) }; return }
        sv.scrollTo(0, y)
        if (keep || row == null) return
        val h = sv.height - sv.paddingTop - sv.paddingBottom
        val top = tree.top + row.top; val bottom = tree.top + row.bottom
        if (top < sv.scrollY) sv.scrollTo(0, top)
        else if (bottom > sv.scrollY + h) sv.scrollTo(0, bottom - h)
    }

    fun pathLongPress(gid: UUID, x: Int, y: Int): Boolean {
        // 0.4.31: the long-pressed folder is framed (dotted) while its menu is open
        val tree = folderTree
        tree?.menuTarget = gid
        Menus.at(pathBox, x, y, treeMenu(gid)).setOnDismissListener { if (tree?.menuTarget == gid) tree.menuTarget = null }
        return true
    }

    /**
     * 0.4.24: the path tree's folder menu, like the list's folder menu plus the commands that act "in" a folder.
     * Root: open / show all / new / paste. Recycle bin: open / show all / empty / settings.
     */
    fun treeMenu(gid: UUID): List<Menus.Item> {
        val d = db()
        val g = Tree.findGroup(d, gid) ?: return emptyList()
        val w = !Session.readOnlyTemp
        val isRoot = gid == Tree.root(d).uuid
        val inBin = Tree.isInRecycleBin(d, gid)
        val out = arrayListOf(
            Menus.Item(ctx.getString(R.string.open)) { navigate(gid) },
            Menus.Item(L.t("下部ファイルをすべて表示", "Show all files below")) { showAllBelow(gid) })
        if (gid == Tree.recycleBinId(d)) {
            out.add(Menus.sep())
            out.add(Menus.Item(ctx.getString(R.string.empty_recycle_bin), w && Tree.recycleBinChildCount(d) > 0) { askEmptyBin() })
            out.add(Menus.Item(L.t("ごみ箱の設定…", "Recycle Bin settings…"), w) { BinSettings.show(a) })
            return out
        }
        if (inBin) {   // a folder inside the bin: as in the list
            out.add(Menus.sep())
            out.add(Menus.Item(restoreLabel(gid), w) { restoreItem(gid) })
            out.add(Menus.Item(ctx.getString(R.string.delete_permanently), w) { askDelete(gid) })
            return out
        }
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.tool_new_folder), w) { navigate(gid); askNewFolder() })
        out.add(Menus.Item(ctx.getString(R.string.tool_new_key), w) { navigate(gid); newKey() })
        out.add(Menus.Item(ctx.getString(R.string.tool_new_memo), w) { navigate(gid); askNewMemo() })
        out.add(Menus.sep())
        if (!isRoot) out.add(Menus.Item(ctx.getString(R.string.rename), w) { rename(Order.Node.F(g)) })
        if (!isRoot) out.add(Menus.Item(ctx.getString(R.string.cut), w) { cut(gid) })
        out.add(Menus.Item(ctx.getString(R.string.paste_here), w && cut != null && cut != gid) { paste(gid) })
        if (!isRoot) {
            out.add(Menus.Item(ctx.getString(R.string.move_to_folder), w) { moveToDialog(gid) })
            out.add(Menus.Item(ctx.getString(R.string.move_up_folder), w && canMoveToParentFolder(gid)) { moveToParentFolder(gid) })
            out.add(Menus.sep())
            out.add(Menus.Item(ctx.getString(R.string.delete), w) { askDelete(gid) })
        }
        return out
    }
    private fun post(f: () -> Unit) { pathBox.post(f) }

    /** ScrollView with a maximum height (folder tree pane). */
    class MaxScroll(ctx: android.content.Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }

    fun bmpFor(r: Order.Node, large: Boolean): Bitmap {
        val ic = (r as? Order.Node.E)?.takeIf { it.kind == Order.Kind.KEY }?.let { io.github.zmlworks.kpexplorer.db.EntryIcon.of(it.e) }
        val sz = if (large) 32 else 16
        val b = when {
            isRecycleBinFolder(r) -> if (large) Px.bin32 else Px.bin16
            ic != null -> io.github.zmlworks.kpexplorer.ui.FugueIcons.bitmap(ctx, ic, sz)
            r.kind == Order.Kind.FOLDER -> if (large) Px.folder32 else Px.folder16
            r.kind == Order.Kind.MEMO -> if (large) Ico.notepad32 else Ico.notepad16
            else -> io.github.zmlworks.kpexplorer.ui.FugueIcons.bitmap(ctx, "key", sz)
        }
        return when {
            r.uuid == selection -> Px.selected(b)
            r.uuid == cut || r.uuid == drag?.id -> Px.ghost(b)
            else -> b
        }
    }

    // ------------------------------------------------------------------ preview pane

    /**
     * Preview container that watches taps without taking them from its children (Pass tap = show / hide,
     * selectable text, scrolling keep working). A tap = down/up within the touch slop, shorter than a long
     * press, not on the Pass value.
     */
    @android.annotation.SuppressLint("ViewConstructor")
    class PreviewBox(ctx: android.content.Context, private val onTap: () -> Unit) : LinearLayout(ctx) {
        private var x0 = 0f; private var y0 = 0f; private var t0 = 0L; private var candidate = false
        companion object {
            /** [x] (view coordinates) is on the drawn text of [t], not the empty space right of it. */
            fun onText(t: TextView, x: Float): Boolean {
                val l = t.layout ?: return true
                var w = 0f; for (i in 0 until l.lineCount) w = maxOf(w, l.getLineWidth(i))
                return x <= t.totalPaddingLeft + w + W.dp(8)
            }
        }
        private fun onPass(v: View, x: Float, y: Float): Boolean {
            if (v.tag == "pass") return v !is TextView || onText(v, x)
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                if (c.visibility == View.VISIBLE && x >= c.left && x < c.right && y >= c.top && y < c.bottom &&
                    onPass(c, x - c.left + c.scrollX, y - c.top + c.scrollY)) return true
            }
            return false
        }
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; t0 = e.eventTime; candidate = !onPass(this, e.x, e.y) }
                MotionEvent.ACTION_MOVE -> if (abs(e.x - x0) > slop || abs(e.y - y0) > slop) candidate = false
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> candidate = false
            }
            val r = super.dispatchTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && candidate) {
                candidate = false
                if (e.eventTime - t0 < W.longPressMs()) onTap()
            }
            return e.actionMasked == MotionEvent.ACTION_DOWN || r
        }
    }

    /** First tap on the preview selects it (title highlighted), the next tap opens the item, like the list. */
    fun onPreviewTap() {
        val r = rows.firstOrNull { it.uuid == selection } ?: return
        if (previewActive == r.uuid) { previewActive = null; open(r); return }
        previewActive = r.uuid
        highlightPreview()
    }

    /**
     * 0.4.27: the preview's scroll view. It draws the dotted [frame] fixed to its window. 0.4.12–0.4.26 set the
     * frame as the View foreground, which a ScrollView draws in the scrolled content's coordinates, so the frame
     * moved with the content. Drawn like the background is: shifted back by the scroll offset.
     */
    class PreviewScroll(c: android.content.Context) : ScrollView(c) {
        var frame: Drawable? = null
            set(v) { field = v; invalidate() }
        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            val f = frame ?: return
            val n = canvas.save()
            canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            f.setBounds(0, 0, width, height); f.draw(canvas)
            canvas.restoreToCount(n)
        }
    }

    /** Dotted focus frame inside the preview window (fixed to the window, not scrolled with the content). */
    class FocusFrame : Drawable() {
        private val p = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = W.px.toFloat(); color = W.TEXT
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(DASH_ON_DP * W.dp(1), DASH_OFF_DP * W.dp(1)), 0f)
        }
        override fun draw(c: Canvas) {
            val b = bounds; val i = W.dp(4) + W.px / 2f
            c.drawRect(b.left + i, b.top + i, b.right - i, b.bottom - i, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** 0.4.12: selected preview = dotted frame + a slightly deeper pale blue; the name is not inverted. */
    private fun highlightPreview() {
        val on = previewActive != null && previewActive == selection
        preview.setBackgroundColor(if (on) PREVIEW_SELECTED_BG else W.WINDOW)
        previewScroll.frame = if (on) FocusFrame() else null
    }

    /** Preview is in the selected (first-tapped) state (tests). */
    val previewSelected: Boolean get() = previewActive != null && previewActive == selection

    fun updatePreview() {
        if (previewHeld) return
        preview.removeAllViews()
        val r = rows.firstOrNull { it.uuid == selection }
        val vis = if (r == null) View.GONE else View.VISIBLE
        previewScroll.visibility = vis; split.visibility = vis
        if (r == null) return
        previewScroll.scrollTo(0, 0)
        if (previewActive != r.uuid) previewActive = null
        val head = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(W.icon(ctx, bmpFor(r, true).let { if (r.uuid == selection) unselected(r) else it }, maxOf(1, W.iconScale() - 2)), W.lp(W.WRAP, W.WRAP, r = 8))
        val title = W.text(ctx, label(r), 17f, true).apply { setPadding(W.dp(3), W.dp(1), W.dp(3), W.dp(1)) }
        head.addView(FrameLayout(ctx).apply { addView(title, FrameLayout.LayoutParams(W.WRAP, W.WRAP)) }, W.lp(0, W.WRAP, 1f))
        preview.addView(head, W.lp(W.MATCH, W.WRAP, b = 6))
        // 0.4.26: an item in the bin shows where 元に戻す puts it
        if (!isRecycleBinFolder(r) && Tree.isInRecycleBin(db(), r.uuid)) restoreInfo(r.uuid)?.let {
            preview.addView(W.text(ctx, it, 14f, true, W.NAVY).apply { tag = "restore-to" }, W.lp(W.MATCH, W.WRAP, b = 6))
        }
        when (r) {
            is Order.Node.F -> {
                val kids = Order.children(r.g)
                val nf = kids.count { it.kind == Order.Kind.FOLDER }; val nk = kids.count { it.kind == Order.Kind.KEY }
                val nm = kids.count { it.kind == Order.Kind.MEMO }
                preview.addView(W.text(ctx, L.t("フォルダー ${nf} 個、鍵 ${nk} 個、メモ ${nm} 個", "${nf} folders, ${nk} keys, ${nm} memos"), 14f))
                // 0.4.17: each item with its small icon (like the list), instead of a bullet
                kids.take(30).forEach { k ->
                    val line = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL; tag = "preview-child" }
                    line.addView(W.icon(ctx, bmpFor(k, false), maxOf(1, W.iconScale() - 1)), W.lp(W.WRAP, W.WRAP, l = 8, r = 6))
                    line.addView(W.text(ctx, label(k), 14f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }, W.lp(0, W.WRAP, 1f))
                    preview.addView(line, W.lp(W.MATCH, W.WRAP, t = 2))
                }
            }
            is Order.Node.E -> if (r.kind == Order.Kind.MEMO) {
                preview.addView(Parts.RuledText(ctx, editable = false).apply { setText(Tree.notes(r.e)); minLines = 4 }, W.lp(W.MATCH, W.WRAP))
            } else keyPreview(r.e)
        }
        highlightPreview()
    }

    private fun unselected(r: Order.Node): Bitmap = when {
        isBin(r) -> Px.bin32; r.kind == Order.Kind.FOLDER -> Px.folder32; r.kind == Order.Kind.MEMO -> Ico.notepad32
        else -> io.github.zmlworks.kpexplorer.ui.FugueIcons.bitmap(ctx, (r as? Order.Node.E)?.let { io.github.zmlworks.kpexplorer.db.EntryIcon.of(it.e) }, 32)
    }

    private fun keyPreview(e: Entry) {
        preview.addView(W.text(ctx, KeyFields.lastModified(e), 13f, color = W.SHADOW), W.lp(W.MATCH, W.WRAP, b = 4))
        fun kv(k: String, v: View) {
            val row = W.hbox(ctx).apply { gravity = Gravity.TOP }
            row.addView(W.text(ctx, KeyFields.label(k), 14f, true), W.lp(W.dp(76), W.WRAP))
            row.addView(v, W.lp(0, W.WRAP, 1f))
            preview.addView(row, W.lp(W.MATCH, W.WRAP, b = 4))
        }
        fun tv(s: String, k: String) = W.text(ctx, s, 14f).apply { setTextIsSelectable(true); KeyFields.valueFont(this, k) }
        for ((k, vis) in Order.layout(e)) {
            if (!vis) continue
            when (k) {
                Order.F_TAGS -> kv(k, tv(Tree.formatTags(e.tags), k))
                Order.F_OTP -> Order.otpUri(e)?.let { u -> kv(L.t("ワンタイム:", "One-time:"), KeyFields.OtpView(ctx) { u }.apply { textSize = 15f }) }
                Order.F_PASS -> kv(k, passPreview(Tree.password(e)))
                else -> kv(k, tv(Order.string(e, k), k))
            }
        }
    }

    /**
     * 0.4.26: the preview's password, the same cycle as the key page with 「表示する」 for ******:
     * 表示する → one line (" …" when it does not fit) → whole (wrapped) → 表示する; when it fits: 表示する ↔ shown.
     * Only the text itself is the tap target (to the right of it is the preview as usual).
     */
    private fun passPreview(pw: String): View {
        val box = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        if (pw.isEmpty()) { box.addView(W.text(ctx, "", 14f).apply { tag = "pass-value" }); return box }
        val t = W.text(ctx, pw, 14f).apply { tag = "pass"; setPadding(0, W.dp(4), 0, W.dp(4)) }
        val showMark = L.t("表示する", "Show")
        lateinit var fold: io.github.zmlworks.kpexplorer.ui.ValueFold
        fun style() {
            val hidden = fold.state == io.github.zmlworks.kpexplorer.ui.ValueFold.State.HIDDEN
            t.paintFlags = if (hidden) t.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG else t.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            t.setTextColor(if (hidden) W.NAVY else W.TEXT)
            t.typeface = if (hidden) android.graphics.Typeface.DEFAULT else io.github.zmlworks.kpexplorer.ui.Mono.typeface(ctx)
            t.contentDescription = if (hidden) L.t("パスワードを表示する", "Show the password") else null
        }
        fold = io.github.zmlworks.kpexplorer.ui.ValueFold(t, secret = true, hiddenMark = showMark) { style() }
        style()
        t.isClickable = true
        t.setOnClickListener { fold.tap() }
        // a touch right of the text is not a tap on it (it stays a preview tap, see [PreviewBox])
        var outside = false
        t.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) outside = !PreviewBox.onText(t, e.x)
            outside   // true = swallowed here without a click
        }
        box.addView(t, W.lp(0, W.WRAP, 1f))
        return box
    }

    // ------------------------------------------------------------------ item views

    /**
     * 0.4.12 touch handling, like an Android home launcher (icon and detail views alike):
     *  - touch and move without a long press → the list scrolls (anywhere; no scrollbar strip any more);
     *  - long press without moving → context menu (background menu on empty space); releasing keeps it open;
     *  - after the long press on an item, moving the finger past [dragStartPx] (~8 mm) closes the menu and
     *    drags the item (drop on a folder = into it, between items = reorder, on the path tree = there);
     *  - short tap = folder opens / file selected (or opened if it was already selected).
     */
    private fun attachGestures(v: AbsListView) {
        listView = v
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
        val longMs = W.longPressMs()
        var downR: Order.Node? = null; var x0 = 0f; var y0 = 0f; var rx0 = 0f; var ry0 = 0f
        var moved = false; var longFired = false
        val long = Runnable {
            if (moved) return@Runnable
            longFired = true
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { v.onTouchEvent(it); it.recycle() }
            try { frame.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
            val r = downR
            if (r == null) { select(null); Menus.at(v, (loc[0] + x0).toInt(), (loc[1] + y0).toInt(), backgroundMenu()) }
            else {
                // 0.4.20: the list must not move under the finger during the gesture (see [holdSelect])
                holdSelect(r.uuid)
                // 0.4.13: beside / below / above the pressed cell, never over it
                val c = v.getChildAt(v.pointToPosition(x0.toInt(), y0.toInt()) - v.firstVisiblePosition)
                val pw = if (c == null) Menus.at(v, (loc[0] + x0).toInt(), (loc[1] + y0).toInt(), itemMenu(r))
                else {
                    val cl = IntArray(2); c.getLocationOnScreen(cl)
                    Menus.beside(v, android.graphics.Rect(cl[0], cl[1], cl[0] + c.width, cl[1] + c.height), (loc[0] + x0).toInt(), itemMenu(r))
                }
                pw.setOnDismissListener { if (drag == null) releasePreview() }
            }
        }
        v.setOnTouchListener { _, e ->
            if (drag != null) { dragEvent(e); return@setOnTouchListener true }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y; rx0 = e.rawX; ry0 = e.rawY; moved = false; longFired = false
                    downR = rowAt(v, v.pointToPosition(e.x.toInt(), e.y.toInt()))
                    v.postDelayed(long, longMs)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longFired) {
                        // menu is up: jitter is ignored; a real move turns it into a drag of the pressed item
                        val r = downR
                        if (r != null && !Session.readOnlyTemp &&
                            (abs(e.x - x0) > dragStartPx() || abs(e.y - y0) > dragStartPx())) {
                            beginDrag(r, rx0, ry0)   // before dismissing, so the menu's dismiss keeps the preview held
                            Menus.dismiss()
                            drag?.let { it.moving = true; dragTo(e.rawX, e.rawY) }
                        }
                        return@setOnTouchListener true
                    }
                    // 0.4.27: no drag without a long press in any column (0.4.15's name-column drag is gone)
                    if (!moved && (abs(e.x - x0) > slop || abs(e.y - y0) > slop)) { moved = true; v.removeCallbacks(long) }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(long)
                    if (longFired) return@setOnTouchListener true   // menu stays open
                    if (!moved) tap(downR)
                    false
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(long); val f = longFired; longFired = false; f }
                else -> longFired
            }
        }
    }

    /** Finger travel after a long press that turns the open menu into a drag: ~8 mm (at least 32 dp). */
    fun dragStartPx(): Float = maxOf(W.dp(32).toFloat(),
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_MM, DRAG_START_MM, ctx.resources.displayMetrics))

    /** Folder: open. Key / memo: first tap selects, tapping the selected item again opens it. Empty: deselect. */
    fun tap(r: Order.Node?) {
        // 0.4.24 one cushion: the preview pane had the focus (dotted frame) → this tap only gives it back to the list
        if (previewActive != null && previewActive == selection) { previewActive = null; highlightPreview(); return }
        previewActive = null
        when {
            r == null -> select(null)
            // 0.4.15: folders (and the recycle bin) too — first tap selects, the next tap opens
            r.uuid == selection -> open(r)
            else -> select(r.uuid)
        }
    }

    private fun rowAt(v: AbsListView, pos: Int): Order.Node? {
        if (pos == AbsListView.INVALID_POSITION) return null
        val p = if (v is ListView) pos - v.headerViewsCount else pos
        return rows.getOrNull(p)
    }

    /**
     * 0.4.20: long press. Opening the preview pane shrinks the list (and 0.4.19 then scrolled it to keep the
     * selection visible), which moved every cell under the finger right as a drag started: the drop marker then
     * showed at a cell that had slid there (often a row start, at the left edge). Now, if the pane is closed,
     * only the selection is drawn; the pane opens when the menu closes without a drag, or when the drag ends.
     */
    fun holdSelect(id: UUID) {
        if (previewScroll.visibility == View.VISIBLE) { select(id); return }
        selection = id; previewHeld = true
        notifyList(); updateStatus()
    }

    /** The preview is waiting for the end of a long-press gesture (tests). */
    var previewHeld = false
        private set

    fun releasePreview(ensureVisible: Boolean = true) {
        if (!previewHeld) return
        previewHeld = false
        if (!Session.isOpen) return   // the menu can close after a lock
        updatePreview()
        // 0.4.27: after a drag, keep the scroll where the finger left it (opening the preview must not jump the list)
        if (ensureVisible && selection != null) content.post { if (Session.isOpen) ensureSelectionVisible() }
    }

    /**
     * 0.4.24 "one cushion": while a key / memo / folder is selected in the list (preview pane open), the first tap
     * on the path tree only clears the selection; the tree acts on the next tap. Returns true when it used the tap.
     */
    fun cushionTap(): Boolean {
        if (selection == null) return false
        previewActive = null; select(null)
        return true
    }

    fun select(id: UUID?) {
        previewHeld = false
        selection = id
        notifyList(); updatePreview(); updateStatus()
        // 0.4.19: the preview pane takes space from the list; once laid out, bring the selected item above it
        if (id != null) content.post { ensureSelectionVisible() }
    }

    /**
     * 0.4.19: scrolls the list / grid just enough that the selected item is fully inside it (above the preview
     * pane). Called after a selection and whenever the list area changes height (preview shown, splitter dragged).
     */
    fun ensureSelectionVisible() {
        if (drag != null || previewHeld) return
        val v = listView ?: return
        val id = selection ?: return
        val pos = rows.indexOfFirst { it.uuid == id }.takeIf { it >= 0 } ?: return
        if (v.height <= 0) return
        val lp = if (v is ListView) pos + v.headerViewsCount else pos
        val top = v.paddingTop; val bottom = v.height - v.paddingBottom
        val c = v.getChildAt(lp - v.firstVisiblePosition)
        val margin = W.dp(4)
        if (c == null) {
            // off screen: jump to it (rows below → align to the bottom edge, rows above → to the top)
            if (lp > v.lastVisiblePosition) {
                if (v is ListView) v.setSelectionFromTop(lp, (bottom - (v.getChildAt(0)?.height ?: W.dp(40)) - margin).coerceAtLeast(0)) else v.setSelection(lp)
            } else v.setSelection(lp)
            v.post { ensureSelectionVisibleOnce(v, lp, top, margin) }
            return
        }
        when {
            c.bottom > bottom -> v.scrollListBy(minOf(c.bottom - bottom + margin, c.top - top))
            c.top < top -> v.scrollListBy(c.top - top - margin)
        }
    }

    private fun ensureSelectionVisibleOnce(v: AbsListView, lp: Int, top: Int, margin: Int) {
        val c = v.getChildAt(lp - v.firstVisiblePosition) ?: return
        val bottom = v.height - v.paddingBottom
        if (c.bottom > bottom) v.scrollListBy(minOf(c.bottom - bottom + margin, c.top - top))
    }

    private fun notifyList() {
        val v = listView ?: return
        // 0.4.27: GridView (and some ListView layouts) can jump on notifyDataSetChanged; pin the window.
        val first = v.firstVisiblePosition
        val top = v.getChildAt(0)?.top ?: 0
        (v.adapter as? BaseAdapter)?.notifyDataSetChanged()
        ((v as? ListView)?.adapter as? android.widget.HeaderViewListAdapter)?.wrappedAdapter?.let { (it as BaseAdapter).notifyDataSetChanged() }
        v.setSelectionFromTop(first, top)
    }

    /** Drop-position highlight around a cell. */
    private class Marker(val mode: Drop.Mode, val vertical: Boolean) : Drawable() {
        private val p = Paint().apply { color = W.NAVY }
        override fun draw(c: Canvas) {
            val b = bounds; val t = W.dp(4)
            when (mode) {
                Drop.Mode.INTO -> { p.style = Paint.Style.STROKE; p.strokeWidth = t.toFloat(); c.drawRect(Rect(b.left + t, b.top + t, b.right - t, b.bottom - t), p) }
                Drop.Mode.BEFORE -> { p.style = Paint.Style.FILL; if (vertical) c.drawRect(Rect(b.left, b.top, b.right, b.top + t), p) else c.drawRect(Rect(b.left, b.top, b.left + t, b.bottom), p) }
                Drop.Mode.AFTER -> { p.style = Paint.Style.FILL; if (vertical) c.drawRect(Rect(b.left, b.bottom - t, b.right, b.bottom), p) else c.drawRect(Rect(b.right - t, b.top, b.right, b.bottom), p) }
                Drop.Mode.END -> {}
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun buildGrid(): View {
        val grid = GridView(ctx)
        grid.numColumns = 5
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.verticalSpacing = W.dp(4)
        grid.setPadding(W.dp(2), W.dp(6), W.dp(2), W.dp(6))
        // 0.4.12: no scrollbar (launcher-like: touch anywhere and move to scroll)
        grid.isVerticalScrollBarEnabled = false; grid.isHorizontalScrollBarEnabled = false
        grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(0)
        grid.setBackgroundColor(W.WINDOW)
        val iconScale = maxOf(1, W.iconScale() - 1)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val r = rows[p]
                val cell = W.vbox(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(W.dp(1), W.dp(3), W.dp(1), W.dp(3)) }
                cell.addView(ImageView(ctx).apply {
                    setImageDrawable(Px.drawable(ctx, bmpFor(r, true), iconScale)); scaleType = ImageView.ScaleType.CENTER
                }, W.lp(W.WRAP, W.WRAP))
                val sel = r.uuid == selection
                cell.addView(W.text(ctx, label(r), 12f, color = if (sel) W.HILITE else W.TEXT).apply {
                    gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setPadding(W.dp(2), W.dp(1), W.dp(2), W.dp(1))
                    if (sel) setBackgroundColor(W.NAVY)
                }, W.lp(W.WRAP, W.WRAP, t = 2))
                drop?.takeIf { it.target == r.uuid && !it.pathFolder }?.let { cell.foreground = Marker(it.mode, false) }
                cell.layoutParams = AbsListView.LayoutParams(W.MATCH, W.dp(32) * iconScale / 2 + W.dp(48))
                return cell
            }
        }
        attachGestures(grid)
        return if (rows.isEmpty()) empty(grid) else grid
    }

    private fun empty(v: View): View {
        val f = FrameLayout(ctx)
        f.addView(v)
        f.addView(W.text(ctx, if (search != null) (if (search!!.isEmpty()) "" else L.t("見つかりませんでした。", "Nothing found.")) else L.t("このフォルダーは空です。", "This folder is empty."), 14f, color = W.SHADOW).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, FrameLayout.LayoutParams(W.MATCH, W.WRAP).apply { topMargin = W.dp(40) })
        return f
    }

    // ------------------------------------------------------------------ details columns (0.4.10)

    /** Current name / user column widths in px (header and rows); the date column takes the rest. */
    var colName = 0
        private set
    var colUser = 0
        private set
    private var listHeader: LinearLayout? = null

    /** Width the detail rows can use (pane minus the scrollbar strip). */
    private fun detailAvail(): Int {
        val w = if (content.width > 0) content.width - content.paddingLeft - content.paddingRight
            else ctx.resources.displayMetrics.widthPixels - W.dp(4) - 4 * W.px
        return w.coerceAtLeast(W.dp(160))
    }

    private fun clampCols(name: Int, user: Int, avail: Int): Pair<Int, Int> {
        val n = name.coerceIn(W.dp(MIN_NAME_DP), (avail - W.dp(MIN_USER_DP) - W.dp(MIN_DATE_DP)).coerceAtLeast(W.dp(MIN_NAME_DP)))
        val u = user.coerceIn(W.dp(MIN_USER_DP), (avail - n - W.dp(MIN_DATE_DP)).coerceAtLeast(W.dp(MIN_USER_DP)))
        return n to u
    }

    /** Stored widths (dp) or the old 1.4 : 1 split of what is left beside a 92 dp date column. */
    private fun loadCols() {
        val avail = detailAvail()
        val p = App.prefs(ctx)
        val nd = p.getInt(PREF_COL_NAME, -1); val ud = p.getInt(PREF_COL_USER, -1)
        val rest = avail - W.dp(92)
        val n = if (nd > 0) W.dp(nd) else (rest * 1.4f / 2.4f).toInt()
        val u = if (ud > 0) W.dp(ud) else rest - (rest * 1.4f / 2.4f).toInt()
        val (cn, cu) = clampCols(n, u, avail)
        colName = cn; colUser = cu
    }

    /** Sets the widths (px), relayouts header and rows; [persist] stores them in dp. */
    fun setColumnWidths(name: Int, user: Int, persist: Boolean = true) {
        val (n, u) = clampCols(name, user, detailAvail())
        colName = n; colUser = u
        listHeader?.let { h ->
            (h.getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.width = n
            (h.getChildAt(1)?.layoutParams as? LinearLayout.LayoutParams)?.width = u
            h.requestLayout()
        }
        notifyList()
        if (persist) {
            val d = ctx.resources.displayMetrics.density
            App.prefs(ctx).edit().putInt(PREF_COL_NAME, (n / d).toInt().coerceAtLeast(1)).putInt(PREF_COL_USER, (u / d).toInt().coerceAtLeast(1)).apply()
        }
    }

    fun resetColumnWidths() {
        App.prefs(ctx).edit().remove(PREF_COL_NAME).remove(PREF_COL_USER).apply()
        loadCols(); setColumnWidths(colName, colUser, persist = false)
    }

    /** Header grip at the right edge of a resizable column (two short raised lines like a column divider). */
    private class Grip : Drawable() {
        private val dark = Paint().apply { color = W.SHADOW }
        private val light = Paint().apply { color = W.HILITE }
        override fun draw(c: Canvas) {
            val b = bounds; val px = W.px.toFloat(); val x = b.right - 4 * px
            val t = b.top + b.height() * 0.22f; val bt = b.bottom - b.height() * 0.22f
            c.drawRect(x - 3 * px, t, x - 2 * px, bt, dark); c.drawRect(x - 2 * px, t, x - px, bt, light)
            c.drawRect(x, t, x + px, bt, dark); c.drawRect(x + px, t, x + 2 * px, bt, light)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Which divider a header touch at [x] grabs: 1 = after 名前, 2 = after ユーザー名/場所, 0 = none. */
    fun headerDividerAt(x: Float): Int {
        val r = W.dp(16)
        val d1 = abs(x - colName); val d2 = abs(x - (colName + colUser))
        return when {
            d1 <= r && d1 <= d2 -> 1
            d2 <= r -> 2
            else -> 0
        }
    }

    private fun attachHeaderResize(header: LinearLayout) {
        var which = 0; var x0 = 0f; var n0 = 0; var u0 = 0
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    which = headerDividerAt(e.x); x0 = e.x; n0 = colName; u0 = colUser
                    if (which != 0) header.parent?.requestDisallowInterceptTouchEvent(true)
                    which != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.x - x0).toInt()
                    when (which) { 1 -> setColumnWidths(n0 + dx, u0, persist = false); 2 -> setColumnWidths(n0, u0 + dx, persist = false) }
                    which != 0
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val w = which; which = 0
                    if (w != 0) setColumnWidths(colName, colUser, persist = true)
                    w != 0
                }
                else -> which != 0
            }
        }
    }

    private fun buildList(): View {
        val col = W.vbox(ctx).apply { setBackgroundColor(W.WINDOW) }
        loadCols()
        val header = W.hbox(ctx)
        listHeader = header
        fun h(s: String, w: Int, weight: Float, grip: Boolean) = header.addView(W.text(ctx, s, 13f).apply {
            background = W.Bevel(W.Style.HEADER); setPadding(W.dp(6), W.dp(8), W.dp(10), W.dp(8)); isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            if (grip) foreground = Grip()
        }, W.lp(w, W.WRAP, weight))
        val inSearch = search != null
        h(L.t("名前", "Name"), colName, 0f, true)
        h(if (inSearch) L.t("場所", "Location") else L.t("ユーザー名", "User name"), colUser, 0f, true)
        h(L.t("更新日時", "Modified"), 0, 1f, false)
        header.contentDescription = L.t("列の見出し (境目をドラッグで幅を変更)", "Column headers (drag a border to resize)")
        attachHeaderResize(header)
        col.addView(header, W.lp())
        val list = ListView(ctx)
        list.setPadding(0, 0, 0, 0)
        list.isVerticalScrollBarEnabled = false; list.isHorizontalScrollBarEnabled = false
        list.divider = null
        list.selector = android.graphics.drawable.ColorDrawable(0)
        val dateFmt = L.date()
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val r = rows[p]
                val row = W.hbox(ctx).apply { minimumHeight = W.dp(40); gravity = Gravity.CENTER_VERTICAL }
                val nameCol = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(W.dp(4), 0, 0, 0) }
                nameCol.addView(ImageView(ctx).apply { setImageDrawable(Px.drawable(ctx, bmpFor(r, false), W.iconScale())) }, W.lp(W.WRAP, W.WRAP, r = 4))
                val sel = r.uuid == selection
                val nameBox = FrameLayout(ctx)
                nameBox.addView(W.text(ctx, label(r), 15f, color = if (sel) W.HILITE else W.TEXT).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setPadding(W.dp(3), W.dp(2), W.dp(3), W.dp(2))
                    if (sel) setBackgroundColor(W.NAVY)
                }, FrameLayout.LayoutParams(W.WRAP, W.WRAP, Gravity.CENTER_VERTICAL))
                nameCol.addView(nameBox, W.lp(0, W.WRAP, 1f))
                row.addView(nameCol, W.lp(colName, W.WRAP))
                val second = when (r) {
                    is Order.Node.E -> if (inSearch) Tree.parentOf(Tree.root(db()), r.uuid)?.name.orEmpty()
                        else if (r.kind == Order.Kind.MEMO) L.t("メモ", "Memo") else Tree.userName(r.e)
                    is Order.Node.F -> L.t("フォルダー", "Folder")
                }
                row.addView(W.text(ctx, second, 14f, color = if (inSearch) W.SHADOW else W.TEXT).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(W.dp(6), 0, W.dp(2), 0)
                }, W.lp(colUser, W.WRAP))
                val t = when (r) { is Order.Node.E -> r.e.times?.lastModificationTime; is Order.Node.F -> r.g.times?.lastModificationTime }
                row.addView(W.text(ctx, t?.let { dateFmt.format(it) } ?: "", 13f).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(W.dp(6), 0, 0, 0) },
                    W.lp(0, W.WRAP, 1f))
                drop?.takeIf { it.target == r.uuid && !it.pathFolder }?.let { row.foreground = Marker(it.mode, true) }
                return row
            }
        }
        // 0.4.12: same launcher-style gestures as the icon view (long press, then move = drag; else scroll)
        attachGestures(list)
        col.addView(if (rows.isEmpty()) empty(list) else list, W.lp(W.MATCH, 0, 1f))
        return col
    }

    // ------------------------------------------------------------------ drag & drop

    private class DragState(val id: UUID, val r: Order.Node, val x0: Float, val y0: Float) { var moving = false }
    /** Where a drop would go: [target] item (or folder in the path tree) and how. */
    data class Drop(val mode: Mode, val target: UUID?, val pathFolder: Boolean = false) { enum class Mode { INTO, BEFORE, AFTER, END } }

    private var drag: DragState? = null
    /** A drag (move / reorder) is in progress (tests). */
    val dragging: Boolean get() = drag != null
    private var drop: Drop? = null

    private fun beginDrag(r: Order.Node, rx: Float, ry: Float) {
        // 0.4.14: the recycle bin can be dragged too, but only to reorder it inside the root (see [hit])
        drag = DragState(r.uuid, r, rx, ry)
        ghost.setImageDrawable(Px.drawable(ctx, unselected(r), maxOf(1, W.iconScale() - 1)))
        notifyList()
    }

    private fun dragEvent(e: MotionEvent) {
        val d = drag ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 1.5f
                if (!d.moving && (abs(e.rawX - d.x0) > slop || abs(e.rawY - d.y0) > slop)) d.moving = true
                if (d.moving) dragTo(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_UP -> {
                if (d.moving) { val target = drop; endDrag(); target?.let { performDrop(d.id, it) } }
                else endDrag()
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
        }
    }

    private fun endDrag() {
        drag = null; drop = null
        ghost.visibility = View.GONE
        pathLines.forEach { it.setBackgroundColor(0) }
        notifyList()
        releasePreview(ensureVisible = false)
    }

    /** Move the ghost and compute the drop target under the finger (raw screen coordinates). */
    fun dragTo(rx: Float, ry: Float) {
        val d = drag ?: return
        val fl = IntArray(2); frame.getLocationOnScreen(fl)
        ghost.visibility = View.VISIBLE
        ghost.translationX = rx - fl[0] - W.dp(24); ghost.translationY = ry - fl[1] - W.dp(56)
        val nd = hit(d.id, rx, ry)
        if (nd != drop) {
            drop = nd
            pathLines.forEach { line -> line.setBackgroundColor(if (nd?.pathFolder == true && line.tag == nd.target) 0xFF8080FF.toInt() else 0) }
            notifyList()
        }
        // auto-scroll near the pane edges
        listView?.let { v ->
            val l = IntArray(2); v.getLocationOnScreen(l)
            val y = ry - l[1]
            if (y in 0f..W.dp(36).toFloat()) v.smoothScrollBy(-W.dp(24), 50)
            else if (y in (v.height - W.dp(36)).toFloat()..v.height.toFloat()) v.smoothScrollBy(W.dp(24), 50)
        }
    }

    fun hit(dragged: UUID, rx: Float, ry: Float): Drop? {
        val binDrag = dragged == Tree.recycleBinId(db())
        // 0.4.21: the path tree sits in a scroll box (at most 30 % of the screen). Rows scrolled out of that box
        // still have screen positions — below it they lie over the item list — so only the visible part counts.
        // (Before, a hidden row (usually the recycle bin, the last row) caught drops in the middle of the list.)
        val box = (pathBox.getChildAt(0) as? ScrollView)?.takeIf { it.isShown }
        val bl = IntArray(2); box?.getLocationOnScreen(bl)
        val inBox = box != null && rx >= bl[0] && rx < bl[0] + box.width && ry >= bl[1] && ry < bl[1] + box.height
        for (line in pathLines) {
            if (binDrag || !inBox || !line.isShown) continue
            val l = IntArray(2); line.getLocationOnScreen(l)
            if (rx >= l[0] && rx < l[0] + line.width && ry >= l[1] && ry < l[1] + line.height) {
                val id = line.tag as UUID
                return if (id == current) null else Drop(Drop.Mode.INTO, id, pathFolder = true)
            }
        }
        val v = listView ?: return null
        val l = IntArray(2); v.getLocationOnScreen(l)
        val x = rx - l[0]; val y = ry - l[1]
        if (x < 0 || y < 0 || x >= v.width || y >= v.height) return null
        var pos = v.pointToPosition(x.toInt(), y.toInt())
        // 0.4.20: in the small gaps between cells / rows, use the nearest cell instead of "to the end"; END only
        // below the last row or after the last cell of the last row
        if (pos == AbsListView.INVALID_POSITION && v.childCount > 0) {
            val lastC = v.getChildAt(v.childCount - 1)
            val afterAll = v.lastVisiblePosition >= v.count - 1 &&
                (y > lastC.bottom || (y >= lastC.top && x > lastC.right))
            if (!afterAll) {
                var best = -1; var bestD = Float.MAX_VALUE
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    val dx = if (x < c.left) c.left - x else if (x > c.right) x - c.right else 0f
                    val dy = if (y < c.top) c.top - y else if (y > c.bottom) y - c.bottom else 0f
                    val dd = dx * dx + dy * dy
                    if (dd < bestD) { bestD = dd; best = i }
                }
                if (best >= 0 && bestD <= (W.dp(12) * W.dp(12)).toFloat()) pos = v.firstVisiblePosition + best
            }
        }
        // 0.4.23: the flat list (items of many folders) has no order of its own: drop only on the path tree
        if (flat && search == null) return null
        val r = rowAt(v, pos) ?: return if (search == null) Drop(Drop.Mode.END, null) else null
        if (search != null) return if (!binDrag && r.kind == Order.Kind.FOLDER && r.uuid != dragged) Drop(Drop.Mode.INTO, r.uuid) else null
        if (r.uuid == dragged) return null
        val cell = v.getChildAt(pos - v.firstVisiblePosition) ?: return null
        val vertical = v is ListView
        val f = (if (vertical) (y - cell.top) / cell.height.coerceAtLeast(1) else (x - cell.left) / cell.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        // 0.4.20: "into the folder" only in the middle 40 % (was 56 %): 30 % bands on both sides reorder
        val fy = ((y - cell.top) / cell.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        val into = if (vertical) f in INTO_FROM..INTO_TO else f in INTO_FROM..INTO_TO && fy in 0.08f..0.92f
        return if (r.kind == Order.Kind.FOLDER && !binDrag && into) Drop(Drop.Mode.INTO, r.uuid)
        else Drop(if (f < 0.5f) Drop.Mode.BEFORE else Drop.Mode.AFTER, r.uuid)
    }

    fun performDrop(id: UUID, dr: Drop) {
        if (id == Tree.recycleBinId(db()) && (dr.mode == Drop.Mode.INTO || current != Tree.root(db()).uuid)) return
        try {
            when (dr.mode) {
                Drop.Mode.INTO -> Session.update { Order.moveTo(it, id, dr.target!!) }
                Drop.Mode.END -> Session.update { Order.moveTo(it, id, current) }
                Drop.Mode.BEFORE, Drop.Mode.AFTER -> {
                    val ids = Order.children(currentGroup()).map { it.uuid }.filter { it != id }
                    val i = ids.indexOf(dr.target) + if (dr.mode == Drop.Mode.AFTER) 1 else 0
                    Session.update { Order.moveTo(it, id, current, i) }
                }
            }
            if (dr.mode == Drop.Mode.INTO) selection = null
            refresh()
        } catch (e: Tree.TreeException) {
            Dlg.message(ctx, L.t("移動エラー", "Move Error"), L.t("移動できません: 移動先のフォルダーは、移動するフォルダーの中にあります。", "Cannot move: the target folder is inside the folder being moved."), 'x')
        }
    }

    // ------------------------------------------------------------------ navigation

    fun navigate(id: UUID, push: Boolean = true) {
        if (search == null && id == current && !flat) return
        if (push && id != current) { history.add(current); if (history.size > 50) history.removeAt(0) }
        search = null; flat = false
        current = id
        selection = null
        refresh()
    }

    /** 0.4.23: list every key / memo below folder [id] (as if it were one folder). */
    fun showAllBelow(id: UUID) {
        if (Tree.findGroup(db(), id) == null) return
        if (id != current) { history.add(current); if (history.size > 50) history.removeAt(0) }
        search = null; current = id; selection = null; flat = true
        refresh()
    }

    fun exitFlat() { if (!flat) return; flat = false; selection = null; refresh() }

    fun goUp() {
        if (search != null) { search = null; refresh(); return }
        if (flat) { exitFlat(); return }
        val p = Tree.parentOf(Tree.root(db()), current) ?: return
        val from = current
        navigate(p.uuid)
        select(from)
    }

    fun goBack() {
        if (search != null) { search = null; refresh(); return }
        if (flat) { exitFlat(); return }
        val prev = history.removeLastOrNull() ?: return
        if (Tree.findGroup(db(), prev) != null) navigate(prev, push = false) else goBack()
    }

    override fun onBack(): Boolean {
        if (search != null) { search = null; refresh(); return true }
        if (flat) { exitFlat(); return true }
        if (current != Tree.root(db()).uuid) { goUp(); return true }
        if (Session.readOnlyTemp) { TempImport.exitTemp(a); return true }
        return false
    }

    fun open(r: Order.Node) {
        when {
            r is Order.Node.F -> navigate(r.uuid)
            r.kind == Order.Kind.MEMO -> a.show(MemoScreen(a, r.uuid))
            else -> a.show(KeyScreen(a, r.uuid, Tree.parentOf(Tree.root(db()), r.uuid)?.uuid ?: current))
        }
    }

    fun toggleView() {
        App.prefs(ctx).edit().putString("view", if (App.prefs(ctx).getString("view", "icons") == "icons") "details" else "icons").apply()
        refresh()
    }

    /** Sets the live query ("" = search mode with no criteria yet; results list stays empty). */
    fun searchFor(q: String) { search = q.trim(); selection = null; refresh() }

    /** Tags currently toggled on in the search panel (kept between searches). */
    val searchTags = LinkedHashSet<String>()

    /** Builds the query: free words AND every ticked tag ("#tag" = exact tag). */
    fun searchQuery(text: String, tags: Collection<String>): String =
        (listOf(text.trim()) + tags.map { "#$it" }).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Opens the inline search panel at the top: a text field and tag toggles. Results appear in the list
     * directly below on every keystroke (150 ms debounce) or tag toggle — no search button.
     */
    fun openSearch() {
        searchPanel.removeAllViews()
        searchPanel.setPadding(W.dp(6), W.dp(5), W.dp(6), W.dp(6))
        val row = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(ctx).apply { setImageDrawable(Px.drawable(ctx, Px.find16, maxOf(1, W.iconScale() - 1))) }, W.lp(W.WRAP, W.WRAP, r = 4))
        searchField = W.field(ctx).apply { isSingleLine = true; hint = "Search" }
        row.addView(searchField, W.lp(0, W.WRAP, 1f))
        row.addView(W.button(ctx, L.t("クリア", "Clear")) { searchTags.clear(); searchField.setText(""); renderSearchTags(); applyLiveSearch() }, W.lp(W.WRAP, W.WRAP, l = 4))
        row.addView(W.button(ctx, L.t("閉じる", "Close")) { closeSearch() }, W.lp(W.WRAP, W.WRAP, l = 4))
        searchPanel.addView(row)
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { liveHandler.removeCallbacks(liveRun); liveHandler.postDelayed(liveRun, LIVE_MS) }
        })
        searchPanel.addView(searchTagHolder, W.lp(W.MATCH, W.WRAP, t = 4))
        renderSearchTags()
        searchFor(searchQuery("", searchTags))
        searchField.requestFocus()
    }

    private fun renderSearchTags() {
        searchTagHolder.removeAllViews()
        val tags = Tree.allTags(db())
        if (tags.isEmpty()) return
        searchTagHolder.addView(Parts.tagChips(ctx, tags, searchTags) { t ->
            if (!searchTags.remove(t)) searchTags.add(t)
            renderSearchTags(); applyLiveSearch()
        })
    }

    /** Re-runs the search with the panel's current text + tags (called live). */
    fun applyLiveSearch() {
        liveHandler.removeCallbacks(liveRun)
        if (search == null) return
        searchFor(searchQuery(if (::searchField.isInitialized) searchField.text.toString() else "", searchTags))
    }

    fun closeSearch() {
        liveHandler.removeCallbacks(liveRun)
        search = null; refresh()
    }

    fun saveNow() {
        Session.flush()
        Session.afterSave({ Unit }) { r ->
            r.onSuccess { Toast.makeText(ctx, L.t("保存しました", "Saved"), Toast.LENGTH_SHORT).show() }
             .onFailure { Dlg.message(ctx, L.t("保存", "Save"), L.t("保存できませんでした。\n${it.message ?: it}", "Could not save.\n${it.message ?: it}"), 'x') }
            updateStatus()
        }
    }

    // ------------------------------------------------------------------ menus

    fun mainMenu(): List<Menus.Item> {
        val p = App.prefs(ctx)
        val lockMin = a.autoLockMinutes()
        val ro = Session.readOnlyTemp
        val out = ArrayList<Menus.Item>()
        if (ro) {
            out.add(Menus.Item(ctx.getString(R.string.temp_exit)) { TempImport.exitTemp(a) })
            out.add(Menus.sep())
        }
        out.add(Menus.Item(ctx.getString(R.string.menu_lock)) { a.lock() })
        // 0.4.16: 履歴 moved to the key page (「- 履歴」 next to Last Modified)
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.browse_external), !ro) { TempImport.browseExternal(a) })
        out.add(Menus.Item(ctx.getString(R.string.import_menu), !ro) { TempImport.showImportMenu(a) })
        out.add(Menus.Item(ctx.getString(R.string.passgen_menu)) { PassGen.show(a) })
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.menu_internal_save), !ro) { Security.internalBackup(a) })
        out.add(Menus.Item(ctx.getString(R.string.menu_export), !ro) { Security.askExportPassword(a, "keepass-explorer-${java.time.LocalDate.now()}.kdbx") { Session.db } })
        out.add(Menus.Item(ctx.getString(R.string.menu_backup_list), !ro) { a.show(BackupScreen(a)) })
        out.add(Menus.Item(ctx.getString(R.string.menu_change_master), !ro) { Security.changeMasterPassword(a) })
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.menu_secure_screen), checked = p.getBoolean("secure", true)) {
            p.edit().putBoolean("secure", !p.getBoolean("secure", true)).apply(); a.applySecure()
        })
        // 0.4.23: one row per setting; tap → choose
        out.add(Menus.Item(ctx.getString(when { lockMin == 0 -> R.string.menu_autolock_now; lockMin < 0 -> R.string.menu_autolock_never; else -> R.string.menu_autolock_5 })) { pickAutoLock() })
        // 0.4.32: "Language" in English in every language (findable by anyone), on its own group below a line
        out.add(Menus.sep())
        out.add(Menus.Item("Language: " + langName(L.lang)) { pickLanguage() })
        out.add(Menus.Item(L.t("日付: ", "Date: ") + dateName(L.dateOrder)) { pickDateOrder() })
        out.add(Menus.sep())
        val bin = Tree.recycleBinId(db())?.takeIf { Tree.findGroup(db(), it) != null }
        out.add(Menus.Item(L.t("ごみ箱を開く", "Open Recycle Bin"), bin != null) { bin?.let { navigate(it) } })
        out.add(Menus.Item(L.t("ごみ箱の設定…", "Recycle Bin settings…"), !ro) { BinSettings.show(a) })
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.menu_usage)) { Help.usage(a) })
        out.add(Menus.Item(ctx.getString(R.string.menu_db_info)) { Help.dbInfo(a) })
        out.add(Menus.Item(ctx.getString(R.string.menu_about)) { Help.about(a) })
        return out
    }

    fun langName(l: L.Lang) = when (l) { L.Lang.SYSTEM -> L.t("端末に合わせる", "System"); L.Lang.JA -> "日本語"; L.Lang.EN -> "English" }
    fun dateName(d: L.DateOrder) = when (d) { L.DateOrder.YMD -> "YYYY/MM/DD"; L.DateOrder.MDY -> "MM/DD/YYYY"; L.DateOrder.DMY -> "DD/MM/YYYY" }

    fun pickLanguage(): ClassicDialog {
        val all = L.Lang.entries
        return Dlg.choose(ctx, L.t("言語", "Language"), all.map { langName(it) }, all.indexOf(L.lang)) { a.setLanguage(all[it]) }
    }

    fun pickDateOrder(): ClassicDialog {
        val all = L.DateOrder.entries
        val today = java.time.Instant.now()
        return Dlg.choose(ctx, L.t("日付の表示", "Date format"),
            all.map { dateName(it) + "  (" + java.time.format.DateTimeFormatter.ofPattern(it.pattern).withZone(java.time.ZoneId.systemDefault()).format(today) + ")" },
            all.indexOf(L.dateOrder)) { L.setDateOrder(ctx, all[it]); refresh() }
    }

    fun pickAutoLock(): ClassicDialog {
        val vals = listOf(0, 5, -1)
        val cur = a.autoLockMinutes().let { if (it < 0) -1 else if (it == 0) 0 else 5 }
        return Dlg.choose(ctx, L.t("自動ロック", "Auto-lock"),
            listOf(L.t("すぐ (アプリを離れたとき)", "Immediately (when leaving the app)"), L.t("5 分後", "After 5 minutes"), L.t("しない", "Never")),
            vals.indexOf(cur)) { App.prefs(ctx).edit().putInt("autolock", vals[it]).apply() }
    }

    fun itemMenu(r: Order.Node): List<Menus.Item> {
        val inSearch = search != null
        val bin = isBin(r)
        val binFolder = isRecycleBinFolder(r)
        val inBin = Tree.isInRecycleBin(db(), r.uuid) && !binFolder
        val ro = Session.readOnlyTemp
        if (r is Order.Node.E && !inBin) return fileMenu(r)
        val out = ArrayList<Menus.Item>(viewHead())
        if (inBin || binFolder) {
            // recycle bin and items inside it: as before (open / restore / delete permanently / empty)
            out.add(Menus.Item(ctx.getString(R.string.open)) { open(r) })
            if (binFolder) out.add(Menus.Item(L.t("下部ファイルをすべて表示", "Show all files below")) { showAllBelow(r.uuid) })
            if (r is Order.Node.E && r.kind == Order.Kind.KEY) {
                out.add(Menus.Item(ctx.getString(R.string.copy_username), Tree.userName(r.e).isNotEmpty()) { copy(ctx.getString(R.string.copy_username), Tree.userName(r.e), false) })
                out.add(Menus.Item(ctx.getString(R.string.copy_password), Tree.password(r.e).isNotEmpty()) { copy(ctx.getString(R.string.copy_password), Tree.password(r.e), true) })
                Order.otpUri(r.e)?.let { u -> out.add(Menus.Item(ctx.getString(R.string.copy_otp)) {
                    runCatching { io.github.zmlworks.kpexplorer.db.Totp.code(io.github.zmlworks.kpexplorer.db.Totp.parse(u), java.time.Instant.now().epochSecond) }
                        .onSuccess { copy(ctx.getString(R.string.copy_otp), it, true) } }) }
            }
            if (ro) return out
            out.add(Menus.sep())
            if (inBin) {
                out.add(Menus.Item(restoreLabel(r.uuid)) { restoreItem(r.uuid) })
                out.add(Menus.Item(ctx.getString(R.string.delete_permanently)) { askDelete(r.uuid) })
            } else out.add(Menus.Item(ctx.getString(R.string.empty_recycle_bin), Tree.recycleBinChildCount(db()) > 0) { askEmptyBin() })
            return out
        }
        // 0.4.15 folder menu: no 開く (tap twice) and no 前へ／後ろへ移動 (long press + drag)
        val w = !ro
        // 0.4.24: as in the path tree's menu
        out.add(Menus.Item(L.t("下部ファイルをすべて表示", "Show all files below")) { showAllBelow(r.uuid) })
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.rename), w && !bin) { rename(r) })
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.cut), w && !bin) { cut(r.uuid) })
        out.add(Menus.Item(ctx.getString(R.string.paste_here), w && cut != null && cut != r.uuid) { paste(r.uuid) })
        out.add(Menus.Item(ctx.getString(R.string.move_to_folder), w && !bin) { moveToDialog(r.uuid) })
        out.add(Menus.Item(ctx.getString(R.string.move_up_folder), w && !bin && canMoveToParentFolder(r.uuid)) { moveToParentFolder(r.uuid) })
        if (inSearch) out.add(Menus.Item(ctx.getString(R.string.open_item_folder)) {
            Tree.parentOf(Tree.root(db()), r.uuid)?.let { navigate(it.uuid); select(r.uuid) } })
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.delete), w) { askDelete(r.uuid) })
        return out
    }

    /**
     * 0.4.13: long-press menu of a file (key or memo) — exactly these, as the user specified:
     * 名前の変更 | 切り取り, フォルダーを指定して移動, 一つ上の階層へ移動 | 削除.
     * Open = tap (again), copy = swipe on the key page, reorder = long press + drag.
     * Read-only temporary view shows the same items disabled.
     */
    fun fileMenu(r: Order.Node.E): List<Menus.Item> {
        val w = !Session.readOnlyTemp
        return viewHead() + listOf(
            Menus.Item(ctx.getString(R.string.rename), w) { rename(r) },
            Menus.sep(),
            Menus.Item(ctx.getString(R.string.cut), w) { cut(r.uuid) },
            Menus.Item(ctx.getString(R.string.move_to_folder), w) { moveToDialog(r.uuid) },
            Menus.Item(ctx.getString(R.string.move_up_folder), w && canMoveToParentFolder(r.uuid)) { moveToParentFolder(r.uuid) },
            Menus.sep(),
            Menus.Item(ctx.getString(R.string.delete), w) { askDelete(r.uuid) })
    }

    /**
     * 0.4.15: top of every list context menu — 表示: 詳細 (icon view) or 表示: アイコン + 列の幅を元に戻す (detail
     * view), then a separator.
     */
    fun viewHead(): List<Menus.Item> = listOf(
        Menus.Item(if (iconMode) ctx.getString(R.string.view_details) else ctx.getString(R.string.view_icons)) { toggleView() }) +
        (if (!iconMode) listOf(Menus.Item(ctx.getString(R.string.reset_columns),
            App.prefs(ctx).contains(PREF_COL_NAME) || App.prefs(ctx).contains(PREF_COL_USER)) { resetColumnWidths() }) else emptyList()) +
        listOf(Menus.sep())

    fun backgroundMenu(): List<Menus.Item> {
        val ns = search == null
        val ro = Session.readOnlyTemp
        return viewHead() + listOf(
            Menus.Item(ctx.getString(R.string.paste), cut != null && ns && !ro) { paste(current) }) +
            (if (cut != null) listOf(Menus.Item(ctx.getString(R.string.cancel_cut)) { cancelCut() }) else emptyList()) + listOf(
            Menus.sep(),
            Menus.Item(ctx.getString(R.string.tool_new_folder), ns && !flat && !ro) { askNewFolder() },
            Menus.Item(ctx.getString(R.string.tool_new_key), ns && !ro) { newKey() },
            Menus.Item(ctx.getString(R.string.tool_new_memo), ns && !ro) { askNewMemo() }
        )
    }

    // ------------------------------------------------------------------ operations

    /** 0.4.15: ask for the name first (default name pre-selected); キャンセル creates nothing; blank = default. */
    fun askNewFolder(): ClassicDialog? {
        if (Session.readOnlyTemp || search != null || flat) return null
        val def = Order.uniqueChildName(currentGroup(), ctx.getString(R.string.new_folder_default))
        return Dlg.prompt(ctx, ctx.getString(R.string.tool_new_folder), ctx.getString(R.string.new_name_label), def) { n ->
            newFolder(n.trim().ifEmpty { def }); true }
    }

    fun askNewMemo(): ClassicDialog? {
        if (Session.readOnlyTemp || search != null) return null
        val def = Order.uniqueChildName(currentGroup(), ctx.getString(R.string.new_memo_default))
        return Dlg.prompt(ctx, ctx.getString(R.string.tool_new_memo), ctx.getString(R.string.new_name_label), def) { n ->
            newMemo(n.trim().ifEmpty { def }); true }
    }

    fun newFolder(name: String? = null): UUID {
        if (Session.readOnlyTemp) throw IllegalStateException("read-only")
        val g = Tree.newGroup(name ?: Order.uniqueChildName(currentGroup(), ctx.getString(R.string.new_folder_default)))
        Session.update { Order.appendGroup(it, current, g) }
        selection = g.uuid; refresh(); content.post { ensureSelectionVisible() }
        return g.uuid
    }

    fun newMemo(name: String? = null): UUID {
        if (Session.readOnlyTemp) throw IllegalStateException("read-only")
        val e = Order.newMemo(name ?: Order.uniqueChildName(currentGroup(), ctx.getString(R.string.new_memo_default)))
        Session.update { Order.appendEntry(it, current, e) }
        selection = e.uuid; refresh(); content.post { ensureSelectionVisible() }
        return e.uuid
    }

    fun newKey() = a.show(NewKeyScreen(a, current))

    fun rename(r: Order.Node) {
        Dlg.prompt(ctx, L.t("名前の変更", "Rename"), L.t("新しい名前:", "New name:"), r.name) { n ->
            when (r) {
                is Order.Node.F -> Session.update { Tree.renameGroup(it, r.uuid, n) }
                is Order.Node.E -> Session.update { Order.renameEntry(it, r.uuid, n) }
            }
            refresh(); true
        }
    }

    fun cut(id: UUID) {
        cut = id; refresh()
        Toast.makeText(ctx, L.t("切り取りました。移動先で「貼り付け」を選んでください。", "Cut. Choose \"Paste\" where you want to move it."), Toast.LENGTH_SHORT).show()
    }

    /** 0.4.13: drop the pending cut (the item is no longer shown dimmed). */
    fun cancelCut() { cut = null; refresh() }

    fun paste(target: UUID) {
        val id = cut ?: return
        try { Session.update { Order.moveTo(it, id, target) }; cut = null; refresh() }
        catch (e: Tree.TreeException) { Dlg.message(ctx, L.t("移動エラー", "Move Error"), L.t("移動できません: 移動先のフォルダーは、移動するフォルダーの中にあります。", "Cannot move: the target folder is inside the folder being moved."), 'x') }
    }

    fun moveIndex(id: UUID, index: Int) { Session.update { Order.moveTo(it, id, current, index) }; selection = id; refresh() }

    /** Parent of the entry's containing folder (null when the entry lives in the database root). */
    fun parentFolderOf(id: UUID): UUID? {
        val root = Tree.root(db())
        val containing = Tree.parentOf(root, id) ?: return null
        if (containing.uuid == root.uuid) return null
        return Tree.parentOf(root, containing.uuid)?.uuid
    }

    fun canMoveToParentFolder(id: UUID): Boolean = parentFolderOf(id) != null

    /**
     * Move an entry or (0.4.26) a folder into the parent of its current folder, at the front of that folder's
     * combined display order (index 0). No-op at the root level (and so for the bin, which stays in the root).
     */
    fun moveToParentFolder(id: UUID) {
        val dest = parentFolderOf(id) ?: return
        try {
            Session.update { Order.moveTo(it, id, dest, 0) }
            selection = id
            // Navigate to destination so the user sees the entry at the front
            if (search == null && !flat) navigate(dest)
            refresh()
        } catch (e: Tree.TreeException) {
            Dlg.message(ctx, L.t("移動エラー", "Move Error"), L.t("移動できませんでした。", "Could not move it."), 'x')
        }
    }

    fun moveToDialog(id: UUID) {
        val d = db()
        var target: UUID? = null
        val tree = FolderTree(ctx, Tree.root(d), null, Tree.recycleBinId(d), disabled = setOf(id), hidden = Tree.hiddenGroups(d), striped = true, binLast = Tree.binLastInTree(d),
            onToggle = { gid, exp -> Session.update { Tree.setExpanded(it, gid, exp) } },
            onSelect = { target = it })
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, L.t("「${rows.firstOrNull { it.uuid == id }?.let { label(it) } ?: ""}」の移動先:", "Move \"${rows.firstOrNull { it.uuid == id }?.let { label(it) } ?: ""}\" to:"), 15f), W.lp(W.MATCH, W.WRAP, b = 8))
        col.addView(FrameLayout(ctx).apply { background = W.Bevel(W.Style.FIELD); setPadding(2 * W.px, 2 * W.px, 2 * W.px, 2 * W.px); addView(tree) })
        ClassicDialog(ctx, L.t("フォルダーへ移動", "Move to Folder"), col, listOf(
            ClassicDialog.Btn(L.t("移動", "Move"), true) {
                val t = target ?: return@Btn false
                try { Session.update { Order.moveTo(it, id, t) }; refresh(); true }
                catch (e: Tree.TreeException) { Dlg.message(ctx, L.t("移動エラー", "Move Error"), L.t("そのフォルダーには移動できません。", "It cannot be moved to that folder."), 'x'); false }
            },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel"))), scroll = true).show()
    }

    fun askDelete(id: UUID) {
        if (Session.readOnlyTemp) {
            Dlg.message(ctx, ctx.getString(R.string.delete), ctx.getString(R.string.temp_no_edit), 'i')
            return
        }
        val d = db()
        val binId = Tree.recycleBinId(d)
        if (id == binId) {
            Dlg.message(ctx, ctx.getString(R.string.delete), ctx.getString(R.string.cannot_delete_bin), '!')
            return
        }
        val r = rows.firstOrNull { it.uuid == id } ?: Tree.findGroup(d, id)?.let { Order.Node.F(it) }
            ?: Tree.findEntry(d, id)?.let { Order.Node.E(it.second) } ?: return
        val what = if (r is Order.Node.F)
            ctx.getString(R.string.folder_and_contents, label(r))
        else ctx.getString(R.string.quoted_name, label(r))
        val inBin = Tree.isInRecycleBin(d, id)
        val soft = d.content.meta.recycleBinEnabled && !inBin
        // 0.4.24 (path tree menu): deleting the open folder or one above it → go to its parent first
        val g = Tree.findGroup(d, id)
        fun has(x: Group): Boolean = x.uuid == current || x.groups.any { has(it) }
        val leaveTo = if (g != null && has(g)) Tree.parentOf(Tree.root(d), id)?.uuid else null
        fun perform() {
            val binName = ctx.getString(R.string.recycle_bin)
            Session.update {
                if (soft) Tree.moveToBin(it, id, binName)
                else Tree.deletePermanently(it, id)
            }
            if (cut == id) cut = null
            if (leaveTo != null) { navigate(leaveTo); return }
            selection = null; refresh()
        }
        // Soft move-to-bin: no confirmation (t522u). Permanent / from-bin still confirms.
        if (soft) perform()
        else Dlg.confirm(ctx, ctx.getString(R.string.delete_confirm_title),
            ctx.getString(R.string.confirm_delete_permanent, what), '!') { perform() }
    }

    /** 0.4.26: 「元に戻す (→ /仕事/メール)」 — where it goes, from [Tree.restoreTarget] (the restore uses the same). */
    fun restoreLabel(id: UUID): String {
        val t = runCatching { Tree.restoreTarget(db(), id) }.getOrNull() ?: return ctx.getString(R.string.restore)
        return ctx.getString(R.string.restore) + " (→ " + Tree.pathText(db(), t.target) + ")"
    }

    /** 0.4.26: the line in the preview of an item in the bin. */
    fun restoreInfo(id: UUID): String? {
        val d = db()
        val t = runCatching { Tree.restoreTarget(d, id) }.getOrNull() ?: return null
        val p = Tree.pathText(d, t.target)
        return when (t.reason) {
            Tree.RestoreTarget.Reason.ORIGINAL -> L.t("元の場所: $p", "Original location: $p")
            Tree.RestoreTarget.Reason.GONE -> L.t("元のフォルダーはありません → / に戻します", "The original folder no longer exists → restores to /")
            Tree.RestoreTarget.Reason.IN_BIN -> L.t("元のフォルダーもごみ箱の中です → / に戻します", "The original folder is in the Recycle Bin too → restores to /")
            Tree.RestoreTarget.Reason.NO_RECORD -> L.t("元の場所の記録がありません → / に戻します", "No original location recorded → restores to /")
        }
    }

    fun restoreItem(id: UUID) {
        if (Session.readOnlyTemp) return
        try {
            val to = Tree.pathText(db(), Tree.restoreTarget(db(), id).target)
            Session.update { Order.restoreFromBin(it, id) }
            Toast.makeText(ctx, L.t("$to に戻しました", "Restored to $to"), Toast.LENGTH_SHORT).show()
            selection = id; refresh()
        } catch (e: Tree.TreeException) {
            Dlg.message(ctx, ctx.getString(R.string.restore), e.message ?: e.toString(), 'x')
        }
    }

    fun askEmptyBin() {
        if (Session.readOnlyTemp) return
        Dlg.confirm(ctx, ctx.getString(R.string.empty_recycle_bin), ctx.getString(R.string.confirm_empty_bin), '!') {
            Session.update { Tree.emptyRecycleBin(it) }
            selection = null; refresh()
        }
    }

    private fun copy(label: String, s: String, sensitive: Boolean) {
        Clip.copy(ctx, label, s, sensitive)
        Toast.makeText(ctx, L.t("${label}をコピーしました", "${label} copied") + if (sensitive) L.t(" (${Clip.CLEAR_MS / 1000} 秒後に消去)", " (cleared in ${Clip.CLEAR_MS / 1000} s)") else "", Toast.LENGTH_SHORT).show()
    }
}
