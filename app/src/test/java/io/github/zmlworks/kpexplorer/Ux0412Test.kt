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

/** 0.4.12: launcher-style gestures (long press = menu, then move = drag; else scroll), no scrollbars, preview frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0412Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0412").mkdirs() }
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
            v.measure(View.MeasureSpec.makeMeasureSpec(W.dp(230), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_, View.MeasureSpec.AT_MOST))
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

    @Test fun noScrollbars_inIconAndDetailViews() {
        val ex = icons()
        assertFalse(grid().isVerticalScrollBarEnabled)
        assertEquals("no right gutter", W.dp(2), grid().paddingRight)
        shot("v0412/01-icons-no-scrollbar.png")
        ex.toggleView(); idle(); layoutHost()
        val list = find(ex.view, ListView::class.java)!!
        assertFalse(list.isVerticalScrollBarEnabled); assertEquals(0, list.paddingRight)
    }

    @Test fun plainSwipe_scrolls_noMenuNoDrag() {
        val ex = icons()
        val (x, y) = cellCenter(grid(), ids["Steam"]!!)
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_MOVE, x, y - W.dp(20)); touch(MotionEvent.ACTION_MOVE, x, y - W.dp(60))
        hold()
        assertFalse(menuShowing()); assertFalse(ex.dragging)
        touch(MotionEvent.ACTION_UP, x, y - W.dp(60)); idle()
        assertFalse(ex.dragging); assertNull("a swipe is not a tap", ex.selection)
    }

    @Test fun longPress_menu_releaseKeepsIt_jitterKeepsIt() {
        val ex = icons()
        val (x, y) = cellCenter(grid(), ids["Steam"]!!)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        assertTrue(menuShowing()); assertEquals(ids["Steam"], ex.selection)
        touch(MotionEvent.ACTION_MOVE, x + W.dp(8), y + W.dp(6))   // small jitter, well under 8 mm
        assertTrue(menuShowing()); assertFalse(ex.dragging)
        touch(MotionEvent.ACTION_UP, x + W.dp(8), y + W.dp(6)); idle()
        assertTrue("release without moving keeps the menu", menuShowing()); assertFalse(ex.dragging)
        assertTrue(ex.dragStartPx() >= W.dp(32))
    }

    @Test fun longPressThenMove_closesMenu_dragsAndReorders() {
        val ex = icons()
        val steam = ids["Steam"]!!; val gmail = ids["Gmail"]!!
        val (x, y) = cellCenter(grid(), steam)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        assertTrue(menuShowing())
        val (tx, ty) = cellCenter(grid(), gmail, fx = 0.2f)   // left part of Gmail = before it
        assertTrue("cells are far enough apart for this test", kotlin.math.hypot(tx - x, ty - y) > ex.dragStartPx())
        touch(MotionEvent.ACTION_MOVE, tx, ty)
        assertFalse("menu closed once the finger really moves", menuShowing())
        assertTrue(ex.dragging)
        touch(MotionEvent.ACTION_MOVE, tx, ty)
        shot("v0412/02-drag-in-progress.png")
        touch(MotionEvent.ACTION_UP, tx, ty); idle()
        assertFalse(ex.dragging)
        val o = order()
        assertEquals("dropped before Gmail", o.indexOf(gmail) - 1, o.indexOf(steam))
    }

    @Test fun longPressThenMove_ontoFolder_movesInto() {
        val ex = icons()
        val gmail = ids["Gmail"]!!
        val (x, y) = cellCenter(grid(), gmail)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        val (fx, fy) = cellCenter(grid(), ids["G"]!!)
        touch(MotionEvent.ACTION_MOVE, (x + fx) / 2, (y + fy) / 2); touch(MotionEvent.ACTION_MOVE, fx, fy)
        assertTrue(ex.dragging)
        touch(MotionEvent.ACTION_UP, fx, fy); idle()
        assertEquals(ids["G"], Tree.parentOf(Tree.root(Session.db!!), gmail)!!.uuid)
    }

    @Test fun recycleBin_longPressThenMove_dragsSince0414() {
        val ex = icons()
        val bin = Tree.recycleBinId(Session.db!!)!!
        val (x, y) = cellCenter(grid(), bin)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        touch(MotionEvent.ACTION_MOVE, x + W.dp(120), y + W.dp(120))
        assertTrue("0.4.14: the bin can be reordered", ex.dragging); assertFalse(menuShowing())
        touch(MotionEvent.ACTION_UP, x + W.dp(120), y + W.dp(120)); idle()
        assertEquals("still in the root", Tree.root(Session.db!!).uuid, Tree.parentOf(Tree.root(Session.db!!), bin)!!.uuid)
    }

    @Test fun detailView_sameScheme() {
        val ex = details()
        val list = find(ex.view, ListView::class.java)!!
        val steam = ids["Steam"]!!; val amazon = ids["Amazon"]!!
        val (x, y) = cellCenter(list, steam, fx = 0.7f)   // user-name / date area: long press works anywhere
        touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_MOVE, x, y - W.dp(50)); hold()
        assertFalse(menuShowing()); assertFalse(ex.dragging)
        touch(MotionEvent.ACTION_UP, x, y - W.dp(50)); idle()
        val (x2, y2) = cellCenter(list, steam, fx = 0.7f)
        touch(MotionEvent.ACTION_DOWN, x2, y2); hold()
        assertTrue(menuShowing())
        val (tx, ty) = cellCenter(list, amazon, fx = 0.5f, fy = 0.1f)
        touch(MotionEvent.ACTION_MOVE, x2, (y2 + ty) / 2); assertTrue(ex.dragging); assertFalse(menuShowing())
        touch(MotionEvent.ACTION_MOVE, tx, ty); touch(MotionEvent.ACTION_UP, tx, ty); idle()
        val o = order(); assertEquals(o.indexOf(amazon) - 1, o.indexOf(steam))
    }

    @Test fun tap_stillSelectsAndOpens_andPreviewFrame() {
        val ex = icons()
        val (x, y) = cellCenter(grid(), ids["Gmail"]!!)
        touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y); idle(); layoutHost()
        assertEquals(ids["Gmail"], ex.selection); assertFalse(ex.previewSelected)
        val sv = find(ex.view, ExplorerScreen.PreviewBox::class.java)!!.parent as ExplorerScreen.PreviewScroll
        assertNull(sv.frame)
        ex.onPreviewTap(); idle(); layoutHost()
        assertTrue(ex.previewSelected)
        assertTrue(sv.frame is ExplorerScreen.FocusFrame)   // 0.4.27: drawn fixed to the window (Ux0427Test)
        val box = find(ex.view, ExplorerScreen.PreviewBox::class.java)!!
        assertEquals(ExplorerScreen.PREVIEW_SELECTED_BG, (box.background as android.graphics.drawable.ColorDrawable).color)
        val title = texts(box).let { _ -> var tv: android.widget.TextView? = null
            fun go(v: View) { if (tv == null && v is android.widget.TextView && v.text.toString() == "Gmail") tv = v; if (v is ViewGroup) for (i in 0 until v.childCount) go(v.getChildAt(i)) }
            go(box); tv!! }
        assertEquals("name not inverted", W.TEXT, title.currentTextColor); assertNull(title.background)
        shot("v0412/03-preview-selected.png")
        ex.onPreviewTap(); idle()
        assertTrue(a.screen is KeyScreen)
    }
}
