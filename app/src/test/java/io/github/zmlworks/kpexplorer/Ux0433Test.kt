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

/** 0.4.33: no "…" in labels, easier ×, menu names, latched toolbar buttons, the Save box, the internal backup list, backups follow a master password change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0433Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0433").mkdirs() }
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

    private fun waitFor(what: String, cond: () -> Boolean) {
        repeat(1500) { if (cond()) return; idle(); Thread.sleep(10) }
        assertTrue("timed out: $what", cond())
    }
    private fun measureDialog(d: io.github.zmlworks.kpexplorer.ui.ClassicDialog): View = d.frame.also { v ->
        v.measure(View.MeasureSpec.makeMeasureSpec(W_ - 72, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_ - 300, View.MeasureSpec.AT_MOST))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }
    private fun bar(frame: View) = (frame as ViewGroup).getChildAt(0) as ViewGroup
    private fun closeOf(frame: View) = bar(frame).let { it.getChildAt(it.childCount - 1) }
    private fun tapIn(v: View, x: Float, y: Float) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_UP, x, y, 0); v.dispatchTouchEvent(u); u.recycle()
        idle()
    }
    /** The app's own strings (values/strings.xml), in the language of [c]. */
    private fun stringsOf(c: android.content.Context): Map<String, String> {
        val own = Regex("<string name=\"([^\"]+)\"").findAll(File("src/main/res/values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        assertTrue(own.size > 50)
        return R.string::class.java.fields.filter { it.name in own }.associate { f -> f.name to c.getString(f.getInt(null)) }
    }
    private fun hasEllipsis(s: String) = s.contains('…') || s.contains("...")
    private fun en() = L.wrap(app().also { L.setLang(it, L.Lang.EN) })
    private fun opens(f: File, pw: String) = runCatching { Kdbx.open(f.readBytes(), Kdbx.compositeKey(pw)) }.isSuccess
    private fun today() = java.time.LocalDate.now().toString()

    // ---------------------------------------------------------------- 1, 3, 4, 6, 7: labels

    @Test fun noEllipsis_inAnyStringOrMenu_jaAndEn() {
        val ja = stringsOf(app()).filterValues { hasEllipsis(it) }
        assertTrue("ja: $ja", ja.isEmpty())
        val ex = a.explorer!!
        val lists = listOf(ex.mainMenu(), ex.itemMenu(row(ids["Gmail"]!!)), ex.itemMenu(row(ids["G"]!!)), ex.treeMenu(Tree.root(Session.db!!).uuid), ex.treeMenu(bin()))
        for (l in lists) assertTrue(labels(l).toString(), labels(l).none { hasEllipsis(it) })
        a.store.backup()
        val bs = BackupScreen(a); a.show(bs); idle(); layoutHost()
        assertTrue(texts(bs.view).toString(), texts(bs.view).none { hasEllipsis(it) })
        assertTrue(labels(bs.menuFor(a.store.backups()[0])).none { hasEllipsis(it) })
        val enS = stringsOf(en()).filterValues { hasEllipsis(it) }
        assertTrue("en: $enS", enS.isEmpty())
        // the boxes opened from the menu, in both languages
        L.setLang(app(), L.Lang.JA)
        a.showExplorer(); idle()
        for (d in listOf(Security.saveDialog(a), Security.changeMasterPassword(a), Security.askExportPassword(a, "x.kdbx") { Session.db })) {
            idle(); assertTrue(texts(d.frame).toString(), texts(d.frame).none { hasEllipsis(it) }); d.dismiss()
        }
        BinSettings.show(a); idle()
        assertTrue(texts(latestDialog().frame).none { hasEllipsis(it) }); latestDialog().dismiss()
    }

    @Test fun mainMenu_newNames_noHowToUse_aboutLast_withoutTheBackLog() {
        val l = labels(a.explorer!!.mainMenu())
        assertFalse(l.toString(), l.any { it.contains("使い方") })
        assertTrue(l.containsAll(listOf("現在のバックアップを作成", "外部に書き出す", "内部バックアップの一覧", "マスターパスワードの変更", "データベースのプロパティ", "About")))
        assertFalse(l.any { it.contains("データベースの情報") || it.contains("バージョン情報") || it.contains("パスワード付き") || it.contains("保存したファイル") })
        assertEquals("About", l.last())
        assertEquals("データベースのプロパティ", l[l.size - 2])
        showMainMenu()
        shot("v0433/01-menu.png", menuAt = W_ - W.dp(240) to W.dp(60))
        Menus.dismiss(); idle()
        item(a.explorer!!.mainMenu(), "About").action(); idle()
        val ab = latestDialog()
        assertTrue(texts(ab.frame).contains("About"))
        assertEquals(listOf("ライセンス", "OK"), ab.buttonViews.map { it.text.toString() })
        assertFalse(Help::class.java.declaredMethods.any { it.name == "backLog" || it.name == "usage" })
        ab.dismiss()
        item(a.explorer!!.mainMenu(), "データベースのプロパティ").action(); idle()
        assertTrue(texts(latestDialog().frame).contains("データベースのプロパティ"))
        latestDialog().dismiss()
        val e = en()
        assertEquals("Database properties", e.getString(R.string.menu_db_info))
        assertEquals("About", e.getString(R.string.menu_about))
        assertEquals("Internal backups", e.getString(R.string.menu_backup_list))
        assertEquals("Export", e.getString(R.string.menu_export))
        assertEquals("Back up the current database", e.getString(R.string.menu_internal_save))
    }

    // ---------------------------------------------------------------- 2: the × is easier to hit

    @Test fun closeButton_hasA48dpTarget_tapNextToItCloses() {
        val d = Dlg.message(a, "タイトル", "本文"); idle()
        val f = measureDialog(d) as ViewGroup
        val close = closeOf(f)
        assertTrue("look unchanged: ${close.width}", close.width == W.dp(26) && close.height == W.dp(22))
        val r = W.closeTargetRect(f, close)
        assertTrue("target $r", r.width() >= W.dp(48) && r.height() >= W.dp(48))
        assertNotNull(f.touchDelegate)
        // a tap 10 dp left of the × and 8 dp below the title bar closes the box
        val cr = android.graphics.Rect(0, 0, close.width, close.height); f.offsetDescendantRectToMyCoords(close, cr)
        val x = cr.left - W.dp(10f).toFloat(); val y = bar(f).bottom + W.dp(8).toFloat()
        assertTrue(r.contains(x.toInt(), y.toInt()))
        tapIn(f, x, y)
        assertFalse("closed", d.isShowing)
        // further away: nothing happens
        val d2 = Dlg.message(a, "タイトル", "本文"); idle()
        val f2 = measureDialog(d2) as ViewGroup
        tapIn(f2, W.dp(30).toFloat(), bar(f2).bottom + W.dp(8).toFloat())
        assertTrue(d2.isShowing)
        // the buttons of the box still work as before
        clickButton(d2, "OK"); assertFalse(d2.isShowing)
        // full-screen window (backup list): a tap on the title bar left of the × goes back
        val bs = BackupScreen(a); a.show(bs); idle(); layoutHost()
        val b = bar(bs.view); val c = closeOf(bs.view)
        val l = IntArray(2); c.getLocationInWindow(l)
        touch(MotionEvent.ACTION_DOWN, (l[0] - W.dp(12)).toFloat(), l[1] + c.height / 2f); touch(MotionEvent.ACTION_UP, (l[0] - W.dp(12)).toFloat(), l[1] + c.height / 2f); idle()
        assertTrue("back to the folders", a.screen is ExplorerScreen)
        assertTrue(b.height > 0)
    }

    // ---------------------------------------------------------------- 8: latched tool buttons

    @Test fun saveMenuSearch_stayPressedWhileOpen() {
        val ex = a.explorer!!; layoutHost()
        fun toolView(label: String) = allViews(ex.view).first { it.contentDescription == label }
        assertFalse(ex.toolHeld("save") || ex.toolHeld("menu") || ex.toolHeld("find"))
        toolView("保存").performClick(); idle()
        assertTrue(ex.toolHeld("save"))
        val d = latestDialog()
        d.dismiss(); idle()
        assertFalse("released when the box closes", ex.toolHeld("save"))
        toolView("メニュー").performClick(); idle()
        assertTrue(ex.toolHeld("menu")); assertTrue(menuShowing())
        shot("v0433/02-toolbar-menu-held.png", menuAt = (screenRect(toolView("メニュー")).right - (Menus.current?.width ?: W.dp(220))).coerceAtLeast(0) to screenRect(toolView("メニュー")).bottom)
        Menus.dismiss(); idle()
        assertFalse(ex.toolHeld("menu"))
        toolView("検索").performClick(); idle(); layoutHost()
        assertTrue(ex.toolHeld("find"))
        // the latched look: a sunken frame on a dithered face (not the flat face of the others)
        val bmp = draw(); val sr = screenRect(toolView("検索")); val ur = screenRect(toolView("上へ"))
        val px = W.px
        assertEquals("dark top-left edge", W.SHADOW, bmp.getPixel(sr.left + 4 * px, sr.top))
        val face = (0 until 4).map { bmp.getPixel(sr.left + 3 * px + it * px, sr.bottom - 3 * px) }.toSet()
        assertTrue("dithered: $face", face.contains(W.HILITE) && face.contains(W.FACE))
        assertNotEquals(W.SHADOW, bmp.getPixel(ur.left + 4 * px, ur.top))
        shot("v0433/03-toolbar-search-held.png")
        ex.closeSearch(); idle()
        assertFalse(ex.toolHeld("find"))
    }

    private fun screenRect(v: View): android.graphics.Rect { val l = IntArray(2); v.getLocationInWindow(l); return android.graphics.Rect(l[0], l[1], l[0] + v.width, l[1] + v.height) }

    // ---------------------------------------------------------------- 5, 9: the Save box and 現在のバックアップを作成

    @Test fun saveBox_namesTodaysBackup_dashN_andMakesIt() {
        val d = Security.saveDialog(a); idle()
        val t = texts(d.frame)
        val name = today() + ".kdbx"
        assertEquals(listOf("保存", "アプリ内に保存", "$name として内部にバックアップします", "外部に書き出す", "保存先を選び、パスワード付きKDBXファイルを書き出します", "キャンセル"),
            t.filter { it.isNotEmpty() })
        assertEquals(listOf("キャンセル"), d.buttonViews.map { it.text.toString() })
        Dlg.last = d; shot("v0433/04-save-dialog.png", dialog = true)
        findTag<View>(d.frame, "save-internal")!!.performClick(); idle()
        waitFor("backup") { a.store.backups().any { it.name == name } }
        assertFalse(d.isShowing)
        // the next one of the same day: -2, then -3
        val d2 = Security.saveDialog(a); idle()
        assertTrue(texts(d2.frame).contains("${today()}-2.kdbx として内部にバックアップします"))
        findTag<View>(d2.frame, "save-internal")!!.performClick(); idle()
        waitFor("backup -2") { a.store.backups().any { it.name == "${today()}-2.kdbx" } }
        assertEquals("${today()}-3.kdbx", a.store.nextBackupName())
        // 外部に書き出す opens the export box
        val d3 = Security.saveDialog(a); idle()
        findTag<View>(d3.frame, "save-export")!!.performClick(); idle()
        assertTrue(texts(latestDialog().frame).contains("書き出すファイルのパスワードを選んでください。KeePass / KeePassXC などで開くときに必要です。"))
        latestDialog().dismiss()
        // English
        L.setLang(app(), L.Lang.EN)
        val de = Security.saveDialog(a); idle()
        assertTrue(texts(de.frame).containsAll(listOf("Save in app", "Backs up inside the app as ${today()}-3.kdbx", "Export", "Choose a location and write a password-protected KDBX file", "Cancel")))
        de.dismiss()
    }

    @Test fun menuBackupNow_asksFirst_namingTheFile() {
        item(a.explorer!!.mainMenu(), "現在のバックアップを作成").action(); idle()
        val c = latestDialog()
        assertTrue(texts(c.frame).toString(), texts(c.frame).contains("現在のデータベースを ${today()}.kdbx として内部にバックアップしますか?"))
        assertEquals(listOf("作成", "キャンセル"), c.buttonViews.map { it.text.toString() })
        clickButton(c, "キャンセル")
        Session.flush(); idle()
        assertTrue("nothing made", a.store.backups().isEmpty())
        item(a.explorer!!.mainMenu(), "現在のバックアップを作成").action(); idle()
        clickButton(latestDialog(), "作成")
        waitFor("backup") { a.store.backups().map { it.name } == listOf("${today()}.kdbx") }
    }

    // ---------------------------------------------------------------- 10: the internal backup list

    private fun backupFiles(): List<File> {
        val s = a.store
        val f1 = s.backup(java.time.LocalDate.of(2026, 11, 1)); f1.setLastModified(1_700_000_000_000)
        val f2 = s.backup(java.time.LocalDate.of(2026, 11, 2)); f2.setLastModified(1_700_000_300_000)
        val f3 = s.backup(java.time.LocalDate.of(2026, 11, 3)); f3.setLastModified(1_700_000_200_000)
        // a bigger one: same database with an extra entry
        val big = Session.db!!.let { db -> var d = db; repeat(40) { i -> d = Tree.addEntry(d, Tree.root(d).uuid, Tree.newEntry(Tree.Fields("追加$i", "u$i", "p", "", "", emptyList()))) }; d }
        val f4 = File(s.backupDir, "Zz-big.kdbx"); Store.writeAtomic(f4, Kdbx.encode(big, keepSalt = true)); f4.setLastModified(1_700_000_100_000)
        return listOf(f1, f2, f3, f4)
    }
    private fun shownNames(bs: BackupScreen) = bs.files.map { it.name }
    private fun headerText(bs: BackupScreen, key: String) = (findTag<View>(bs.view, "sort-$key") as android.widget.TextView).text.toString()

    @Test fun backupList_title_noIconNoLocation_sortsByHeaders_remembered() {
        backupFiles()
        item(a.explorer!!.mainMenu(), "内部バックアップの一覧").action(); idle(); layoutHost()
        val bs = a.screen as BackupScreen
        val tx = texts(bs.view)
        assertTrue(tx.toString(), tx.contains("内部バックアップ"))
        assertFalse("no 場所 row", tx.any { it == "場所" || it.contains("アプリ内 / backups") })
        assertFalse("no icon in the title bar", allViews(bar(bs.view)).any { it is android.widget.ImageView })
        // default: newest first
        assertEquals(listOf("2026-11-02.kdbx", "2026-11-03.kdbx", "Zz-big.kdbx", "2026-11-01.kdbx"), shownNames(bs))
        assertEquals("更新日時 ▼", headerText(bs, "date")); assertEquals("名前", headerText(bs, "name"))
        tapView(findTag<View>(bs.view, "sort-name")!!); layoutHost()
        assertEquals(listOf("2026-11-01.kdbx", "2026-11-02.kdbx", "2026-11-03.kdbx", "Zz-big.kdbx"), shownNames(bs))
        assertEquals("名前 ▲", headerText(bs, "name")); assertEquals("更新日時", headerText(bs, "date"))
        tapView(findTag<View>(bs.view, "sort-name")!!); layoutHost()
        assertEquals(listOf("Zz-big.kdbx", "2026-11-03.kdbx", "2026-11-02.kdbx", "2026-11-01.kdbx"), shownNames(bs))
        assertEquals("名前 ▼", headerText(bs, "name"))
        tapView(findTag<View>(bs.view, "sort-size")!!); layoutHost()
        assertEquals("サイズ ▲", headerText(bs, "size"))
        assertEquals("Zz-big.kdbx", shownNames(bs).last())
        tapView(findTag<View>(bs.view, "sort-size")!!); layoutHost()
        assertEquals("Zz-big.kdbx", shownNames(bs).first())
        tapView(findTag<View>(bs.view, "sort-date")!!); layoutHost()
        assertEquals("a new column: dates newest first", "2026-11-02.kdbx", shownNames(bs).first())
        tapView(findTag<View>(bs.view, "sort-date")!!); layoutHost()
        assertEquals(listOf("2026-11-01.kdbx", "Zz-big.kdbx", "2026-11-03.kdbx", "2026-11-02.kdbx"), shownNames(bs))
        tapView(findTag<View>(bs.view, "sort-name")!!); layoutHost()
        shot("v0433/05-backup-list-sorted-by-name.png")
        // remembered
        val again = BackupScreen(a); a.show(again); idle(); layoutHost()
        assertEquals(BackupScreen.SortBy.NAME, again.sortBy); assertTrue(again.ascending)
        assertEquals("2026-11-01.kdbx", shownNames(again).first())
        // the long-press menu starts with 名前の変更
        val m = labels(again.menuFor(again.files[0]))
        assertEquals(listOf("名前の変更", "|"), m.take(2))
        assertTrue(m.containsAll(listOf("閲覧 (読み取り専用)", "外部に書き出す", "このバックアップから復元", "削除")))
        // 今すぐ作成 asks first
        tapView(allViews(again.view).first { it is android.widget.TextView && it.text.toString() == "今すぐ作成" })
        val c = latestDialog()
        assertTrue(texts(c.frame).contains("現在のデータベースを ${today()}.kdbx として内部にバックアップしますか?"))
        assertEquals(4, a.store.backups().size)
        clickButton(c, "作成")
        waitFor("made") { a.store.backups().size == 5 }
        waitFor("listed") { again.files.size == 5 }
        assertEquals("${today()}.kdbx", again.selected?.name)
        // English headers
        L.setLang(app(), L.Lang.EN)
        val e = BackupScreen(a); a.show(e); idle(); layoutHost()
        assertTrue(texts(e.view).containsAll(listOf("Internal backups", "Name ▲", "Size", "Modified")))
    }

    @Test fun backupList_browse_endsBackInTheList() {
        val fs = backupFiles()
        val bs = BackupScreen(a); a.show(bs); idle(); layoutHost()
        bs.menuFor(fs[1]).first { it.label == "閲覧 (読み取り専用)" }.action()
        waitFor("read-only view") { Session.readOnlyTemp && a.screen is ExplorerScreen }
        TempImport.exitTemp(a); idle()
        assertFalse(Session.readOnlyTemp)
        val back = a.screen
        assertTrue("back in the list: $back", back is BackupScreen)
        assertEquals(fs[1].name, (back as BackupScreen).selected?.name)
        // a file browsed from the menu (外部ファイルを閲覧 / not the list) still ends in the folders: exitTemp without a list
        a.showExplorer(); idle()
        assertTrue(a.screen is ExplorerScreen)
    }

    // ---------------------------------------------------------------- 11: master password change rewrites backups

    private fun otherBackup(): File {
        val other = Kdbx.create("other", Kdbx.compositeKey("other"), rootName = "", light = true)
        val f = File(a.store.backupDir, "other-password.kdbx"); Store.writeAtomic(f, Kdbx.encode(other)); f.setLastModified(1_600_000_000_000)
        return f
    }

    @Test fun masterPassword_rewritesTheBackupsThatOpenWithIt() {
        val fs = backupFiles().take(2)
        val oth = otherBackup(); val othBytes = oth.readBytes()
        val mt = fs.map { it.lastModified() }
        item(a.explorer!!.mainMenu(), "マスターパスワードの変更").action(); idle()
        val d = latestDialog()
        val tx = texts(d.frame).filter { it.isNotEmpty() }
        assertEquals(listOf("マスターパスワードの変更", "現在のパスワードで開けられる内部バックアップも新しいパスワードに書き換えます",
            "新しいパスワード", "新しいパスワードの確認", "パスワードを表示する", "内部バックアップのパスワードを書き換えない", "変更", "キャンセル"), tx)
        val fields = allViews(d.frame).filterIsInstance<android.widget.EditText>()
        fields[0].setText("新しいpw"); fields[1].setText("新しいpw")
        Dlg.last = d; shot("v0433/06-master-password.png", dialog = true)
        clickButton(d, "変更")
        waitFor("report") { Dlg.last?.let { texts(it.frame).any { t -> t.startsWith("マスターパスワードを変更しました。") } } == true }
        val msg = texts(Dlg.last!!.frame).first { it.startsWith("マスターパスワードを変更しました。") }
        assertEquals("マスターパスワードを変更しました。\n内部バックアップ: 4 件を新しいパスワードに書き換えました。\n1 件は現在のパスワードで開けないため、そのままです。", msg)
        Dlg.last!!.dismiss()
        assertTrue("database", runCatching { Kdbx.open(a.store.read(), Kdbx.compositeKey("新しいpw")) }.isSuccess)
        for (f in a.store.backups().filter { it.name != oth.name }) {
            assertTrue("${f.name} opens with the new one", opens(f, "新しいpw"))
            assertFalse("${f.name} no longer with the old one", opens(f, "sample"))
        }
        assertEquals("kept the list order", mt, fs.map { it.lastModified() })
        assertArrayEquals("another password: untouched", othBytes, oth.readBytes())
        assertFalse("no temporary files left", (a.store.backupDir.listFiles() ?: emptyArray()).any { it.name.endsWith(".tmp") })
    }

    @Test fun masterPassword_keepBackups_onlyAfterTheWarning() {
        val f = backupFiles()[0]
        Security.changeMasterPassword(a); idle()
        val d = latestDialog()
        val keep = findTag<View>(d.frame, "keep-backups") as android.widget.CheckBox
        keep.performClick(); idle()
        assertFalse("not ticked before the warning is accepted", keep.isChecked)
        val warn = latestDialog()
        assertTrue(texts(warn.frame).contains("バックアップを開くのに古いパスワードの入力が必要になります"))
        Dlg.last = warn; shot("v0433/07-master-password-keep-warning.png", dialog = true)
        clickButton(warn, "キャンセル")
        assertFalse(keep.isChecked)
        keep.performClick(); idle()
        clickButton(latestDialog(), "OK")
        assertTrue("ticked after OK", keep.isChecked)
        val before = latestDialog()
        keep.performClick(); idle()
        assertFalse("unticking needs no warning", keep.isChecked); assertSame(before, latestDialog())
        keep.performClick(); idle(); clickButton(latestDialog(), "OK"); assertTrue(keep.isChecked)
        val fields = allViews(d.frame).filterIsInstance<android.widget.EditText>()
        fields[0].setText("pw2"); fields[1].setText("pw2")
        clickButton(d, "変更")
        waitFor("report") { Dlg.last?.let { texts(it.frame).any { t -> t.startsWith("マスターパスワードを変更しました。") } } == true }
        assertTrue(texts(Dlg.last!!.frame).any { it.contains("内部バックアップはそのままです") })
        assertTrue("backup still with the old password", opens(f, "sample"))
        assertFalse(opens(f, "pw2"))
        assertTrue(runCatching { Kdbx.open(a.store.read(), Kdbx.compositeKey("pw2")) }.isSuccess)
    }

    @Test fun rekeyBackups_skipsWhatDoesNotOpen_andKeepsItsBytes() {
        val s = a.store
        backupFiles()
        val oth = otherBackup()
        val junk = File(s.backupDir, "junk.kdbx").apply { writeBytes(ByteArray(100) { 7 }) }
        val r = s.rekeyBackups(Kdbx.compositeKey("sample"), Kdbx.compositeKey("x"))
        assertEquals(4, r.rewritten.size)
        assertEquals(setOf(oth.name, junk.name), r.skipped.toSet())
        assertTrue(r.failed.isEmpty())
        assertArrayEquals(ByteArray(100) { 7 }, junk.readBytes())
        assertTrue(opens(File(s.backupDir, "2026-11-01.kdbx"), "x"))
    }
}
