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

/** 0.4.29: ごみ箱の設定「階層表示でごみ箱をいつも一番下にする」, memo text centred in the page height (rules back under the baselines). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0429Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0429").mkdirs() }
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

    @Test fun binSetting_lastInTree_canBeTurnedOff_storedInTheDatabase() {
        val ex = a.explorer!!
        val bin = bin()
        ex.moveIndex(bin, 0); idle(); layoutHost()
        assertEquals("ごみ箱", listFolders().first())
        assertTrue(Tree.binLastInTree(Session.db!!))
        assertEquals("default (0.4.28): last in the tree", "ごみ箱", treeTop().last())
        BinSettings.show(a); idle()
        val cb = findTag<View>(Dlg.last!!.frame, "bin-tree-last") as android.widget.CheckBox
        assertTrue(cb.isChecked)
        assertEquals("階層表示でごみ箱をいつも一番下にする", cb.text.toString())
        cb.performClick(); idle()
        assertFalse(Tree.binLastInTree(Session.db!!))
        assertEquals("false", Session.db!!.content.meta.customData[Tree.CD_BIN_TREE_LAST]?.value)
        assertEquals("off: where the list has it", "ごみ箱", treeTop().first())
        val cb2 = findTag<View>(Dlg.last!!.frame, "bin-tree-last") as android.widget.CheckBox
        assertFalse(cb2.isChecked)
        shot("v0429/01-bin-setting.png", dialog = true)
        // saved in the file: survives a save and reopen
        Session.flush(); idle()
        val again = Kdbx.open(App.store(app()).read(), key)
        assertFalse(Tree.binLastInTree(again))
        // the 「フォルダーを指定して移動」 tree follows the setting too
        ex.moveToDialog(ids["Gmail"]!!); idle()
        val mt = find(latestDialog().frame, io.github.zmlworks.kpexplorer.ui.FolderTree::class.java)!!
        val root = Tree.root(Session.db!!).uuid
        assertEquals(bin, mt.rows.map { it.tag as UUID }.first { Tree.parentOf(Tree.root(Session.db!!), it)?.uuid == root })
        latestDialog().dismiss(); idle()
        // back on: the default again (the key is removed)
        BinSettings.show(a); idle()
        (findTag<View>(Dlg.last!!.frame, "bin-tree-last") as android.widget.CheckBox).performClick(); idle()
        assertTrue(Tree.binLastInTree(Session.db!!))
        assertNull(Session.db!!.content.meta.customData[Tree.CD_BIN_TREE_LAST])
        assertEquals("ごみ箱", treeTop().last())
        Dlg.last?.dismiss()
    }

    @Test fun binSetting_readOnlyAndEnglish() {
        L.setLang(app(), L.Lang.EN)
        BinSettings.show(a); idle()
        val cb = findTag<View>(Dlg.last!!.frame, "bin-tree-last") as android.widget.CheckBox
        assertEquals("Always show the recycle bin last in the folder tree", cb.text.toString())
        assertTrue(texts(Dlg.last!!.frame).any { it.startsWith("When off, the folder tree shows the bin where it is in the list") })
        Dlg.last!!.dismiss()
    }

    @Test fun memo_short_sitsInTheMiddleOfThePage_leftAligned_rulesAllOver() {
        setMemo("・牛乳\n・卵\n・食パン")
        a.show(MemoScreen(a, memoId())); idle(); draw()
        val rt = ruled()
        assertEquals(3, rt.lineCount)
        assertCentred(rt)
        assertEquals(android.view.Gravity.LEFT, android.view.Gravity.getAbsoluteGravity(rt.gravity, View.LAYOUT_DIRECTION_LTR) and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals("left edge as before", rt.paddingLeft.toFloat(), rt.layout.getLineLeft(0) + rt.paddingLeft, 0.5f)
        // rules: just under each baseline (as 0.4.27), and in step above the text up to the top
        for (i in 0 until rt.lineCount) assertEquals(rt.getLineBounds(i, android.graphics.Rect()) + W.dp(4), rt.ruleYs[i])
        val step = rt.ruleYs[1] - rt.ruleYs[0]
        assertTrue("rules above: ${rt.ruleYsAbove}", rt.ruleYsAbove.size >= 3)
        assertEquals(step, rt.ruleYs[0] - rt.ruleYsAbove[0])
        rt.ruleYsAbove.zipWithNext().forEach { (x, y) -> assertEquals(step, x - y) }
        assertTrue(rt.ruleYsAbove.last() >= rt.paddingTop)
        assertTrue("rules reach the bottom", rt.ruleYs.last() + rt.lineHeight >= rt.height)
        shot("v0429/02-memo-short-centred.png")
        // a tap above the text puts the cursor in the first line, below it in the last line (EditText's own mapping)
        val (top, bottom) = box(rt)
        val o1 = rt.getOffsetForPosition(W.dp(60).toFloat(), (top / 2).toFloat())
        assertTrue("above → first line: $o1", rt.layout.getLineForOffset(o1) == 0)
        val o2 = rt.getOffsetForPosition(W.dp(60).toFloat(), ((bottom + rt.height) / 2).toFloat())
        assertEquals(rt.lineCount - 1, rt.layout.getLineForOffset(o2))
    }

    @Test fun memo_editingWithTheKeyboard_staysCentredInWhatIsLeft_typingKeepsRulesUnderLines() {
        setMemo("・牛乳\n・卵")
        a.show(MemoScreen(a, memoId())); idle(); draw()
        val rt = ruled()
        rt.requestFocus(); rt.setSelection(rt.text.length); idle()
        // the window shrinks above the keyboard (adjustResize: MainActivity pads the host by the IME height)
        val ime = H_ * 2 / 5
        a.host.setPadding(0, 0, 0, ime); draw()
        assertCentred(rt)
        rt.text.append("\n・食パン"); idle(); draw()
        assertEquals(3, rt.lineCount); assertCentred(rt)
        for (i in 0 until rt.lineCount) assertEquals(rt.getLineBounds(i, android.graphics.Rect()) + W.dp(4), rt.ruleYs[i])
        val cl = rt.layout.getLineForOffset(rt.selectionEnd)
        assertEquals("cursor on the new line", 2, cl)
        val r = android.graphics.Rect(); rt.getLineBounds(cl, r)
        assertTrue("cursor line visible", r.bottom - rt.scrollY <= rt.height)
        // picture: the keyboard's area drawn grey (Robolectric has no keyboard)
        layoutHost()
        val b = Bitmap.createBitmap(W_, H_, Bitmap.Config.ARGB_8888); val c = Canvas(b); a.host.draw(c)
        c.drawRect(0f, (H_ - ime).toFloat(), W_.toFloat(), H_.toFloat(), android.graphics.Paint().apply { color = 0xFF9AA0A6.toInt() })
        c.drawText("(keyboard)", W_ / 2f - 150, H_ - ime / 2f, android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = 60f })
        FileOutputStream(File(out, "v0429/03-memo-editing-keyboard.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        a.host.setPadding(0, 0, 0, 0)
    }

    @Test fun memo_long_startsAtTheTop_andScrolls() {
        setMemo((1..80).joinToString("\n") { "行 $it" })
        a.show(MemoScreen(a, memoId())); idle(); draw()
        val rt = ruled()
        val (top, _) = box(rt)
        assertEquals("from the top as before", rt.paddingTop, top)
        assertTrue(rt.ruleYsAbove.isEmpty())
        rt.scrollTo(0, rt.lineHeight * 30); draw()
        assertTrue(rt.scrollY > 0)
        assertTrue("rules reach the bottom after scrolling", rt.ruleYs.last() + rt.lineHeight >= rt.scrollY + rt.height)
    }

    @Test fun previewMemo_staysAtTheTop() {
        val ex = a.explorer!!
        setMemo("・牛乳"); ex.refresh(); ex.select(memoId()); idle(); draw()
        val rt = find(ex.view, io.github.zmlworks.kpexplorer.ui.Parts.RuledText::class.java)!!
        val (top, _) = box(rt)
        assertEquals(rt.paddingTop, top)
        assertTrue(rt.ruleYsAbove.isEmpty())
    }
}
