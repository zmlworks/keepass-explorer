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

/** 0.4.35: browsing another KDBX and exporting the encrypted file from the unlock screen, without unlocking the app's database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0435Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0435").mkdirs() }
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

    @Test fun launchScreen_browseAndExportButtons_inTheStack() {
        val un = lockWithStoredKey()
        val labels = (0 until un.stack.childCount).map { un.stack.getChildAt(it) }.filterIsInstance<android.widget.TextView>()
            .filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
        assertEquals(listOf("OS認証で解除", "マスターパスワード", "外部ファイルを閲覧", "外部に書き出す", "終了"), labels)
        val rs = listOf(un.bio, un.pwButton, un.browse, un.export, un.exit).map { screenRect(it) }
        for (r in rs) {
            assertEquals("70 % of the width", W_ * 0.7, r.width().toDouble(), W_ * 0.02)
            assertEquals("centred", W_ / 2.0, r.exactCenterX().toDouble(), W.dp(2).toDouble())
            assertEquals(W.dp(52), r.height())
            assertTrue(un.browse.background is io.github.zmlworks.kpexplorer.ui.SoftButton)
        }
        for (k in 1 until rs.size) assertTrue(rs[k - 1].bottom < rs[k].top)
        // the two file buttons are a group of their own: a wider gap above and below
        assertTrue(rs[2].top - rs[1].bottom > rs[3].top - rs[2].bottom)
        assertTrue(rs[4].top - rs[3].bottom > rs[3].top - rs[2].bottom)
        assertEquals("stack in the middle", H_ / 2.0, ((rs[0].top + rs[4].bottom) / 2).toDouble(), H_ * 0.08)
        shot("v0435/01-launch.png")
        L.setLang(app(), L.Lang.EN)
        a.lock(); idle(); layoutHost()
        val en = a.screen as UnlockScreen
        assertEquals(listOf("Browse external file", "Export"), listOf(en.browse, en.export).map { it.text.toString() })
    }

    @Test fun export_fromLaunch_copiesTheEncryptedFileAsItIs_withoutUnlocking() {
        val un = lockWithStoredKey()
        val file = a.store.read()
        un.export.performClick(); idle()
        val d = latestDialog()
        val name = "keepass-explorer-${java.time.LocalDate.now()}.kdbx"
        val msg = texts(d.frame).joinToString("\n")
        assertTrue(msg, msg.contains("暗号化したまま") && msg.contains(name) && msg.contains("今のマスターパスワード"))
        assertFalse("no ellipsis", msg.contains("…") || msg.contains("..."))
        Dlg.last = d; shot("v0435/02-export-explanation.png", dialog = true)
        val sink = java.io.ByteArrayOutputStream()
        val uri = android.net.Uri.parse("content://test.docs/document/out.kdbx")
        resolver.registerOutputStream(uri, sink)
        clickButton(d, "保存先を選ぶ")
        val i = answerPicker(uri)
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, i.action)
        assertEquals(name, i.getStringExtra(android.content.Intent.EXTRA_TITLE))
        until("written") { sink.size() > 0 }
        assertArrayEquals("byte for byte", file, sink.toByteArray())
        assertFalse("still locked", Session.isOpen); assertTrue(a.screen === un)
        // KeePass opens it with the master password
        assertTrue(Tree.findEntry(Kdbx.open(sink.toByteArray(), key), ids["Gmail"]!!) != null)
        assertArrayEquals("the app's file untouched", file, a.store.read())
        // cancelling the save screen writes nothing
        un.export.performClick(); idle(); clickButton(latestDialog(), "保存先を選ぶ")
        val st = org.robolectric.Shadows.shadowOf(a).nextStartedActivityForResult
        org.robolectric.Shadows.shadowOf(a).receiveResult(st.intent, android.app.Activity.RESULT_CANCELED, null); idle()
        assertFalse(Session.isOpen)
    }

    @Test fun export_refusesAFileThatIsNotKdbx() {
        val un = lockWithStoredKey()
        a.store.write("not a database".toByteArray())
        un.export.performClick(); idle(); clickButton(latestDialog(), "保存先を選ぶ")
        val sink = java.io.ByteArrayOutputStream()
        val uri = android.net.Uri.parse("content://test.docs/document/bad.kdbx")
        resolver.registerOutputStream(uri, sink)
        answerPicker(uri)
        until("error shown") { texts(latestDialog().frame).any { it.contains("書き出せませんでした") } }
        assertEquals(0, sink.size())
        assertFalse(Kdbx.isKdbx("not a database".toByteArray())); assertTrue(Kdbx.isKdbx(Kdbx.encode(Kdbx.create("x", key, light = true))))
    }

    @Test fun browse_fromLaunch_readOnly_ownDatabaseStaysLocked_closingLocksAgain() {
        val un = lockWithStoredKey()
        val file = a.store.read()
        // wrong password: a message, nothing opened
        openExternalFromLaunch(un, "nope")
        until("message") { texts(latestDialog().frame).any { it == s(R.string.temp_wrong_password) } }
        clickButton(latestDialog(), "OK")
        assertFalse(Session.isOpen); assertTrue(a.screen is UnlockScreen)
        // the password box says it opens read-only and the app's database stays locked (not "the current password")
        un.browse.performClick(); idle()
        val (uri, _) = externalFile(); answerPicker(uri)
        val ask = texts(latestDialog().frame).joinToString("\n")
        assertTrue(ask, ask.contains("「外部.kdbx」のパスワード") && ask.contains("ロックしたまま"))
        assertFalse(ask.contains("今のパスワードでは"))
        Dlg.last = latestDialog(); shot("v0435/03-external-password.png", dialog = true)
        latestDialog().dismiss(); idle()
        // right password: the read-only view of that file
        openExternalFromLaunch(un, "other-pass")
        until("opened") { a.screen is ExplorerScreen }
        idle(); layoutHost()
        assertTrue(Session.readOnlyTemp); assertTrue(Session.tempOverLocked)
        assertTrue(names().contains("外部の鍵")); assertFalse(names().contains("Gmail"))
        assertEquals("外部.kdbx", Session.tempSourceLabel)
        shot("v0435/04-external-from-launch.png")
        try { Session.update { it }; fail("read-only") } catch (_: IllegalStateException) {}
        assertTrue(a.explorer!!.mainMenu().first().label == s(R.string.temp_exit))
        // close: back to the unlock screen, locked, nothing of the external file kept
        a.explorer!!.mainMenu().first().action()
        idle(); layoutHost()
        assertTrue(a.screen is UnlockScreen)
        assertFalse(Session.isOpen); assertNull(Session.db); assertFalse(Session.readOnlyTemp); assertFalse(Session.tempOverLocked)
        assertNull(a.explorer)
        assertArrayEquals("the app's file untouched", file, a.store.read())
        // and the app's own database still opens normally
        val un2 = a.screen as UnlockScreen
        un2.pw.setText("sample"); un2.openWithPassword()
        until("unlocked") { a.screen is ExplorerScreen }
        idle(); layoutHost()
        assertFalse(Session.readOnlyTemp); assertTrue(names().contains("Gmail"))
    }

    @Test fun browse_fromLaunch_backKeyOrLock_endsLocked() {
        var un = lockWithStoredKey()
        openExternalFromLaunch(un, "other-pass"); until("opened") { a.screen is ExplorerScreen }
        @Suppress("DEPRECATION") a.onBackPressed(); idle()
        assertTrue(a.screen is UnlockScreen); assertFalse(Session.isOpen); assertFalse(a.isFinishing)
        un = a.screen as UnlockScreen
        openExternalFromLaunch(un, "other-pass"); until("opened") { a.screen is ExplorerScreen }
        a.lock(); idle()
        assertTrue(a.screen is UnlockScreen); assertFalse(Session.isOpen); assertFalse(Session.tempOverLocked)
        // the ordinary read-only view (from the unlocked app) still returns to the app's database
        un = a.screen as UnlockScreen
        un.pw.setText("sample"); un.openWithPassword(); until("unlocked") { a.screen is ExplorerScreen }
        val (_, bytes) = externalFile()
        Session.beginTempView(Kdbx.open(bytes, otherKey), "x.kdbx"); assertFalse(Session.tempOverLocked)
        Session.exitTempView()
        assertTrue(Session.isOpen); assertTrue(Tree.findEntry(Session.db!!, ids["Gmail"]!!) != null)
    }
}
