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

/** 0.4.31: the app's shorter long press, the long-pressed tree folder framed while its menu is open, a tapped tree folder stays put, tag buttons above the keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0431Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0431").mkdirs() }
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


    private fun gid(name: String) = Order.children(Tree.root(Session.db!!)).first { it.name == name }.uuid
    private fun names(ids: List<UUID>) = ids.map { id -> Tree.findGroup(Session.db!!, id)!!.name.ifEmpty { "/" } }
    /** Top-level folders as the tree shows them (root's direct children). */
    private fun treeTop(): List<String> = a.explorer!!.folderTree!!.let { t ->
        Tree.root(Session.db!!).let { r -> treeIds().filter { id -> Tree.parentOf(r, id)?.uuid == r.uuid } } }.let { names(it) }
    private fun listFolders() = a.explorer!!.rows.filterIsInstance<Order.Node.F>().map { it.name }


    private fun ruled() = find(a.host, io.github.zmlworks.kpexplorer.ui.Parts.RuledText::class.java)!!
    private fun draw(): Bitmap { layoutHost(); return Bitmap.createBitmap(W_, H_, Bitmap.Config.ARGB_8888).also { a.host.draw(Canvas(it)) } }
    private fun setMemo(text: String) { val id = memoId(); Session.update { Order.saveEntry(it, id, mapOf("Title" to "買い物メモ", "Notes" to text), emptyList(), null) } }
    private fun box(rt: io.github.zmlworks.kpexplorer.ui.Parts.RuledText): Pair<Int, Int> {
        val r0 = android.graphics.Rect(); rt.getLineBounds(0, r0)
        val r1 = android.graphics.Rect(); rt.getLineBounds(rt.lineCount - 1, r1)
        return r0.top to r1.bottom
    }
    private fun assertCentred(rt: io.github.zmlworks.kpexplorer.ui.Parts.RuledText) {
        val (top, bottom) = box(rt)
        val mid = (top + bottom) / 2; val page = (rt.paddingTop + rt.height - rt.paddingBottom) / 2
        assertTrue("text block $top..$bottom centred in ${rt.height}: $mid vs $page", kotlin.math.abs(mid - page) <= rt.lineHeight / 2)
        assertTrue("well below the top", top > rt.paddingTop + rt.lineHeight)
    }

    /** A tap at ([x], [y]) on [v] from the touch screen, well apart from the last one (not a double tap). */
    private fun tapOn(v: View, x: Float, y: Float, hold: Long = 40) {
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        t = android.os.SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(d); d.recycle()
        ShadowLooper.idleMainLooper(hold, java.util.concurrent.TimeUnit.MILLISECONDS)
        val u = MotionEvent.obtain(t, t + hold, MotionEvent.ACTION_UP, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(u); u.recycle(); t += hold
        idle()
        v.viewTreeObserver.dispatchOnPreDraw()   // the editor shows / places its handles before drawing
    }
    private fun editor(tv: android.widget.TextView): Any = android.widget.TextView::class.java.getDeclaredField("mEditor").apply { isAccessible = true }.get(tv)!!
    private fun insertion(tv: android.widget.TextView): Any = editor(tv).javaClass.getDeclaredMethod("getInsertionController").apply { isAccessible = true }.invoke(editor(tv))!!
    private fun handleShown(tv: android.widget.TextView): Boolean = insertion(tv).javaClass.getMethod("isActive").invoke(insertion(tv)) as Boolean
    /** The insertion handle's y (text view coordinates of the cursor line bottom, as the editor last placed it). */
    private fun handleY(tv: android.widget.TextView): Int {
        val h = insertion(tv).javaClass.getMethod("getHandle").invoke(insertion(tv))!!
        var c: Class<*> = h.javaClass
        while (c.simpleName != "HandleView") c = c.superclass
        return c.getDeclaredField("mPositionY").apply { isAccessible = true }.getInt(h)
    }
    private fun lineMid(rt: android.widget.TextView, line: Int): Float { val r = android.graphics.Rect(); rt.getLineBounds(line, r); return ((r.top + r.bottom) / 2 - rt.scrollY).toFloat() }
    private fun finishScroll(sv: android.widget.ScrollView) {
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS); sv.computeScroll(); idle()
    }
    private fun visible(sv: android.widget.ScrollView, v: View): Pair<Int, Int> {
        val r = android.graphics.Rect(0, 0, v.width, v.height); sv.offsetDescendantRectToMyCoords(v, r)
        return (r.top - sv.scrollY) to (r.bottom - sv.scrollY)
    }

    private fun tree() = a.explorer!!.folderTree!!
    private fun rowOf(id: UUID) = tree().rows.first { it.tag == id }
    /** y of [id]'s tree row inside the tree box (what the finger sees). */
    private fun rowY(id: UUID) = rowOf(id).top + tree().top - treeBox().scrollY
    private fun downOn(v: View, x: Float, y: Float) { t = android.os.SystemClock.uptimeMillis(); val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0); v.dispatchTouchEvent(e); e.recycle() }
    private fun upOn(v: View, x: Float, y: Float) { val e = MotionEvent.obtain(t, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0); v.dispatchTouchEvent(e); e.recycle(); idle() }
    private fun waitMs(ms: Long) = ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

    @Test fun longPress_isEightyPercentOfTheSystemDelay_everywhere() {
        val sys = android.view.ViewConfiguration.getLongPressTimeout()
        val lp = W.longPressMs()
        assertEquals("80 % of the system delay", Math.round(sys * 0.8), lp)
        assertTrue(lp < sys)
        val early = lp - 20; val late = 30L
        // folder tree: no menu just before, the menu just after
        val work = ids["G"]!!
        val r = rowOf(work)
        downOn(r, r.width - 10f, r.height / 2f); waitMs(early)
        assertFalse(menuShowing())
        waitMs(late); assertTrue("tree long press", menuShowing())
        upOn(r, r.width - 10f, r.height / 2f); Menus.dismiss(); idle()
        // list (detail view): the same
        val ex = details(); layoutHost()
        val (x, y) = cellCenter(list(), gid("銀行"))
        touch(MotionEvent.ACTION_DOWN, x, y); waitMs(early)
        assertFalse(menuShowing())
        waitMs(late); assertTrue("list long press", menuShowing())
        touch(MotionEvent.ACTION_UP, x, y); Menus.dismiss(); idle()
        assertNotNull(ex)
        // key field (was a fixed 320 ms): the same shared time
        val ks = openKey(ids["Gmail"]!!)
        val box = ks.boxes[Order.F_USER]!!
        downOn(box, box.width / 2f, box.height / 2f); waitMs(early)
        assertFalse(box.editing)
        waitMs(late); assertTrue(box.editing)
        upOn(box, box.width / 2f, box.height / 2f)
        FieldEditSession.commitActive(); idle()
        // key icon: the picker
        val tile = ks.iconTile!!
        downOn(tile, 10f, 10f); waitMs(early)
        assertFalse(org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true)
        waitMs(late); assertTrue("icon long press", org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true)
        upOn(tile, 10f, 10f); latestDialog().dismiss(); idle()
    }

    @Test fun longPress_backupList_menuAtTheAppsTime_notATapAfterwards() {
        val lp = W.longPressMs(); val early = lp - 20; val late = 30L
        Session.flush(); idle()
        App.store(app()).backup()
        val bs = BackupScreen(a); a.show(bs); idle(); layoutHost()
        val lv = find(bs.view, ListView::class.java)!!
        val c = lv.getChildAt(0)!!
        val y = c.top + c.height / 2f
        downOn(lv, W.dp(60).toFloat(), y); waitMs(early)
        assertFalse(menuShowing())
        waitMs(late); assertTrue("backup long press", menuShowing())
        upOn(lv, W.dp(60).toFloat(), y)
        assertTrue("a long press does not count as a tap afterwards", menuShowing())
        Menus.dismiss(); idle()
    }

    @Test fun treeMenu_longPressedFolderIsFramed_untilTheMenuCloses() {
        val ex = a.explorer!!
        val work = ids["G"]!!
        val bank = gid("銀行")
        ex.navigate(work); idle(); layoutHost()
        assertEquals("仕事 is the selected (blue) folder", work, tree().selected)
        val r = rowOf(bank)
        val plain = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888).also { r.draw(Canvas(it)) }
        assertTrue(tree().longPress(bank)); idle()
        assertTrue(menuShowing())
        assertEquals(bank, tree().menuTarget)
        assertNull(tree().menuTarget.takeIf { it == work })
        val framed = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888).also { r.draw(Canvas(it)) }
        // the frame: dark dots around the label that the plain row does not have
        var dots = 0
        for (yy in 0 until r.height) for (xx in 0 until r.width) if (framed.getPixel(xx, yy) != plain.getPixel(xx, yy)) dots++
        assertTrue("dotted frame drawn ($dots px)", dots > 100)
        val loc = IntArray(2); r.getLocationOnScreen(loc)
        shot("v0431/01-tree-menu-framed.png", menuAt = loc[0] + r.width / 3 to loc[1] + r.height / 2)
        Menus.dismiss(); idle()
        assertNull("frame gone with the menu", tree().menuTarget)
        val after = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888).also { r.draw(Canvas(it)) }
        assertTrue(after.sameAs(plain))
        // the selected folder itself: framed too, outside its blue band
        assertTrue(tree().longPress(work)); idle()
        assertEquals(work, tree().menuTarget)
        Menus.dismiss(); idle()
        assertNull(tree().menuTarget)
    }

    @Test fun treeTap_selectedFolderStaysUnderTheFinger() {
        val ex = a.explorer!!
        layoutHost()
        val sv = treeBox()
        assertTrue("the tree scrolls", tree().height > sv.height)
        sv.scrollTo(0, W.dp(32) * 5); layoutHost()
        val f = gid("フォルダ06")
        val y0 = rowY(f)
        assertTrue("row in view", y0 >= 0 && y0 + rowOf(f).height <= sv.height)
        tree().tap(f); idle(); layoutHost()
        assertEquals(f, ex.current)
        assertEquals("the tapped folder did not move", y0, rowY(f))
        assertEquals(f, tree().selected)
        // the next one below, too
        val g = gid("フォルダ07")
        val y1 = rowY(g)
        tree().tap(g); idle(); layoutHost()
        assertEquals(y1, rowY(g))
        // a folder opened elsewhere (not visible in the tree): scrolled just into view
        sv.scrollTo(0, 0); layoutHost()
        ex.navigate(gid("フォルダ14")); idle(); layoutHost()
        val yy = rowY(gid("フォルダ14"))
        assertTrue("current folder visible: $yy", yy >= 0 && yy + rowOf(gid("フォルダ14")).height <= treeBox().height)
        // the same folder refreshed: offset kept exactly (0.4.27)
        val keep = treeBox().scrollY
        ex.refresh(); idle(); layoutHost()
        assertEquals(keep, treeBox().scrollY)
    }

    @Test fun readOnlyBrowse_openClosedFolders_surviveMovingAround() {
        val ex = a.explorer!!
        val work = ids["G"]!!
        Session.beginTempView(Session.db!!, "test")
        try {
            ex.refresh(); idle(); layoutHost()
            fun shows() = tree().rows.any { it.tag == ids["メール"]!! }
            val open0 = shows()
            tree().toggle(work); idle(); layoutHost()
            assertEquals(!open0, shows())
            assertEquals("not written to the browsed file", open0, Tree.findGroup(Session.db!!, work)!!.expanded)
            tree().tap(gid("銀行")); idle(); layoutHost()
            assertEquals(gid("銀行"), ex.current)
            assertEquals("kept after moving to another folder", !open0, shows())
        } finally { Session.exitTempView() }
    }

    @Test fun tagEdit_nearTheBottom_buttonsStayAboveTheKeyboard() {
        for ((k, tg) in listOf("Amazon" to listOf("買い物", "通販"), "Steam" to listOf("ゲーム"), "楽天" to listOf("買い物", "ポイント"),
                "X (Twitter)" to listOf("SNS"), "会社のVPN" to listOf("仕事"))) {
            val e = entry(ids[k]!!)
            val v = HashMap<String, String>(); e.fields.forEach { (n, f) -> v[n] = f.content }
            Session.update { Order.saveEntry(it, ids[k]!!, v, tg, null) }
        }
        val ks = openKey(ids["Gmail"]!!)
        for (i in 1..6) ks.addCustomField("項目$i", "値 $i")
        repeat(20) { ks.moveField(Order.F_TAGS, 1) }
        idle(); layoutHost()
        val sv = ks.scroll
        val box = ks.boxes[Order.F_TAGS]!!
        sv.scrollTo(0, 0); layoutHost()
        box.startEdit(); idle(); layoutHost()
        assertTrue(ks.tagsShown)
        val ime = H_ * 2 / 5
        a.host.setPadding(0, 0, 0, ime); layoutHost(); finishScroll(sv); layoutHost()
        val chips = ks.chips!!
        assertTrue(chips.height > 0)
        val (bt, bb) = visible(sv, box)
        val (ct, cb) = visible(sv, chips)
        assertTrue("Tag field visible: $bt..$bb in 0..${sv.height}", bt >= 0 && bb <= sv.height)
        assertTrue("tag buttons visible: $ct..$cb in 0..${sv.height}", ct >= 0 && cb <= sv.height)
        val b = Bitmap.createBitmap(W_, H_, Bitmap.Config.ARGB_8888); val c = Canvas(b); a.host.draw(c)
        c.drawRect(0f, (H_ - ime).toFloat(), W_.toFloat(), H_.toFloat(), android.graphics.Paint().apply { color = 0xFF9AA0A6.toInt() })
        c.drawText("(keyboard)", W_ / 2f - 150, H_ - ime / 2f, android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = 60f })
        FileOutputStream(File(out, "v0431/02-tag-buttons-above-keyboard.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FieldEditSession.commitActive(); idle()
        a.host.setPadding(0, 0, 0, 0)
    }
}
