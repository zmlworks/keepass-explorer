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
import io.github.zmlworks.kpexplorer.db.FileInfo
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

/** 0.4.37: welcome screen with three protections, password strength per file, launch-screen export of strong files only (menu setting), key page names in the launch teal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0437Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0437").mkdirs() }
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
    @After fun tearDown() { runCatching { FieldEditSession.commitActive() }; Menus.dismiss(); Session.lock(); L.setLang(app(), L.Lang.SYSTEM); L.setDateOrder(app(), L.DateOrder.YMD); Vault.forceAvailableForTest = null; Vault.testBackend = null; Menus.dismiss(); Dlg.last = null }

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

    private fun lockWithStoredKey(): UnlockScreen {
        // a wrapped key on file (Robolectric has no Keystore; the screen only looks at whether one exists)
        File(app().filesDir, "wrapped.bin").writeBytes(ByteArray(40) { it.toByte() })
        // 0.4.37: the launch screen's 外部に書き出す needs the menu setting, and the file a strong master password
        App.prefs(app()).edit().putBoolean(Security.PREF_LAUNCH_EXPORT, true).commit()
        a.store.setInfo(io.github.zmlworks.kpexplorer.db.FileInfo(io.github.zmlworks.kpexplorer.db.FileInfo.Protection.PASSWORD, io.github.zmlworks.kpexplorer.db.FileInfo.Strength.STRONG))
        a.lock(); idle(); layoutHost()
        assertTrue(a.screen is UnlockScreen)
        return a.screen as UnlockScreen
    }
    private fun screenRect(v: View): android.graphics.Rect { val l = IntArray(2); v.getLocationInWindow(l); return android.graphics.Rect(l[0], l[1], l[0] + v.width, l[1] + v.height) }
    private fun until(what: String, cond: () -> Boolean) {
        repeat(600) { if (cond()) return; idle(); Thread.sleep(10) }
        fail("timed out: $what")
    }
    private val resolver get() = org.robolectric.Shadows.shadowOf(app().contentResolver)
    /** The system file screen "returns" [uri] for the last started picker; its intent is returned. */
    private fun answerPicker(uri: android.net.Uri): android.content.Intent {
        val started = org.robolectric.Shadows.shadowOf(a).nextStartedActivityForResult ?: error("no picker started")
        org.robolectric.Shadows.shadowOf(a).receiveResult(started.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
        idle()
        return started.intent
    }
    private val otherKey = Kdbx.compositeKey("other-pass")
    /** Another KeePass file (its own password), offered by the picker as content://test/外部.kdbx. */
    private fun externalFile(): Pair<android.net.Uri, ByteArray> {
        var db = Kdbx.create("ext", otherKey, rootName = "", light = true)
        val e = Tree.newEntry(Tree.Fields("外部の鍵", "ext-user", "ext-pass", "", "", emptyList()))
        db = Tree.addEntry(db, Tree.root(db).uuid, e)
        val bytes = Kdbx.encodeVerified(db)
        val uri = android.net.Uri.parse("content://test.docs/document/" + android.net.Uri.encode("primary:Download/外部.kdbx"))
        resolver.registerInputStream(uri, java.io.ByteArrayInputStream(bytes))
        return uri to bytes
    }
    private fun names() = a.explorer!!.rows.map { it.name }
    private fun openExternalFromLaunch(un: UnlockScreen, password: String) {
        val (uri, _) = externalFile()
        un.browse.performClick(); idle()
        val i = answerPicker(uri)
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, i.action)
        val d = latestDialog()
        (findTag<View>(d.frame, "temp-password") as android.widget.EditText).setText(password)
        clickButton(d, s(R.string.open))
    }

    private val base = java.time.Instant.parse("2026-11-01T00:00:00Z")
    private fun setTimes(id: UUID, created: java.time.Instant, modified: java.time.Instant) {
        Session.update { db -> val p = Tree.findEntry(db, id)!!.first
            Tree.modifyGroup(db, p.uuid) { g -> g.copy(entries = g.entries.map { e -> if (e.uuid != id) e else
                e.copy(times = (e.times ?: app.keemobile.kotpass.models.TimeData.create(created)).copy(creationTime = created, lastModificationTime = modified)) }) } }
    }
    /** Every key and memo dated at least a month back (setUp made them all "now"), S1 the newest of them. */
    private fun ageAll() {
        val all = ArrayList<UUID>()
        fun walk(g: app.keemobile.kotpass.models.Group) { g.entries.forEach { all.add(it.uuid) }; g.groups.forEach { walk(it) } }
        walk(Tree.root(Session.db!!))
        all.forEachIndexed { i, id -> val t = base.minus(java.time.Duration.ofDays(30L + i)); setTimes(id, t, t) }
        for (i in 1..30) { val t = base.minus(java.time.Duration.ofHours(i.toLong())); setTimes(ids["S$i"]!!, t, t) }
    }
    private fun tool(label: String) = allViews(a.explorer!!.view).first { it.contentDescription == label }
    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double { fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(android.graphics.Color.red(c)) + 0.7152 * ch(android.graphics.Color.green(c)) + 0.0722 * ch(android.graphics.Color.blue(c)) }
        val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    // ------------------------------------------------------------------------------------------------ 0.4.37

    private val STRONG_PW = "vGy7-Kq2p-9Zx4-Rt8w"
    private fun info(p: FileInfo.Protection, s: FileInfo.Strength) = FileInfo(p, s)
    private fun welcome(): SetupScreen {
        Session.lock(); a.explorer = null
        // the final save of the session just locked runs on the I/O thread: let it finish before the file goes
        java.util.concurrent.CountDownLatch(1).also { l -> Session.io.execute { l.countDown() }; l.await() }
        a.store.delete(); a.vault.clear()
        a.route(); idle(); layoutHost()
        assertTrue(a.screen is SetupScreen)
        return a.screen as SetupScreen
    }
    private fun lockNow(): UnlockScreen { a.lock(); idle(); layoutHost(); assertTrue(a.screen is UnlockScreen); return a.screen as UnlockScreen }
    private fun vis(v: View) = v.visibility == View.VISIBLE
    private fun panelTexts(s: SetupScreen) = texts(s.panel).filter { it.isNotEmpty() }
    private fun typePw(s: SetupScreen, pw: String) { s.p1!!.setText(pw); s.p2!!.setText(pw); idle(); layoutHost() }

    @Test fun keyPage_namesExactlyTheLaunchTeal_tagGlyphStaysBlue() {
        assertEquals(0xFF00797A.toInt(), W.KEY_LABEL)
        assertEquals(io.github.zmlworks.kpexplorer.ui.DotLogo.TEAL, W.KEY_LABEL)
        val ks = openKey(ids["Gmail"]!!)
        val tvs = allViews(ks.view).filterIsInstance<android.widget.TextView>()
        for (n in listOf("Title", "User", "Pass", "URL", "Tag")) assertEquals(n, W.KEY_LABEL, tvs.first { it.text.toString() == n }.currentTextColor)
        // (glyph pixels: teal for every field, blue for Tag — Ux0436Test.keyPage_namesAndGlyphsTeal_tagGlyphBlue, now with #00797A)
        shot("v0437/09-key-page.png")
    }

    @Test fun welcome_titleAndThreeChoices_noTaskbar_passwordFirst() {
        val s = welcome()
        val all = texts(s.view)
        assertTrue(all.toString(), all.contains("ようこそ"))
        assertFalse("no スタート / taskbar", all.any { it == "スタート" || it.matches(Regex("\\d{1,2}:\\d{2}")) })
        assertEquals(listOf("マスターパスワードを設定して始める(推奨)", "暗号化せずに使う", "OSのkeystoreを利用する"), listOf(s.rPassword, s.rNone, s.rKeystore).map { it.text.toString() })
        assertTrue("default", s.rPassword.isChecked); assertFalse(s.rNone.isChecked); assertFalse(s.rKeystore.isChecked)
        assertEquals(FileInfo.Protection.PASSWORD, s.mode)
        val t = panelTexts(s)
        for (x in listOf("マスターパスワード:", "もう一度入力:", "パスワードを表示する", "強度: (未入力)", "はじめる(S)")) assertTrue("$x in $t", t.contains(x))
        assertTrue("teal backdrop", s.backdrop == io.github.zmlworks.kpexplorer.ui.DotLogo.TEAL)
        // live meter: weak, then strong
        typePw(s, "password1")
        assertEquals(FileInfo.Strength.WEAK, s.meter!!.result!!.verdict)
        assertTrue(s.meter!!.label.text.toString(), s.meter!!.label.text.startsWith("強度: とても弱い"))
        shot("v0437/01-welcome-password-weak.png")
        typePw(s, STRONG_PW)
        assertEquals(FileInfo.Strength.STRONG, s.meter!!.result!!.verdict)
        assertEquals("強度: 強い", s.meter!!.label.text.toString())
        shot("v0437/02-welcome-password-strong.png")
        // the show-password box shows both fields
        val show = allViews(s.panel).filterIsInstance<android.widget.CheckBox>().first { it.text.toString() == "パスワードを表示する" }
        show.performClick(); idle()
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, s.p1!!.inputType and android.text.InputType.TYPE_MASK_VARIATION)
        // another choice replaces everything under the radio buttons (and forgets the typed password)
        s.rNone.performClick(); idle(); layoutHost()
        assertTrue(s.rNone.isChecked); assertFalse(s.rPassword.isChecked)
        assertNull(s.p1); assertTrue(allViews(s.panel).none { it is android.widget.EditText })
        val w2 = panelTexts(s).joinToString("\n")
        assertTrue(w2, w2.contains("暗号化されません") && w2.contains("root") && w2.contains("OS の乗っ取り") && w2.contains("はじめる(S)"))
        shot("v0437/03-welcome-unencrypted.png")
        s.rKeystore.performClick(); idle(); layoutHost()
        val w3 = panelTexts(s).joinToString("\n")
        assertTrue(w3, w3.contains("画面ロックを解除・無効に") && w3.contains("端末を初期化") && w3.contains("永久に消え") && w3.contains("二度と開けなく"))
        assertTrue(s.start.isEnabled)
        shot("v0437/04-welcome-keystore.png")
    }

    @Test fun welcome_keystore_withoutScreenLock_explainedAndOff() {
        Vault.forceAvailableForTest = false
        val s = welcome()
        s.rKeystore.performClick(); idle(); layoutHost()
        assertNotNull(findTag<View>(s.panel, "keystore-no-lock"))
        assertTrue(panelTexts(s).joinToString().contains("画面ロック (PIN・パターン・パスワード、または指紋) が設定されていない"))
        assertFalse(s.start.isEnabled)
        shot("v0437/05-welcome-keystore-no-lock.png")
        s.start.performClick(); idle()
        assertFalse(a.store.exists())
        // asking anyway (a screen lock removed meanwhile) says why
        var err = ""
        Security.firstRunKeystore(a, { err = it }) { fail("must not start") }
        assertTrue(err, err.contains("画面ロック")); assertFalse(a.store.exists())
    }

    @Test fun setup_masterPassword_recordsTheVerdict_weakAsksFirst() {
        var s = welcome()
        typePw(s, STRONG_PW)
        s.start.performClick()
        until("explorer") { a.screen is ExplorerScreen }
        assertEquals(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.STRONG), a.store.info())
        assertTrue(runCatching { Kdbx.open(a.store.read(), Kdbx.compositeKey(STRONG_PW)) }.isSuccess)
        val text = File(a.store.dbFile.parentFile, "files.properties").readText()
        assertFalse("never the password", text.contains(STRONG_PW) || text.contains("Kq2p"))
        // weak: a warning first; Back keeps the screen
        s = welcome()
        typePw(s, "sakura2026")
        s.start.performClick(); idle()
        val warn = latestDialog()
        assertTrue(texts(warn.frame).joinToString().contains("弱いと判定されました"))
        clickButton(warn, "戻る"); assertFalse("nothing made", a.store.exists()); assertTrue("still ${a.screen}", a.screen === s)
        s.start.performClick(); idle(); clickButton(latestDialog(), "はじめる")
        until("explorer") { a.screen is ExplorerScreen }
        assertEquals(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK), a.store.info())
    }

    @Test fun setup_unencrypted_emptyKeyKdbx_saidPlainly_opensWithOneButton() {
        val s = welcome()
        s.rNone.performClick(); idle()
        s.start.performClick()
        until("explorer") { a.screen is ExplorerScreen }
        assertEquals(FileInfo.Protection.NONE, a.store.info().protection)
        val db = Kdbx.open(a.store.read(), Kdbx.compositeKey(""))   // KeePass: an empty master password
        assertTrue(Kdbx.describe(db), Kdbx.describe(db).contains("1MiB×1"))
        assertFalse(a.vault.hasStoredKey())
        Help.dbInfo(a); idle()
        assertTrue(texts(latestDialog().frame).joinToString().contains("保護: 暗号化なし"))
        latestDialog().dismiss()
        assertTrue(labels(a.explorer!!.mainMenu()).contains("マスターパスワードの設定"))
        val un = lockNow()
        assertTrue(vis(un.plain)); assertEquals("開く (暗号化なし)", un.plain.text.toString())
        assertFalse(vis(un.bio)); assertFalse(vis(un.pwButton)); assertFalse(vis(un.pwBox))
        assertFalse("launch export off by default", vis(un.export))
        un.plain.performClick()
        until("opened") { a.screen is ExplorerScreen }
    }

    @Test fun setup_keystore_randomKeyWrappedFirst_opensWithTheScreenLockOnly() {
        val kb = Vault.TestBackend(answer = ""); Vault.testBackend = kb
        var s = welcome()
        s.rKeystore.performClick(); idle(); s.start.performClick(); idle()
        assertTrue("cancelled prompt: nothing written", !a.store.exists() && s.error.text.contains("キャンセル"))
        kb.answer = null
        s = a.screen as SetupScreen
        s.rKeystore.performClick(); idle(); s.start.performClick()
        until("explorer") { a.screen is ExplorerScreen }
        assertEquals(FileInfo.Protection.KEYSTORE, a.store.info().protection)
        val k = kb.key!!
        assertEquals(32, k.size); assertFalse(k.contentEquals(Kdbx.compositeKey("")))
        assertTrue(runCatching { Kdbx.open(a.store.read(), k) }.isSuccess)
        val un = lockNow()
        assertTrue(vis(un.bio)); assertFalse(vis(un.pwButton)); assertFalse(vis(un.pwBox)); assertFalse(vis(un.plain))
        un.bio.performClick()
        until("opened") { a.screen is ExplorerScreen }
        // the screen lock went away: the key is gone, said plainly; wrapped.bin is left alone
        kb.key = null
        val un2 = lockNow()
        un2.bio.performClick(); idle()
        assertTrue(un2.error.text.toString(), un2.error.text.contains("もう開けません"))
        assertTrue(a.vault.hasStoredKey()); assertFalse(Session.isOpen)
    }

    @Test fun strength_zxcvbnStyle_threshold10to12() {
        assertEquals(12.0, io.github.zmlworks.kpexplorer.sec.PasswordStrength.STRONG_LOG10, 0.0)
        fun v(p: String) = io.github.zmlworks.kpexplorer.sec.PasswordStrength.verdict(p)
        for (p in listOf("password1", "Tr0ub4dor&3", "qwertyuiopasdfgh", "aaaaaaaaaaaaaaaaaaaa", "1234567890123456", "sakura2026", "yamada.taro1985", "keepassexplorer2026"))
            assertEquals(p, FileInfo.Strength.WEAK, v(p))
        for (p in listOf(STRONG_PW, "correcthorsebatterystaple", "x8Fq2LmZ9pRt", "7hJ2kL9mN4pQ"))
            assertEquals(p, FileInfo.Strength.STRONG, v(p))
        // long input is cut for the estimate, never judged stronger than its start
        assertEquals(FileInfo.Strength.WEAK, v("a".repeat(5000)))
    }

    @Test fun verdicts_typedUnlock_backupsInherit_rekeyUpdates_renameAndDeleteFollow() {
        assertEquals("before 0.4.37: unknown", FileInfo.LEGACY, a.store.info())
        val old = a.store.backup(java.time.LocalDate.of(2026, 11, 1))
        assertEquals(FileInfo.LEGACY, a.store.info(old))
        val un = lockNow()
        un.showPassword(null); un.pw.setText("sample"); un.openWithPassword()
        until("opened") { a.screen is ExplorerScreen }
        assertEquals(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK), a.store.info())
        assertEquals("an older backup keeps its record", FileInfo.LEGACY, a.store.info(old))
        val b = a.store.backup(java.time.LocalDate.of(2026, 11, 2))
        assertEquals("inherits", FileInfo.Strength.WEAK, a.store.info(b).strength)
        // a backup under another password is not rewritten and keeps its record
        val oth = File(a.store.backupDir, "other.kdbx").apply { writeBytes(Kdbx.encodeVerified(Kdbx.create("o", Kdbx.compositeKey("zzz"), light = true))) }
        a.store.setInfo(oth, info(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK))
        Security.changeMasterPassword(a); idle()
        val d = latestDialog()
        assertNotNull(findTag<View>(d.frame, io.github.zmlworks.kpexplorer.ui.StrengthMeter.TAG))
        val f = allViews(d.frame).filterIsInstance<android.widget.EditText>()
        f[0].setText(STRONG_PW); f[1].setText(STRONG_PW); idle()
        assertTrue(texts(d.frame).contains("強度: 強い"))
        Dlg.last = d; shot("v0437/10-master-password-change.png", dialog = true)
        clickButton(d, "変更")
        until("report") { Dlg.last?.let { texts(it.frame).any { t -> t.startsWith("マスターパスワードを変更しました。") } } == true }
        assertEquals(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.STRONG), a.store.info())
        assertEquals(FileInfo.Strength.STRONG, a.store.info(old).strength)
        assertEquals(FileInfo.Strength.STRONG, a.store.info(b).strength)
        assertEquals(FileInfo.Strength.WEAK, a.store.info(oth).strength)
        val renamed = a.store.renameBackup(b, "名前")
        assertEquals(FileInfo.Strength.STRONG, a.store.info(renamed).strength)
        a.store.deleteBackup(renamed)
        File(a.store.backupDir, "名前.kdbx").writeBytes(a.store.read())
        assertEquals("a new file of the same name starts unknown", FileInfo.LEGACY, a.store.info(File(a.store.backupDir, "名前.kdbx")))
    }

    @Test fun backup_openedWithItsTypedPassword_getsJudged() {
        val f = File(a.store.backupDir, "old-pass.kdbx").apply { parentFile!!.mkdirs(); writeBytes(Kdbx.encodeVerified(Kdbx.create("o", Kdbx.compositeKey(STRONG_PW), light = true))) }
        assertEquals(FileInfo.LEGACY, a.store.info(f))
        TempImport.browseBackupFile(a, f)
        until("password asked") { Dlg.last?.let { findTag<View>(it.frame, "temp-password") } != null }
        val d = latestDialog()
        (findTag<View>(d.frame, "temp-password") as android.widget.EditText).setText(STRONG_PW)
        clickButton(d, s(R.string.open))
        until("read-only view") { Session.readOnlyTemp }
        assertEquals(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.STRONG), a.store.info(f))
        TempImport.exitTemp(a); idle()
        // one that opens with the database's key gets the database's record
        a.store.setInfo(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK))
        val g = File(a.store.backupDir, "same.kdbx").apply { writeBytes(a.store.read()) }
        TempImport.browseBackupFile(a, g)
        until("read-only view") { Session.readOnlyTemp }
        assertEquals(FileInfo.Strength.WEAK, a.store.info(g).strength)
        TempImport.exitTemp(a); idle()
    }

    @Test fun menuSetting_launchExport_offByDefault_onAfterExplaining() {
        assertFalse(Security.launchExportEnabled(a))
        val item = a.explorer!!.mainMenu().first { it.label == "起動画面からの書き出し" }
        assertFalse(item.checked)
        val l = labels(a.explorer!!.mainMenu())
        assertEquals("right under the screenshot setting", l.indexOf(s(R.string.menu_secure_screen)) + 1, l.indexOf("起動画面からの書き出し"))
        assertFalse("hidden on the launch screen while off", vis(lockNow().export))
        a.lock(); idle()
        // unlock again
        Session.open(Kdbx.open(a.store.read(), key)); a.showExplorer(); idle()
        showMainMenu(); shot("v0437/06-menu-setting.png", menuAt = W_ - W.dp(240) to W.dp(60)); Menus.dismiss()
        Security.toggleLaunchExport(a); idle()
        val d = latestDialog()
        assertTrue(texts(d.frame).joinToString().contains("「強い」と判定されたファイルだけ"))
        Dlg.last = d; shot("v0437/07-menu-setting-confirm.png", dialog = true)
        clickButton(d, "キャンセル"); assertFalse(Security.launchExportEnabled(a))
        Security.toggleLaunchExport(a); idle(); clickButton(latestDialog(), "有効にする")
        assertTrue(Security.launchExportEnabled(a))
        assertTrue(a.explorer!!.mainMenu().first { it.label == "起動画面からの書き出し" }.checked)
        assertTrue(vis(lockNow().export))
        Session.open(Kdbx.open(a.store.read(), key)); a.showExplorer(); idle()
        Security.toggleLaunchExport(a); idle()
        assertFalse("off at once", Security.launchExportEnabled(a))
    }

    @Test fun launchExport_onlyStrongMasterPasswordFiles_othersGreyedWithTheReason() {
        App.prefs(app()).edit().putBoolean(Security.PREF_LAUNCH_EXPORT, true).commit()
        a.store.setInfo(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.STRONG))
        val strong = a.store.backup(java.time.LocalDate.of(2026, 11, 10))
        val weak = a.store.backup(java.time.LocalDate.of(2026, 11, 9)).also { a.store.setInfo(it, info(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK)) }
        val unknown = File(a.store.backupDir, "2026-11-08.kdbx").apply { writeBytes(a.store.read()) }
        val none = a.store.backup(java.time.LocalDate.of(2026, 11, 7)).also { a.store.setInfo(it, info(FileInfo.Protection.NONE, FileInfo.Strength.UNKNOWN)) }
        val ks = a.store.backup(java.time.LocalDate.of(2026, 11, 6)).also { a.store.setInfo(it, info(FileInfo.Protection.KEYSTORE, FileInfo.Strength.UNKNOWN)) }
        listOf(strong, weak, unknown, none, ks).forEachIndexed { i, f -> f.setLastModified(1_794_500_000_000L - i * 86_400_000L) }
        val un = lockNow()
        assertTrue(vis(un.export))
        un.export.performClick(); idle()
        val d = latestDialog()
        val items = Security.launchExportItems(a)
        assertEquals(listOf("現在のデータベース", strong.name, weak.name, unknown.name, none.name, ks.name), items.map { it.label })
        val radios = items.indices.map { findTag<View>(d.frame, "export-$it") as android.widget.RadioButton }
        assertEquals(listOf(true, true, false, false, false, false), radios.map { it.isEnabled })
        assertTrue("the database is picked first", radios[0].isChecked)
        fun note(i: Int) = (findTag<View>(d.frame, "export-note-$i") as android.widget.TextView).text.toString()
        assertTrue(note(2), note(2).contains("マスターパスワードが弱い"))
        assertTrue(note(3), note(3).contains("未判定"))
        assertTrue(note(4), note(4).contains("暗号化されていない"))
        assertTrue(note(5), note(5).contains("OSのkeystore"))
        Dlg.last = d; shot("v0437/08-launch-export-picker.png", dialog = true)
        // a greyed one cannot be picked
        radios[2].performClick(); idle(); assertTrue(radios[0].isChecked); assertFalse(radios[2].isChecked)
        radios[1].performClick(); idle(); assertTrue(radios[1].isChecked); assertFalse(radios[0].isChecked)
        val sink = java.io.ByteArrayOutputStream()
        val uri = android.net.Uri.parse("content://test.docs/document/b.kdbx")
        resolver.registerOutputStream(uri, sink)
        clickButton(d, "保存先を選ぶ")
        val i = answerPicker(uri)
        assertEquals("keepass-explorer-${strong.nameWithoutExtension}.kdbx", i.getStringExtra(android.content.Intent.EXTRA_TITLE))
        until("written") { sink.size() > 0 }
        assertArrayEquals("byte for byte", strong.readBytes(), sink.toByteArray())
        assertFalse(Session.isOpen)
        // nothing exportable: said, and the button is off
        a.store.setInfo(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.UNKNOWN)); a.store.setInfo(strong, info(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK))
        un.export.performClick(); idle()
        val d2 = latestDialog()
        assertNotNull(findTag<View>(d2.frame, "export-none"))
        assertFalse(d2.buttonViews.first().isEnabled)
    }

    @Test fun unlockedExport_withoutMasterPassword_needsAPasswordForTheCopy() {
        a.store.setInfo(info(FileInfo.Protection.KEYSTORE, FileInfo.Strength.UNKNOWN))
        val d = Security.askExportPassword(a, "x.kdbx") { Session.db }; idle()
        val rs = allViews(d.frame).filterIsInstance<android.widget.RadioButton>()
        assertFalse("no 'same password'", vis(rs.first { it.text.toString() == "今のマスターパスワードと同じ" }))
        assertTrue(rs.first { it.text.toString() == "別のパスワードを入力する" }.isChecked)
        assertNotNull(findTag<View>(d.frame, "export-needs-password"))
        assertNotNull(findTag<View>(d.frame, io.github.zmlworks.kpexplorer.ui.StrengthMeter.TAG))
        d.dismiss()
        a.store.setInfo(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK))
        val d2 = Security.askExportPassword(a, "x.kdbx") { Session.db }; idle()
        assertTrue(vis(allViews(d2.frame).filterIsInstance<android.widget.RadioButton>().first { it.text.toString() == "今のマスターパスワードと同じ" }))
    }

    @Test fun setMasterPassword_fromUnencrypted_switchesToMasterPassword_backupsToo() {
        val s = welcome()
        s.rNone.performClick(); idle(); s.start.performClick()
        until("explorer") { a.screen is ExplorerScreen }
        val b = a.store.backup(java.time.LocalDate.of(2026, 11, 3))
        assertEquals(FileInfo.Protection.NONE, a.store.info(b).protection)
        Security.changeMasterPassword(a); idle()
        val d = latestDialog()
        val t = texts(d.frame)
        assertEquals("マスターパスワードの設定", t.first())
        assertTrue(t.joinToString(), t.joinToString().contains("今は暗号化されていません"))
        assertNull("backups always follow", findTag<View>(d.frame, "keep-backups")?.takeIf { it.parent != null })
        val f = allViews(d.frame).filterIsInstance<android.widget.EditText>()
        f[0].setText(STRONG_PW); f[1].setText(STRONG_PW)
        clickButton(d, "設定")
        until("report") { Dlg.last?.let { texts(it.frame).any { x -> x.startsWith("マスターパスワードを変更しました。") } } == true }
        assertEquals(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.STRONG), a.store.info())
        assertEquals(info(FileInfo.Protection.PASSWORD, FileInfo.Strength.STRONG), a.store.info(b))
        assertTrue(runCatching { Kdbx.open(a.store.read(), Kdbx.compositeKey(STRONG_PW)) }.isSuccess)
        assertTrue(runCatching { Kdbx.open(b.readBytes(), Kdbx.compositeKey(STRONG_PW)) }.isSuccess)
        assertTrue(runCatching { Kdbx.open(a.store.read(), Kdbx.compositeKey("")) }.isFailure)
        assertTrue(Kdbx.describe(Kdbx.open(a.store.read(), Kdbx.compositeKey(STRONG_PW))).contains("32MiB×3"))
    }
}
