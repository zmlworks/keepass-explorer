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
 * "└" path tree, upper item pane (5-column icons or details) and lower preview pane, status bar.
 * Tap = select + preview, double tap = open, long press = context menu, long press + drag = move.
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
    private lateinit var caption: W.Caption
    private val pathBox = FrameLayout(ctx)
    private val pathLines = ArrayList<View>()
    private val content = FrameLayout(ctx)
    private val preview = W.vbox(ctx, 8)
    private lateinit var previewScroll: ScrollView
    private lateinit var statusLeft: TextView
    private lateinit var statusRight: TextView
    private val tools = HashMap<String, View>()
    private var listView: AbsListView? = null
    private val ghost = ImageView(ctx).apply { visibility = View.GONE; alpha = 0.75f }

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
        root.addView(caption.bar, W.lp())

        val tb = W.hbox(ctx).apply { setPadding(W.dp(1), W.dp(2), W.dp(1), W.dp(2)) }
        val dm = ctx.resources.displayMetrics
        val bw = maxOf(W.dp(40), (dm.widthPixels - W.dp(4)) / 9)
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
        tool("back", Px.back16, "戻る") { goBack() }
        tool("up", Px.up16, "上へ") { goUp() }
        tool("view", if (iconMode) Ico.viewDetails16 else Ico.viewIcons16, "表示") { toggleView() }
        tool("newFolder", Px.newFolder16, "フォルダ") { newFolder() }
        tool("newKey", Ico.newKey16, "鍵") { newKey() }
        tool("newMemo", Ico.newMemo16, "メモ") { newMemo() }
        tool("save", Px.floppy16, "保存") { saveNow() }
        tool("find", Px.find16, "検索") { askSearch() }
        tool("menu", Ico.menu16, "メニュー") { Menus.dropDown(tools["menu"]!!, mainMenu()) }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tb) }, W.lp())
        root.addView(W.hsep(ctx))

        pathBox.background = W.Bevel(W.Style.FIELD)
        root.addView(pathBox, W.lp(W.MATCH, W.WRAP, l = 2, r = 2, t = 2, b = 2))

        content.background = W.Bevel(W.Style.FIELD)
        content.setPadding(2 * W.px, 2 * W.px, 2 * W.px, 2 * W.px)
        root.addView(content, W.lp(W.MATCH, 0, 1.15f, l = 2, r = 2))

        // splitter (drag to resize the two panes)
        val split = View(ctx).apply { background = W.Bevel(W.Style.RAISED) }
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
        sb.addView(statusRight, W.lp(W.dp(100), W.WRAP, l = 3))
        root.addView(sb, W.lp())

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
            pathBox.addView(W.text(ctx, "検索: $q", 15f, true).apply { setPadding(W.dp(8), W.dp(6), W.dp(8), W.dp(6)) })
        } else {
            rows = Order.children(g)
            caption.title.text = "${g.name.ifEmpty { "(名前なし)" }} - KeePass Explorer"
            pathBox.addView(Parts.pathTree(ctx, path, onTap = { navigate(it.uuid) }, lines = pathLines))
            App.prefs(ctx).edit().putString("lastGroup", g.uuid.toString()).apply()
        }
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
        val isRoot = current == Tree.root(d).uuid && search == null
        en("back", history.isNotEmpty() || search != null)
        en("up", !isRoot)
        en("newFolder", search == null); en("newKey", search == null); en("newMemo", search == null)
    }

    fun updateStatus() {
        if (!Session.isOpen) return
        val sel = rows.firstOrNull { it.uuid == selection }
        val c = if (cut != null) "  (切り取り中)" else ""
        statusLeft.text = when {
            search != null -> "${rows.size} 件見つかりました"
            sel != null -> "「${label(sel)}」を選択$c"
            else -> "${rows.size} 個のオブジェクト$c"
        }
        statusRight.text = when (Session.saveState) {
            Session.SaveState.SAVED -> "保存済み"
            Session.SaveState.PENDING -> "未保存…"
            Session.SaveState.SAVING -> "保存中…"
            Session.SaveState.ERROR -> "⚠ 保存エラー"
        }
    }

    fun label(r: Order.Node): String = r.name.ifEmpty { if (r is Order.Node.F) "(名前なし)" else "(無題)" }

    private fun isBin(r: Order.Node) = r is Order.Node.F && r.uuid == Tree.recycleBinId(db())

    fun bmpFor(r: Order.Node, large: Boolean): Bitmap {
        val b = when {
            isBin(r) -> if (large) Px.bin32 else Px.bin16
            r.kind == Order.Kind.FOLDER -> if (large) Px.folder32 else Px.folder16
            r.kind == Order.Kind.MEMO -> if (large) Ico.notepad32 else Ico.notepad16
            else -> if (large) Ico.bigKey32 else Ico.key16
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
        if (r == null) {
            preview.addView(W.text(ctx, "項目をタップすると、ここに内容が表示されます。\nダブルタップで開く / 長押しでメニュー / 長押ししたまま動かすと移動。", 14f, color = W.SHADOW)
                .apply { setLineSpacing(0f, 1.2f) })
            return
        }
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
                Order.F_PASS -> kv(k, tv(Tree.password(e), mono = true))
                else -> kv(k, tv(Order.string(e, k)))
            }
        }
    }

    // ------------------------------------------------------------------ item views

    private fun attachGestures(v: AbsListView) {
        listView = v
        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                select(rowAt(v, v.pointToPosition(e.x.toInt(), e.y.toInt()))?.uuid); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                rowAt(v, v.pointToPosition(e.x.toInt(), e.y.toInt()))?.let { open(it) }; return true
            }
            override fun onLongPress(e: MotionEvent) {
                val r = rowAt(v, v.pointToPosition(e.x.toInt(), e.y.toInt()))
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val rx = loc[0] + e.x; val ry = loc[1] + e.y
                // stop the list from scrolling: from now on the drag handler owns this gesture
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                v.onTouchEvent(cancel); cancel.recycle()
                if (r == null) { select(null); Menus.at(v, rx.toInt(), ry.toInt(), backgroundMenu()); return }
                select(r.uuid)
                beginDrag(r, rx, ry)
            }
        })
        v.setOnTouchListener { _, e ->
            if (drag != null) { dragEvent(e); true }
            else { gd.onTouchEvent(e); false }
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
        grid.setPadding(W.dp(2), W.dp(6), W.dp(2), W.dp(6))
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
        attachGestures(grid)
        return if (rows.isEmpty()) empty(grid) else grid
    }

    private fun empty(v: View): View {
        val f = FrameLayout(ctx)
        f.addView(v)
        f.addView(W.text(ctx, if (search != null) "見つかりませんでした。" else "このフォルダーは空です。", 14f, color = W.SHADOW).apply {
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
        try { frame.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
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
                else { endDrag(); val loc = IntArray(2); frame.getLocationInWindow(loc); Menus.at(frame, e.rawX.toInt(), e.rawY.toInt(), itemMenu(d.r)) }
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

    fun searchFor(q: String) { search = q.trim().ifEmpty { null }; selection = null; refresh() }

    private fun askSearch() {
        Dlg.prompt(ctx, "検索", "探す文字列 (タイトル・ユーザー名・URL・メモ・タグ。#タグ で完全一致):", search ?: "", ok = "検索開始") { q ->
            searchFor(q); true
        }
    }

    fun saveNow() {
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
        val bin = Tree.recycleBinId(db())?.let { Tree.findGroup(db(), it) }
        return listOf(
            Menus.Item("ロック") { a.lock() },
            Menus.sep(),
            Menus.Item("バックアップを作成") { quickBackup() },
            Menus.Item("バックアップの一覧...") { a.show(BackupScreen(a)) },
            Menus.Item("ごみ箱を空にする", bin != null && (bin.groups.isNotEmpty() || bin.entries.isNotEmpty())) { askEmptyBin() },
            Menus.sep(),
            Menus.Item("マスターパスワードの変更...") { Security.changePassword(a) { refresh() } },
            Menus.Item(if (a.vault.hasStoredKey()) "指紋認証を無効にする" else "指紋認証を有効にする...", a.vault.isAvailable() || a.vault.hasStoredKey()) {
                Security.toggleBiometric(a) { updateStatus() }
            },
            Menus.Item(if (a.pins.hasPin()) "PIN を変更..." else "PIN を設定...") { Security.setupPin(a) {} },
            Menus.Item("PIN を解除", a.pins.hasPin()) { a.pins.clear(); Toast.makeText(ctx, "PIN を解除しました", Toast.LENGTH_SHORT).show() },
            Menus.Item("画面を保護 (スクリーンショット禁止)", checked = p.getBoolean("secure", true)) {
                p.edit().putBoolean("secure", !p.getBoolean("secure", true)).apply(); a.applySecure()
            },
            Menus.Item("自動ロック: すぐ", checked = lockMin == 0) { p.edit().putInt("autolock", 0).apply() },
            Menus.Item("自動ロック: 5 分後", checked = lockMin == 5) { p.edit().putInt("autolock", 5).apply() },
            Menus.Item("自動ロック: しない", checked = lockMin < 0) { p.edit().putInt("autolock", -1).apply() },
            Menus.sep(),
            Menus.Item("使い方") { Help.usage(a) },
            Menus.Item("データベースの情報") { Help.dbInfo(a) },
            Menus.Item("バージョン情報") { Help.about(a) }
        )
    }

    fun itemMenu(r: Order.Node): List<Menus.Item> {
        val inSearch = search != null
        val bin = isBin(r)
        val idx = rows.indexOfFirst { it.uuid == r.uuid }
        val out = ArrayList<Menus.Item>()
        out.add(Menus.Item("開く") { open(r) })
        if (r is Order.Node.E && r.kind == Order.Kind.KEY) {
            out.add(Menus.Item("ユーザー名をコピー", Tree.userName(r.e).isNotEmpty()) { copy("ユーザー名", Tree.userName(r.e), false) })
            out.add(Menus.Item("パスワードをコピー", Tree.password(r.e).isNotEmpty()) { copy("パスワード", Tree.password(r.e), true) })
            Order.otpUri(r.e)?.let { u -> out.add(Menus.Item("ワンタイムパスワードをコピー") {
                runCatching { io.github.zmlworks.kpexplorer.db.Totp.code(io.github.zmlworks.kpexplorer.db.Totp.parse(u), java.time.Instant.now().epochSecond) }
                    .onSuccess { copy("ワンタイムパスワード", it, true) } }) }
        }
        if (inSearch) out.add(Menus.Item("この項目のフォルダーを開く") {
            Tree.parentOf(Tree.root(db()), r.uuid)?.let { navigate(it.uuid); select(r.uuid) } })
        out.add(Menus.sep())
        out.add(Menus.Item("名前の変更", !bin) { rename(r) })
        out.add(Menus.Item("切り取り", !bin) { cut(r.uuid) })
        if (r is Order.Node.F) out.add(Menus.Item("ここに貼り付け", cut != null && cut != r.uuid) { paste(r.uuid) })
        out.add(Menus.Item("フォルダーへ移動...", !bin) { moveToDialog(r.uuid) })
        out.add(Menus.Item("前へ移動", !inSearch && idx > 0) { moveIndex(r.uuid, idx - 1) })
        out.add(Menus.Item("後ろへ移動", !inSearch && idx in 0 until rows.size - 1) { moveIndex(r.uuid, idx + 1) })
        out.add(Menus.sep())
        if (bin) out.add(Menus.Item("ごみ箱を空にする") { askEmptyBin() })
        out.add(Menus.Item("削除") { askDelete(r.uuid) })
        return out
    }

    fun backgroundMenu(): List<Menus.Item> {
        val ns = search == null
        return listOf(
            Menus.Item(if (iconMode) "表示: 詳細" else "表示: アイコン") { toggleView() },
            Menus.Item("貼り付け", cut != null && ns) { paste(current) },
            Menus.sep(),
            Menus.Item("新しいフォルダ", ns) { newFolder() },
            Menus.Item("新しい鍵", ns) { newKey() },
            Menus.Item("新しいメモ", ns) { newMemo() }
        )
    }

    // ------------------------------------------------------------------ operations

    fun newFolder(): UUID {
        val g = Tree.newGroup(Order.uniqueChildName(currentGroup(), "新しいフォルダ"))
        Session.update { Order.appendGroup(it, current, g) }
        selection = g.uuid; refresh()
        return g.uuid
    }

    fun newMemo(): UUID {
        val e = Order.newMemo(Order.uniqueChildName(currentGroup(), "新しいメモ"))
        Session.update { Order.appendEntry(it, current, e) }
        selection = e.uuid; refresh()
        return e.uuid
    }

    fun newKey() = a.show(KeyScreen(a, null, current))

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

    fun moveToDialog(id: UUID) {
        val d = db()
        var target: UUID? = null
        val tree = FolderTree(ctx, Tree.root(d), null, Tree.recycleBinId(d), disabled = setOf(id),
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
        val d = db()
        val r = rows.firstOrNull { it.uuid == id } ?: return
        val toBin = d.content.meta.recycleBinEnabled && !Tree.isInRecycleBin(d, id)
        val what = if (r is Order.Node.F) "フォルダー「${label(r)}」とその内容" else "「${label(r)}」"
        val msg = if (toBin) "$what をごみ箱に移動しますか?" else "$what を完全に削除しますか?\nこの操作は元に戻せません。"
        Dlg.confirm(ctx, "削除の確認", msg, if (toBin) '?' else '!') {
            Session.update { Tree.delete(it, id).first }
            if (cut == id) cut = null
            selection = null; refresh()
        }
    }

    private fun askEmptyBin() {
        Dlg.confirm(ctx, "ごみ箱を空にする", "ごみ箱の中身をすべて完全に削除しますか?\nこの操作は元に戻せません。", '!') {
            Session.update { Tree.emptyRecycleBin(it) }; refresh()
        }
    }

    private fun copy(label: String, s: String, sensitive: Boolean) {
        Clip.copy(ctx, label, s, sensitive)
        Toast.makeText(ctx, "${label}をコピーしました" + if (sensitive) " (${Clip.CLEAR_MS / 1000} 秒後に消去)" else "", Toast.LENGTH_SHORT).show()
    }

    fun quickBackup() {
        val busy = Dlg.busy(ctx, "バックアップ", "バックアップを作成しています…")
        Session.afterSave({ a.store.backup() }) { res ->
            busy.dismiss()
            res.onSuccess { f -> statusLeft.text = "バックアップを作成しました: ${f.name}" }
               .onFailure { Dlg.message(ctx, "バックアップ", "バックアップを作成できませんでした。\n${it.message ?: it}", 'x') }
        }
    }
}
