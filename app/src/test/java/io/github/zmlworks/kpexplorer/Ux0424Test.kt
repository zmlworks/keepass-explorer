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

/** 0.4.24: one cushion between areas, striped tree, fuller tree / folder menus, 表示する for the preview password, monospace values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0424Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0424").mkdirs() }
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

    private fun rowBg(id: UUID) = ((a.explorer!!.folderTree!!.rows.first { it.tag == id }).background as android.graphics.drawable.ColorDrawable).color
    private fun allViews(v: View, acc: MutableList<View> = ArrayList()): List<View> {
        acc.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) allViews(v.getChildAt(i), acc); return acc
    }
    private fun tapView(v: View) {
        val l = IntArray(2); v.getLocationInWindow(l)
        val x = l[0] + v.width / 2f; val y = l[1] + v.height / 2f
        touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y); idle()
    }
    private fun treeRow(id: UUID) = a.explorer!!.folderTree!!.rows.first { it.tag == id }

    @Test fun cushion_treeTapWhileAFileIsSelected_onlyClearsTheSelection() {
        val ex = a.explorer!!; layoutHost()
        val root = Tree.root(Session.db!!).uuid
        val g = ids["G"]!!
        ex.select(ids["Gmail"]!!); idle(); layoutHost()
        // a real finger tap on 仕事 in the tree: only the selection goes (the preview closes)
        tapView(treeRow(g)); layoutHost()
        assertNull(ex.selection); assertEquals(root, ex.current)
        // the next tap opens it
        tapView(treeRow(g)); layoutHost()
        assertEquals(g, ex.current)
        // nothing selected → one tap acts at once
        ex.folderTree!!.tap(root); idle()
        assertEquals(root, ex.current)
        // +/− too
        ex.select(ids["Amazon"]!!); idle()
        val before = ex.folderTree!!.isExpanded(Tree.findGroup(Session.db!!, g)!!)
        ex.folderTree!!.toggle(g); idle()
        assertNull(ex.selection)
        assertEquals("first tap: no toggle", before, ex.folderTree!!.isExpanded(Tree.findGroup(Session.db!!, g)!!))
        ex.folderTree!!.toggle(g); idle()
        assertEquals(!before, ex.folderTree!!.isExpanded(Tree.findGroup(Session.db!!, g)!!))
    }

    @Test fun cushion_longPressAndDragOnTheTree_stillWorkWhileSelected() {
        val ex = a.explorer!!; layoutHost()
        val id = ids["Gmail"]!!
        ex.select(id); idle()
        assertTrue(ex.folderTree!!.longPress(ids["G"]!!)); idle()
        assertTrue(menuShowing())
        assertEquals("a long press is not a cushion tap", id, ex.selection)
        Menus.dismiss()
        // a drop on a tree row works with a selection
        ex.performDrop(id, ExplorerScreen.Drop(M.INTO, ids["G"]!!, pathFolder = true)); idle()
        assertEquals(ids["G"]!!, Tree.parentOf(Tree.root(Session.db!!), id)!!.uuid)
    }

    @Test fun cushion_previewHasFocus_listTapOnlyGivesFocusBack() {
        val ex = a.explorer!!; layoutHost()
        ex.select(ids["Gmail"]!!); idle()
        ex.onPreviewTap(); idle()
        assertTrue(ex.previewSelected)
        ex.tap(row(ids["Amazon"]!!)); idle()
        assertFalse(ex.previewSelected)
        assertEquals("still Gmail", ids["Gmail"]!!, ex.selection)
        ex.tap(row(ids["Amazon"]!!)); idle()
        assertEquals(ids["Amazon"]!!, ex.selection)
        // without the preview focus: list taps act as before (select, then open on the second tap)
        ex.tap(row(ids["Steam"]!!)); idle()
        assertEquals(ids["Steam"]!!, ex.selection)
    }

    @Test fun tree_rowsAlternateWhiteAndPaleBlue_selectionStillStandsOut() {
        val ex = a.explorer!!
        ex.navigate(ids["G"]!!); idle(); layoutHost()
        val rows = ex.folderTree!!.rows
        rows.forEachIndexed { i, v -> assertEquals("row $i", if (i % 2 == 0) W.WINDOW else io.github.zmlworks.kpexplorer.ui.STRIPE, (v.background as android.graphics.drawable.ColorDrawable).color) }
        assertNotEquals(rowBg(rows[0].tag as UUID), rowBg(rows[1].tag as UUID))
        shot("v0424/01-striped-tree.png")
        // the move dialog's tree too
        ex.moveToDialog(ids["Gmail"]!!); idle()
        val t = find(Dlg.last!!.frame, io.github.zmlworks.kpexplorer.ui.FolderTree::class.java)!!
        assertEquals(io.github.zmlworks.kpexplorer.ui.STRIPE, (t.rows[1].background as android.graphics.drawable.ColorDrawable).color)
        Dlg.last!!.dismiss()
    }

    @Test fun treeMenu_folder_root_bin() {
        val ex = a.explorer!!; layoutHost()
        val root = Tree.root(Session.db!!).uuid
        val g = ids["G"]!!
        assertEquals(listOf("開く", "下部ファイルをすべて表示", "|", "新しいフォルダ", "新しい鍵", "新しいメモ", "|",
            "名前の変更", "切り取り", "ここに貼り付け", "フォルダーを指定して移動", "|", "削除"), labels(ex.treeMenu(g)))
        assertEquals(listOf("開く", "下部ファイルをすべて表示", "|", "新しいフォルダ", "新しい鍵", "新しいメモ", "|", "ここに貼り付け"), labels(ex.treeMenu(root)))
        assertEquals(listOf("開く", "下部ファイルをすべて表示", "|", "ごみ箱を空にする", "ごみ箱の設定…"), labels(ex.treeMenu(bin())))
        assertFalse("paste: nothing cut", ex.treeMenu(g).first { it.label == "ここに貼り付け" }.enabled)
        val row = treeRow(g)
        val l = IntArray(2); row.getLocationInWindow(l)
        assertTrue(ex.folderTree!!.longPress(g)); idle()
        shot("v0424/02-tree-menu.png", menuAt = (l[0] + W.dp(90)) to (l[1] + row.height / 2))
        Menus.dismiss()
        // new memo here: opens that folder and asks the name
        item(ex.treeMenu(ids["メール"]!!), "新しいメモ").action(); idle()
        assertEquals(ids["メール"]!!, ex.current)
        latestDialog().buttonViews.first().performClick(); idle()
        assertEquals(3, Tree.findGroup(Session.db!!, ids["メール"]!!)!!.entries.size)
        // cut 銀行 in the tree, paste into 仕事 from the tree
        val bank = Tree.root(Session.db!!).groups.first { it.name == "銀行" }.uuid
        item(ex.treeMenu(bank), "切り取り").action(); idle()
        item(ex.treeMenu(g), "ここに貼り付け").action(); idle()
        assertEquals(g, Tree.parentOf(Tree.root(Session.db!!), bank)!!.uuid)
        // rename from the tree
        item(ex.treeMenu(bank), "名前の変更").action(); idle()
        find(latestDialog().frame, android.widget.EditText::class.java)!!.setText("口座")
        latestDialog().buttonViews.first().performClick(); idle()
        assertEquals("口座", Tree.findGroup(Session.db!!, bank)!!.name)
        // delete the open folder's parent from the tree → to the bin, and the view goes to its parent
        ex.navigate(ids["メール"]!!); idle()
        item(ex.treeMenu(g), "削除").action(); idle()
        assertTrue(Tree.isInRecycleBin(Session.db!!, g))
        assertEquals(root, ex.current)
    }

    @Test fun listFolderMenu_hasShowAllBelow() {
        val ex = icons()
        val g = ids["G"]!!
        val m = labels(ex.itemMenu(row(g)))
        assertTrue(m.toString(), m.contains("下部ファイルをすべて表示"))
        assertTrue(labels(ex.itemMenu(row(bin()))).contains("下部ファイルをすべて表示"))
        assertFalse("not for keys", labels(ex.itemMenu(row(ids["Gmail"]!!))).contains("下部ファイルをすべて表示"))
        longPressAt(grid(), g)
        shot("v0424/03-list-folder-menu.png", menuAt = Menus.lastPlace!!.left to Menus.lastPlace!!.top)
        Menus.dismiss(); idle()
        item(ex.itemMenu(row(g)), "下部ファイルをすべて表示").action(); idle()
        assertTrue(ex.flat); assertEquals(g, ex.current)
        assertEquals(3, ex.rows.size)
    }

    // preview password: changed in 0.4.25 (「表示する」 in place of ******) — see Ux0425Test

    @Test fun monospace_onlyForFieldValues() {
        val ex = a.explorer!!; layoutHost()
        val id = ids["Gmail"]!!
        Session.update { Order.saveEntry(it, id, mapOf("Title" to "Gmail", "UserName" to "taro.yamada@gmail.com", "Password" to "p", "URL" to "https://mail.google.com", "Notes" to "メモ本文", "暗証番号" to "0O1lI"), emptyList(), null) }
        ex.refresh(); ex.select(id); idle(); layoutHost()
        val tv = allViews(ex.view).filterIsInstance<android.widget.TextView>()
        fun font(s: String) = tv.first { it.text.toString() == s }.typeface
        assertEquals(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), font("taro.yamada@gmail.com"))
        assertEquals(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), font("https://mail.google.com"))
        assertEquals(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), font("0O1lI"))
        assertNotEquals("labels stay", io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), tv.first { it.text.toString() == "User:" }.typeface)
        assertNotEquals("notes stay", io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), font("メモ本文"))
        shot("v0424/06-preview-monospace.png")
        val ks = openKey(id)
        val ed = allViews(ks.view).filterIsInstance<android.widget.EditText>()
        fun ef(s: String) = ed.first { it.text.toString() == s }.typeface
        assertEquals(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), ef("taro.yamada@gmail.com"))
        assertEquals(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), ef("https://mail.google.com"))
        assertEquals(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), ef("0O1lI"))
        assertEquals(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), ef("p"))
        assertNotEquals("title stays", io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), ef("Gmail"))
        assertNotEquals("notes stay", io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), ef("メモ本文"))
        shot("v0424/07-key-page-monospace.png")
        // new key page: user name / password / URL in monospace too
        a.show(NewKeyScreen(a, Tree.root(Session.db!!).uuid)); idle(); layoutHost()
        assertTrue(KeyFields.monospace(Order.F_USER) && KeyFields.monospace(Order.F_URL) && KeyFields.monospace("暗証番号"))
        assertFalse(KeyFields.monospace(Order.F_TITLE) || KeyFields.monospace(Order.F_NOTES) || KeyFields.monospace(Order.F_TAGS))
    }

    @Test fun english_labels() {
        L.setLang(app(), L.Lang.EN)
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val act = ctl.get(); if (act.explorer == null) act.showExplorer(); idle()
        val ex = act.explorer!!
        assertEquals(listOf("Open", "Show all files below", "|", "New folder", "New key", "New memo", "|", "Rename", "Cut", "Paste here", "Move to a chosen folder", "|", "Delete"),
            labels(ex.treeMenu(ids["G"]!!)))
        ex.select(ids["Gmail"]!!); idle()
        assertEquals("Show", (findTag<View>(ex.view, "pass") as android.widget.TextView).text.toString())
    }
}
