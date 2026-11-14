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

/** 0.4.30: memo 「- 履歴」 and [OK], no stray teardrop on the first tap, tap on the selected tree folder opens / closes it, the edited key field stays above the keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0430Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0430").mkdirs() }
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

    @Test fun memo_historyLink_isCalled履歴_likeTheKeyPage() {
        val ms = MemoScreen(a, memoId()); a.show(ms); idle()
        assertTrue(ms.appMemo)
        assertEquals("- 履歴", ms.historyLink.text.toString())
        ms.openHistory(); idle()
        assertTrue(texts(latestDialog().frame).any { it.startsWith("履歴 — 買い物メモ") })
        latestDialog().dismiss(); idle()
        L.setLang(app(), L.Lang.EN)
        val en = MemoScreen(a, memoId()); a.show(en); idle()
        assertEquals("- History", en.historyLink.text.toString())
        en.openHistory(); idle()
        assertTrue(texts(latestDialog().frame).any { it.startsWith("History — ") })
        latestDialog().dismiss()
    }

    @Test fun memo_unchanged_showsOneLargeOk_changed_backAndSave() {
        setMemo("・牛乳\n・卵")
        val ms = MemoScreen(a, memoId()); a.show(ms); idle(); draw()
        assertFalse(ms.footer.changed)
        assertEquals(View.VISIBLE, ms.footer.ok.visibility); assertEquals(View.GONE, ms.footer.pair.visibility)
        assertTrue(ms.footer.ok.height >= W.dp(56) && ms.footer.ok.width > W_ * 9 / 10)
        shot("v0430/01-memo-ok.png")
        ms.text.text.append("\n・食パン"); idle(); draw()
        assertTrue(ms.footer.changed)
        assertEquals(View.GONE, ms.footer.ok.visibility); assertEquals(View.VISIBLE, ms.footer.pair.visibility)
        shot("v0430/02-memo-changed.png")
        // typed back to what it was: unchanged again
        ms.text.setText("・牛乳\n・卵"); idle()
        assertFalse(ms.footer.changed)
        ms.text.text.append("\n・バター"); idle()
        assertTrue(ms.save()); idle()
        assertFalse("after 保存: [OK]", ms.footer.changed)
        assertEquals("・牛乳\n・卵\n・バター", Tree.notes(entry(memoId())))
        // [OK] closes the page
        val okButton = (ms.footer.ok as ViewGroup).getChildAt(0)
        okButton.performClick(); idle()
        assertTrue(a.screen is ExplorerScreen)
    }

    @Test fun memo_firstTap_putsTheCursorThere_withoutTheTeardrop_laterTapsAsUsual() {
        setMemo("・牛乳\n・卵\n・食パン")
        a.show(MemoScreen(a, memoId())); idle(); draw()
        val rt = ruled()
        assertFalse(rt.isFocused)
        val x = W.dp(40).toFloat()
        tapOn(rt, x, lineMid(rt, 1))
        assertTrue(rt.isFocused)
        assertEquals("cursor in the tapped line", 1, rt.layout.getLineForOffset(rt.selectionStart))
        assertEquals(rt.selectionStart, rt.selectionEnd)
        assertFalse("no teardrop on the first tap", handleShown(rt))
        // a later tap (already editing): Android's usual behaviour, the handle under the cursor
        tapOn(rt, x, lineMid(rt, 2))
        assertEquals(2, rt.layout.getLineForOffset(rt.selectionStart))
        assertTrue("usual handle on later taps", handleShown(rt))
        // leaving the page's focus: the next tap is a first tap again
        rt.clearFocus(); idle()
        assertFalse(rt.isFocused)
    }

    @Test fun memo_keyboardOpening_handleFollowsTheCentredText() {
        setMemo("・牛乳\n・卵\n・食パン")
        a.show(MemoScreen(a, memoId())); idle(); draw()
        val rt = ruled()
        val x = W.dp(40).toFloat()
        tapOn(rt, x, lineMid(rt, 1)); draw()
        tapOn(rt, x, lineMid(rt, 1)); draw()
        assertTrue(handleShown(rt))
        rt.viewTreeObserver.dispatchOnPreDraw()
        val before = handleY(rt)
        // the keyboard opens: the page gets shorter and the centred text moves up inside the view
        a.host.setPadding(0, 0, 0, H_ * 2 / 5); draw()
        rt.viewTreeObserver.dispatchOnPreDraw()
        val after = handleY(rt)
        // where the editor puts it when asked to place it afresh
        insertion(rt).javaClass.getMethod("invalidateHandle").invoke(insertion(rt))
        val fresh = handleY(rt)
        assertTrue("the text moved ($before → $fresh)", before - fresh > rt.lineHeight)
        assertEquals("the handle moved with it", fresh, after)
        a.host.setPadding(0, 0, 0, 0); draw()
        rt.viewTreeObserver.dispatchOnPreDraw()
        val back = handleY(rt)
        insertion(rt).javaClass.getMethod("invalidateHandle").invoke(insertion(rt))
        assertEquals("and back when it closes", handleY(rt), back)
    }

    @Test fun tree_tapOnTheSelectedFolder_opensAndClosesIt_cushionFirst() {
        val ex = a.explorer!!
        val work = ids["G"]!!
        ex.navigate(work); idle(); layoutHost()
        fun tree() = a.explorer!!.folderTree!!
        fun shows(id: UUID) = tree().rows.any { it.tag == id }
        assertEquals(work, tree().selected)
        val open0 = shows(ids["メール"]!!)
        tree().tap(work); idle(); layoutHost()
        assertEquals("tap on the selected folder toggles", !open0, shows(ids["メール"]!!))
        assertEquals("still in 仕事", work, ex.current)
        assertEquals(!open0, Tree.findGroup(Session.db!!, work)!!.expanded)
        tree().tap(work); idle(); layoutHost()
        assertEquals(open0, shows(ids["メール"]!!))
        // one cushion: with an item selected in the list, the first tap only clears it
        ex.select(ids["勤怠"]!!); idle()
        tree().tap(work); idle(); layoutHost()
        assertNull(ex.selection)
        assertEquals("not toggled by the cushion tap", open0, shows(ids["メール"]!!))
        tree().tap(work); idle(); layoutHost()
        assertEquals(!open0, shows(ids["メール"]!!))
        if (!shows(ids["メール"]!!)) { tree().tap(work); idle(); layoutHost() }
        shot("v0430/03-tree-selected-folder-tap.png")
        // 「下部ファイルをすべて表示」: a tap on the highlighted folder still returns to its normal view (0.4.23), no toggling
        val open1 = shows(ids["メール"]!!)
        ex.showAllBelow(work); idle(); layoutHost()
        tree().tap(work); idle(); layoutHost()
        assertFalse(ex.flat)
        assertEquals(open1, shows(ids["メール"]!!))
        // a tap on another folder still opens that folder (no toggling there)
        val bank = gid("銀行")
        tree().tap(bank); idle(); layoutHost()
        assertEquals(bank, ex.current)
        // a folder without sub-folders: the tap does nothing more
        tree().tap(bank); idle(); layoutHost()
        assertEquals(bank, ex.current)
    }

    @Test fun moveDialogTree_secondTapOnTheSelectedFolder_opensAndClosesIt() {
        val ex = a.explorer!!
        ex.moveToDialog(ids["Gmail"]!!); idle()
        val mt = find(latestDialog().frame, io.github.zmlworks.kpexplorer.ui.FolderTree::class.java)!!
        val work = ids["G"]!!
        fun shows() = mt.rows.any { it.tag == ids["メール"]!! }
        val open0 = shows()
        mt.tap(work); idle()
        assertEquals("first tap selects", work, mt.selected)
        assertEquals(open0, shows())
        mt.tap(work); idle()
        assertEquals("second tap toggles", !open0, shows())
        assertEquals(work, mt.selected)
        latestDialog().dismiss()
    }

    private fun keyWithLongNotes(): KeyScreen {
        val ks = openKey(ids["Gmail"]!!)
        for (i in 1..6) ks.addCustomField("項目$i", "値 $i")
        ks.addCustomField("備考", "1 行目\n2 行目\n3 行目\n4 行目", multiline = true)
        idle(); layoutHost()
        return ks
    }
    private fun finishScroll(sv: android.widget.ScrollView) {
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS); sv.computeScroll(); idle()
    }
    private fun visible(sv: android.widget.ScrollView, v: View): Pair<Int, Int> {
        val r = android.graphics.Rect(0, 0, v.width, v.height); sv.offsetDescendantRectToMyCoords(v, r)
        return (r.top - sv.scrollY) to (r.bottom - sv.scrollY)
    }

    @Test fun keyField_nearTheBottom_wholeFieldStaysAboveTheKeyboard() {
        val ks = keyWithLongNotes()
        val sv = ks.scroll
        val box = ks.boxes["備考"]!!
        sv.scrollTo(0, 0); layoutHost()
        // long press (edit) on the field, the cursor at the end of the first line
        box.startEdit(); idle()
        box.field.setSelection(box.field.text.indexOf('\n'))
        val ime = H_ * 2 / 5
        a.host.setPadding(0, 0, 0, ime); layoutHost(); finishScroll(sv); layoutHost()
        val (top, bottom) = visible(sv, box)
        assertTrue("whole field visible: $top..$bottom in 0..${sv.height}", top >= 0 && bottom <= sv.height)
        // picture with the keyboard's area drawn grey
        val b = Bitmap.createBitmap(W_, H_, Bitmap.Config.ARGB_8888); val c = Canvas(b); a.host.draw(c)
        c.drawRect(0f, (H_ - ime).toFloat(), W_.toFloat(), H_.toFloat(), android.graphics.Paint().apply { color = 0xFF9AA0A6.toInt() })
        c.drawText("(keyboard)", W_ / 2f - 150, H_ - ime / 2f, android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = 60f })
        FileOutputStream(File(out, "v0430/04-key-field-above-keyboard.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // typing new lines (the field grows): still all in view
        box.field.text.insert(box.field.text.length, "\n5 行目\n6 行目"); idle(); layoutHost(); finishScroll(sv); layoutHost()
        val (t2, b2) = visible(sv, box)
        assertTrue("grown field visible: $t2..$b2 in 0..${sv.height}", t2 >= 0 && b2 <= sv.height)
        FieldEditSession.commitActive(); idle()
        a.host.setPadding(0, 0, 0, 0)
    }

    @Test fun keyField_tallerThanTheSpace_keepsTheCursorLine() {
        val ks = openKey(ids["Gmail"]!!)
        ks.addCustomField("備考", (1..60).joinToString("\n") { "行 $it" }, multiline = true)
        idle(); layoutHost()
        val sv = ks.scroll
        val box = ks.boxes["備考"]!!
        box.startEdit(); idle()
        a.host.setPadding(0, 0, 0, H_ * 2 / 5); layoutHost(); finishScroll(sv); layoutHost()
        val (top, bottom) = visible(sv, box)
        assertTrue("taller than the space", bottom - top > sv.height)
        assertTrue("the space is all field: $top..$bottom", top <= 0 && bottom >= sv.height)
        val f = box.field
        val line = f.layout.getLineForOffset(f.selectionEnd)
        val r = android.graphics.Rect(0, f.layout.getLineTop(line) + f.totalPaddingTop, f.width, f.layout.getLineBottom(line) + f.totalPaddingTop)
        sv.offsetDescendantRectToMyCoords(f, r)
        assertTrue("cursor line visible", r.top - sv.scrollY >= 0 && r.bottom - sv.scrollY <= sv.height)
        FieldEditSession.commitActive(); idle()
        a.host.setPadding(0, 0, 0, 0)
    }
}
