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

/** 0.4.39: the virtual folders 最近更新した鍵 / 最近使用した鍵 behave like folders; 最近使用した鍵 from KeePass LastAccessTime / UsageCount; 最近の鍵の設定. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0439Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0439").mkdirs() }
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

    // ------------------------------------------------------------------------------------------------ 0.4.39

    private val R_ = io.github.zmlworks.kpexplorer.db.Recent
    private fun t(d: io.github.zmlworks.kpexplorer.ui.ClassicDialog, tag: String) = (findTag<View>(d.frame, tag) as android.widget.TextView).text.toString()
    private fun entryOf(id: UUID) = Tree.findEntry(Session.db!!, id)!!.second
    private fun setAll(id: UUID, created: java.time.Instant, modified: java.time.Instant, accessed: java.time.Instant) {
        Session.update { db -> val p = Tree.findEntry(db, id)!!.first
            Tree.modifyGroup(db, p.uuid) { g -> g.copy(entries = g.entries.map { e -> if (e.uuid != id) e else
                e.copy(times = (e.times ?: app.keemobile.kotpass.models.TimeData.create(created)).copy(creationTime = created, lastModificationTime = modified, lastAccessTime = accessed)) }) } }
    }
    /** Every key and memo created, changed and last accessed at least a month back: nothing used yet. */
    private fun ageEverything() {
        val all = ArrayList<UUID>()
        fun walk(g: app.keemobile.kotpass.models.Group) { g.entries.forEach { all.add(it.uuid) }; g.groups.forEach { walk(it) } }
        walk(Tree.root(Session.db!!))
        all.forEachIndexed { i, id -> val t = base.minus(java.time.Duration.ofDays(30L + i)); setAll(id, t, t, t) }
    }
    private fun viewAs(det: Boolean) { val ex = a.explorer!!; if (ex.iconMode == det) ex.toggleView(); idle(); layoutHost(); layoutHost() }
    private fun topLabels() = tree().topRows.map { it.contentDescription.toString() }

    // ---------------------------------------------------------------- the record (KDBX LastAccessTime / UsageCount)

    @Test fun used_isKeePassLastAccessTime_markedWithoutTouchingModificationOrHistory() {
        ageEverything()
        val id = ids["Gmail"]!!
        val before = entryOf(id)
        assertNull("access = creation: never used", R_.usedAt(before))
        val t = base.plusSeconds(3600)
        Session.update { R_.markUsed(it, id, t) }
        val e = entryOf(id)
        assertEquals(t, R_.usedAt(e)); assertEquals(t, e.times!!.lastAccessTime)
        assertEquals(1, e.times!!.usageCount)
        assertEquals("modification kept", before.times!!.lastModificationTime, e.times!!.lastModificationTime)
        assertEquals("no History copy", before.history.size, e.history.size)
        assertEquals("fields kept", Tree.fieldsOf(before), Tree.fieldsOf(e))
        Session.update { R_.markUsed(it, id, t.plusSeconds(60)) }
        assertEquals(2, entryOf(id).times!!.usageCount)
        // in the file, as KeePass keeps it
        Session.flush(); java.util.concurrent.CountDownLatch(1).also { l -> Session.io.execute { l.countDown() }; l.await() }; idle()
        val back = Tree.findEntry(Kdbx.open(a.store.read(), key), id)!!.second
        assertEquals(t.plusSeconds(60), back.times!!.lastAccessTime); assertEquals(2, back.times!!.usageCount)
        // the order: most recently used first; not memos, not the bin
        Session.update { R_.markUsed(it, ids["Amazon"]!!, t.plusSeconds(120)) }
        Session.update { R_.markUsed(it, memoId(), t.plusSeconds(180)) }
        Session.update { R_.markUsed(it, ids["old"]!!, t.plusSeconds(240)) }
        assertEquals(listOf(ids["Amazon"]!!, id), R_.used(Session.db!!).map { it.entry.uuid })
        assertEquals(1, R_.used(Session.db!!, 1).size)
        // an edit counts as a use (this app, like KeePass, sets the access time when saving a change)
        Session.update { Order.saveEntry(it, ids["Steam"]!!, mapOf("Title" to "Steam", "UserName" to "taro_games2", "Password" to "p"), emptyList(), null) }
        assertEquals(ids["Steam"], R_.used(Session.db!!).first().entry.uuid)
        // clearing: access back to creation, count 0; modification times stay
        val mod = entryOf(ids["Steam"]!!).times!!.lastModificationTime
        Session.update { R_.clearUsed(it) }
        assertTrue(R_.used(Session.db!!).isEmpty()); assertFalse(R_.anyUsed(Session.db!!))
        assertEquals(0, entryOf(id).times!!.usageCount); assertEquals(entryOf(id).times!!.creationTime, entryOf(id).times!!.lastAccessTime)
        assertEquals(mod, entryOf(ids["Steam"]!!).times!!.lastModificationTime)
    }

    @Test fun used_openingAKeyPage_orCopyingAField_records_notWhileBrowsingAFile() {
        ageEverything()
        val id = ids["Amazon"]!!
        val ks = openKey(id)
        assertNotNull("opened = used", R_.usedAt(entryOf(id)))
        assertEquals(1, entryOf(id).times!!.usageCount)
        assertEquals("written to the file (as any change)", Session.SaveState.PENDING, Session.saveState)
        assertFalse("not a change: 最近更新した鍵 does not move", R_.list(Session.db!!).first().entry.uuid == id)
        val copy = KeyScreen::class.java.getDeclaredMethod("copy", String::class.java, String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        copy.invoke(ks, "ユーザー名", "taro_y", false); idle()
        assertEquals("copied = used again", 2, entryOf(id).times!!.usageCount)
        copy.invoke(ks, "ユーザー名", "", false); idle()
        assertEquals("nothing copied", 2, entryOf(id).times!!.usageCount)
        a.showExplorer(); idle()
        // a read-only browse (an external file or a backup) records nothing
        Session.flush(); java.util.concurrent.CountDownLatch(1).also { l -> Session.io.execute { l.countDown() }; l.await() }; idle()
        Session.beginTempView(Session.db!!, "外部.kdbx")
        openKey(ids["Steam"]!!)
        assertNull(R_.usedAt(entryOf(ids["Steam"]!!)))
        assertEquals(Session.SaveState.SAVED, Session.saveState)
        a.showExplorer(); idle(); Session.exitTempView(); idle()
        assertNull(R_.usedAt(entryOf(ids["Steam"]!!)))
    }

    // ---------------------------------------------------------------- both virtual folders under "/"

    @Test fun virtualFolders_bothUnderTheRoot_withTheirIcons() {
        ageEverything()
        Session.update { R_.markUsed(it, ids["Gmail"]!!, base.plusSeconds(60)) }
        val ex = a.explorer!!; ex.refresh(); idle(); layoutHost()
        assertEquals(listOf("最近更新した鍵", "最近使用した鍵"), topLabels())
        assertEquals(listOf(1, 2), tree().topRows.map { tree().indexOfChild(it) })
        assertTrue(io.github.zmlworks.kpexplorer.ui.FugueIcons.used(app()).sameAs(android.graphics.BitmapFactory.decodeResource(app().resources, R.drawable.fugue_clock_arrow, android.graphics.BitmapFactory.Options().apply { inScaled = false })))
        assertFalse(io.github.zmlworks.kpexplorer.ui.FugueIcons.used(app()).sameAs(io.github.zmlworks.kpexplorer.ui.FugueIcons.recent(app())))
        shot("v0439/01-tree-both.png")
    }

    @Test fun virtualFolder_behavesLikeAFolder_historyUpViewMenusDrag() {
        ageEverything()
        val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        setAll(ids["Gmail"]!!, base.minus(java.time.Duration.ofDays(400)), now.minusSeconds(60), now.minusSeconds(60))
        setAll(ids["勤怠"]!!, base.minus(java.time.Duration.ofDays(100)), now.minusSeconds(3600), now.minusSeconds(3600))
        Session.update { R_.markUsed(it, ids["Amazon"]!!, now.minusSeconds(30)) }
        Session.update { R_.markUsed(it, ids["会社メール"]!!, now.minusSeconds(90)) }
        val ex = a.explorer!!
        ex.navigate(ids["G"]!!); idle(); layoutHost()
        // open from the tree: a place in the back history, like a folder
        tree().tapTop(1); idle(); layoutHost()
        assertEquals(RecentSettings.Kind.USED, ex.virtual)
        assertEquals(listOf(ids["Amazon"], ids["Gmail"], ids["会社メール"], ids["勤怠"]), ex.rows.map { it.uuid })
        assertEquals("最近使用した鍵 - KeePass Explorer", ex.caption.title.text.toString())
        assertEquals("no band (as a folder)", View.GONE, findTag<View>(ex.view, "flat-band")!!.visibility)
        assertTrue("icons, as chosen for folders", ex.iconMode)
        assertTrue(tool("上へ").isEnabled)
        for (k in listOf("フォルダ", "鍵", "メモ")) assertFalse(k, tool(k).isEnabled)
        shot("v0439/02-recently-used-icons.png")
        viewAs(true)
        val shown = texts(ex.view)
        assertTrue(shown.toString(), shown.containsAll(listOf("名前", "場所", "使用日時", "仕事", "メール", L.date().format(now.minusSeconds(30)))))
        shot("v0439/03-recently-used-details.png")
        // the same long-press menus as a folder's keys (plus この項目のフォルダーを開く)
        val m = labels(ex.itemMenu(ex.rows.first { it.uuid == ids["勤怠"] }))
        assertEquals(listOf(s(R.string.view_icons), s(R.string.reset_columns), "|", s(R.string.rename), "|", s(R.string.cut), s(R.string.move_to_folder),
            s(R.string.move_up_folder), s(R.string.open_item_folder), "|", s(R.string.delete)), m)
        assertEquals(listOf(s(R.string.view_icons), s(R.string.reset_columns), "|"), labels(ex.backgroundMenu()).take(3))
        // back returns to 仕事, forward again by the tree; up goes to "/", back to the folder
        ex.goBack(); idle()
        assertNull(ex.virtual); assertEquals(ids["G"], ex.current)
        tree().tapTop(1); idle()
        ex.goUp(); idle()
        assertNull(ex.virtual); assertEquals(Tree.root(Session.db!!).uuid, ex.current)
        ex.goBack(); idle()
        assertEquals("back into it", RecentSettings.Kind.USED, ex.virtual)
        // the other one, from here
        tree().tapTop(0); idle(); layoutHost()
        assertEquals(RecentSettings.Kind.CHANGED, ex.virtual)
        assertEquals("更新日時", texts(ex.view).first { it == "更新日時" })
        shot("v0439/04-recently-changed-details.png")
        ex.goBack(); idle(); assertEquals(RecentSettings.Kind.USED, ex.virtual)
        viewAs(false); tree().tapTop(0); idle(); layoutHost()
        shot("v0439/05-recently-changed-icons.png")
        viewAs(true); tree().tapTop(1); idle(); layoutHost()
        // tap selects, tap again opens; coming back keeps the list (the key used moves to the top)
        ex.select(ids["勤怠"]); idle()
        ex.open(ex.rows.first { it.uuid == ids["勤怠"] }); idle()
        assertTrue(a.screen is KeyScreen)
        a.showExplorer(); idle(); layoutHost()
        assertEquals(RecentSettings.Kind.USED, a.explorer!!.virtual)
        assertEquals(ids["勤怠"], a.explorer!!.rows.first().uuid)
        // the system back goes up to "/", as from a folder under it
        @Suppress("DEPRECATION") a.onBackPressed(); idle()
        assertNull(ex.virtual); assertEquals(Tree.root(Session.db!!).uuid, ex.current)
        // the usual operations work on its keys: move to the recycle bin
        tree().tapTop(1); idle()
        Session.update { Tree.moveToBin(it, ids["Amazon"]!!, "ごみ箱") }; ex.refresh(); idle()
        assertFalse("the bin is not listed", ex.rows.any { it.uuid == ids["Amazon"] })
    }

    @Test fun virtualFolder_dragAKeyOntoAFolderOfTheTree_movesIt() {
        ageEverything()
        Session.update { R_.markUsed(it, ids["Gmail"]!!, base.plusSeconds(60)) }
        val ex = a.explorer!!
        tree().tapTop(1); idle(); layoutHost()
        val r = ex.rows.first { it.uuid == ids["Gmail"] }
        ex.performDrop(r.uuid, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.INTO, gid("銀行"), pathFolder = true)); idle()
        assertEquals(gid("銀行"), Tree.parentOf(Tree.root(Session.db!!), ids["Gmail"]!!)!!.uuid)
        assertEquals("still listed (still used)", RecentSettings.Kind.USED, ex.virtual); assertTrue(ex.rows.any { it.uuid == ids["Gmail"] })
    }

    // ---------------------------------------------------------------- 最近の鍵の設定

    @Test fun settings_bothFolders_hideCountOpen_andClearTheHistory() {
        ageEverything()
        Session.update { R_.markUsed(it, ids["Gmail"]!!, base.plusSeconds(60)) }
        Session.update { R_.markUsed(it, ids["Steam"]!!, base.plusSeconds(120)) }
        val ex = a.explorer!!; ex.refresh(); idle(); layoutHost()
        assertTrue(labels(ex.mainMenu()).contains("最近の鍵の設定"))
        val d = RecentSettings.show(a); idle()
        val tx = texts(d.frame)
        assertEquals("最近の鍵の設定", tx.first())
        for (x in listOf("最近更新した鍵", "最近使用した鍵", "最近更新した鍵を見る", "最近使用した鍵を見る", "使用履歴を消去")) assertTrue("$x in $tx", tx.contains(x))
        assertFalse("no ellipsis in the window", tx.any { it.contains("…") || it.contains("...") })
        assertTrue(findTag<View>(d.frame, "used-clear")!!.isEnabled)
        Dlg.last = d; shot("v0439/06-recent-settings.png", dialog = true)
        // counts per folder
        findTag<View>(d.frame, "used-count")!!.performClick(); idle(); radio(0).performClick(); idle()
        assertEquals(10, RecentSettings.count(a, RecentSettings.Kind.USED)); assertEquals(30, RecentSettings.count(a, RecentSettings.Kind.CHANGED))
        assertEquals("表示する数: 10 件", t(d, "used-count-text"))
        // hide 最近使用した鍵 only
        (findTag<View>(d.frame, "used-hide") as android.widget.CheckBox).performClick(); idle(); layoutHost()
        assertFalse(RecentSettings.shown(a, RecentSettings.Kind.USED)); assertTrue(RecentSettings.shown(a, RecentSettings.Kind.CHANGED))
        assertEquals(listOf("最近更新した鍵"), topLabels())
        findTag<View>(d.frame, "used-open")!!.performClick(); idle(); layoutHost()
        assertFalse(d.isShowing)
        assertEquals(RecentSettings.Kind.USED, ex.virtual); assertEquals(listOf("最近更新した鍵", "最近使用した鍵"), topLabels())
        ex.goUp(); idle(); layoutHost()
        assertEquals(listOf("最近更新した鍵"), topLabels())
        (findTag<View>(RecentSettings.show(a).frame, "used-hide") as android.widget.CheckBox).performClick(); idle()
        Dlg.last!!.dismiss(); layoutHost()
        // the tree's long-press menu: 開く, the settings, 使用履歴を消去
        val menu = ex.virtualTreeMenu(RecentSettings.Kind.USED)
        assertEquals(listOf(s(R.string.open), "|", "最近の鍵の設定", "使用履歴を消去"), labels(menu))
        assertEquals(listOf(s(R.string.open), "|", "最近の鍵の設定"), labels(ex.virtualTreeMenu(RecentSettings.Kind.CHANGED)))
        val row = tree().topRows[1]; val l = IntArray(2); row.getLocationInWindow(l)
        assertTrue(tree().longPressTop(1)); idle()
        shot("v0439/07-used-tree-menu.png", menuAt = (l[0] + W.dp(120)) to (l[1] + row.height / 2)); Menus.dismiss()
        // clearing asks first
        menu.last().action(); idle()
        val c = latestDialog()
        assertTrue(texts(c.frame).joinToString(), texts(c.frame).joinToString().contains("「最近使用した鍵」の履歴を消去しますか?"))
        Dlg.last = c; shot("v0439/08-clear-history-confirm.png", dialog = true)
        clickButton(c, "キャンセル"); assertTrue(R_.anyUsed(Session.db!!))
        menu.last().action(); idle(); clickButton(latestDialog(), "消去")
        assertFalse(R_.anyUsed(Session.db!!))
        tree().tapTop(1); idle(); layoutHost()
        assertTrue(ex.rows.isEmpty())
        assertTrue(texts(ex.view).contains("このフォルダーは空です。"))
        assertFalse(ex.virtualTreeMenu(RecentSettings.Kind.USED).last().enabled)
        L.setLang(app(), L.Lang.EN)
        assertEquals("Recently used keys", RecentSettings.Kind.USED.label); assertEquals("Recent keys settings", L.t(RecentSettings.TITLE_JA, RecentSettings.TITLE_EN))
    }
}
