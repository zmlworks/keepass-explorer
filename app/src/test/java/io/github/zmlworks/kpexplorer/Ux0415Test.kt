package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Store
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.FieldEditSession
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.W
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 0.4.15: view item on top of every menu, name-column drag in detail view, folder menu, two taps for folders, name prompt for new folder / memo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0415Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0415").mkdirs() }
    private val key = Kdbx.compositeKey("sample")
    private lateinit var a: MainActivity
    private val ids = HashMap<String, UUID>()
    private val W_ = 411 * 3; private val H_ = 891 * 3

    private fun app() = RuntimeEnvironment.getApplication()
    private fun idle() { repeat(30) { ShadowLooper.idleMainLooper(); Thread.sleep(3) } }

    @Before fun setUp() {
        Session.lock(); Session.store = null; Session.debounceMs = 50
        Vault(app()).clear(); App.prefs(app()).edit().clear().commit()
        Vault.forceAvailableForTest = true
        App.store(app()).let { s -> s.delete(); s.backups().forEach { it.delete() } }
        File(app().filesDir, "data-version").writeText(App.DATA_VERSION.toString())
        var db = Kdbx.create("t", key, rootName = "", light = true)
        val root = Tree.root(db).uuid
        val g = Tree.newGroup("仕事"); db = Tree.addGroup(db, root, g); ids["G"] = g.uuid
        db = Tree.addGroup(db, root, Tree.newGroup("銀行"))
        for ((t, u) in listOf("Gmail" to "taro.yamada@gmail.com", "Amazon" to "taro_y", "家のWi-Fi" to "MyHome-5G",
                "ネット銀行 (メイン口座)" to "1234-5678-9012", "会社のVPN" to "t.yamada", "Steam" to "taro_games",
                "楽天" to "taro.r", "X (Twitter)" to "@taro")) {
            val e = Tree.newEntry(Tree.Fields(t, u, "p", "", "", emptyList())); db = Tree.addEntry(db, root, e); ids[t] = e.uuid
        }
        db = Tree.addEntry(db, root, Order.newMemo("買い物メモ"))
        val old = Tree.newEntry(Tree.Fields("古いアカウント", "old", "x", "", "", emptyList())); db = Tree.addEntry(db, g.uuid, old); ids["old"] = old.uuid
        db = Tree.moveToBin(db, old.uuid, "ごみ箱")
        App.store(app()).write(Kdbx.encodeVerified(db, keepSalt = true))
        Session.open(db); Session.store = App.store(app())
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        if (!Session.isOpen) Session.open(db)
        Session.store = a.store
        if (a.explorer == null) a.showExplorer(); idle()
    }
    @After fun tearDown() { runCatching { FieldEditSession.commitActive() }; Session.lock(); Vault.forceAvailableForTest = null; Menus.dismiss(); Dlg.last = null }

    private fun layoutHost() {
        a.host.measure(View.MeasureSpec.makeMeasureSpec(W_, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_, View.MeasureSpec.EXACTLY))
        a.host.layout(0, 0, W_, H_); idle()
    }
    private fun shot(name: String, menuAt: Pair<Int, Int>? = null, dialog: Boolean = false) {
        layoutHost()
        val b = Bitmap.createBitmap(W_, H_, Bitmap.Config.ARGB_8888); val c = Canvas(b); a.host.draw(c)
        if (menuAt != null) Menus.lastView?.let { v ->
            v.measure(View.MeasureSpec.makeMeasureSpec(Menus.current?.width ?: W.dp(230), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_, View.MeasureSpec.AT_MOST))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            c.save(); c.translate(menuAt.first.toFloat(), menuAt.second.toFloat()); v.draw(c); c.restore()
        }
        if (dialog) Dlg.last?.frame?.let { v ->
            v.measure(View.MeasureSpec.makeMeasureSpec(W_ - 72, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_ - 300, View.MeasureSpec.AT_MOST))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            c.save(); c.translate(36f, ((H_ - v.measuredHeight) / 2).toFloat()); v.draw(c); c.restore()
        }
        FileOutputStream(File(out, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun texts(v: View, acc: MutableList<String> = ArrayList()): List<String> {
        if (v is android.widget.TextView) acc.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), acc)
        return acc
    }
    private fun <T : View> find(v: View, cls: Class<T>): T? {
        if (cls.isInstance(v)) return cls.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), cls)?.let { return it }
        return null
    }
    private fun details(): ExplorerScreen {
        val ex = a.explorer!!
        if (ex.iconMode) ex.toggleView()
        idle(); layoutHost(); layoutHost()
        val list = find(ex.view, ListView::class.java)!!
        assertTrue("rows laid out: ${list.childCount} h=${list.height}", list.childCount >= 6)
        return ex
    }
    private fun ev(act: Int, x: Float, y: Float, t: Long) = MotionEvent.obtain(0, t, act, x, y, 0)

    private fun grid(): android.widget.GridView = find(a.explorer!!.view, android.widget.GridView::class.java)!!
    private fun icons(): ExplorerScreen { val ex = a.explorer!!; if (!ex.iconMode) ex.toggleView(); idle(); layoutHost(); layoutHost(); return ex }
    private fun cellCenter(v: android.widget.AbsListView, id: UUID, fx: Float = 0.5f, fy: Float = 0.5f): Pair<Float, Float> {
        val ex = a.explorer!!
        val pos = ex.rows.indexOfFirst { it.uuid == id }
        val c = v.getChildAt(pos - v.firstVisiblePosition)!!
        val l = IntArray(2); c.getLocationInWindow(l)
        return (l[0] + c.width * fx) to (l[1] + c.height * fy)
    }
    private var t = 0L
    /** Events go through the window root (like a real finger): x/y local per view, raw = window coordinates. */
    private fun touch(act: Int, x: Float, y: Float) { t += 16; val e = MotionEvent.obtain(0, t, act, x, y, 0); a.host.rootView.dispatchTouchEvent(e); e.recycle() }
    private fun hold() { ShadowLooper.idleMainLooper(android.view.ViewConfiguration.getLongPressTimeout() + 60L, java.util.concurrent.TimeUnit.MILLISECONDS) }
    private fun menuShowing() = Menus.current?.isShowing == true
    private fun order() = a.explorer!!.rows.map { it.uuid }

    private fun s(id: Int) = app().getString(id)
    private fun labels(items: List<Menus.Item>) = items.map { if (it.sep) "|" else it.label }
    private val FILE = listOf("名前の変更", "|", "切り取り", "フォルダーを指定して移動", "一つ上の階層へ移動", "|", "削除")
    private fun row(id: UUID) = a.explorer!!.rows.first { it.uuid == id }
    private fun memoId() = a.explorer!!.rows.first { it.name == "買い物メモ" }.uuid
    private fun cellRect(v: android.widget.AbsListView, id: UUID): android.graphics.Rect {
        val pos = a.explorer!!.rows.indexOfFirst { it.uuid == id }
        val c = v.getChildAt(pos - v.firstVisiblePosition)!!
        val l = IntArray(2); c.getLocationOnScreen(l)
        return android.graphics.Rect(l[0], l[1], l[0] + c.width, l[1] + c.height)
    }
    private fun longPressAt(v: android.widget.AbsListView, id: UUID): android.graphics.Rect {
        val (x, y) = cellCenter(v, id)
        Menus.lastPlace = null
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        touch(MotionEvent.ACTION_UP, x, y); idle()
        assertTrue(menuShowing())
        return Menus.lastPlace!!
    }

    private val FIVE = listOf("名前の変更", "|", "切り取り", "フォルダーを指定して移動", "一つ上の階層へ移動", "|", "削除")
    private val FOLDER = listOf("下部ファイルをすべて表示", "|", "名前の変更", "|", "切り取り", "ここに貼り付け", "フォルダーを指定して移動", "|", "削除")
    private val ICON_HEAD = listOf("表示: 詳細", "|")
    private val DETAIL_HEAD = listOf("表示: アイコン", "列の幅を元に戻す", "|")
    private fun tapAt(v: android.widget.AbsListView, id: UUID) { val (x, y) = cellCenter(v, id); touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y); idle(); layoutHost() }
    private fun list() = find(a.explorer!!.view, ListView::class.java)!!

    // ---------------------------------------------------------------- 1 + 3 menus
    @Test fun menus_viewItemOnTop_iconView() {
        val ex = icons()
        assertEquals(ICON_HEAD + FIVE, labels(ex.itemMenu(row(ids["Gmail"]!!))))
        assertEquals(ICON_HEAD + FIVE, labels(ex.itemMenu(row(memoId()))))
        assertEquals(ICON_HEAD + FOLDER, labels(ex.itemMenu(row(ids["G"]!!))))
        val removed = listOf(s(R.string.open), s(R.string.move_earlier), s(R.string.move_later))
        assertTrue(labels(ex.itemMenu(row(ids["G"]!!))).none { it in removed })
        assertEquals(ICON_HEAD + listOf("開く", "下部ファイルをすべて表示", "|", "ごみ箱を空にする"), labels(ex.itemMenu(row(Tree.recycleBinId(Session.db!!)!!))))
        assertEquals(ICON_HEAD + listOf("貼り付け", "|", "新しいフォルダ", "新しい鍵", "新しいメモ"), labels(ex.backgroundMenu()))
        var p = longPressAt(grid(), ids["家のWi-Fi"]!!)
        shot("v0415/01-file-menu-icons.png", menuAt = p.left to p.top); Menus.dismiss(); idle()
        p = longPressAt(grid(), ids["G"]!!)
        assertEquals(ids["G"], ex.selection)
        shot("v0415/02-folder-menu.png", menuAt = p.left to p.top); Menus.dismiss(); idle()
        // the view item works from a folder menu too
        ex.itemMenu(row(ids["G"]!!)).first { it.label == "表示: 詳細" }.action(); idle()
        assertFalse(ex.iconMode)
    }

    @Test fun menus_viewItemOnTop_detailView() {
        val ex = details()
        assertEquals(DETAIL_HEAD + FIVE, labels(ex.itemMenu(row(ids["Gmail"]!!))))
        assertEquals(DETAIL_HEAD + FOLDER, labels(ex.itemMenu(row(ids["G"]!!))))
        assertEquals(DETAIL_HEAD + listOf("貼り付け", "|", "新しいフォルダ", "新しい鍵", "新しいメモ"), labels(ex.backgroundMenu()))
        val p = longPressAt(list(), ids["Gmail"]!!)
        shot("v0415/03-detail-menu.png", menuAt = p.left to p.top); Menus.dismiss(); idle()
        ex.itemMenu(row(ids["Gmail"]!!)).first { it.label == "表示: アイコン" }.action(); idle()
        assertTrue(ex.iconMode)
    }

    @Test fun folderMenu_readOnly_disabled_andCutPasteHere() {
        val ex = icons()
        ex.itemMenu(row(ids["Gmail"]!!)).first { it.label == "切り取り" }.action(); idle()
        ex.itemMenu(row(ids["G"]!!)).first { it.label == "ここに貼り付け" }.also { assertTrue(it.enabled) }.action(); idle()
        assertEquals(ids["G"], Tree.parentOf(Tree.root(Session.db!!), ids["Gmail"]!!)!!.uuid)
        Session.beginTempView(Session.db!!, "t")
        try {
            val m = ex.itemMenu(row(ids["G"]!!))
            assertEquals(ICON_HEAD + FOLDER, labels(m))
            assertTrue(m.first().enabled); assertTrue("viewing stays", m[2].enabled)   // 0.4.24: 下部ファイルをすべて表示
            assertTrue(m.drop(3).filter { !it.sep }.none { it.enabled })
        } finally { Session.exitTempView() }
    }

    // ---------------------------------------------------------------- 2 detail drag
    @Test fun detailView_nameColumnDragsWithoutLongPress_otherColumnsScroll() {
        val ex = details()
        val steam = ids["Steam"]!!; val amazon = ids["Amazon"]!!
        // user-name column: plain swipe = scroll, no drag, no menu
        var (x, y) = cellCenter(list(), steam, fx = 0.6f)
        touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_MOVE, x, y - W.dp(40)); hold()
        assertFalse(ex.dragging); assertFalse(menuShowing())
        touch(MotionEvent.ACTION_UP, x, y - W.dp(40)); idle(); layoutHost()
        // name column: touch and move = drag at once
        cellCenter(list(), steam, fx = 0.1f).let { x = it.first; y = it.second }
        assertTrue(x < ex.colName)
        touch(MotionEvent.ACTION_DOWN, x, y)
        val (tx, ty) = cellCenter(list(), amazon, fx = 0.1f, fy = 0.15f)
        touch(MotionEvent.ACTION_MOVE, x, y - W.dp(30))
        assertTrue("drag without long press", ex.dragging); assertFalse(menuShowing())
        touch(MotionEvent.ACTION_MOVE, tx, ty)
        touch(MotionEvent.ACTION_UP, tx, ty); idle(); layoutHost()
        var o = order(); assertEquals(o.indexOf(amazon) - 1, o.indexOf(steam))
        // long press anywhere = menu; long press then move on the user column still drags (0.4.12)
        cellCenter(list(), ids["楽天"]!!, fx = 0.6f).let { x = it.first; y = it.second }
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        assertTrue(menuShowing())
        val (gx, gy) = cellCenter(list(), ids["Gmail"]!!, fx = 0.6f, fy = 0.15f)
        touch(MotionEvent.ACTION_MOVE, gx, gy); assertTrue(ex.dragging); assertFalse(menuShowing())
        touch(MotionEvent.ACTION_UP, gx, gy); idle()
        o = order(); assertEquals(o.indexOf(ids["Gmail"]!!) - 1, o.indexOf(ids["楽天"]!!))
        // name column: long press without moving = menu (no drag)
        layoutHost()
        cellCenter(list(), ids["Amazon"]!!, fx = 0.1f).let { x = it.first; y = it.second }
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        assertTrue(menuShowing()); assertFalse(ex.dragging)
        touch(MotionEvent.ACTION_UP, x, y); idle()
    }

    @Test fun iconView_unchanged_plainMoveScrolls() {
        val ex = icons()
        val (x, y) = cellCenter(grid(), ids["Steam"]!!)
        touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_MOVE, x + W.dp(40), y); hold()
        assertFalse(ex.dragging); assertFalse(menuShowing())
        touch(MotionEvent.ACTION_UP, x + W.dp(40), y); idle()
    }

    // ---------------------------------------------------------------- 4 two taps
    @Test fun folders_twoTapsToOpen_pathTreeOneTap() {
        val ex = icons()
        val root = Tree.root(Session.db!!).uuid
        tapAt(grid(), ids["G"]!!)
        assertEquals(root, ex.current); assertEquals(ids["G"], ex.selection)
        assertTrue(texts(ex.view).any { it.startsWith("フォルダー 0 個") })   // preview of the folder
        tapAt(grid(), ids["G"]!!)
        assertEquals(ids["G"], ex.current)
        // path tree: one tap goes back to the root
        ex.folderTree!!.tap(root); idle(); layoutHost()
        assertEquals(root, ex.current)
        // recycle bin: two taps too
        val bin = Tree.recycleBinId(Session.db!!)!!
        tapAt(grid(), bin); assertEquals(root, ex.current); assertEquals(bin, ex.selection)
        tapAt(grid(), bin); assertEquals(bin, ex.current)
        ex.goUp(); idle(); layoutHost()
        // preview: first tap selects it, second opens the folder
        tapAt(grid(), ids["G"]!!)
        ex.onPreviewTap(); assertTrue(ex.previewSelected); ex.onPreviewTap(); idle()
        assertEquals(ids["G"], ex.current)
    }

    @Test fun folders_twoTaps_detailView() {
        val ex = details()
        val root = Tree.root(Session.db!!).uuid
        tapAt(list(), ids["G"]!!); assertEquals(root, ex.current); assertEquals(ids["G"], ex.selection)
        tapAt(list(), ids["G"]!!); assertEquals(ids["G"], ex.current)
    }

    // ---------------------------------------------------------------- 5 names
    @Test fun newFolder_asksName_okCancelBlank() {
        val ex = icons()
        val n0 = Order.children(Tree.root(Session.db!!)).size
        ex.backgroundMenu().first { it.label == "新しいフォルダ" }.action(); idle()
        var d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as io.github.zmlworks.kpexplorer.ui.ClassicDialog
        val f = find(d.frame, android.widget.EditText::class.java)!!
        assertEquals("新しいフォルダ", f.text.toString())
        assertEquals(0, f.selectionStart); assertEquals(f.text.length, f.selectionEnd)
        assertTrue(texts(d.frame).contains("新しいフォルダ")); assertTrue(texts(d.frame).contains("キャンセル"))
        f.setText("旅行")
        Dlg.last = d; shot("v0415/04-new-folder-name.png", dialog = true)
        d.buttonViews.first().performClick(); idle()
        assertEquals(n0 + 1, Order.children(Tree.root(Session.db!!)).size)
        assertTrue(Order.children(Tree.root(Session.db!!)).any { it is Order.Node.F && it.name == "旅行" })
        // cancel: nothing
        d = ex.askNewFolder()!!
        d.buttonViews.last().performClick(); idle()
        assertEquals(n0 + 1, Order.children(Tree.root(Session.db!!)).size)
        // blank → default name
        d = ex.askNewFolder()!!
        find(d.frame, android.widget.EditText::class.java)!!.setText("  ")
        d.buttonViews.first().performClick(); idle()
        assertTrue(Order.children(Tree.root(Session.db!!)).any { it is Order.Node.F && it.name == "新しいフォルダ" })
    }

    @Test fun newMemo_asksName_andKeysUnaffected() {
        val ex = icons()
        ex.backgroundMenu().first { it.label == "新しいメモ" }.action(); idle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as io.github.zmlworks.kpexplorer.ui.ClassicDialog
        val f = find(d.frame, android.widget.EditText::class.java)!!
        assertEquals("新しいメモ", f.text.toString())
        f.setText("買い物 (週末)"); d.buttonViews.first().performClick(); idle()
        val m = Order.children(Tree.root(Session.db!!)).first { it.name == "買い物 (週末)" }
        assertEquals(Order.Kind.MEMO, m.kind); assertEquals(m.uuid, ex.selection)
        // cancel creates nothing
        val n = Order.children(Tree.root(Session.db!!)).size
        ex.askNewMemo()!!.buttonViews.last().performClick(); idle()
        assertEquals(n, Order.children(Tree.root(Session.db!!)).size)
        // new key: still the create screen, no name dialog
        val before = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        ex.backgroundMenu().first { it.label == "新しい鍵" }.action(); idle()
        assertSame("no name dialog for keys", before, org.robolectric.shadows.ShadowDialog.getLatestDialog()); assertTrue(a.screen is NewKeyScreen)
    }
}
