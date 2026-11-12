package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
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

/** 0.4.14: https:// for bare URLs, カスタム項目の追加, root label, draggable bin, [OK] footer, view items in the file menu, dotted memo rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0414Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0414").mkdirs() }
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
        for ((t, u) in listOf("Gmail" to "taro.yamada@gmail.com", "Amazon" to "taro_y", "家のWi-Fi" to "MyHome-5G",
                "ネット銀行 (メイン口座)" to "1234-5678-9012", "会社のVPN" to "t.yamada", "Steam" to "taro_games",
                "楽天" to "taro.r", "X (Twitter)" to "@taro")) {
            val e = Tree.newEntry(Tree.Fields(t, u, "p", "", "", emptyList())); db = Tree.addEntry(db, root, e); ids[t] = e.uuid
        }
        db = Tree.addEntry(db, root, Order.newMemo("買い物メモ"))
        val old = Tree.newEntry(Tree.Fields("古いアカウント", "old", "x", "", "", emptyList())); db = Tree.addEntry(db, g.uuid, old); ids["old"] = old.uuid
        db = Tree.moveToBin(db, old.uuid, "ごみ箱")
        App.store(app()).write(Kdbx.encodeVerified(db, keepSalt = true))
        Session.open(db); Session.store = App.store(app())
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        if (!Session.isOpen) Session.open(db)
        Session.store = a.store
        if (a.explorer == null) a.showExplorer(); idle()
    }
    @After fun tearDown() { runCatching { FieldEditSession.commitActive() }; Session.lock(); Vault.forceAvailableForTest = null; Menus.dismiss(); Dlg.last = null }

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

    // ---------------------------------------------------------------- 1 URL
    @Test fun url_domainOnly_getsHttps_schemesUntouched() {
        assertEquals("https://example.com/login", UrlCheck.safe("example.com/login"))
        assertEquals("https://www.example.com", UrlCheck.safe(" www.example.com "))
        assertEquals("https://example.com/x", UrlCheck.safe("//example.com/x"))
        assertEquals("https://example.com:8443/a?b=1#c", UrlCheck.safe("example.com:8443/a?b=1#c"))
        assertEquals("https://localhost:3000", UrlCheck.safe("localhost:3000"))
        assertEquals("https://192.168.1.1/admin", UrlCheck.safe("192.168.1.1/admin"))
        assertEquals("http://x.jp/", UrlCheck.safe("http://x.jp/"))
        assertEquals("HTTPS://Example.com", UrlCheck.safe("HTTPS://Example.com"))
        for (bad in listOf("mailto:a@example.com", "otpauth://totp/x?secret=AAAA", "intent://x#Intent;end", "cmd://ls",
                "javascript:alert(1)", "tel:0312345678", "file:///sdcard/x", "メモ", "memo", "", "example .com", "https://"))
            assertNull(bad, UrlCheck.safe(bad))
    }

    @Test fun url_swipeOpen_prependsHttps_storedValueUnchanged() {
        val ks = KeyScreen(a, ids["Gmail"], Tree.root(Session.db!!).uuid); a.show(ks); idle(); layoutHost()
        ks.boxes["URL"]!!.field.setText("mail.google.com/mail"); assertTrue(ks.save(silent = true)); idle()
        ks.confirmOpenUrl(ks.values["URL"]!!); idle()
        assertTrue(texts(Dlg.last!!.frame).any { it.contains("https://mail.google.com/mail") })
        Dlg.last!!.buttonViews.first().performClick(); idle()
        val i = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        assertEquals("https://mail.google.com/mail", i.dataString)
        assertEquals("mail.google.com/mail", ks.values["URL"]); assertFalse(ks.dirty)
        assertEquals("mail.google.com/mail", Tree.findEntry(Session.db!!, ids["Gmail"]!!)!!.second.fields["URL"]!!.content)
    }

    // ---------------------------------------------------------------- 2 label
    @Test fun customFieldLabel_everywhere() {
        val ks = KeyScreen(a, ids["Gmail"], Tree.root(Session.db!!).uuid); a.show(ks); idle(); layoutHost()
        assertTrue(texts(a.host).contains("カスタム項目の追加")); assertTrue(texts(a.host).contains("項目の編集"))
        assertTrue(texts(a.host).none { it == "項目を追加" })
        assertTrue(ks.menu().any { it.label == "カスタム項目の追加" })
        ks.addCustom(); idle()
        assertTrue(texts(Dlg.last!!.frame).contains("カスタム項目の追加")); Dlg.last!!.dismiss(); idle()
        a.show(NewKeyScreen(a, Tree.root(Session.db!!).uuid)); idle(); layoutHost()
        assertTrue(texts(a.host).contains("カスタム項目の追加")); assertTrue(texts(a.host).none { it == "項目を追加" })
    }

    // ---------------------------------------------------------------- 3 root name
    @Test fun rootLabel_alwaysSlash_evenWhenTheFileNamesIt() {
        assertEquals("/", io.github.zmlworks.kpexplorer.ui.folderLabel(0, ""))
        assertEquals("/", io.github.zmlworks.kpexplorer.ui.folderLabel(0, "Root"))
        assertEquals("(名前なし)", io.github.zmlworks.kpexplorer.ui.folderLabel(1, ""))
        assertEquals("仕事", io.github.zmlworks.kpexplorer.ui.folderLabel(1, "仕事"))
        // an imported KeePassXC-style file: root group "Root" → shown as "/" in the tree and the title
        Session.update { d -> Tree.renameGroup(d, Tree.root(d).uuid, "Root") }; idle()
        val ex = a.explorer!!; ex.refresh(); idle(); layoutHost()
        assertEquals("/", ex.groupLabel(Tree.root(Session.db!!)))
        shot("v0414/04-root-slash.png")
        assertEquals("the file keeps its own name", "Root", Tree.root(Session.db!!).name)
    }

    // ---------------------------------------------------------------- 4 bin drag
    @Test fun recycleBin_reorderInRoot_neverIntoAFolder() {
        val ex = icons()
        val bin = Tree.recycleBinId(Session.db!!)!!
        val root = Tree.root(Session.db!!).uuid
        // drag the bin before Gmail
        var (x, y) = cellCenter(grid(), bin)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        val (tx, ty) = cellCenter(grid(), ids["Steam"]!!, fx = 0.2f)
        touch(MotionEvent.ACTION_MOVE, tx, ty)
        assertTrue(ex.dragging); assertFalse(menuShowing())
        shot("v0414/05-bin-dragging.png")
        touch(MotionEvent.ACTION_UP, tx, ty); idle()
        var o = order(); assertEquals(o.indexOf(ids["Steam"]!!) - 1, o.indexOf(bin))
        // drop on the middle of a folder: no "into" for the bin — it is placed next to the folder instead
        layoutHost()
        cellCenter(grid(), bin).let { x = it.first; y = it.second }
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        val (fx, fy) = cellCenter(grid(), ids["G"]!!)
        touch(MotionEvent.ACTION_MOVE, fx, fy)
        assertNotEquals(ExplorerScreen.Drop.Mode.INTO, ex.hit(bin, fx, fy)?.mode)
        touch(MotionEvent.ACTION_UP, fx, fy); idle()
        assertEquals(root, Tree.parentOf(Tree.root(Session.db!!), bin)!!.uuid)
        o = order(); assertTrue("next to 仕事", kotlin.math.abs(o.indexOf(bin) - o.indexOf(ids["G"]!!)) == 1)
        // the path tree is never a target for the bin; the model refuses other folders too
        assertTrue(ex.pathLines.isNotEmpty())
        ex.pathLines.forEach { line -> val l = IntArray(2); line.getLocationOnScreen(l)
            val d = ex.hit(bin, l[0] + line.width / 2f, l[1] + line.height / 2f); assertTrue("$d", d == null || !d.pathFolder) }
        try { Order.moveTo(Session.db!!, bin, ids["G"]!!); fail("moved into a folder") } catch (_: Tree.TreeException) {}
        ex.performDrop(bin, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.INTO, ids["G"]!!)); idle()
        assertEquals(root, Tree.parentOf(Tree.root(Session.db!!), bin)!!.uuid)
        // long-press menu unchanged
        assertTrue(labels(ex.itemMenu(row(bin))).contains(s(R.string.empty_recycle_bin)))
        // other items can still be dropped into the bin
        val gx = cellCenter(grid(), ids["Gmail"]!!); val bx = cellCenter(grid(), bin)
        touch(MotionEvent.ACTION_DOWN, gx.first, gx.second); hold()
        touch(MotionEvent.ACTION_MOVE, bx.first, bx.second); touch(MotionEvent.ACTION_UP, bx.first, bx.second); idle()
        assertTrue(Tree.isInRecycleBin(Session.db!!, ids["Gmail"]!!))
    }

    // ---------------------------------------------------------------- 5 OK footer
    @Test fun keyPage_okWhenUnchanged_backSaveWhenChanged() {
        val ks = KeyScreen(a, ids["Gmail"], Tree.root(Session.db!!).uuid); a.show(ks); idle(); layoutHost()
        val f = ks.footer!!
        assertFalse(f.changed); assertEquals(View.VISIBLE, f.ok.visibility); assertEquals(View.GONE, f.pair.visibility)
        shot("v0414/01-key-ok.png")
        val title = ks.boxes["Title"]!!.field
        title.setText("Gmail2"); idle(); layoutHost()
        assertTrue(f.changed); assertEquals(View.GONE, f.ok.visibility); assertEquals(View.VISIBLE, f.pair.visibility)
        shot("v0414/02-key-changed.png")
        title.setText("Gmail"); idle()
        assertFalse("back to the original → OK again", ks.footer!!.changed)
        // arrangement change counts too
        ks.applyArrangement(ks.layout.map { if (it.first == "URL") it.first to false else it }); idle()
        assertTrue(ks.footer!!.changed)
        assertTrue(ks.save(silent = true)); idle()
        assertFalse("saved → OK", ks.footer!!.changed)
        ks.footer!!.ok.let { (it as ViewGroup).getChildAt(0).performClick() }; idle()
        assertTrue(a.screen is ExplorerScreen)
    }

    @Test fun keyPage_okClosesWithoutDialog() {
        val ks = KeyScreen(a, ids["Amazon"], Tree.root(Session.db!!).uuid); a.show(ks); idle()
        Dlg.last = null
        (ks.footer!!.ok as ViewGroup).getChildAt(0).performClick(); idle()
        assertNull(Dlg.last); assertTrue(a.screen is ExplorerScreen)
    }

    // ---------------------------------------------------------------- 6 view items in the file menu
    @Test fun fileMenu_viewItemsOnTopSince0415() {
        val ex = icons()
        val five = listOf("名前の変更", "|", "切り取り", "フォルダーを指定して移動", "一つ上の階層へ移動", "|", "削除")
        assertEquals(listOf("表示: 詳細", "|") + five, labels(ex.itemMenu(row(ids["Gmail"]!!))))
        assertEquals(listOf("表示: 詳細", "|") + five, labels(ex.itemMenu(row(memoId()))))
        ex.itemMenu(row(ids["Gmail"]!!)).first { it.label == "表示: 詳細" }.action(); idle()
        assertFalse(ex.iconMode)
        assertEquals(listOf("表示: アイコン", "列の幅を元に戻す", "|") + five, labels(ex.itemMenu(row(ids["Gmail"]!!))))
    }

    // ---------------------------------------------------------------- 7 memo page
    @Test fun memoPage_dottedRules_noRedMargin() {
        a.show(MemoScreen(a, memoId())); idle(); layoutHost()
        val rt = find(a.host, io.github.zmlworks.kpexplorer.ui.Parts.RuledText::class.java)!!
        rt.setText("買うもの\n牛乳\nパン\nたまご"); idle(); layoutHost()
        assertTrue(rt.rulePaint.pathEffect is android.graphics.DashPathEffect)
        assertEquals(W.dp(12), rt.paddingLeft)
        // no reddish vertical line: sample the left strip of the page
        val b = Bitmap.createBitmap(rt.width, rt.height, Bitmap.Config.ARGB_8888); rt.draw(Canvas(b))
        for (x in 0 until W.dp(20)) for (y in 0 until rt.height step 7) {
            val c = b.getPixel(x, y); val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val bl = c and 0xFF
            assertFalse("red pixel at $x,$y", r > 200 && g < 180 && bl < 180)
        }
        shot("v0414/06-memo-page.png")
    }
}
