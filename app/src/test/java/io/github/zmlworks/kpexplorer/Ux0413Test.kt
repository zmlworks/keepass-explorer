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

/** 0.4.13: five-item file menu, cut/paste, menu beside the item, longer preview dashes, 項目の編集／項目を追加. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0413Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0413").mkdirs() }
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

    @Test fun fileMenu_exactlyTheFiveItems_keyAndMemo() {
        val ex = icons()
        assertEquals(FILE, labels(ex.itemMenu(row(ids["Gmail"]!!))).let { l -> l.drop(l.indexOf("|") + 1) }.take(7))
        assertEquals(FILE, labels(ex.itemMenu(row(memoId()))).let { l -> l.drop(l.indexOf("|") + 1) }.take(7))
        val removed = listOf(R.string.open, R.string.copy_username, R.string.copy_password, R.string.copy_otp,
            R.string.move_earlier, R.string.move_later, R.string.open_item_folder).map { s(it) }
        assertTrue(labels(ex.itemMenu(row(ids["Gmail"]!!))).none { it in removed })
        // root: 一つ上の階層へ移動 disabled; inside 仕事 enabled
        assertFalse(ex.itemMenu(row(ids["Gmail"]!!)).first { it.label == "一つ上の階層へ移動" }.enabled)
        Session.update { Order.moveTo(it, ids["Gmail"]!!, ids["G"]!!) }; ex.navigate(ids["G"]!!); idle()
        val m = ex.itemMenu(row(ids["Gmail"]!!))
        assertEquals(FILE, labels(m).let { l -> l.drop(l.indexOf("|") + 1) }.take(7))
        m.first { it.label == "一つ上の階層へ移動" }.also { assertTrue(it.enabled) }.action(); idle()
        assertEquals(Tree.root(Session.db!!).uuid, Tree.parentOf(Tree.root(Session.db!!), ids["Gmail"]!!)!!.uuid)
        // search results get the same menu
        ex.searchFor("Steam"); idle()
        assertEquals(FILE, labels(ex.itemMenu(row(ids["Steam"]!!))).let { l -> l.drop(l.indexOf("|") + 1) }.take(7))
    }

    @Test fun folderAndBinMenus_unchangedApartFromLabels() {
        val ex = icons()
        val f = labels(ex.itemMenu(row(ids["G"]!!)))
        assertTrue(f.containsAll(listOf("名前の変更", "切り取り", s(R.string.paste_here), "フォルダーを指定して移動", "削除")))
        assertFalse("0.4.15: no 開く in the folder menu", f.contains(s(R.string.open)))
        val bin = labels(ex.itemMenu(row(Tree.recycleBinId(Session.db!!)!!)))
        assertTrue(bin.contains(s(R.string.empty_recycle_bin)))
    }

    @Test fun cutThenPaste_andCancel() {
        val ex = icons()
        val gmail = ids["Gmail"]!!
        ex.itemMenu(row(gmail)).first { it.label == "切り取り" }.action(); idle()
        assertEquals(gmail, ex.cut)
        // cancel from the empty-space menu
        val bg = ex.backgroundMenu()
        assertTrue(bg.first { it.label == "貼り付け" }.enabled)
        bg.first { it.label == "切り取りの取り消し" }.action(); idle()
        assertNull(ex.cut); assertTrue(ex.backgroundMenu().none { it.label == "切り取りの取り消し" })
        assertFalse(ex.backgroundMenu().first { it.label == "貼り付け" }.enabled)
        // cut → go into 仕事 → paste on empty space
        ex.itemMenu(row(gmail)).first { it.label == "切り取り" }.action(); idle()
        ex.navigate(ids["G"]!!); idle()
        assertEquals("cut survives navigation", gmail, ex.cut)
        ex.backgroundMenu().first { it.label == "貼り付け" }.action(); idle()
        assertEquals(ids["G"], Tree.parentOf(Tree.root(Session.db!!), gmail)!!.uuid); assertNull(ex.cut)
        // a new cut replaces the old one; paste onto a folder's menu
        ex.goUp(); idle()
        ex.itemMenu(row(ids["Steam"]!!)).first { it.label == "切り取り" }.action()
        ex.itemMenu(row(ids["Amazon"]!!)).first { it.label == "切り取り" }.action(); idle()
        assertEquals(ids["Amazon"], ex.cut)
        ex.itemMenu(row(ids["G"]!!)).first { it.label == s(R.string.paste_here) }.action(); idle()
        assertEquals(ids["G"], Tree.parentOf(Tree.root(Session.db!!), ids["Amazon"]!!)!!.uuid)
        assertEquals(Tree.root(Session.db!!).uuid, Tree.parentOf(Tree.root(Session.db!!), ids["Steam"]!!)!!.uuid)
    }

    @Test fun readOnlyTemp_sameItemsDisabled() {
        val ex = icons()
        Session.beginTempView(Session.db!!, "test")
        try {
            val m = ex.itemMenu(row(ids["Gmail"]!!))
            assertEquals(FILE, labels(m).let { l -> l.drop(l.indexOf("|") + 1) }.take(7)); assertTrue(m.drop(m.indexOfFirst { it.sep } + 1).take(7).filter { !it.sep }.none { it.enabled })
        } finally { Session.exitTempView() }
    }

    @Test fun place_rule() {
        val sw = 1000; val sh = 2000; val w = 400; val h = 600; val m = 10; val top = 50
        // room on the right → right, top-aligned
        assertEquals(android.graphics.Rect(200, 300, 600, 900), Menus.place(android.graphics.Rect(0, 300, 200, 500), 100, w, h, sw, sh, m, top))
        // right edge column → left
        assertEquals(android.graphics.Rect(400, 300, 800, 900), Menus.place(android.graphics.Rect(800, 300, 1000, 500), 900, w, h, sw, sh, m, top))
        // full-width row → below, near the finger
        assertEquals(android.graphics.Rect(300, 500, 700, 1100), Menus.place(android.graphics.Rect(0, 300, 1000, 500), 500, w, h, sw, sh, m, top))
        // near the bottom → above
        assertEquals(android.graphics.Rect(300, 1200, 700, 1800), Menus.place(android.graphics.Rect(0, 1800, 1000, 1990), 500, w, h, sw, sh, m, top))
        // middle with no room either way → roomier side, shortened
        val r = Menus.place(android.graphics.Rect(0, 900, 1000, 1500), 500, w, 1200, sw, sh, m, top)
        assertEquals(top, r.top); assertEquals(900, r.bottom)
        // beside an item near the bottom: shifted up but still beside
        val b = Menus.place(android.graphics.Rect(0, 1800, 200, 1990), 100, w, h, sw, sh, m, top)
        assertEquals(200, b.left); assertEquals(sh - m, b.bottom)
    }

    @Test fun iconView_menuNeverCoversTheCell_allColumns() {
        icons()
        val dm = app().resources.displayMetrics
        for (t in listOf("Gmail", "Amazon", "家のWi-Fi", "Steam", "楽天")) {
            val cell = cellRect(grid(), ids[t]!!)
            val p = longPressAt(grid(), ids[t]!!)
            assertTrue("compact menu ${p.width()}", p.width() < dm.widthPixels * 2 / 3)
            assertFalse("$t: menu $p over cell $cell", android.graphics.Rect.intersects(p, cell))
            assertTrue("$t on screen $p", p.left >= 0 && p.right <= dm.widthPixels && p.top >= 0 && p.bottom <= dm.heightPixels)
            if (t == "家のWi-Fi") assertEquals("first column: menu to the right", cell.right, p.left)
            if (t == "楽天") assertEquals("last column: menu to the left", cell.left, p.right)
            if (t == "家のWi-Fi") shot("v0413/01-icon-menu-beside.png", menuAt = p.left to p.top)
            if (t == "楽天") shot("v0413/02-icon-menu-left.png", menuAt = p.left to p.top)
            Menus.dismiss(); idle()
        }
    }

    @Test fun detailView_menuBelowOrAboveTheRow() {
        val ex = details()
        val list = find(ex.view, ListView::class.java)!!
        for (t in listOf("Gmail", "last")) {
            layoutHost()
            val lv = find(ex.view, ListView::class.java)!!
            val id = if (t == "last") ex.rows[lv.firstVisiblePosition + lv.childCount - 2].uuid else ids[t]!!
            val cell = cellRect(lv, id)
            val p = longPressAt(lv, id)
            assertFalse("$t: $p over $cell", android.graphics.Rect.intersects(p, cell))
            if (t == "Gmail") shot("v0413/03-detail-menu-below.png", menuAt = p.left to p.top)
            Menus.dismiss(); idle()
        }
    }

    @Test fun longPressThenDrag_stillWorks_withMenuBeside() {
        val ex = icons()
        val steam = ids["Steam"]!!; val gmail = ids["Gmail"]!!
        val (x, y) = cellCenter(grid(), steam)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        assertTrue(menuShowing())
        val (tx, ty) = cellCenter(grid(), gmail, fx = 0.2f)
        touch(MotionEvent.ACTION_MOVE, tx, ty)
        assertFalse(menuShowing()); assertTrue(ex.dragging)
        touch(MotionEvent.ACTION_UP, tx, ty); idle()
        val o = order(); assertEquals(o.indexOf(gmail) - 1, o.indexOf(steam))
    }

    @Test fun previewFrame_longerDashes() {
        val ex = icons()
        ex.select(ids["Gmail"]); idle(); layoutHost()
        ex.onPreviewTap(); idle(); layoutHost()
        assertTrue(ex.previewSelected)
        assertTrue(ExplorerScreen.DASH_ON_DP >= 3f && ExplorerScreen.DASH_OFF_DP >= 2f)
        shot("v0413/04-preview-selected-dashes.png")
    }

    @Test fun keyPage_newLabels() {
        a.show(KeyScreen(a, ids["Gmail"], Tree.root(Session.db!!).uuid)); idle(); layoutHost()
        val all = texts(a.host)
        assertTrue(all.toString(), all.contains("項目の編集")); assertTrue(all.contains("カスタム項目の追加"))
        assertTrue(all.none { it.contains("欄を編集") || it.contains("欄を追加") || it == "項目を追加" })
        val ks = a.screen as KeyScreen
        assertTrue(ks.menu().map { it.label }.containsAll(listOf("項目の編集", "カスタム項目の追加")))
        shot("v0413/05-key-page-labels.png")
    }
}
