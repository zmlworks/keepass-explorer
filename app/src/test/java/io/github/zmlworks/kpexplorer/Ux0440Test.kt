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

/** 0.4.40 (with the 0.4.39 tests moved here): 機能フォルダ in "/" (reorderable, the tree follows), 頻繁に使用, memos counted, uses saved quietly, 機能フォルダの設定, finer export strengths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0440Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0440").mkdirs() }
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

    // ------------------------------------------------------------------------------------------------ 0.4.40

    private val R_ = io.github.zmlworks.kpexplorer.db.Recent
    private fun t(d: io.github.zmlworks.kpexplorer.ui.ClassicDialog, tag: String) = (findTag<View>(d.frame, tag) as android.widget.TextView).text.toString()
    private fun entryOf(id: UUID) = Tree.findEntry(Session.db!!, id)!!.second
    private fun rootId() = Tree.root(Session.db!!).uuid
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
        settle()
    }
    /** Lets every pending save finish (debounce, io thread). */
    private fun settle() {
        Session.flush(); repeat(3) { java.util.concurrent.CountDownLatch(1).also { l -> Session.io.execute { l.countDown() }; l.await() }; idle() }
    }
    private fun viewAs(det: Boolean) { val ex = a.explorer!!; if (ex.iconMode == det) ex.toggleView(); idle(); layoutHost(); layoutHost() }
    private fun topLabels() = tree().topRows.map { it.contentDescription.toString() }
    private fun fileEntry(id: UUID) = Tree.findEntry(Kdbx.open(a.store.read(), key), id)!!.second
    /** The root's children in the folder tree, top to bottom (virtual folders by name). */
    private fun treeRootRows(): List<String> {
        val t = tree(); val out = ArrayList<String>()
        for (i in 0 until t.childCount) {
            val v = t.getChildAt(i)
            if (v.tag == io.github.zmlworks.kpexplorer.ui.TOP_TAG) out.add(v.contentDescription.toString())
            else (v.tag as? UUID)?.let { id -> if (Tree.parentOf(Tree.root(Session.db!!), id)?.uuid == rootId()) out.add(Tree.findGroup(Session.db!!, id)!!.name) }
        }
        return out
    }
    private fun offsetIn(v: View, root: View): Pair<Int, Int> {
        var x = 0; var y = 0; var c: View? = v
        while (c != null && c !== root) { x += c.left - c.scrollX; y += c.top - c.scrollY; c = c.parent as? View }
        return x to y
    }
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
            v.measure(View.MeasureSpec.makeMeasureSpec(maxOf(anchor.width, W.dp(260)), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_, View.MeasureSpec.AT_MOST))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            c.save(); c.translate(36f + ax, (top + ay + anchor.height).toFloat().coerceAtMost((H_ - v.measuredHeight).toFloat())); v.draw(c); c.restore()
        }
        FileOutputStream(File(out, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun pickMenuRow(label: String) {
        val row = allViews(Menus.lastView!!).filterIsInstance<android.widget.TextView>().first { it.text.toString() == label }.parent as View
        row.performClick(); idle()
    }

    // ---------------------------------------------------------------- the use record (KDBX LastAccessTime / UsageCount)

    @Test fun used_isKeePassLastAccessTime_markedWithoutTouchingModificationOrHistory() {
        ageEverything()
        val id = ids["Gmail"]!!
        val before = entryOf(id)
        assertNull("access = creation: never used", R_.usedAt(before))
        val t = base.plusSeconds(3600)
        Session.update { R_.markUsed(it, id, t) }
        val e = entryOf(id)
        assertEquals(t, R_.usedAt(e)); assertEquals(1, e.times!!.usageCount)
        assertEquals("modification kept", before.times!!.lastModificationTime, e.times!!.lastModificationTime)
        assertEquals("no History copy", before.history.size, e.history.size)
        Session.update { R_.markUsed(it, id, t.plusSeconds(60)) }
        settle()
        assertEquals(t.plusSeconds(60), fileEntry(id).times!!.lastAccessTime); assertEquals(2, fileEntry(id).times!!.usageCount)
        // most recently used first; memos too (0.4.40); not the bin
        Session.update { R_.markUsed(it, ids["Amazon"]!!, t.plusSeconds(120)) }
        Session.update { R_.markUsed(it, memoId(), t.plusSeconds(180)) }
        Session.update { R_.markUsed(it, ids["old"]!!, t.plusSeconds(240)) }
        assertEquals(listOf(memoId(), ids["Amazon"]!!, id), R_.used(Session.db!!).map { it.entry.uuid })
        // よく利用する: the highest count first, then the most recent
        assertEquals(listOf(id, memoId(), ids["Amazon"]!!), R_.frequent(Session.db!!).map { it.entry.uuid })
        assertEquals(listOf(2, 1, 1), R_.frequent(Session.db!!).map { it.count })
        assertEquals(1, R_.frequent(Session.db!!, 1).size)
        // 最近の更新 lists memos too (0.4.40)
        setAll(ids["議事録"]!!, base, base.plusSeconds(7200), base)
        assertEquals(ids["議事録"], R_.list(Session.db!!).first().entry.uuid)
        // clearing empties both use lists; modification times stay
        val mod = entryOf(id).times!!.lastModificationTime
        Session.update { R_.clearUsed(it) }
        assertTrue(R_.used(Session.db!!).isEmpty()); assertTrue(R_.frequent(Session.db!!).isEmpty()); assertFalse(R_.anyUsed(Session.db!!))
        assertEquals(mod, entryOf(id).times!!.lastModificationTime)
    }

    @Test fun usage_isKeptInMemory_andWrittenWithTheNextSave_orTheLock() {
        ageEverything()
        val id = ids["Amazon"]!!
        val n0 = Session.saveCount
        assertFalse("default: off", FeatureFolders.saveOnOpen(a))
        openKey(id)
        assertNotNull("shown at once", R_.usedAt(entryOf(id)))
        assertEquals(Session.SaveState.SAVED, Session.saveState)
        assertTrue(Session.hasQuietUnsaved)
        ShadowLooper.idleMainLooper(Session.debounceMs + 100, java.util.concurrent.TimeUnit.MILLISECONDS); settle()
        assertEquals("no save just from viewing", n0, Session.saveCount)
        assertNull("not in the file yet", R_.usedAt(fileEntry(id)))
        a.showExplorer(); idle()
        // a memo opened: a use too
        a.show(MemoScreen(a, memoId())); idle()
        assertEquals(1, entryOf(memoId()).times!!.usageCount)
        a.showExplorer(); idle()
        // the next save of a change writes it along
        Session.update { Order.renameEntry(it, ids["Steam"]!!, "Steam2") }; settle()
        assertEquals(n0 + 1, Session.saveCount)
        assertNotNull(R_.usedAt(fileEntry(id))); assertEquals(1, fileEntry(memoId()).times!!.usageCount)
        assertFalse(Session.hasQuietUnsaved)
        // another view, then leaving the app / the lock writes it (once)
        openKey(ids["Gmail"]!!); a.showExplorer(); idle()
        assertTrue(Session.hasQuietUnsaved)
        Session.flushAll(); settle()
        assertEquals(n0 + 2, Session.saveCount)
        assertNotNull(R_.usedAt(fileEntry(ids["Gmail"]!!)))
        // with 「ファイルを開くたびにデータベースを更新する」 on: saved like a change (0.4.39)
        App.prefs(app()).edit().putBoolean(FeatureFolders.PREF_SAVE_ON_OPEN, true).commit()
        openKey(ids["楽天"]!!)
        assertEquals(Session.SaveState.PENDING, Session.saveState)
        a.showExplorer(); idle(); settle()
        assertNotNull(R_.usedAt(fileEntry(ids["楽天"]!!)))
        // a read-only browse records nothing
        val steamUses = entryOf(ids["Steam"]!!).times!!.usageCount
        Session.beginTempView(Session.db!!, "外部.kdbx")
        openKey(ids["Steam"]!!)
        assertEquals(steamUses, entryOf(ids["Steam"]!!).times!!.usageCount)
        a.showExplorer(); idle(); Session.exitTempView(); idle()
        // the lock writes what is left
        App.prefs(app()).edit().putBoolean(FeatureFolders.PREF_SAVE_ON_OPEN, false).commit()
        openKey(ids["X (Twitter)"]!!); a.showExplorer(); idle()
        a.lock(); idle()
        java.util.concurrent.CountDownLatch(1).also { l -> Session.io.execute { l.countDown() }; l.await() }
        assertNotNull(R_.usedAt(Tree.findEntry(Kdbx.open(App.store(app()).read(), key), ids["X (Twitter)"]!!)!!.second))
    }

    // ---------------------------------------------------------------- the virtual folders in "/"

    @Test fun virtualFolders_inTheRoot_iconsAndDetails_treeInTheSameOrder() {
        ageEverything()
        Session.update { R_.markUsed(it, ids["Gmail"]!!, base.plusSeconds(60)) }
        Session.update { R_.markUsed(it, ids["Gmail"]!!, base.plusSeconds(70)) }
        Session.update { R_.markUsed(it, memoId(), base.plusSeconds(80)) }
        val ex = a.explorer!!
        assertEquals(2, R_.used(Session.db!!).size)
        ex.navigate(rootId()); ex.refresh(); idle(); viewAs(false)
        val names3 = listOf("最近の更新", "最近使用", "頻繁に使用")
        // (an older file: its root order does not name them) first, as the root's first children in 0.4.38 / 0.4.39
        assertEquals(names3, ex.rows.take(3).map { it.name })
        assertTrue(ex.rows.take(3).all { it is Order.Node.V })
        assertEquals(names3, topLabels())
        assertEquals(names3 + listOf("仕事", "銀行"), treeRootRows().take(5))
        assertEquals("ごみ箱", treeRootRows().last())
        assertEquals(listOf(R_.LIMIT, 2, 2), ex.rows.take(3).map { (it as Order.Node.V).count })
        shot("v0440/01-root-icons.png")
        viewAs(true)
        val shown = texts(ex.view)
        assertTrue(shown.toString(), shown.containsAll(names3 + listOf("機能フォルダ")))
        shot("v0440/02-root-details.png")
        // a tap selects it (preview: its files), the next tap opens it, like a folder
        ex.tap(ex.rows[1]); idle(); layoutHost()
        assertEquals(FeatureFolders.Kind.USED.id, ex.selection)
        assertTrue(texts(ex.view).contains("鍵とメモ 2 個 (機能フォルダ)"))
        ex.tap(ex.rows.first { it.uuid == FeatureFolders.Kind.USED.id }); idle(); layoutHost()
        assertEquals(FeatureFolders.Kind.USED, ex.virtual)
        assertEquals(listOf(memoId(), ids["Gmail"]), ex.rows.map { it.uuid })
        ex.goUp(); idle()
        // 頻繁に使用: 使用回数 column
        ex.openVirtual(FeatureFolders.Kind.FREQUENT); idle(); layoutHost()
        assertTrue(texts(ex.view).containsAll(listOf("使用回数", "2 回", "1 回")))
        assertEquals(listOf(ids["Gmail"], memoId()), ex.rows.map { it.uuid })
        shot("v0440/03-frequently-used-details.png")
        ex.goUp(); idle(); viewAs(false)
    }

    @Test fun virtualFolders_reorderedInTheRoot_treeFollows_binStaysLast() {
        val ex = a.explorer!!
        ex.navigate(rootId()); viewAs(false)
        // drop 最近使用 after 銀行
        ex.performDrop(FeatureFolders.Kind.USED.id, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.AFTER, gid("銀行"))); idle(); layoutHost()
        val order = ex.rows.map { it.name }
        assertEquals(listOf("最近の更新", "頻繁に使用", "仕事", "銀行", "最近使用", "フォルダ01"), order.take(6))
        assertEquals("the tree follows the list", order.filter { it in treeRootRows() && it != "ごみ箱" }.take(6), treeRootRows().take(6))
        // stored in the file (the root's order; KeePass ignores it)
        assertTrue(Order.storedOrder(Tree.root(Session.db!!)).containsAll(R_.IDS))
        settle()
        assertEquals(Order.order(Tree.root(Session.db!!), true), Order.order(Tree.root(Kdbx.open(a.store.read(), key)), true))
        // to the end of "/": after the bin in the list, the bin still last in the tree (setting on)
        ex.performDrop(FeatureFolders.Kind.CHANGED.id, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.END, null)); idle(); layoutHost()
        assertEquals("最近の更新", ex.rows.last().name)
        assertEquals(listOf("最近の更新", "ごみ箱"), treeRootRows().takeLast(2))
        shot("v0440/04-tree-reordered.png")
        Session.update { Tree.setBinLastInTree(it, false) }; ex.refresh(); idle(); layoutHost()
        assertEquals("setting off: as in the list", "最近の更新", treeRootRows().last())
        Session.update { Tree.setBinLastInTree(it, true) }; ex.refresh(); idle()
        // nothing goes into it, it goes nowhere else
        val before = Order.order(Tree.root(Session.db!!), true)
        ex.performDrop(ids["Gmail"]!!, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.INTO, FeatureFolders.Kind.FREQUENT.id)); idle()
        assertEquals(rootId(), Tree.parentOf(Tree.root(Session.db!!), ids["Gmail"]!!)!!.uuid)
        ex.performDrop(FeatureFolders.Kind.FREQUENT.id, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.INTO, gid("銀行"), pathFolder = true)); idle()
        assertEquals(before, Order.order(Tree.root(Session.db!!), true))
        try { Order.moveTo(Session.db!!, FeatureFolders.Kind.FREQUENT.id, gid("銀行")); fail() } catch (_: Tree.TreeException) {}
        try { Order.moveTo(Session.db!!, ids["Gmail"]!!, FeatureFolders.Kind.FREQUENT.id); fail() } catch (_: Tree.TreeException) {}
        // keys around them keep their places: a key dropped before 最近使用 lands there
        ex.performDrop(ids["Gmail"]!!, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.BEFORE, FeatureFolders.Kind.USED.id)); idle()
        val o = ex.rows.map { it.uuid }
        assertEquals(o.indexOf(FeatureFolders.Kind.USED.id) - 1, o.indexOf(ids["Gmail"]))
        // a new folder in "/" still goes to the end; a folder restored from the bin after the folders
        ex.newFolder("新規"); idle()
        assertEquals("新規", ex.rows.last().name)
    }

    @Test fun virtualFolders_menus_hiddenOnes_openFromTheMainMenu() {
        ageEverything()
        Session.update { R_.markUsed(it, ids["Gmail"]!!, base.plusSeconds(60)) }
        val ex = a.explorer!!
        ex.navigate(rootId()); viewAs(false)
        val v = ex.rows.first { it.uuid == FeatureFolders.Kind.USED.id }
        assertEquals(listOf(s(R.string.view_details), "|", "表示する数: 30 件", "利用履歴を消去", "|", "機能フォルダの設定"), labels(ex.itemMenu(v)))
        assertEquals(listOf(s(R.string.view_details), "|", "表示する数: 30 件", "|", "機能フォルダの設定"), labels(ex.itemMenu(ex.rows.first { it.uuid == FeatureFolders.Kind.CHANGED.id })))
        assertEquals(listOf(s(R.string.open), "|", "表示する数: 30 件", "利用履歴を消去", "|", "機能フォルダの設定"), labels(ex.virtualTreeMenu(FeatureFolders.Kind.FREQUENT)))
        val row = tree().topRows[1]; val l = IntArray(2); row.getLocationInWindow(l)
        assertTrue(tree().longPressTop(1)); idle()
        shot("v0440/05-tree-menu.png", menuAt = (l[0] + W.dp(120)) to (l[1] + row.height / 2)); Menus.dismiss()
        // 表示する数
        ex.itemMenu(v).first { it.label == "表示する数: 30 件" }.action(); idle(); radio(0).performClick(); idle()
        assertEquals(10, FeatureFolders.count(a, FeatureFolders.Kind.USED))
        // 利用履歴を消去 asks first
        ex.itemMenu(v).first { it.label == "利用履歴を消去" }.action(); idle()
        assertTrue(texts(latestDialog().frame).joinToString().contains("「最近使用」と「頻繁に使用」の履歴を消去しますか?"))
        clickButton(latestDialog(), "消去")
        assertFalse(R_.anyUsed(Session.db!!))
        assertFalse(ex.virtualTreeMenu(FeatureFolders.Kind.USED).first { it.label == "利用履歴を消去" }.enabled)
        // the main menu: ごみ箱, the three, 機能フォルダの設定 between separators
        val m = labels(ex.mainMenu())
        val i = m.indexOf("ごみ箱")
        assertEquals(listOf("|", "ごみ箱", "最近の更新", "最近使用", "頻繁に使用", "機能フォルダの設定", "|"), m.subList(i - 1, i + 6))
        showMainMenu(); shot("v0440/06-menu.png", menuAt = (W_ - (Menus.current?.width ?: W.dp(300)) - W.dp(4)) to W.dp(60)); Menus.dismiss()
        // hidden: not in "/" nor the tree; the menu opens it, and the tree shows it while open
        App.prefs(app()).edit().putBoolean(FeatureFolders.Kind.FREQUENT.prefShow, false).commit(); ex.refresh(); idle()
        assertFalse(ex.rows.any { it.uuid == FeatureFolders.Kind.FREQUENT.id }); assertFalse(topLabels().contains("頻繁に使用"))
        ex.mainMenu().first { it.label == "頻繁に使用" }.action(); idle()
        assertEquals(FeatureFolders.Kind.FREQUENT, ex.virtual); assertTrue(topLabels().contains("頻繁に使用"))
        ex.goUp(); idle(); assertFalse(topLabels().contains("頻繁に使用"))
        // ごみ箱 opens the bin (also hidden)
        Session.update { Tree.setBinHidden(it, true) }; ex.refresh(); idle()
        ex.mainMenu().first { it.label == "ごみ箱" }.action(); idle()
        assertEquals(bin(), ex.current)
    }

    // ---------------------------------------------------------------- 機能フォルダの設定

    @Test fun settings_theUsersLayout_defaults_andEachSetting() {
        Session.update { Tree.moveToBin(it, ids["Steam"]!!, "ごみ箱") }
        val ex = a.explorer!!
        item(ex.mainMenu(), "機能フォルダの設定").action(); idle()
        val d = Dlg.last!!
        val tx = texts(d.frame)
        assertEquals("機能フォルダの設定", tx.first())
        val want = listOf("ごみ箱の名前: ごみ箱", "変更", "2 個の項目", "ごみ箱を空にする", "自動削除: しない", "変更", "ごみ箱を表示する", "ごみ箱をフォルダツリーで一番下にする",
            "「最近の更新」を表示する", "「最近使用」を表示する", "「頻繁に使用」を表示する", "ファイルを開くたびにデータベースを更新する", "閉じる")
        assertEquals(want, tx.drop(1).filter { it.isNotEmpty() })
        fun cb(tag: String) = findTag<View>(d.frame, tag) as android.widget.CheckBox
        assertTrue(cb("bin-show").isChecked); assertTrue(cb("bin-tree-last").isChecked)
        for (k in FeatureFolders.Kind.entries) assertTrue(cb("${k.name.lowercase()}-show").isChecked)
        assertFalse(cb("save-on-open").isChecked)
        assertEquals("grey", W.SHADOW, cb("save-on-open").currentTextColor)
        assertEquals(W.TEXT, cb("bin-show").currentTextColor)
        assertTrue(tx.none { it.contains("…") || it.contains("...") })
        shot("v0440/07-feature-folder-settings.png", dialog = true)
        // each one
        cb("used-show").performClick(); idle(); layoutHost()
        assertFalse(FeatureFolders.shown(a, FeatureFolders.Kind.USED)); assertFalse(topLabels().contains("最近使用"))
        cb("used-show").performClick(); idle()
        cb("save-on-open").performClick(); idle()
        assertTrue(FeatureFolders.saveOnOpen(a))
        cb("bin-show").performClick(); idle()
        assertTrue(Tree.binHidden(Session.db!!)); assertFalse(treeIds().contains(bin()))
        cb("bin-show").performClick(); idle()
        cb("bin-tree-last").performClick(); idle()
        assertFalse(Tree.binLastInTree(Session.db!!))
        findTag<View>(d.frame, "bin-empty")!!.performClick(); idle()
        latestDialog().buttonViews.first().performClick(); idle()
        assertEquals("0 個の項目", t(d, "bin-empty-text"))
        d.dismiss()
        L.setLang(app(), L.Lang.EN)
        val e = FeatureFolders.show(a); idle()
        assertTrue(texts(e.frame).containsAll(listOf("Special folder settings", "Show \"Recently updated\"", "Show \"Frequently used\"", "Update the database every time a file is opened")))
        e.dismiss()
    }

    // ---------------------------------------------------------------- export strength

    @Test fun exportStrength_moreLevels_typedPowerOfTen_warnsBelowStrong() {
        a.store.setInfo(FileInfo(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK, 9))
        val d = SecuritySettings.show(a); idle()
        assertTrue(t(d, "sec-strength").contains("やや弱い (推測に約 10^9 回)"))
        val combo = findTag<View>(d.frame, "sec-export-level")!!
        combo.performClick(); idle()
        shotDialogWithMenu("v0440/08-security-dropdown.png", combo)
        pickMenuRow("やや弱い (10^8以上)")
        val w = latestDialog()
        assertTrue(texts(w.frame).joinToString(), texts(w.frame).joinToString().contains("「強い」(10^12) より弱い設定です"))
        assertTrue(texts(w.frame).joinToString().contains("GPU 1 台で"))
        clickButton(w, "キャンセル"); assertEquals(0, Security.exportMinLog10(a))
        findTag<View>(d.frame, "sec-export-level")!!.performClick(); idle(); pickMenuRow("やや弱い (10^8以上)"); clickButton(latestDialog(), "設定する")
        assertEquals(8, Security.exportMinLog10(a))
        assertEquals("現在のデータベース: 起動画面から書き出せます", t(d, "sec-export-current"))
        assertTrue(t(d, "sec-export-note").contains("10^8 回の推測は、平均でGPU 1 台で約 1 時間"))
        Dlg.last = d; shot("v0440/09-security-settings.png", dialog = true)
        // typed: 10^11
        findTag<View>(d.frame, "sec-export-level")!!.performClick(); idle(); pickMenuRow("数値で指定 (10^n)")
        val p = latestDialog()
        find(p.frame, android.widget.EditText::class.java)!!.setText("99"); p.buttonViews.first().performClick(); idle()
        assertTrue("out of range: stays open", p.isShowing)
        find(p.frame, android.widget.EditText::class.java)!!.setText("11"); p.buttonViews.first().performClick(); idle()
        clickButton(latestDialog(), "設定する")
        assertEquals(11, Security.exportMinLog10(a))
        assertEquals("10^11以上 (もう少し)", texts(findTag<View>(d.frame, "sec-export-level")!!).first { it.contains("10^") })
        assertEquals("現在のデータベース: 起動画面からは書き出せません", t(d, "sec-export-current"))
        // strong ones apply without asking; すべて asks
        findTag<View>(d.frame, "sec-export-level")!!.performClick(); idle(); pickMenuRow("強い (10^12以上)")
        assertEquals(12, Security.exportMinLog10(a))
        findTag<View>(d.frame, "sec-export-level")!!.performClick(); idle(); pickMenuRow("すべて (強度を問わない)")
        assertTrue(texts(latestDialog().frame).joinToString().contains("「すべて」にすると"))
        clickButton(latestDialog(), "設定する")
        assertEquals(Security.EXPORT_ANY, Security.exportMinLog10(a))
        d.dismiss()
        // what each level lets out
        val unknown = FileInfo(FileInfo.Protection.PASSWORD, FileInfo.Strength.UNKNOWN)
        assertTrue(unknown.exportableAt(Security.EXPORT_ANY)); assertFalse(unknown.exportableAt(5))
        assertFalse(FileInfo(FileInfo.Protection.NONE, FileInfo.Strength.UNKNOWN).exportableAt(Security.EXPORT_ANY))
        assertFalse(FileInfo(FileInfo.Protection.KEYSTORE, FileInfo.Strength.UNKNOWN).exportableAt(Security.EXPORT_ANY))
        assertTrue(FileInfo(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK, 6).exportableAt(5))
        assertFalse(FileInfo(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK, 4).exportableAt(5))
        assertTrue(Security.launchExportEnabled(a))
        // the green list follows
        Security.setExportMinLog10(a, 10)
        assertFalse(a.store.info().exportableAt(Security.exportMinLog10(a)))
    }
}
