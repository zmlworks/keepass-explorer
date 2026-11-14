package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import app.keemobile.kotpass.database.modifiers.modifyContent
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
import io.github.zmlworks.kpexplorer.ExplorerScreen.Drop.Mode as M

/** 0.4.27: search page texts, preview frame fixed while the pane scrolls, scroll kept after a drag, detail view drag needs a long press. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0427Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0427").mkdirs() }
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
        // enough folders that the path tree is taller than its box (at most 30 % of the screen) and scrolls
        for (i in 1..14) db = Tree.addGroup(db, root, Tree.newGroup("フォルダ%02d".format(i)))
        for ((t, u) in listOf("Gmail" to "taro.yamada@gmail.com", "Amazon" to "taro_y", "家のWi-Fi" to "MyHome-5G",
                "ネット銀行 (メイン口座)" to "1234-5678-9012", "会社のVPN" to "t.yamada", "Steam" to "taro_games",
                "楽天" to "taro.r", "X (Twitter)" to "@taro")) {
            val e = Tree.newEntry(Tree.Fields(t, u, "p", "", "", emptyList())); db = Tree.addEntry(db, root, e); ids[t] = e.uuid
        }
        db = Tree.addEntry(db, root, Order.newMemo("買い物メモ"))
        // enough items that the last ones sit at the bottom of the list (behind where the preview pane opens)
        for (i in 1..30) { val k = Tree.newEntry(Tree.Fields("サービス%02d".format(i), "user$i", "p$i", "", "", emptyList())); db = Tree.addEntry(db, root, k); ids["S$i"] = k.uuid }
        val old = Tree.newEntry(Tree.Fields("古いアカウント", "old", "x", "", "", emptyList())); db = Tree.addEntry(db, g.uuid, old); ids["old"] = old.uuid
        db = Tree.moveToBin(db, old.uuid, "ごみ箱")
        // a subtree under 仕事: 仕事/勤怠, 仕事/メール/{会社メール, 議事録 (memo)}
        val k1 = Tree.newEntry(Tree.Fields("勤怠", "t.yamada", "p", "", "", emptyList())); db = Tree.addEntry(db, g.uuid, k1); ids["勤怠"] = k1.uuid
        val sub = Tree.newGroup("メール"); db = Tree.addGroup(db, g.uuid, sub); ids["メール"] = sub.uuid
        val k2 = Tree.newEntry(Tree.Fields("会社メール", "yamada@corp", "p", "", "", emptyList())); db = Tree.addEntry(db, sub.uuid, k2); ids["会社メール"] = k2.uuid
        val m2 = Order.newMemo("議事録"); db = Tree.addEntry(db, sub.uuid, m2); ids["議事録"] = m2.uuid
        App.store(app()).write(Kdbx.encodeVerified(db, keepSalt = true))
        Session.open(db); Session.store = App.store(app())
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        if (!Session.isOpen) Session.open(db)
        Session.store = a.store
        if (a.explorer == null) a.showExplorer(); idle()
    }
    @After fun tearDown() { runCatching { FieldEditSession.commitActive() }; Menus.dismiss(); Session.lock(); L.setLang(app(), L.Lang.SYSTEM); L.setDateOrder(app(), L.DateOrder.YMD); Vault.forceAvailableForTest = null; Menus.dismiss(); Dlg.last = null }

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

    private fun entry(id: UUID) = Tree.findEntry(Session.db!!, id)!!.second
    private fun hist(id: UUID) = entry(id).history
    private fun mtime(id: UUID) = entry(id).times?.lastModificationTime
    private fun openKey(id: UUID): KeyScreen { val ks = KeyScreen(a, id, Tree.root(Session.db!!).uuid); a.show(ks); idle(); layoutHost(); return ks }
    private fun later() = Thread.sleep(5)
    private fun latestDialog() = org.robolectric.shadows.ShadowDialog.getLatestDialog() as io.github.zmlworks.kpexplorer.ui.ClassicDialog
    private fun clickButton(d: io.github.zmlworks.kpexplorer.ui.ClassicDialog, label: String) { d.buttonViews.first { it.text.toString() == label }.performClick(); idle() }
    private fun <T : View> findTag(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTag<View>(v.getChildAt(i), tag)?.let { return it }
        return null
    }
    private fun withHistory(id: UUID, n: Int) {
        repeat(n) { i -> Session.update { Order.saveEntry(it, id, mapOf("Title" to Tree.title(entry(id)), "UserName" to "u$i", "Password" to "p$i"), emptyList(), null) } }
        idle()
    }



    private fun bin() = Tree.recycleBinId(Session.db!!)!!
    private fun binGroup() = Tree.findGroup(Session.db!!, bin())!!
    private fun item(items: List<Menus.Item>, label: String) = items.first { it.label == label }
    private fun treeIds() = a.explorer!!.folderTree!!.rows.map { it.tag as UUID }
    private fun radio(i: Int) = findTag<View>(Dlg.last!!.frame, "pick-$i") as android.widget.RadioButton
    private fun showMainMenu() {
        val ex = a.explorer!!; layoutHost()
        Menus.at(ex.view, W_ - W.dp(240), W.dp(60), ex.mainMenu()); idle()
    }

    private fun allViews(v: View, acc: MutableList<View> = ArrayList()): List<View> {
        acc.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) allViews(v.getChildAt(i), acc); return acc
    }
    private fun tapView(v: View) {
        val l = IntArray(2); v.getLocationInWindow(l)
        val x = l[0] + v.width / 2f; val y = l[1] + v.height / 2f
        touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y); idle()
    }
    private val LONG_USER = "taro.yamada.very.long.address.for.testing.the.ellipsis@example-company.co.jp"
    private val LONG_PASS = "Xq7!rT2#pL9@vW4\$mN8%kJ3^hG6&fD1*sA5(zC0)yB-uE+iO=Lk"
    /** What is drawn: the laid-out text (after the transformation), else the transformation itself. */
    private fun shown(t: android.widget.TextView) = t.layout?.text?.toString()
        ?: t.transformationMethod?.getTransformation(t.text, t)?.toString() ?: t.text.toString()
    private fun setGmail(user: String, pass: String) {
        Session.update { Order.saveEntry(it, ids["Gmail"]!!, mapOf("Title" to "Gmail", "UserName" to user, "Password" to pass, "URL" to "https://mail.google.com"), emptyList(), null) }
    }
    private fun ed(ks: KeyScreen, v: String) = allViews(ks.view).filterIsInstance<android.widget.EditText>().first { it.text.toString() == v }

    private fun head() = findTag<View>(a.explorer!!.view, "search-head") as android.widget.TextView
    private fun typeSearch(s: String) { a.explorer!!.searchField.setText(s); ShadowLooper.idleMainLooper(ExplorerScreen.LIVE_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS); idle(); layoutHost() }
    private fun bg(v: View) = (v.background as android.graphics.drawable.ColorDrawable).color
    /** Grid / list position: first visible item and its top. */
    private fun at(v: android.widget.AbsListView) = v.firstVisiblePosition to (v.getChildAt(0)?.top ?: 0)
    private fun list() = find(a.explorer!!.view, ListView::class.java)!!
    private fun treeBox() = a.explorer!!.folderTree!!.parent as android.widget.ScrollView
    /** Long press on [from], move to [to] (fractions of its cell), release. */
    private fun dragCell(v: () -> android.widget.AbsListView, from: UUID, to: UUID, fx: Float, fy: Float) {
        val (x, y) = cellCenter(v(), from)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        assertTrue(menuShowing())
        val (tx, ty) = cellCenter(v(), to, fx, fy)
        touch(MotionEvent.ACTION_MOVE, (x + tx) / 2, (y + ty) / 2 + W.dp(40)); assertTrue(a.explorer!!.dragging)
        touch(MotionEvent.ACTION_MOVE, tx, ty)
        touch(MotionEvent.ACTION_UP, tx, ty); idle(); layoutHost(); idle(); layoutHost()
        assertFalse(a.explorer!!.dragging)
    }

    @Test fun searchPage_hintIsSearch_headIsSearchResults_onPaleBlue() {
        val ex = a.explorer!!
        ex.openSearch(); idle(); layoutHost()
        assertEquals("Search", ex.searchField.hint.toString())
        assertEquals("検索結果", head().text.toString())
        assertEquals(io.github.zmlworks.kpexplorer.ui.STRIPE, bg(head()))
        assertEquals(0xFFE8F1FB.toInt(), io.github.zmlworks.kpexplorer.ui.STRIPE)
        assertTrue("full width: ${head().width}", head().width > W_ / 2)
        assertFalse(texts(ex.view).any { it.contains("文字を入力するか") || it.contains("文字で検索") })
        shot("v0427/01-search-empty.png")
        typeSearch("サービス")
        assertTrue(head().text.toString(), head().text.toString().startsWith("検索結果 30 件"))
        assertEquals(io.github.zmlworks.kpexplorer.ui.STRIPE, bg(head()))
        assertEquals(30, ex.rows.size)
        shot("v0427/02-search-results.png")
    }

    @Test fun searchPage_english() {
        L.setLang(app(), L.Lang.EN)
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val act = ctl.get(); if (act.explorer == null) act.showExplorer(); idle()
        val ex = act.explorer!!
        ex.openSearch(); idle()
        assertEquals("Search", ex.searchField.hint.toString())
        val h = findTag<View>(ex.view, "search-head") as android.widget.TextView
        assertEquals("Search results", h.text.toString())
        ex.searchField.setText("Gmail"); ShadowLooper.idleMainLooper(ExplorerScreen.LIVE_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS); idle()
        val h2 = findTag<View>(ex.view, "search-head") as android.widget.TextView
        assertTrue(h2.text.toString(), h2.text.toString().startsWith("Search results: 1"))
    }

    @Test fun previewFrame_staysWithThePane_whenItsContentScrolls() {
        val ex = a.explorer!!
        val notes = (1..80).joinToString("\n") { "メモの行 $it" }
        Session.update { Order.saveEntry(it, ids["Gmail"]!!, mapOf("Title" to "Gmail", "UserName" to "taro", "Password" to "p", "Notes" to notes), emptyList(), null) }
        ex.refresh(); ex.select(ids["Gmail"]!!); idle(); layoutHost()
        ex.onPreviewTap(); idle(); layoutHost()
        assertTrue(ex.previewSelected)
        val sv = find(ex.view, ExplorerScreen.PreviewBox::class.java)!!.parent as ExplorerScreen.PreviewScroll
        assertTrue(sv.frame is ExplorerScreen.FocusFrame)
        assertNull("not a View foreground (that scrolls with the content)", sv.foreground)
        fun dashes(): Pair<Float, Float> {
            layoutHost()
            val b = Bitmap.createBitmap(W_, H_, Bitmap.Config.ARGB_8888); a.host.draw(Canvas(b))
            val hl = IntArray(2); a.host.getLocationInWindow(hl); val l = IntArray(2); sv.getLocationInWindow(l)
            val x0 = l[0] - hl[0] + W.dp(12); val x1 = l[0] - hl[0] + sv.width - W.dp(12)
            fun row(y: Int) = (x0 until x1).count { b.getPixel(it, y) == W.TEXT }.toFloat() / (x1 - x0)
            val top = l[1] - hl[1] + W.dp(4); val bottom = l[1] - hl[1] + sv.height - W.dp(4) - 1
            return row(top) to row(bottom)
        }
        val (t0, b0) = dashes()
        assertTrue("frame top before: $t0", t0 > 0.4f); assertTrue("frame bottom before: $b0", b0 > 0.4f)
        sv.scrollTo(0, W.dp(200)); idle()
        assertTrue("content scrolled: ${sv.scrollY}", sv.scrollY > W.dp(100))
        val (t1, b1) = dashes()
        assertTrue("frame top after scroll: $t1", t1 > 0.4f); assertTrue("frame bottom after scroll: $b1", b1 > 0.4f)
        shot("v0427/03-preview-frame-after-scroll.png")
    }

    @Test fun dragReorder_iconView_keepsTheScrollPosition() {
        val ex = icons()
        grid().scrollListBy(W.dp(330)); layoutHost(); layoutHost()
        val before = at(grid())
        assertTrue("scrolled: $before", before.first >= 15)
        val p = before.first
        val a1 = ex.rows[p].uuid; val c1 = ex.rows[p + 2].uuid
        dragCell({ grid() }, c1, a1, 0.1f, 0.5f)
        val o = order(); assertEquals(o.indexOf(a1) - 1, o.indexOf(c1))
        val after = at(grid())
        // auto-scroll near the pane edge can nudge a little during the drag; jumping to the top is the bug
        assertTrue("no jump to the top: before=$before after=$after", after.first >= 8)
        shot("v0427/04-icons-after-reorder.png")
        // another folder still starts at the top
        ex.navigate(ids["G"]!!); idle(); layoutHost()
        assertEquals(0, grid().firstVisiblePosition)
    }

    @Test fun dragReorder_detailView_keepsTheScrollPosition_andTheTreeToo() {
        val ex = details()
        list().setSelectionFromTop(20, 0); layoutHost(); layoutHost()
        val before = at(list())
        assertTrue("scrolled: $before", before.first >= 15)
        val p = before.first - list().headerViewsCount
        val a1 = ex.rows[p + 1].uuid; val c1 = ex.rows[p + 3].uuid
        dragCell({ list() }, c1, a1, 0.1f, 0.15f)
        val o = order(); assertEquals(o.indexOf(a1) - 1, o.indexOf(c1))
        assertEquals("no jump to the top", before, at(list()))
        shot("v0427/05-details-after-reorder.png")
        // onto a folder in the path tree (scrolled down): neither the tree nor the list jumps
        treeBox().scrollTo(0, W.dp(150)); idle(); layoutHost()
        val ty = treeBox().scrollY; assertTrue("tree scrolled: $ty", ty > 0)
        val b2 = at(list())
        val tree = ex.folderTree!!
        val target = tree.rows.first { it.top - ty > W.dp(40) && (it.tag as UUID) != Tree.recycleBinId(Session.db!!) && (it.tag as UUID) != Tree.root(Session.db!!).uuid }
        val tid = target.tag as UUID
        val k = ex.rows[p + 2].uuid
        val (x, y) = cellCenter(list(), k)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        val l = IntArray(2); target.getLocationInWindow(l)
        val tx = l[0] + W.dp(80).toFloat(); val tyy = l[1] + target.height / 2f
        touch(MotionEvent.ACTION_MOVE, x, y - W.dp(60)); assertTrue(ex.dragging)
        touch(MotionEvent.ACTION_MOVE, tx, tyy); touch(MotionEvent.ACTION_UP, tx, tyy); idle(); layoutHost(); idle(); layoutHost()
        assertEquals(tid, Tree.parentOf(Tree.root(Session.db!!), k)!!.uuid)
        assertEquals("tree kept its scroll", ty, treeBox().scrollY)
        assertEquals(b2, at(list()))
    }

    @Test fun detailView_nameColumn_longPressToDrag_swipeScrolls() {
        val ex = details()
        val r = ex.rows[20].uuid
        list().setSelectionFromTop(18, 0); layoutHost(); layoutHost()
        val before = at(list())
        var (x, y) = cellCenter(list(), r, fx = 0.1f)
        assertTrue(x < ex.colName)
        touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..12) touch(MotionEvent.ACTION_MOVE, x, y - W.dp(20) * i)
        assertFalse("no drag without a long press", ex.dragging); assertFalse(menuShowing())
        touch(MotionEvent.ACTION_UP, x, y - W.dp(240)); idle(); layoutHost()
        assertNotEquals("the list scrolled", before, at(list()))
        assertTrue(at(list()).first >= before.first)
        // long press on the name, then move = drag (reorder)
        layoutHost()
        val p = list().firstVisiblePosition - list().headerViewsCount
        val from = ex.rows[p + 3].uuid; val to = ex.rows[p + 1].uuid
        dragCell({ list() }, from, to, 0.1f, 0.15f)
        val o = order(); assertEquals(o.indexOf(to) - 1, o.indexOf(from))
    }
}
