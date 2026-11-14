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

/** 0.4.25: preview password 「表示する」 in place of ******; bundled monospace font for field values (glyph widths measured). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0425Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0425").mkdirs() }
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
    private fun mono() = io.github.zmlworks.kpexplorer.ui.Mono.typeface(app())
    private fun widths(tf: android.graphics.Typeface, vararg s: String): List<Float> {
        val p = android.graphics.Paint().apply { typeface = tf; textSize = 48f }
        return s.map { p.measureText(it) }
    }
    private fun setValues(id: UUID) {
        Session.update { Order.saveEntry(it, id, mapOf("Title" to "Gmail", "UserName" to "iiii.WWWW@gmail.com", "Password" to "iI1l|0Oo-WWmm",
            "URL" to "https://mail.google.com", "Notes" to "メモ本文", "暗証番号" to "0O1lI 日本語"), emptyList(), null) }
    }

    @Test fun monospaceFont_isReallyMonospace_measured() {
        val m = mono()
        assertNotSame("bundled font, not the system 'monospace' family", android.graphics.Typeface.MONOSPACE, m)
        val (i, w, d, z) = widths(m, "iiii", "WWWW", "....", "0O1l")
        assertEquals(i, w, 0.01f); assertEquals(i, d, 0.01f); assertEquals(i, z, 0.01f)
        val (pi, pw) = widths(android.graphics.Typeface.DEFAULT, "iiii", "WWWW")
        assertTrue("the normal font is proportional: $pi vs $pw", pw > pi * 1.5f)
        // no ligatures: "->", "!=" and "www" are as wide as their characters one by one
        val one = widths(m, "-")[0]
        assertEquals(2 * one, widths(m, "->")[0], 0.01f); assertEquals(2 * one, widths(m, "!=")[0], 0.01f)
        // Japanese falls back to a system font (drawn, not blank)
        val b = android.graphics.Bitmap.createBitmap(200, 80, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(b).drawText("日本", 10f, 60f, android.graphics.Paint().apply { typeface = m; textSize = 48f; color = android.graphics.Color.BLACK })
        var ink = 0; for (x in 0 until 200) for (y in 0 until 80) if (android.graphics.Color.alpha(b.getPixel(x, y)) > 0) ink++
        assertTrue("fallback glyphs drawn: $ink", ink > 200)
    }

    @Test fun keyPage_and_preview_valuesUseTheMonospaceFont_measuredOnTheViews() {
        val ex = a.explorer!!; layoutHost()
        val id = ids["Gmail"]!!
        setValues(id); ex.refresh(); ex.select(id); idle(); layoutHost()
        val tv = allViews(ex.view).filterIsInstance<android.widget.TextView>()
        val user = tv.first { it.text.toString() == "iiii.WWWW@gmail.com" }
        assertSame(mono(), user.typeface)
        assertEquals("i and W cells equal in the preview", user.paint.measureText("iiii"), user.paint.measureText("WWWW"), 0.01f)
        val title = tv.first { it.text.toString() == "Gmail" && it.paint.textSize < W.dp(16) }
        assertTrue("labels / titles stay proportional", title.paint.measureText("WWWW") > title.paint.measureText("iiii") * 1.5f)
        shot("v0425/03-preview-monospace.png")
        val ks = openKey(id)
        val ed = allViews(ks.view).filterIsInstance<android.widget.EditText>()
        for (v in listOf("iiii.WWWW@gmail.com", "iI1l|0Oo-WWmm", "https://mail.google.com", "0O1lI 日本語")) {
            val f = ed.first { it.text.toString() == v }
            assertSame(v, mono(), f.typeface)
            assertEquals(v, f.paint.measureText("iiii"), f.paint.measureText("WWWW"), 0.01f)
        }
        val t = ed.first { it.text.toString() == "Gmail" }
        assertTrue(t.paint.measureText("WWWW") > t.paint.measureText("iiii") * 1.5f)
        // reveal the password on the key page for the picture
        (ed.first { it.text.toString() == "iI1l|0Oo-WWmm" }).let { io.github.zmlworks.kpexplorer.ui.FieldBox.mask(it, false) }
        layoutHost(); shot("v0425/04-key-page-monospace.png")
        // new key page: the password well
        a.show(NewKeyScreen(a, Tree.root(Session.db!!).uuid)); idle(); layoutHost()
        assertTrue(allViews(a.host).filterIsInstance<android.widget.EditText>().any { it.typeface === mono() })
    }

    @Test fun preview_password_showTextInPlaceOfStars_noHide() {
        val ex = a.explorer!!; layoutHost()
        val id = ids["Gmail"]!!
        setValues(id); ex.refresh(); ex.select(id); idle(); layoutHost()
        val t = findTag<View>(ex.view, "pass") as android.widget.TextView
        assertEquals("表示する", t.text.toString())
        assertFalse("no ****** any more", allViews(ex.view).filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("******") ||
            (it.transformationMethod?.getTransformation(it.text, it)?.toString()?.contains("*") == true) })
        val tl = IntArray(2); t.getLocationInWindow(tl)
        val row = t.parent as View
        assertTrue("small target: ${t.width} of ${row.width}", t.width < row.width / 2)
        shot("v0425/01-preview-show-link.png")
        // tap the link → the password in its place (monospace), nothing to hide it again
        tapView(t); layoutHost()
        val v = findTag<View>(ex.view, "pass-value") as android.widget.TextView
        assertSame(t, v)
        assertEquals("iI1l|0Oo-WWmm", v.text.toString())
        assertSame(mono(), v.typeface)
        assertFalse(v.isClickable)
        assertNull(findTag<View>(ex.view, "pass"))
        assertFalse(allViews(ex.view).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "隠す" })
        assertFalse("the link tap was not a preview tap", ex.previewSelected)
        shot("v0425/02-preview-shown.png")
        // a tap on the shown password = ordinary preview tap
        tapView(v); assertTrue(ex.previewSelected)
        // another selection and back: hidden again
        ex.select(ids["Amazon"]!!); idle(); ex.select(id); idle(); layoutHost()
        assertEquals("表示する", (findTag<View>(ex.view, "pass") as android.widget.TextView).text.toString())
        // empty password: nothing to show
        ex.select(ids["S1"]!!); idle()
        Session.update { Order.saveEntry(it, ids["S1"]!!, mapOf("Title" to "サービス01", "UserName" to "user1", "Password" to ""), emptyList(), null) }
        ex.refresh(); ex.select(ids["S1"]!!); idle()
        assertNull(findTag<View>(ex.view, "pass"))
    }

    @Test fun english_show() {
        L.setLang(app(), L.Lang.EN)
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val act = ctl.get(); if (act.explorer == null) act.showExplorer(); idle()
        val ex = act.explorer!!
        ex.select(ids["Gmail"]!!); idle()
        val t = findTag<View>(ex.view, "pass") as android.widget.TextView
        assertEquals("Show", t.text.toString())
        t.performClick(); assertEquals("p", t.text.toString())
    }

    @Test fun notices_listTheFont() {
        val n = app().assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText()
        assertTrue(n.contains("JetBrains Mono") && n.contains("SIL OPEN FONT LICENSE Version 1.1"))
    }
}
