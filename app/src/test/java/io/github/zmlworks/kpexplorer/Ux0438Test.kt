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

/** 0.4.38: menu order, セキュリティの設定 (export strength drop-down), the app's own strength estimate, green exportable backups, import rewrites backups on request, even launch buttons, centred Save window, 最近更新した鍵 under "/". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0438Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0438").mkdirs() }
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
        // 0.4.37: the launch screen's 外部に書き出す needs the setting (0.4.38: セキュリティの設定 強い), and the file a strong master password
        Security.setExportMinLog10(app(), 12)
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

    // ------------------------------------------------------------------------------------------------ 0.4.38

    private val STRONG_PW = "vGy7-Kq2p-9Zx4-Rt8w"
    private val PS = io.github.zmlworks.kpexplorer.sec.PasswordStrength
    private fun lockNow(): UnlockScreen { a.lock(); idle(); layoutHost(); assertTrue(a.screen is UnlockScreen); return a.screen as UnlockScreen }
    private fun vis(v: View) = v.visibility == View.VISIBLE
    private fun pw(p: FileInfo.Strength, n: Int?) = FileInfo(FileInfo.Protection.PASSWORD, p, n)
    /** Top-left of [v] inside [root] (scrolling taken off). */
    private fun offsetIn(v: View, root: View): Pair<Int, Int> {
        var x = 0; var y = 0; var c: View? = v
        while (c != null && c !== root) { x += c.left - c.scrollX; y += c.top - c.scrollY; c = c.parent as? View }
        return x to y
    }
    /** The dialog as [shot] draws it, with the drop-down menu open under [anchor]. */
    private fun shotDialogWithMenu(name: String, anchor: View) {
        layoutHost()
        val b = Bitmap.createBitmap(W_, H_, Bitmap.Config.ARGB_8888); val c = Canvas(b); a.host.draw(c)
        val f = Dlg.last!!.frame
        f.measure(View.MeasureSpec.makeMeasureSpec(W_ - 72, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_ - 300, View.MeasureSpec.AT_MOST))
        f.layout(0, 0, f.measuredWidth, f.measuredHeight)
        val top = (H_ - f.measuredHeight) / 2
        c.save(); c.translate(36f, top.toFloat()); f.draw(c); c.restore()
        val (ax, ay) = offsetIn(anchor, f)
        Menus.lastView?.let { v ->
            v.measure(View.MeasureSpec.makeMeasureSpec(maxOf(anchor.width, W.dp(240)), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_, View.MeasureSpec.AT_MOST))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            c.save(); c.translate(36f + ax, (top + ay + anchor.height).toFloat()); v.draw(c); c.restore()
        }
        FileOutputStream(File(out, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun pickMenuRow(label: String) {
        val row = allViews(Menus.lastView!!).filterIsInstance<android.widget.TextView>().first { it.text.toString() == label }.parent as View
        row.performClick(); idle()
    }
    private fun t(d: io.github.zmlworks.kpexplorer.ui.ClassicDialog, tag: String) = (findTag<View>(d.frame, tag) as android.widget.TextView).text.toString()

    // ---------------------------------------------------------------- item 2: the app's own estimate, no dictionary

    @Test fun strength_repeatsSequencesKeyboardRows_countAsOneCharacter_weak() {
        assertEquals(1, PS.judge("aaaa").effectiveLength)
        assertEquals(Math.log10(26.0), PS.judge("aaaa").log10, 1e-9)
        for (p in listOf("aaaa", "aaaaaaaaaaaaaaaaaaaaaaaa", "abcdefghijklmnop", "1234567890", "9876543210", "zyxwvu", "qwertyuiop", "QWERTY", "poiuytrewq",
                "asdfghjkl", "zxcvbnm", "1111222233334444", "abcd1234", "aaaa1234", "４５６７８９"))
            assertEquals(p, FileInfo.Strength.WEAK, PS.verdict(p))
        assertEquals("each run of three or more is one", 2, PS.judge("abcd1234").effectiveLength)
        assertEquals("shorter stretches count per character", 3, PS.judge("aab").effectiveLength)
        assertEquals("qwerty + asdf + 1234 + !", 4, PS.judge("qwertyasdf1234!").effectiveLength)
        // no dictionary: a word is only its characters
        assertEquals(8, PS.judge("password").effectiveLength)
        assertEquals(FileInfo.Strength.WEAK, PS.verdict("password"))   // 26^8 ≈ 10^11.3
        assertEquals(FileInfo.Strength.STRONG, PS.verdict("passwordpassword"))   // 26^16 by the rule
    }

    @Test fun strength_poolOfTheClassesUsed_toTheEffectiveLength() {
        assertEquals(26, PS.pool("abc".codePoints().toArray()))
        assertEquals(52, PS.pool("aB".codePoints().toArray()))
        assertEquals(62, PS.pool("aB3".codePoints().toArray()))
        assertEquals(95, PS.pool("aB3 ".codePoints().toArray()))
        assertEquals(95, PS.pool("aB3!".codePoints().toArray()))
        assertEquals(100, PS.pool("山".codePoints().toArray()))
        assertEquals(126, PS.pool("山a".codePoints().toArray()))
        // Password-like: 10 characters from all four ASCII classes, no pattern
        val r = PS.judge("Password1!")
        assertEquals(95, r.pool); assertEquals(10, r.effectiveLength)
        assertEquals(10 * Math.log10(95.0), r.log10, 1e-9)
        assertTrue(r.strong); assertTrue(r.veryStrong); assertEquals(19, r.bucket)
        assertEquals("とても強い", r.label)
        assertEquals(PS.info("Password1!"), pw(FileInfo.Strength.STRONG, 19))
        assertEquals(0.0, PS.judge("").log10, 0.0)
    }

    @Test fun strength_thresholdBoundaries_10to12_and_10to14() {
        assertEquals(12.0, PS.STRONG_LOG10, 0.0); assertEquals(14.0, PS.VERY_STRONG_LOG10, 0.0)
        // twelve digits without a pattern: exactly 10^12 → strong; eleven → not
        assertEquals(12.0, PS.judge("135792468024").log10, 1e-9)
        assertEquals(FileInfo.Strength.STRONG, PS.verdict("135792468024"))
        assertEquals(FileInfo.Strength.WEAK, PS.verdict("13579246802"))
        assertEquals("強い", PS.judge("135792468024").label); assertEquals(12, PS.judge("135792468024").bucket)
        assertEquals("もう少し", PS.judge("13579246802").label)
        // 14 digits: very strong; 13: strong only
        assertTrue(PS.judge("13579246802468").veryStrong)
        assertFalse(PS.judge("1357924680246").veryStrong); assertTrue(PS.judge("1357924680246").strong)
        // outside ASCII: 100 per character → six = 10^12
        assertEquals(12.0, PS.judge("山川海空星月").log10, 1e-9)
        assertEquals(FileInfo.Strength.STRONG, PS.verdict("山川海空星月"))
        assertEquals(FileInfo.Strength.WEAK, PS.verdict("山川海空星"))
        // the record and what it allows
        val strong12 = PS.info("135792468024")
        assertTrue(strong12.exportableAt(12)); assertFalse(strong12.exportableAt(14)); assertFalse("無効", strong12.exportableAt(0))
        assertTrue(PS.info("13579246802468").exportableAt(14))
        assertFalse(PS.info("13579246802").exportableAt(12))
        // kept with the file, read back; a 0.4.37 record "strong" counts as 10^12
        assertEquals(strong12, FileInfo.decode(strong12.encode()))
        assertEquals("password,strong,12", strong12.encode())
        val old = FileInfo.decode("password,strong")!!
        assertEquals(12, old.guessesLog10); assertTrue(old.exportableAt(12)); assertFalse(old.exportableAt(14))
        assertFalse("not encrypted: never", FileInfo(FileInfo.Protection.NONE, FileInfo.Strength.STRONG, 30).exportableAt(12))
        assertFalse("keystore: never", FileInfo(FileInfo.Protection.KEYSTORE, FileInfo.Strength.UNKNOWN).exportableAt(12))
    }

    @Test fun strength_noZxcvbnLeft_inTheAppOrItsNotices() {
        assertTrue(runCatching { Class.forName("com.nulabinc.zxcvbn.Zxcvbn") }.isFailure)
        val notices = app().assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText()
        assertFalse(notices.contains("zxcvbn", ignoreCase = true))
    }

    // ---------------------------------------------------------------- item 1: the menu

    @Test fun mainMenu_exactlyTheUsersOrder() {
        assertEquals(listOf("ロック", "|", "Languageと日付形式", "セキュリティの設定", "マスターパスワードの設定", "外部データベースからインポートする", "|",
            "ごみ箱", "最近更新されたファイル", "最近利用したファイル", "よく利用するファイル", "機能フォルダの設定", "|", "乱数生成器", "データベースのプロパティ", "About"),
            labels(a.explorer!!.mainMenu()))   // (0.4.40: the 機能フォルダ group; 0.4.38: ごみ箱の設定, 最近更新された鍵の設定)
        showMainMenu(); shot("v0438/01-menu.png", menuAt = (W_ - (Menus.current?.width ?: W.dp(300)) - W.dp(4)) to W.dp(60)); Menus.dismiss()   // where Menus.at puts it (kept on screen)
        item(a.explorer!!.mainMenu(), "マスターパスワードの設定").action(); idle()
        assertEquals("マスターパスワードの変更", texts(latestDialog().frame).first()); latestDialog().dismiss()
        item(a.explorer!!.mainMenu(), "外部データベースからインポートする").action(); idle()
        val imp = latestDialog()
        assertNotNull("外部ファイルを閲覧 lives here now", findTag<View>(imp.frame, "import-browse"))
        imp.dismiss()
        item(a.explorer!!.mainMenu(), "乱数生成器").action(); idle()
        assertTrue(texts(latestDialog().frame).contains("ランダム文字列生成")); latestDialog().dismiss()
        item(a.explorer!!.mainMenu(), "セキュリティの設定").action(); idle()
        assertEquals("セキュリティの設定", texts(latestDialog().frame).first()); latestDialog().dismiss()
        item(a.explorer!!.mainMenu(), "機能フォルダの設定").action(); idle()   // 0.4.40: the bin and the virtual folders
        assertEquals("機能フォルダの設定", texts(latestDialog().frame).first()); latestDialog().dismiss()
    }

    // ---------------------------------------------------------------- item 9: セキュリティの設定

    @Test fun securitySettings_strengthCautionAndExportLevel_dropDown() {
        a.store.setInfo(pw(FileInfo.Strength.STRONG, 13))
        val d = SecuritySettings.show(a); idle()
        assertEquals("保護: マスターパスワード", t(d, "sec-mode"))
        assertEquals("強度: 強い (推測に約 10^13 回)", t(d, "sec-strength"))
        assertTrue(t(d, "sec-caution").contains("忘れると"))
        val tx = texts(d.frame)
        for (h in listOf("現在のデータベース", "認証なしで外部に書き出す際の強度", "画面とロック", "内部バックアップ")) assertTrue("$h in $tx", tx.contains(h))
        val combo = findTag<View>(d.frame, "sec-export-level")!!
        assertEquals("default", 0, Security.exportMinLog10(a))
        assertTrue(texts(combo).contains("無効"))
        assertTrue(t(d, "sec-export-note").contains("使えません"))
        Dlg.last = d; shot("v0438/02-security-settings.png", dialog = true)
        combo.performClick(); idle()
        val opts = allViews(Menus.lastView!!).filterIsInstance<android.widget.TextView>().map { it.text.toString() }.filter { it.isNotEmpty() && it != "✓" }
        assertEquals(listOf("無効", "とても強い (10^14以上)", "強い (10^12以上)", "もう少し (10^10以上)", "やや弱い (10^8以上)", "弱い (10^5以上)",
            "すべて (強度を問わない)", "数値で指定 (10^n)"), opts)   // (0.4.40: more levels; 0.4.38 had the first three)
        shotDialogWithMenu("v0438/03-security-settings-dropdown.png", combo)
        pickMenuRow("強い (10^12以上)")
        assertEquals(12, Security.exportMinLog10(a))
        assertTrue(texts(findTag<View>(d.frame, "sec-export-level")!!).contains("強い (10^12以上)"))
        assertTrue(t(d, "sec-export-note").contains("緑の文字"))
        findTag<View>(d.frame, "sec-export-level")!!.performClick(); idle(); pickMenuRow("とても強い (10^14以上)")
        assertEquals(14, Security.exportMinLog10(a))
        // screen protection and auto-lock (out of the menu)
        val sec = findTag<View>(d.frame, "sec-secure") as android.widget.CheckBox
        val was = sec.isChecked
        sec.performClick(); idle(); assertEquals(!was, App.prefs(app()).getBoolean("secure", true))
        findTag<View>(d.frame, "sec-autolock")!!.performClick(); idle()
        radio(2).performClick(); idle()
        assertEquals(-1, a.autoLockMinutes())
        assertEquals(s(R.string.menu_autolock_never), t(d, "sec-autolock-text"))
        d.dismiss()
        // the other protections, in words
        a.store.setInfo(FileInfo(FileInfo.Protection.KEYSTORE, FileInfo.Strength.UNKNOWN))
        val k = SecuritySettings.show(a); idle()
        assertEquals("保護: OSのkeystoreの鍵", t(k, "sec-mode"))
        assertTrue(t(k, "sec-caution"), t(k, "sec-caution").contains("画面ロックを解除・無効にする"))
        Dlg.last = k; shot("v0438/04-security-settings-keystore.png", dialog = true); k.dismiss()
        a.store.setInfo(FileInfo(FileInfo.Protection.NONE, FileInfo.Strength.UNKNOWN))
        val n = SecuritySettings.show(a); idle()
        assertEquals("保護: 暗号化なし", t(n, "sec-mode"))
        assertTrue(t(n, "sec-caution").startsWith("暗号化されていません"))
        n.dismiss()
        a.store.setInfo(pw(FileInfo.Strength.WEAK, 8))
        val w = SecuritySettings.show(a); idle()
        assertEquals("強度: やや弱い (推測に約 10^8 回)", t(w, "sec-strength")); w.dismiss()
        // the backup list opens from here
        val b = SecuritySettings.show(a); idle()
        findTag<View>(b.frame, "sec-backups")!!.performClick(); idle()
        assertTrue(a.screen is BackupScreen)
    }

    @Test fun exportLevel_migratesThe0437Switch() {
        App.prefs(app()).edit().putBoolean(Security.PREF_LAUNCH_EXPORT_OLD, true).commit()
        assertEquals("on → 強い", 12, Security.exportMinLog10(a))
        assertFalse(App.prefs(app()).contains(Security.PREF_LAUNCH_EXPORT_OLD))
        App.prefs(app()).edit().clear().putBoolean(Security.PREF_LAUNCH_EXPORT_OLD, false).commit()
        assertEquals("off → 無効", 0, Security.exportMinLog10(a))
        App.prefs(app()).edit().clear().commit()
        assertEquals("default 無効", 0, Security.exportMinLog10(a))
    }

    @Test fun launchExport_followsTheLevel() {
        a.store.setInfo(pw(FileInfo.Strength.STRONG, 13))
        val b14 = a.store.backup(java.time.LocalDate.of(2026, 11, 10)).also { a.store.setInfo(it, pw(FileInfo.Strength.STRONG, 16)) }
        Security.setExportMinLog10(a, 14)
        val items = Security.launchExportItems(a)
        assertEquals(listOf(false, true), items.map { it.exportable })
        assertTrue(Security.notExportableReason(items[0].info, 14)!!.contains("とても強い (10^14以上)"))
        val un = lockNow()
        assertTrue(un.usable(un.export))
        un.export.performClick(); idle()
        val d = latestDialog()
        assertFalse((findTag<View>(d.frame, "export-0") as android.widget.RadioButton).isEnabled)
        assertTrue((findTag<View>(d.frame, "export-1") as android.widget.RadioButton).isChecked)
        d.dismiss()
        assertEquals(b14.name, items[1].label)
        Security.setExportMinLog10(a, 0)
        assertFalse(Security.launchExportItems(a).any { it.exportable })
    }

    // ---------------------------------------------------------------- item 3: green rows in the backup list

    @Test fun backupList_exportableRowsGreen_everyCreationRecordsTheStrength() {
        a.store.setInfo(PS.info("sample"))
        val weak = a.store.backup(java.time.LocalDate.of(2026, 11, 1))
        assertEquals("backup inherits", PS.info("sample"), a.store.info(weak))
        a.store.setInfo(PS.info(STRONG_PW))
        val strong = a.store.backup(java.time.LocalDate.of(2026, 11, 2))
        val stamped = a.store.backupTimestamped(java.time.LocalDateTime.of(2026, 11, 3, 10, 0))
        assertEquals(PS.info(STRONG_PW), a.store.info(strong)); assertEquals(PS.info(STRONG_PW), a.store.info(stamped))
        val unknown = File(a.store.backupDir, "copied.kdbx").apply { writeBytes(a.store.read()) }
        listOf(weak, strong, stamped, unknown).forEachIndexed { i, f -> f.setLastModified(1_794_500_000_000L - i * 86_400_000L) }
        Security.setExportMinLog10(a, 12)
        val bs = BackupScreen(a); a.show(bs); idle(); layoutHost()
        val list = find(bs.view, ListView::class.java)!!
        fun color(name: String): Int {
            val rowView = (0 until list.childCount).map { list.getChildAt(it) }.first { r -> texts(r).contains(name) }
            return allViews(rowView).filterIsInstance<android.widget.TextView>().first { it.text.toString() == name }.currentTextColor
        }
        assertEquals(BackupScreen.EXPORTABLE, color(strong.name)); assertEquals(BackupScreen.EXPORTABLE, color(stamped.name))
        assertEquals(W.TEXT, color(weak.name)); assertEquals(W.TEXT, color(unknown.name))
        shot("v0438/05-backup-list-green.png")
        // 無効: nothing green
        Security.setExportMinLog10(a, 0)
        val bs2 = BackupScreen(a); a.show(bs2); idle(); layoutHost()
        val l2 = find(bs2.view, ListView::class.java)!!
        assertTrue((0 until l2.childCount).flatMap { allViews(l2.getChildAt(it)).filterIsInstance<android.widget.TextView>() }.none { it.currentTextColor == BackupScreen.EXPORTABLE })
    }

    // ---------------------------------------------------------------- item 4: import asks about the backups

    private fun importExternal(password: String) {
        val (uri, _) = externalFile()
        TempImport.showImportMenu(a); idle()
        clickButton(latestDialog(), s(R.string.import_from_external))
        answerPicker(uri)
        clickButton(latestDialog(), s(R.string.yes))
        until("password asked") { Dlg.last?.let { d -> allViews(d.frame).any { it is android.widget.EditText } } == true }
        val d = Dlg.last!!
        allViews(d.frame).filterIsInstance<android.widget.EditText>().first().setText(password)
        d.buttonViews.first().performClick(); idle()
        until("imported") { Dlg.last?.let { texts(it.frame).any { t -> t.startsWith(s(R.string.import_ok)) } } == true }
    }

    @Test fun import_typedPassword_asksToRewriteTheBackups_rewritesThoseItCan() {
        val b1 = a.store.backup(java.time.LocalDate.of(2026, 11, 1))
        val oth = File(a.store.backupDir, "other.kdbx").apply { writeBytes(Kdbx.encodeVerified(Kdbx.create("o", Kdbx.compositeKey("zzz"), light = true))) }
        val othBytes = oth.readBytes()
        importExternal("other-pass")
        clickButton(Dlg.last!!, "OK")
        val q = latestDialog()
        assertTrue(texts(q.frame).joinToString(), texts(q.frame).joinToString().contains("内部バックアップも、取り込んだデータベースのパスワードに書き換えますか?"))
        assertEquals(listOf("書き換える", "書き換えない"), q.buttonViews.map { it.text.toString() })
        Dlg.last = q; shot("v0438/06-import-rewrite-question.png", dialog = true)
        clickButton(q, "書き換える")
        until("report") { Dlg.last?.let { texts(it.frame).any { t -> t.startsWith("内部バックアップ:") } } == true }
        val msg = texts(Dlg.last!!.frame).first { it.startsWith("内部バックアップ:") }
        assertTrue(msg, msg.startsWith("内部バックアップ: 2 件を新しいパスワードに書き換えました。"))   // b1 + the backup made before the import
        assertTrue(msg, msg.contains("1 件は現在のパスワードで開けない"))
        assertTrue(runCatching { Kdbx.open(b1.readBytes(), otherKey) }.isSuccess)
        assertEquals("the record follows", PS.info("other-pass"), a.store.info(b1))
        assertArrayEquals(othBytes, oth.readBytes())
        assertEquals(PS.info("other-pass"), a.store.info())
    }

    @Test fun import_typedPassword_keepTheBackups_leavesThemAlone() {
        val b1 = a.store.backup(java.time.LocalDate.of(2026, 11, 1))
        importExternal("other-pass")
        clickButton(Dlg.last!!, "OK")
        clickButton(latestDialog(), "書き換えない")
        Session.flush(); idle()
        assertTrue(runCatching { Kdbx.open(b1.readBytes(), key) }.isSuccess)
        assertTrue(runCatching { Kdbx.open(a.store.read(), otherKey) }.isSuccess)
    }

    // ---------------------------------------------------------------- item 5: the launch screen

    @Test fun launchScreen_sameFiveButtons_closer_unusableGrey() {
        val un = lockWithStoredKey()
        val buttons = listOf(un.bio, un.pwButton, un.browse, un.export, un.exit)
        assertTrue(buttons.all { vis(it) })
        assertEquals(listOf("OS認証で解除", "マスターパスワード", "外部ファイルを閲覧", "外部に書き出す", "終了"), buttons.map { it.text.toString() })
        assertEquals(12, UnlockScreen.GAP_DP)
        assertEquals(W.dp(12), un.pwButton.top - un.bio.bottom)
        assertEquals(W.dp(12), un.export.top - un.browse.bottom)
        assertEquals(W.dp(12), un.exit.top - un.export.bottom)
        assertTrue(buttons.all { un.usable(it) })
        shot("v0438/07-launch-screen.png")
        // 無効: the button stays where it is, grey; a tap says why
        Security.setExportMinLog10(a, 0)
        Session.open(Kdbx.open(a.store.read(), key)); a.showExplorer(); idle()
        val un2 = lockNow()
        assertTrue(vis(un2.export)); assertFalse(un2.usable(un2.export))
        assertEquals(UnlockScreen.GREY, un2.export.currentTextColor)
        un2.export.performClick(); idle()
        assertTrue(un2.error.text.toString(), un2.error.text.contains("認証なしの書き出しは無効"))
        assertNull("no picker", org.robolectric.Shadows.shadowOf(a).nextStartedActivityForResult)
        shot("v0438/08-launch-screen-export-off.png")
        // keystore: no master password → that button grey, same layout
        a.store.setInfo(FileInfo(FileInfo.Protection.KEYSTORE, FileInfo.Strength.UNKNOWN))
        Session.open(Kdbx.open(a.store.read(), key)); a.showExplorer(); idle()
        val k = lockNow()
        assertTrue(listOf(k.bio, k.pwButton, k.browse, k.export, k.exit).all { vis(it) })
        assertFalse(k.usable(k.pwButton)); assertTrue(k.usable(k.bio))
        k.pwButton.performClick(); idle()
        assertTrue(k.error.text.contains("マスターパスワードはありません")); assertFalse(vis(k.pwBox))
        assertEquals(un.pwButton.top, k.pwButton.top)
    }

    // ---------------------------------------------------------------- item 6: the Save window

    @Test fun saveWindow_buttonsCentred() {
        val d = Security.saveDialog(a); idle()
        val f = d.frame
        f.measure(View.MeasureSpec.makeMeasureSpec(W_ - 72, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_ - 300, View.MeasureSpec.AT_MOST))
        f.layout(0, 0, f.measuredWidth, f.measuredHeight)
        for (tag in listOf("save-internal", "save-export")) {
            val b = findTag<View>(f, tag)!!
            val p = b.parent as View
            assertEquals(tag, p.width / 2.0, b.left + b.width / 2.0, 2.0)
        }
        Dlg.last = d; shot("v0438/09-save-window.png", dialog = true)
    }

    // ---------------------------------------------------------------- item 8: master password screen

    @Test fun masterPassword_rewriteBackups_tickedByDefault() {
        Security.changeMasterPassword(a); idle()
        val d = latestDialog()
        val rw = findTag<View>(d.frame, "rewrite-backups") as android.widget.CheckBox
        assertEquals("内部バックアップのパスワードを書き換える", rw.text.toString())
        assertTrue(rw.isChecked)
        assertFalse(texts(d.frame).any { it.contains("書き換えない") })
        Dlg.last = d; shot("v0438/10-master-password.png", dialog = true)
        rw.performClick(); idle()
        assertTrue("warning first", rw.isChecked)
        assertTrue(texts(latestDialog().frame).contains("バックアップを開くのに古いパスワードの入力が必要になります"))
    }

    // ---------------------------------------------------------------- item 7: 最近更新した鍵

    @Test fun recentKeys_renamed_underTheRoot_hideAndCountFromItsSettings() {
        val ex = a.explorer!!
        ex.refresh(); idle(); layoutHost()
        val top = tree().topRow!!
        assertEquals("最近更新されたファイル", top.contentDescription)   // (0.4.40 name; 0.4.38: 最近更新した鍵)
        assertEquals("the root's first child", 1, tree().indexOfChild(top))
        assertEquals("/", names(listOf(tree().rows[0].tag as UUID)).first())
        shot("v0438/11-recent-under-root.png")
        // (0.4.40: the count in its long-press menu, showing in 機能フォルダの設定, opening from the main menu)
        val k = FeatureFolders.Kind.CHANGED
        FeatureFolders.pickCount(a, k); idle()
        radio(0).performClick(); idle()
        assertEquals(10, FeatureFolders.count(a, k))
        assertEquals("表示する数: 10 件", ex.virtualTreeMenu(k)[2].label)
        val d = FeatureFolders.show(a); idle()
        assertEquals(listOf("閉じる"), d.buttonViews.map { it.text.toString() })
        (findTag<View>(d.frame, "changed-show") as android.widget.CheckBox).performClick(); idle(); layoutHost()
        assertFalse(FeatureFolders.shown(a, k))
        assertNotEquals("hidden", "最近更新されたファイル", tree().topRow?.contentDescription)
        d.dismiss()
        // opened from the menu while hidden; shown in the tree only while open
        labels(ex.mainMenu()).let { assertTrue(it.toString(), it.contains("最近更新されたファイル")) }
        ex.mainMenu().first { it.label == "最近更新されたファイル" }.action(); idle(); layoutHost()
        assertTrue(ex.recent); assertEquals(10, ex.rows.size); assertEquals("最近更新されたファイル", tree().topRow?.contentDescription)
        ex.goUp(); idle(); layoutHost()
        assertFalse(ex.recent); assertNotEquals("最近更新されたファイル", tree().topRow?.contentDescription)
        assertFalse(FeatureFolders.shown(a, k))
    }
}
