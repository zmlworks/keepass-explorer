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
 * Touch an item and move = drag (no long press); scroll from empty space or the scrollbar strip on the right.
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

    private val frame = FrameLayout(ctx)
    private val root = W.vbox(ctx).apply { setBackgroundColor(W.FACE) }
    override val view: View get() = frame
    lateinit var caption: W.Caption
        private set
    private val pathBox = FrameLayout(ctx)
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
    private val pathLines = ArrayList<View>()
    private val content = FrameLayout(ctx)
    private val preview = W.vbox(ctx, 8)
    private lateinit var previewScroll: ScrollView
    private lateinit var split: View
    private var shownSaveError: String? = null
    private val tools = HashMap<String, View>()
    private var listView: AbsListView? = null
    private val ghost = ImageView(ctx).apply { visibility = View.GONE; alpha = 0.75f }

    companion object { const val LIVE_MS = 150L }

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
        tool("up", Px.up16, "上へ") { goUp() }
        tool("view", if (iconMode) Ico.viewDetails16 else Ico.viewIcons16, "表示") { toggleView() }
        sep()
        tool("newFolder", Px.newFolder16, "フォルダ") { newFolder() }
        tool("newKey", Ico.newKey16, "鍵") { newKey() }
        tool("newMemo", Ico.newMemo16, "メモ") { newMemo() }
        sep()
        tool("save", Px.floppy16, "保存") { Security.saveDialog(a) }
        tool("menu", Ico.menu16, "メニュー") { Menus.dropDown(tools["menu"]!!, mainMenu()) }
        tool("find", Px.find16, "検索") { if (search == null) openSearch() else closeSearch() }
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

        content.background = W.Bevel(W.Style.FIELD)
        content.setPadding(2 * W.px, 2 * W.px, 2 * W.px, 2 * W.px)
        root.addView(content, W.lp(W.MATCH, 0, 1.15f, l = 2, r = 2))

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

        previewScroll = ScrollView(ctx).apply { background = W.Bevel(W.Style.FIELD); W.classicScrollbars(this); isFillViewport = true }
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
        pathBox.removeAllViews(); pathLines.clear()
        if (q != null) {
            rows = Tree.search(d, q).map { Order.Node.E(it.second) }
            caption.title.text = "検索結果 - KeePass Explorer"
            val ws = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val tg = ws.filter { it.startsWith("#") && it.length > 1 }.map { it.substring(1) }
            val tx = ws.filterNot { it.startsWith("#") && it.length > 1 }
            val desc = listOfNotNull(tx.takeIf { it.isNotEmpty() }?.let { "文字: ${it.joinToString(" ")}" },
                tg.takeIf { it.isNotEmpty() }?.let { "タグ: ${it.joinToString(" + ")}" }).joinToString("  ")
            pathBox.addView(W.text(ctx, if (q.isEmpty()) "文字を入力するか、タグを選んでください" else "検索結果 ${rows.size} 件  $desc", 15f, true).apply { setPadding(W.dp(8), W.dp(6), W.dp(8), W.dp(6)) })
        } else {
            rows = Order.children(g)
            caption.title.text = "${groupLabel(g)} - KeePass Explorer"
            val tree = FolderTree(ctx, Tree.root(d), g.uuid, null,
                onToggle = { gid, exp -> if (!Session.readOnlyTemp) Session.update { Tree.setExpanded(it, gid, exp) } },
                onSelect = { }, showCounts = true, current = g.uuid, onJump = { navigate(it) }, rowDp = 32)
            folderTree = tree
            pathLines.addAll(tree.rows)
            tree.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(p: View?, c: View?) { pathLines.clear(); pathLines.addAll(tree.rows) }
                override fun onChildViewRemoved(p: View?, c: View?) {}
            })
            pathBox.addView(MaxScroll(ctx, (ctx.resources.displayMetrics.heightPixels * 0.3f).toInt()).apply {
                W.classicScrollbars(this); addView(tree) })
            post { folderTree?.rows?.firstOrNull { it.tag == g.uuid }?.let { v -> (pathBox.getChildAt(0) as? ScrollView)?.scrollTo(0, maxOf(0, v.top - W.dp(32))) } }
            App.prefs(ctx).edit().putString("lastGroup", g.uuid.toString()).apply()
        }
        searchPanel.visibility = if (q != null) View.VISIBLE else View.GONE
        if (selection != null && rows.none { it.uuid == selection }) selection = null
        if (cut != null && Tree.parentOf(Tree.root(d), cut!!) == null) cut = null
        content.removeAllViews()
        content.addView(if (iconMode) buildGrid() else buildList(), FrameLayout.LayoutParams(W.MATCH, W.MATCH))
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
        en("back", history.isNotEmpty() || search != null)
        en("up", !isRoot)
        en("newFolder", search == null && !ro); en("newKey", search == null && !ro); en("newMemo", search == null && !ro)
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
            Dlg.message(ctx, "保存エラー", "変更を保存できませんでした。\n$err", 'x')
        }
    }

    /** The root folder has no name and is shown as "/". */
    fun groupLabel(g: Group): String = if (g.uuid == Tree.root(db()).uuid) "/" else g.name.ifEmpty { "(名前なし)" }

    fun label(r: Order.Node): String = r.name.ifEmpty { if (r is Order.Node.F) "(名前なし)" else "(無題)" }

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
            ic != null -> io.github.zmlworks.kpexplorer.ui.MaterialIcons.bitmap(ctx, ic, sz)
            r.kind == Order.Kind.FOLDER -> if (large) Px.folder32 else Px.folder16
            r.kind == Order.Kind.MEMO -> if (large) Ico.notepad32 else Ico.notepad16
            else -> io.github.zmlworks.kpexplorer.ui.MaterialIcons.bitmap(ctx, "key", sz)
        }
        return when {
            r.uuid == selection -> Px.selected(b)
            r.uuid == cut || r.uuid == drag?.id -> Px.ghost(b)
            else -> b
        }
    }

    // ------------------------------------------------------------------ preview pane

    fun updatePreview() {
        preview.removeAllViews()
        val r = rows.firstOrNull { it.uuid == selection }
        val vis = if (r == null) View.GONE else View.VISIBLE
        previewScroll.visibility = vis; split.visibility = vis
        if (r == null) return
        previewScroll.scrollTo(0, 0)
        val head = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(W.icon(ctx, bmpFor(r, true).let { if (r.uuid == selection) unselected(r) else it }, maxOf(1, W.iconScale() - 2)), W.lp(W.WRAP, W.WRAP, r = 8))
        head.addView(W.text(ctx, label(r), 17f, true), W.lp(0, W.WRAP, 1f))
        preview.addView(head, W.lp(W.MATCH, W.WRAP, b = 6))
        when (r) {
            is Order.Node.F -> {
                val kids = Order.children(r.g)
                val nf = kids.count { it.kind == Order.Kind.FOLDER }; val nk = kids.count { it.kind == Order.Kind.KEY }
                val nm = kids.count { it.kind == Order.Kind.MEMO }
                preview.addView(W.text(ctx, "フォルダー ${nf} 個、鍵 ${nk} 個、メモ ${nm} 個", 14f))
                kids.take(30).forEach { k -> preview.addView(W.text(ctx, "  ・" + label(k), 14f), W.lp(W.MATCH, W.WRAP, t = 2)) }
            }
            is Order.Node.E -> if (r.kind == Order.Kind.MEMO) {
                preview.addView(Parts.RuledText(ctx, editable = false).apply { setText(Tree.notes(r.e)); minLines = 4 }, W.lp(W.MATCH, W.WRAP))
            } else keyPreview(r.e)
        }
    }

    private fun unselected(r: Order.Node): Bitmap = when {
        isBin(r) -> Px.bin32; r.kind == Order.Kind.FOLDER -> Px.folder32; r.kind == Order.Kind.MEMO -> Ico.notepad32; else -> Ico.bigKey32
    }

    private fun keyPreview(e: Entry) {
        preview.addView(W.text(ctx, KeyFields.lastModified(e), 13f, color = W.SHADOW), W.lp(W.MATCH, W.WRAP, b = 4))
        fun kv(k: String, v: View) {
            val row = W.hbox(ctx).apply { gravity = Gravity.TOP }
            row.addView(W.text(ctx, KeyFields.label(k), 14f, true), W.lp(W.dp(76), W.WRAP))
            row.addView(v, W.lp(0, W.WRAP, 1f))
            preview.addView(row, W.lp(W.MATCH, W.WRAP, b = 4))
        }
        fun tv(s: String, mono: Boolean = false) = W.text(ctx, s, 14f).apply {
            setTextIsSelectable(true); if (mono) typeface = android.graphics.Typeface.MONOSPACE
        }
        for ((k, vis) in Order.layout(e)) {
            if (!vis) continue
            when (k) {
                Order.F_TAGS -> kv(k, tv(Tree.formatTags(e.tags)))
                Order.F_OTP -> Order.otpUri(e)?.let { u -> kv("ワンタイム:", KeyFields.OtpView(ctx) { u }.apply { textSize = 15f }) }
                Order.F_PASS -> kv(k, tv(Tree.password(e), mono = true).apply {
                    // masked by default; tap = show / hide
                    setTextIsSelectable(false); var shown = false
                    io.github.zmlworks.kpexplorer.ui.FieldBox.mask(this, true); tag = "pass"
                    isClickable = true; setOnClickListener { shown = !shown; io.github.zmlworks.kpexplorer.ui.FieldBox.mask(this, !shown) }
                })
                else -> kv(k, tv(Order.string(e, k)))
            }
        }
    }

    // ------------------------------------------------------------------ item views

    /**
     * Touch handling (no GestureDetector):
     *  - finger down on an item and moves past a small slop → drag that item (the list never sees the move);
     *  - finger down on empty space, or in the scrollbar strip at the right edge ([gutter]) → normal list scrolling;
     *  - no movement: short tap = folder opens / file selected (or opened if it was already selected);
     *    long press = context menu (background menu on empty space).
     */
    private fun attachGestures(v: AbsListView) {
        listView = v
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 0.6f
        val longMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downR: Order.Node? = null; var onItem = false; var x0 = 0f; var y0 = 0f; var moved = false; var longFired = false
        val long = Runnable {
            longFired = true
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { v.onTouchEvent(it); it.recycle() }
            try { frame.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
            val r = downR
            if (r == null) { select(null); Menus.at(v, (loc[0] + x0).toInt(), (loc[1] + y0).toInt(), backgroundMenu()) }
            else { if (r !is Order.Node.F) select(r.uuid); Menus.at(v, (loc[0] + x0).toInt(), (loc[1] + y0).toInt(), itemMenu(r)) }
        }
        v.setOnTouchListener { _, e ->
            if (drag != null) { dragEvent(e); return@setOnTouchListener true }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y; moved = false; longFired = false
                    val inGutter = e.x >= v.width - gutter()
                    downR = if (inGutter) null else rowAt(v, v.pointToPosition(e.x.toInt(), e.y.toInt()))
                    onItem = downR != null
                    if (!inGutter) v.postDelayed(long, longMs)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.x - x0) > slop || abs(e.y - y0) > slop)) {
                        moved = true; v.removeCallbacks(long)
                        val r = downR
                        if (onItem && r != null && !longFired) {
                            // take the gesture away from the list before it starts scrolling
                            MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.also { v.onTouchEvent(it); it.recycle() }
                            beginDrag(r, e.rawX - (e.x - x0), e.rawY - (e.y - y0))
                            drag!!.moving = true
                            dragTo(e.rawX, e.rawY)
                            return@setOnTouchListener true
                        }
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(long)
                    if (!moved && !longFired) tap(downR)
                    false
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(long); false }
                else -> false
            }
        }
    }

    /** Width of the right-edge strip that always scrolls (the list's scrollbar lives there). */
    private fun gutter() = W.dp(28)

    /** Folder: open. Key / memo: first tap selects, tapping the selected item again opens it. Empty: deselect. */
    fun tap(r: Order.Node?) {
        when {
            r == null -> select(null)
            r is Order.Node.F -> open(r)
            r.uuid == selection -> open(r)
            else -> select(r.uuid)
        }
    }

    private fun rowAt(v: AbsListView, pos: Int): Order.Node? {
        if (pos == AbsListView.INVALID_POSITION) return null
        val p = if (v is ListView) pos - v.headerViewsCount else pos
        return rows.getOrNull(p)
    }

    fun select(id: UUID?) {
        selection = id
        notifyList(); updatePreview(); updateStatus()
    }

    private fun notifyList() {
        val v = listView ?: return
        (v.adapter as? BaseAdapter)?.notifyDataSetChanged()
        ((v as? ListView)?.adapter as? android.widget.HeaderViewListAdapter)?.wrappedAdapter?.let { (it as BaseAdapter).notifyDataSetChanged() }
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
        grid.setPadding(W.dp(2), W.dp(6), gutter(), W.dp(6))
        grid.scrollBarStyle = View.SCROLLBARS_OUTSIDE_INSET
        grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(0)
        grid.setBackgroundColor(W.WINDOW)
        W.classicScrollbars(grid)
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
                drop?.takeIf { it.target == r.uuid }?.let { cell.foreground = Marker(it.mode, false) }
                cell.layoutParams = AbsListView.LayoutParams(W.MATCH, W.dp(32) * iconScale / 2 + W.dp(48))
                return cell
            }
        }
        grid.isScrollbarFadingEnabled = false
        attachGestures(grid)
        return if (rows.isEmpty()) empty(grid) else grid
    }

    private fun empty(v: View): View {
        val f = FrameLayout(ctx)
        f.addView(v)
        f.addView(W.text(ctx, if (search != null) (if (search!!.isEmpty()) "" else "見つかりませんでした。") else "このフォルダーは空です。", 14f, color = W.SHADOW).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, FrameLayout.LayoutParams(W.MATCH, W.WRAP).apply { topMargin = W.dp(40) })
        return f
    }

    private fun buildList(): View {
        val col = W.vbox(ctx).apply { setBackgroundColor(W.WINDOW) }
        val header = W.hbox(ctx)
        fun h(s: String, w: Int, weight: Float) = header.addView(W.text(ctx, s, 13f).apply {
            background = W.Bevel(W.Style.HEADER); setPadding(W.dp(6), W.dp(6), W.dp(4), W.dp(6)); isSingleLine = true
        }, W.lp(w, W.WRAP, weight))
        val inSearch = search != null
        h("名前", 0, 1.4f)
        h(if (inSearch) "場所" else "ユーザー名", 0, 1f)
        h("更新日時", W.dp(92), 0f)
        col.addView(header, W.lp())
        val list = ListView(ctx)
        list.setPadding(0, 0, gutter(), 0)
        list.scrollBarStyle = View.SCROLLBARS_OUTSIDE_INSET
        list.divider = null
        list.selector = android.graphics.drawable.ColorDrawable(0)
        W.classicScrollbars(list)
        val dateFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(java.time.ZoneId.systemDefault())
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val r = rows[p]
                val row = W.hbox(ctx).apply { setPadding(W.dp(4), 0, 0, 0); minimumHeight = W.dp(40); gravity = Gravity.CENTER_VERTICAL }
                row.addView(ImageView(ctx).apply { setImageDrawable(Px.drawable(ctx, bmpFor(r, false), W.iconScale())) }, W.lp(W.WRAP, W.WRAP, r = 4))
                val sel = r.uuid == selection
                val nameBox = FrameLayout(ctx)
                nameBox.addView(W.text(ctx, label(r), 15f, color = if (sel) W.HILITE else W.TEXT).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setPadding(W.dp(3), W.dp(2), W.dp(3), W.dp(2))
                    if (sel) setBackgroundColor(W.NAVY)
                }, FrameLayout.LayoutParams(W.WRAP, W.WRAP, Gravity.CENTER_VERTICAL))
                row.addView(nameBox, W.lp(0, W.WRAP, 1.4f))
                val second = when (r) {
                    is Order.Node.E -> if (inSearch) Tree.parentOf(Tree.root(db()), r.uuid)?.name.orEmpty()
                        else if (r.kind == Order.Kind.MEMO) "メモ" else Tree.userName(r.e)
                    is Order.Node.F -> "フォルダー"
                }
                row.addView(W.text(ctx, second, 14f, color = if (inSearch) W.SHADOW else W.TEXT).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(W.dp(6), 0, W.dp(2), 0)
                }, W.lp(0, W.WRAP, 1f))
                val t = when (r) { is Order.Node.E -> r.e.times?.lastModificationTime; is Order.Node.F -> r.g.times?.lastModificationTime }
                row.addView(W.text(ctx, t?.let { dateFmt.format(it) } ?: "", 13f).apply { isSingleLine = true; setPadding(W.dp(6), 0, 0, 0) },
                    W.lp(W.dp(92), W.WRAP))
                drop?.takeIf { it.target == r.uuid }?.let { row.foreground = Marker(it.mode, true) }
                return row
            }
        }
        list.isScrollbarFadingEnabled = false
        attachGestures(list)
        col.addView(if (rows.isEmpty()) empty(list) else list, W.lp(W.MATCH, 0, 1f))
        return col
    }

    // ------------------------------------------------------------------ drag & drop

    private class DragState(val id: UUID, val r: Order.Node, val x0: Float, val y0: Float) { var moving = false }
    /** Where a drop would go: [target] item (or folder in the path tree) and how. */
    data class Drop(val mode: Mode, val target: UUID?, val pathFolder: Boolean = false) { enum class Mode { INTO, BEFORE, AFTER, END } }

    private var drag: DragState? = null
    private var drop: Drop? = null

    private fun beginDrag(r: Order.Node, rx: Float, ry: Float) {
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
        for (line in pathLines) {
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
        val pos = v.pointToPosition(x.toInt(), y.toInt())
        val r = rowAt(v, pos) ?: return if (search == null) Drop(Drop.Mode.END, null) else null
        if (search != null) return if (r.kind == Order.Kind.FOLDER && r.uuid != dragged) Drop(Drop.Mode.INTO, r.uuid) else null
        if (r.uuid == dragged) return null
        val cell = v.getChildAt(pos - v.firstVisiblePosition) ?: return null
        val vertical = v is ListView
        val f = if (vertical) (y - cell.top) / cell.height.coerceAtLeast(1) else (x - cell.left) / cell.width.coerceAtLeast(1)
        return if (r.kind == Order.Kind.FOLDER) when {
            f < 0.22f -> Drop(Drop.Mode.BEFORE, r.uuid)
            f > 0.78f -> Drop(Drop.Mode.AFTER, r.uuid)
            else -> Drop(Drop.Mode.INTO, r.uuid)
        } else Drop(if (f < 0.5f) Drop.Mode.BEFORE else Drop.Mode.AFTER, r.uuid)
    }

    fun performDrop(id: UUID, dr: Drop) {
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
            Dlg.message(ctx, "移動エラー", "移動できません: 移動先のフォルダーは、移動するフォルダーの中にあります。", 'x')
        }
    }

    // ------------------------------------------------------------------ navigation

    fun navigate(id: UUID, push: Boolean = true) {
        if (search == null && id == current) return
        if (push) { history.add(current); if (history.size > 50) history.removeAt(0) }
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
        select(from)
    }

    fun goBack() {
        if (search != null) { search = null; refresh(); return }
        val prev = history.removeLastOrNull() ?: return
        if (Tree.findGroup(db(), prev) != null) navigate(prev, push = false) else goBack()
    }

    override fun onBack(): Boolean {
        if (search != null) { search = null; refresh(); return true }
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
        searchField = W.field(ctx).apply { isSingleLine = true; hint = "文字で検索 (名前・ユーザー名・URL・メモ)" }
        row.addView(searchField, W.lp(0, W.WRAP, 1f))
        row.addView(W.button(ctx, "クリア") { searchTags.clear(); searchField.setText(""); renderSearchTags(); applyLiveSearch() }, W.lp(W.WRAP, W.WRAP, l = 4))
        row.addView(W.button(ctx, "閉じる") { closeSearch() }, W.lp(W.WRAP, W.WRAP, l = 4))
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
            r.onSuccess { Toast.makeText(ctx, "保存しました", Toast.LENGTH_SHORT).show() }
             .onFailure { Dlg.message(ctx, "保存", "保存できませんでした。\n${it.message ?: it}", 'x') }
            updateStatus()
        }
    }

    // ------------------------------------------------------------------ menus

    fun mainMenu(): List<Menus.Item> {
        val p = App.prefs(ctx)
        val lockMin = a.autoLockMinutes()
        val selEntry = selection?.let { id -> rows.find { it.uuid == id } as? Order.Node.E }
        val histEnabled = selEntry != null
        val ro = Session.readOnlyTemp
        val out = ArrayList<Menus.Item>()
        if (ro) {
            out.add(Menus.Item(ctx.getString(R.string.temp_exit)) { TempImport.exitTemp(a) })
            out.add(Menus.sep())
        }
        out.add(Menus.Item(ctx.getString(R.string.menu_lock)) { a.lock() })
        out.add(Menus.Item(ctx.getString(R.string.menu_history), histEnabled) {
            val id = selEntry?.uuid ?: return@Item
            io.github.zmlworks.kpexplorer.ui.HistoryViewer.show(ctx, id)
                ?: Dlg.message(ctx, ctx.getString(R.string.history_title), ctx.getString(R.string.history_missing), '!')
        })
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
        out.add(Menus.Item(ctx.getString(R.string.menu_autolock_now), checked = lockMin == 0) { p.edit().putInt("autolock", 0).apply() })
        out.add(Menus.Item(ctx.getString(R.string.menu_autolock_5), checked = lockMin == 5) { p.edit().putInt("autolock", 5).apply() })
        out.add(Menus.Item(ctx.getString(R.string.menu_autolock_never), checked = lockMin < 0) { p.edit().putInt("autolock", -1).apply() })
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.menu_usage)) { Help.usage(a) })
        out.add(Menus.Item(ctx.getString(R.string.menu_db_info)) { Help.dbInfo(a) })
        out.add(Menus.Item(ctx.getString(R.string.menu_about)) { Help.about(a) })
        return out
    }

    fun itemMenu(r: Order.Node): List<Menus.Item> {
        val inSearch = search != null
        val bin = isBin(r)
        val binFolder = isRecycleBinFolder(r)
        val inBin = Tree.isInRecycleBin(db(), r.uuid) && !binFolder
        val ro = Session.readOnlyTemp
        val idx = rows.indexOfFirst { it.uuid == r.uuid }
        val out = ArrayList<Menus.Item>()
        out.add(Menus.Item(ctx.getString(R.string.open)) { open(r) })
        if (r is Order.Node.E && r.kind == Order.Kind.KEY) {
            out.add(Menus.Item(ctx.getString(R.string.copy_username), Tree.userName(r.e).isNotEmpty()) { copy(ctx.getString(R.string.copy_username), Tree.userName(r.e), false) })
            out.add(Menus.Item(ctx.getString(R.string.copy_password), Tree.password(r.e).isNotEmpty()) { copy(ctx.getString(R.string.copy_password), Tree.password(r.e), true) })
            Order.otpUri(r.e)?.let { u -> out.add(Menus.Item(ctx.getString(R.string.copy_otp)) {
                runCatching { io.github.zmlworks.kpexplorer.db.Totp.code(io.github.zmlworks.kpexplorer.db.Totp.parse(u), java.time.Instant.now().epochSecond) }
                    .onSuccess { copy(ctx.getString(R.string.copy_otp), it, true) } }) }
        }
        if (inSearch) out.add(Menus.Item(ctx.getString(R.string.open_item_folder)) {
            Tree.parentOf(Tree.root(db()), r.uuid)?.let { navigate(it.uuid); select(r.uuid) } })
        if (ro) return out  // copy/open only in read-only
        out.add(Menus.sep())
        if (inBin) {
            out.add(Menus.Item(ctx.getString(R.string.restore)) { restoreItem(r.uuid) })
            out.add(Menus.Item(ctx.getString(R.string.delete_permanently)) { askDelete(r.uuid) })
            return out
        }
        if (binFolder) {
            out.add(Menus.Item(ctx.getString(R.string.empty_recycle_bin), Tree.recycleBinChildCount(db()) > 0) { askEmptyBin() })
            return out
        }
        out.add(Menus.Item(ctx.getString(R.string.rename), !bin) { rename(r) })
        out.add(Menus.Item(ctx.getString(R.string.cut), !bin) { cut(r.uuid) })
        if (r is Order.Node.F) out.add(Menus.Item(ctx.getString(R.string.paste_here), cut != null && cut != r.uuid) { paste(r.uuid) })
        out.add(Menus.Item(ctx.getString(R.string.move_to_folder), !bin) { moveToDialog(r.uuid) })
        if (r is Order.Node.E && !bin) {
            val canUp = canMoveToParentFolder(r.uuid)
            out.add(Menus.Item(ctx.getString(R.string.move_up_folder), canUp) { moveToParentFolder(r.uuid) })
        }
        out.add(Menus.Item(ctx.getString(R.string.move_earlier), !inSearch && idx > 0) { moveIndex(r.uuid, idx - 1) })
        out.add(Menus.Item(ctx.getString(R.string.move_later), !inSearch && idx in 0 until rows.size - 1) { moveIndex(r.uuid, idx + 1) })
        out.add(Menus.sep())
        out.add(Menus.Item(ctx.getString(R.string.delete)) { askDelete(r.uuid) })
        return out
    }

    fun backgroundMenu(): List<Menus.Item> {
        val ns = search == null
        val ro = Session.readOnlyTemp
        return listOf(
            Menus.Item(if (iconMode) ctx.getString(R.string.view_details) else ctx.getString(R.string.view_icons)) { toggleView() },
            Menus.Item(ctx.getString(R.string.paste), cut != null && ns && !ro) { paste(current) },
            Menus.sep(),
            Menus.Item(ctx.getString(R.string.tool_new_folder), ns && !ro) { newFolder() },
            Menus.Item(ctx.getString(R.string.tool_new_key), ns && !ro) { newKey() },
            Menus.Item(ctx.getString(R.string.tool_new_memo), ns && !ro) { newMemo() }
        )
    }

    // ------------------------------------------------------------------ operations

    fun newFolder(): UUID {
        if (Session.readOnlyTemp) throw IllegalStateException("read-only")
        val g = Tree.newGroup(Order.uniqueChildName(currentGroup(), ctx.getString(R.string.new_folder_default)))
        Session.update { Order.appendGroup(it, current, g) }
        selection = g.uuid; refresh()
        return g.uuid
    }

    fun newMemo(): UUID {
        if (Session.readOnlyTemp) throw IllegalStateException("read-only")
        val e = Order.newMemo(Order.uniqueChildName(currentGroup(), ctx.getString(R.string.new_memo_default)))
        Session.update { Order.appendEntry(it, current, e) }
        selection = e.uuid; refresh()
        return e.uuid
    }

    fun newKey() = a.show(NewKeyScreen(a, current))

    fun rename(r: Order.Node) {
        Dlg.prompt(ctx, "名前の変更", "新しい名前:", r.name) { n ->
            when (r) {
                is Order.Node.F -> Session.update { Tree.renameGroup(it, r.uuid, n) }
                is Order.Node.E -> Session.update { Order.renameEntry(it, r.uuid, n) }
            }
            refresh(); true
        }
    }

    fun cut(id: UUID) {
        cut = id; refresh()
        Toast.makeText(ctx, "切り取りました。移動先で「貼り付け」を選んでください。", Toast.LENGTH_SHORT).show()
    }

    fun paste(target: UUID) {
        val id = cut ?: return
        try { Session.update { Order.moveTo(it, id, target) }; cut = null; refresh() }
        catch (e: Tree.TreeException) { Dlg.message(ctx, "移動エラー", "移動できません: 移動先のフォルダーは、移動するフォルダーの中にあります。", 'x') }
    }

    fun moveIndex(id: UUID, index: Int) { Session.update { Order.moveTo(it, id, current, index) }; selection = id; refresh() }

    /** Parent of the entry's containing folder (null when the entry lives in the database root). */
    fun parentFolderOfEntry(id: UUID): UUID? {
        val root = Tree.root(db())
        val containing = Tree.parentOf(root, id) ?: return null
        if (containing.uuid == root.uuid) return null
        return Tree.parentOf(root, containing.uuid)?.uuid
    }

    fun canMoveToParentFolder(id: UUID): Boolean = parentFolderOfEntry(id) != null

    /**
     * Move an entry into the parent of its current folder, at the front of that folder's
     * combined display order (index 0). No-op for root-level entries.
     */
    fun moveToParentFolder(id: UUID) {
        val dest = parentFolderOfEntry(id) ?: return
        try {
            Session.update { Order.moveTo(it, id, dest, 0) }
            selection = id
            // Navigate to destination so the user sees the entry at the front
            if (search == null) navigate(dest)
            refresh()
        } catch (e: Tree.TreeException) {
            Dlg.message(ctx, "移動エラー", "移動できませんでした。", 'x')
        }
    }

    fun moveToDialog(id: UUID) {
        val d = db()
        var target: UUID? = null
        val tree = FolderTree(ctx, Tree.root(d), null, null, disabled = setOf(id),
            onToggle = { gid, exp -> Session.update { Tree.setExpanded(it, gid, exp) } },
            onSelect = { target = it })
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, "「${rows.firstOrNull { it.uuid == id }?.let { label(it) } ?: ""}」の移動先:", 15f), W.lp(W.MATCH, W.WRAP, b = 8))
        col.addView(FrameLayout(ctx).apply { background = W.Bevel(W.Style.FIELD); setPadding(2 * W.px, 2 * W.px, 2 * W.px, 2 * W.px); addView(tree) })
        ClassicDialog(ctx, "フォルダーへ移動", col, listOf(
            ClassicDialog.Btn("移動", true) {
                val t = target ?: return@Btn false
                try { Session.update { Order.moveTo(it, id, t) }; refresh(); true }
                catch (e: Tree.TreeException) { Dlg.message(ctx, "移動エラー", "そのフォルダーには移動できません。", 'x'); false }
            },
            ClassicDialog.Btn("キャンセル")), scroll = true).show()
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
        fun perform() {
            val binName = ctx.getString(R.string.recycle_bin)
            Session.update {
                if (soft) Tree.moveToBin(it, id, binName)
                else Tree.deletePermanently(it, id)
            }
            if (cut == id) cut = null
            selection = null; refresh()
        }
        // Soft move-to-bin: no confirmation (t522u). Permanent / from-bin still confirms.
        if (soft) perform()
        else Dlg.confirm(ctx, ctx.getString(R.string.delete_confirm_title),
            ctx.getString(R.string.confirm_delete_permanent, what), '!') { perform() }
    }

    fun restoreItem(id: UUID) {
        if (Session.readOnlyTemp) return
        try {
            Session.update { Tree.restoreFromBin(it, id) }
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
        Toast.makeText(ctx, "${label}をコピーしました" + if (sensitive) " (${Clip.CLEAR_MS / 1000} 秒後に消去)" else "", Toast.LENGTH_SHORT).show()
    }
}
