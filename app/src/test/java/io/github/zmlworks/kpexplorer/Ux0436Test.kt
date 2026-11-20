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

/** 0.4.36: narrower Save buttons with a confirmation, one Language / date window, striped backups, even launch buttons, no create items in the tree menu, teal key-page names, 最近の鍵. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0436Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0436").mkdirs() }
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

    @Test fun savePopup_narrowButtons_saveInAppAsksFirst() {
        val name = a.store.nextBackupName()
        tool("保存").performClick(); idle()
        val d = latestDialog()
        val bi = findTag<View>(d.frame, "save-internal")!!; val be = findTag<View>(d.frame, "save-export")!!
        Dlg.last = d; shot("v0436/01-save-popup.png", dialog = true)
        d.frame.measure(View.MeasureSpec.makeMeasureSpec(minOf(W_ - W.dp(24), W.dp(460)), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_, View.MeasureSpec.AT_MOST))
        d.frame.layout(0, 0, d.frame.measuredWidth, d.frame.measuredHeight)
        assertEquals(W.dp(Security.SAVE_BUTTON_DP), bi.width); assertEquals(bi.width, be.width)
        assertTrue("narrower than the window", bi.width < d.frame.width * 0.6)
        bi.performClick(); idle()
        val c = latestDialog()
        assertTrue(texts(c.frame).joinToString().contains("現在のデータベースを $name として内部にバックアップしますか?"))
        assertTrue(a.store.backups().isEmpty())
        clickButton(c, "キャンセル")
        assertTrue("cancel makes nothing", a.store.backups().isEmpty())
        tool("保存").performClick(); idle()
        findTag<View>(latestDialog().frame, "save-internal")!!.performClick(); idle()
        clickButton(latestDialog(), "作成")
        until("backed up") { a.store.backups().size == 1 }
        assertEquals(name, a.store.backups()[0].name)
    }

    @Test fun languageAndDate_oneItemBelowLock_oneWindowForBoth() {
        val l = labels(a.explorer!!.mainMenu())
        assertEquals("Languageと日付形式", l[l.indexOf(s(R.string.menu_lock)) + 2])   // 0.4.38: after a line
        assertTrue(l.toString(), l.none { it.startsWith("Language: ") || it.startsWith("日付: ") })
        item(a.explorer!!.mainMenu(), "Languageと日付形式").action(); idle()
        val d = latestDialog()
        val t = texts(d.frame)
        assertTrue(t.toString(), t.contains("Language") && t.contains("日付の形式") && t.contains("端末に合わせる") && t.contains("English"))
        assertTrue((findTag<View>(d.frame, "lang-0") as android.widget.RadioButton).isChecked)
        assertTrue((findTag<View>(d.frame, "date-0") as android.widget.RadioButton).isChecked)
        Dlg.last = d; shot("v0436/02-language-date.png", dialog = true)
        // the date only: applied, the language left as it is
        (findTag<View>(d.frame, "date-2") as android.widget.RadioButton).performClick()
        clickButton(d, "OK")
        assertEquals(L.DateOrder.DMY, L.dateOrder); assertEquals(L.Lang.SYSTEM, L.lang)
        // cancel changes nothing
        item(a.explorer!!.mainMenu(), "Languageと日付形式").action(); idle()
        (findTag<View>(latestDialog().frame, "date-1") as android.widget.RadioButton).performClick()
        clickButton(latestDialog(), "キャンセル")
        assertEquals(L.DateOrder.DMY, L.dateOrder)
        // both at once
        item(a.explorer!!.mainMenu(), "Languageと日付形式").action(); idle()
        (findTag<View>(latestDialog().frame, "date-1") as android.widget.RadioButton).performClick()
        (findTag<View>(latestDialog().frame, "lang-2") as android.widget.RadioButton).performClick()
        clickButton(latestDialog(), "OK")
        assertEquals(L.DateOrder.MDY, L.dateOrder); assertEquals(L.Lang.EN, L.lang)
        assertEquals("Language and date format", L.t("Languageと日付の形式", "Language and date format"))
    }

    @Test fun backupList_whiteAndPaleBlueStripes() {
        for (day in 10..14) a.store.backup(java.time.LocalDate.of(2026, 11, day))
        a.show(BackupScreen(a)); idle(); layoutHost()
        val list = find(a.host, ListView::class.java)!!
        assertTrue(list.childCount >= 5)
        for (i in 0 until list.childCount) assertEquals("row $i", if (i % 2 == 0) W.WINDOW else io.github.zmlworks.kpexplorer.ui.STRIPE,
            (list.getChildAt(i).background as android.graphics.drawable.ColorDrawable).color)
        shot("v0436/03-backup-list.png")
    }

    @Test fun launch_fiveEvenButtons_taller_lightLabels() {
        val un = lockWithStoredKey()
        val bs = listOf(un.bio, un.pwButton, un.browse, un.export, un.exit)
        val rs = bs.map { screenRect(it) }
        for (r in rs) assertEquals(W.dp(UnlockScreen.BUTTON_DP), r.height())
        assertTrue(W.dp(UnlockScreen.BUTTON_DP) > W.dp(52))
        val gaps = (1 until rs.size).map { rs[it].top - rs[it - 1].bottom }
        assertEquals("even: $gaps", 1, gaps.toSet().size)
        for (b in bs) { assertEquals(UnlockScreen.LABEL_WEIGHT, b.typeface.weight); assertFalse(b.typeface.isBold); assertFalse(b.paint.isFakeBoldText) }
        assertEquals("stack in the middle", H_ / 2.0, ((rs[0].top + rs[4].bottom) / 2).toDouble(), H_ * 0.08)
        shot("v0436/04-launch.png")
    }

    @Test fun treeMenu_noCreateItems() {
        val ex = a.explorer!!
        val creates = listOf(s(R.string.tool_new_folder), s(R.string.tool_new_key), s(R.string.tool_new_memo))
        for (g in listOf(ids["G"]!!, Tree.root(Session.db!!).uuid)) {
            val l = labels(ex.treeMenu(g))
            assertTrue(l.toString(), l.none { it in creates })
            assertTrue(l.contains(s(R.string.open)))
        }
        val l = labels(ex.treeMenu(ids["G"]!!))
        assertEquals(listOf(s(R.string.open), "下部ファイルをすべて表示", "|", s(R.string.rename), s(R.string.cut), s(R.string.paste_here),
            s(R.string.move_to_folder), s(R.string.move_up_folder), "|", s(R.string.delete)), l)
        layoutHost()
        assertTrue(tree().longPress(ids["G"]!!)); idle()
        val r = screenRect(rowOf(ids["G"]!!))
        shot("v0436/05-tree-menu.png", menuAt = r.left + W.dp(120) to r.bottom)
        Menus.dismiss()
    }

    @Test fun keyPage_namesAndGlyphsTeal_tagGlyphBlue() {
        val ks = openKey(ids["Gmail"]!!)
        val tvs = allViews(ks.view).filterIsInstance<android.widget.TextView>()
        for (n in listOf("Title", "User", "Pass", "URL", "Tag")) {
            val tv = tvs.first { it.text.toString() == n }
            assertEquals(n, W.KEY_LABEL, tv.currentTextColor)
        }
        // 0.4.37: exactly the launch screen's teal (the user's choice; 0.4.36 deepened it for 4.5 : 1 contrast)
        assertTrue("still legible on the page grey", contrast(W.KEY_LABEL, io.github.zmlworks.kpexplorer.ui.FieldBox.PLAIN_FILL) >= 2.5)
        val t = io.github.zmlworks.kpexplorer.ui.DotLogo.TEAL
        assertEquals(0, android.graphics.Color.red(W.KEY_LABEL))
        assertEquals(android.graphics.Color.green(t).toDouble() / android.graphics.Color.blue(t), android.graphics.Color.green(W.KEY_LABEL).toDouble() / android.graphics.Color.blue(W.KEY_LABEL), 0.02)
        val fieldKinds = listOf("TITLE", "USER", "PASS", "URL", "TAG", "OTP", "MEMO", "CUSTOM").map { io.github.zmlworks.kpexplorer.ui.Glyph.Kind.valueOf(it) }.toSet()
        val glyphs = allViews(ks.view).filterIsInstance<android.widget.ImageView>().mapNotNull { it.drawable as? io.github.zmlworks.kpexplorer.ui.Glyph }.filter { it.kind in fieldKinds }
        assertTrue(glyphs.map { it.kind }.containsAll(listOf(io.github.zmlworks.kpexplorer.ui.Glyph.Kind.TITLE, io.github.zmlworks.kpexplorer.ui.Glyph.Kind.TAG)))
        for (g in glyphs) {
            val b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888); g.setBounds(0, 0, 48, 48); g.draw(Canvas(b))
            val solid = (0 until 48 * 48).map { b.getPixel(it % 48, it / 48) }.filter { android.graphics.Color.alpha(it) == 255 }.toSet()
            if (g.kind == io.github.zmlworks.kpexplorer.ui.Glyph.Kind.TAG) assertTrue("tag stays blue", W.NAVY in solid)
            else { assertTrue("${g.kind} teal", W.KEY_LABEL in solid); assertFalse("${g.kind} no black", W.TEXT in solid); assertFalse("${g.kind} no navy", W.NAVY in solid) }
        }
        shot("v0436/06-key-page.png")
    }

    @Test fun recentKeys_virtualFolderAtTheTop_newestFirst_addedOrChanged() {
        ageAll()
        val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        // Gmail: created long ago, changed just now → 更新; Amazon: new → 追加; 勤怠 (in 仕事) changed an hour ago
        setTimes(ids["Gmail"]!!, base.minus(java.time.Duration.ofDays(400)), now.minusSeconds(60))
        setTimes(ids["Amazon"]!!, now.minusSeconds(120), now.minusSeconds(110))
        setTimes(ids["勤怠"]!!, base.minus(java.time.Duration.ofDays(100)), now.minusSeconds(3600))
        setTimes(ids["old"]!!, now, now)   // in the recycle bin: left out however new
        // History copies (three saves of Steam) make no extra rows; the bin and memos are left out
        withHistory(ids["Steam"]!!, 3)
        val steamT = entry(ids["Steam"]!!).times!!.lastModificationTime!!
        setTimes(ids["楽天"]!!, now, now)
        Session.update { Tree.moveToBin(it, ids["楽天"]!!, "ごみ箱") }
        setMemo("今日のメモ")
        val ex = a.explorer!!
        ex.refresh(); idle(); layoutHost()
        val top = tree().topRow!!
        assertEquals("最近の更新", top.contentDescription)   // 0.4.38: renamed (0.4.40 again), and right under the root
        assertEquals("the root's first child", 1, tree().indexOfChild(top))
        assertFalse(ex.recent)
        App.prefs(app()).edit().putString("view", "details").commit()   // 0.4.39: the view chosen for folders applies (was always details)
        tree().tapTop(); idle(); layoutHost()
        assertTrue(ex.recent); assertFalse(ex.iconMode)
        val list = ex.rows.map { it.uuid }
        assertEquals(io.github.zmlworks.kpexplorer.db.Recent.LIMIT, list.size)
        assertEquals(ex.rows.take(6).map { "${it.name} ${(it as Order.Node.E).e.times?.creationTime} ${it.e.times?.lastModificationTime}" }.toString(),
            listOf(ids["Steam"]!!, ids["Gmail"]!!, ids["Amazon"]!!, ids["勤怠"]!!), list.filter { it != Order.children(Tree.root(Session.db!!)).first { n -> n.name == "買い物メモ" }.uuid }.take(4))
        assertTrue(steamT >= now.minusSeconds(60))
        assertEquals("one row per key", list.size, list.toSet().size)
        assertFalse("not the bin", ids["楽天"]!! in list || ids["old"]!! in list); assertTrue("0.4.40: memos too", Order.children(Tree.root(Session.db!!)).first { it.name == "買い物メモ" }.uuid in list)
        val shown = texts(ex.view)
        // 0.4.39: the folders' columns (場所 as in search results), the date as in a folder
        assertTrue(shown.toString(), shown.contains("更新日時") && shown.contains("場所"))
        assertTrue(shown.toString(), shown.contains(L.date().format(now.minusSeconds(60))))
        assertTrue(shown.toString(), shown.contains("仕事"))
        shot("v0436/07-recent-keys.png")
        // the date format chosen by the user
        L.setDateOrder(app(), L.DateOrder.DMY); ex.refresh(); idle(); layoutHost()
        assertTrue(texts(ex.view).contains(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(java.time.ZoneId.systemDefault()).format(now.minusSeconds(60))))
        // nothing is created there; the long press is a key's usual menu plus この項目のフォルダーを開く
        assertNull(ex.askNewFolder()); assertNull(ex.askNewMemo())
        for (k in listOf("フォルダ", "鍵", "メモ")) assertFalse(k, tool(k).isEnabled)
        assertTrue(ex.backgroundMenu().filter { it.label in listOf(s(R.string.tool_new_folder), s(R.string.tool_new_key), s(R.string.tool_new_memo), s(R.string.paste)) }.none { it.enabled })
        val m = ex.itemMenu(ex.rows.first { it.uuid == ids["勤怠"] })
        assertTrue(labels(m).toString(), labels(m).containsAll(listOf(s(R.string.rename), s(R.string.cut), s(R.string.move_to_folder), s(R.string.open_item_folder), s(R.string.delete))))
        assertTrue(tree().longPressTop()); idle(); Menus.dismiss()
        m.first { it.label == s(R.string.open_item_folder) }.action(); idle()
        assertFalse(ex.recent); assertEquals(ids["G"], ex.current); assertEquals(ids["勤怠"], ex.selection)
        // like every tree row: with a key selected the first tap only clears the selection ("one cushion")
        tree().tapTop(); idle(); assertFalse(ex.recent); assertNull(ex.selection)
        tree().tapTop(); idle(); assertTrue(ex.recent)
        // back / 上へ leave it (0.4.39: to "/", its parent)
        @Suppress("DEPRECATION") a.onBackPressed(); idle()
        assertFalse(ex.recent); assertTrue(a.screen is ExplorerScreen); assertEquals(Tree.root(Session.db!!).uuid, ex.current)
        tree().tapTop(); idle(); ex.goUp(); assertFalse(ex.recent)
        // opening a key from the list and coming back keeps 最近の鍵
        tree().tapTop(); idle(); layoutHost()
        ex.open(ex.rows.first { it.uuid == ids["Gmail"] }); idle()
        assertTrue(a.screen is KeyScreen)
        a.showExplorer(); idle()
        assertTrue(a.explorer!!.recent)
    }

    @Test fun lastModified_onlyForContent_notForMoveReorderRestore() {
        val id = ids["Gmail"]!!
        val t0 = base; setTimes(id, t0, t0)
        Session.update { Order.moveTo(it, id, ids["G"]!!) }
        assertEquals("move", t0, entry(id).times!!.lastModificationTime)
        Session.update { Order.moveTo(it, id, ids["G"]!!, 0) }
        assertEquals("reorder", t0, entry(id).times!!.lastModificationTime)
        Session.update { Tree.moveToBin(it, id, "ごみ箱") }
        Session.update { Tree.restoreFromBin(it, id) }
        assertEquals("delete + restore", t0, entry(id).times!!.lastModificationTime)
        assertEquals(io.github.zmlworks.kpexplorer.db.Recent.Kind.ADDED, io.github.zmlworks.kpexplorer.db.Recent.item(entry(id), Tree.root(Session.db!!))!!.kind)
        // a change of a value → 更新
        Session.update { Order.saveEntry(it, id, mapOf("Title" to "Gmail", "UserName" to "new@example.com", "Password" to "p"), emptyList(), null) }
        assertTrue(entry(id).times!!.lastModificationTime!!.isAfter(t0))
        assertEquals(io.github.zmlworks.kpexplorer.db.Recent.Kind.UPDATED, io.github.zmlworks.kpexplorer.db.Recent.item(entry(id), Tree.root(Session.db!!))!!.kind)
        // saved within a minute of being created: still 追加
        val e2 = ids["Amazon"]!!
        setTimes(e2, base, base.plusSeconds(30))
        assertEquals(io.github.zmlworks.kpexplorer.db.Recent.Kind.ADDED, io.github.zmlworks.kpexplorer.db.Recent.item(entry(e2), Tree.root(Session.db!!))!!.kind)
        setTimes(e2, base, base.plusSeconds(120))
        assertEquals(io.github.zmlworks.kpexplorer.db.Recent.Kind.UPDATED, io.github.zmlworks.kpexplorer.db.Recent.item(entry(e2), Tree.root(Session.db!!))!!.kind)
    }
}
