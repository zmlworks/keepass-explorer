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
import io.github.zmlworks.kpexplorer.ui.ValueFold.State as S

/** 0.4.26: " …" + tap to unfold long one-line values (password cycle), restore location, auto-delete warning, URL font, folder 一つ上の階層へ移動. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0426Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0426").mkdirs() }
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

    @Test fun keyPage_longValue_showsEllipsis_tapShowsAll_tapAgainOneLine() {
        setGmail(LONG_USER, "p")
        val ks = openKey(ids["Gmail"]!!); layoutHost(); idle(); layoutHost()
        val f = ed(ks, LONG_USER)
        val fold = ks.folds["UserName"]!!
        assertEquals(S.LINE, fold.state); assertTrue(fold.overflows())
        assertTrue(shown(f), shown(f).endsWith(" …"))
        assertTrue(LONG_USER.startsWith(shown(f).removeSuffix(" …")))
        assertTrue("drawn: ${f.layout.text}", f.layout.text.endsWith(" …"))
        assertTrue("fits: ${f.layout.getLineWidth(0)} of ${f.width}", f.layout.getLineWidth(0) <= f.width - f.totalPaddingLeft - f.totalPaddingRight)
        shot("v0426/01-key-long-collapsed.png")
        ks.boxes["UserName"]!!.onTap!!.invoke(); idle(); layoutHost()
        assertEquals(S.FULL, fold.state)
        assertEquals(LONG_USER, shown(f)); assertTrue("wrapped: ${f.lineCount}", f.lineCount >= 2)
        shot("v0426/02-key-long-expanded.png")
        ks.boxes["UserName"]!!.onTap!!.invoke(); idle(); layoutHost()
        assertEquals(S.LINE, fold.state); assertTrue(shown(f).endsWith(" …")); assertEquals(1, f.lineCount)
        // a value that fits: no " …", a tap changes nothing
        val title = ed(ks, "Gmail")
        assertEquals("Gmail", shown(title))
        ks.boxes["Title"]!!.onTap!!.invoke(); idle()
        assertEquals(S.LINE, ks.folds["Title"]!!.state)
        // the swipe still copies the whole value
        ks.boxes["UserName"]!!.onSwipe!!.invoke(); idle()
        val cm = app().getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(LONG_USER, cm.primaryClip!!.getItemAt(0).text.toString())
        // editing shows the plain value on one line; afterwards " …" again
        ks.boxes["UserName"]!!.startEdit(); idle()
        assertNull(f.transformationMethod); assertEquals(LONG_USER, shown(f))
        ks.boxes["UserName"]!!.endEdit(); idle(); layoutHost()
        assertTrue(shown(f).endsWith(" …"))
    }

    @Test fun keyPage_password_cycle_long_and_short() {
        setGmail("u", LONG_PASS)
        val ks = openKey(ids["Gmail"]!!); layoutHost(); idle(); layoutHost()
        val f = ed(ks, LONG_PASS)
        val tap = ks.boxes["Password"]!!.onTap!!
        assertEquals("******", shown(f)); assertFalse(ks.passShown)
        tap(); idle(); layoutHost()
        assertTrue(shown(f), shown(f).endsWith(" …")); assertTrue(ks.passShown)
        assertTrue("fits: ${f.layout.getLineWidth(0)} of ${f.width}", f.layout.getLineWidth(0) <= f.width - f.totalPaddingLeft - f.totalPaddingRight)
        shot("v0426/03-key-pass-one-line.png")
        tap(); idle(); layoutHost()
        assertEquals(LONG_PASS, shown(f)); assertTrue(f.lineCount >= 2)
        shot("v0426/04-key-pass-whole.png")
        tap(); idle(); layoutHost()
        assertEquals("******", shown(f)); assertFalse(ks.passShown)
        // short: ****** ↔ shown
        a.showExplorer(); idle()
        setGmail("u", "short1")
        val k2 = openKey(ids["Gmail"]!!); layoutHost(); idle(); layoutHost()
        val g = ed(k2, "short1")
        val t2 = k2.boxes["Password"]!!.onTap!!
        assertEquals("******", shown(g)); t2(); idle(); layoutHost()
        assertEquals("short1", shown(g)); t2(); idle()
        assertEquals("******", shown(g))
        // long press (edit) reveals it, as before
        k2.boxes["Password"]!!.startEdit(); idle()
        assertEquals("short1", shown(g)); k2.boxes["Password"]!!.endEdit(); idle()
        assertTrue(k2.passShown)
    }

    @Test fun preview_password_cycle_withShowInsteadOfStars() {
        val ex = a.explorer!!; layoutHost()
        setGmail("u", LONG_PASS); ex.refresh(); ex.select(ids["Gmail"]!!); idle(); layoutHost()
        val t = findTag<View>(ex.view, "pass") as android.widget.TextView
        assertEquals("表示する", shown(t))
        shot("v0426/05-preview-pass-show.png")
        t.performClick(); idle(); layoutHost()
        assertTrue(shown(t), shown(t).endsWith(" …")); assertEquals(1, t.lineCount)
        assertTrue("drawn: ${t.layout.text}", t.layout.text.endsWith(" …"))
        assertTrue("fits: ${t.layout.getLineWidth(0)} of ${t.width}", t.layout.getLineWidth(0) <= t.width - t.totalPaddingLeft - t.totalPaddingRight)
        // the whole line (with its " …") is inside the visible preview pane
        val pane = generateSequence(t.parent as View) { it.parent as? View }.first { it is ExplorerScreen.PreviewBox }
        val tl = IntArray(2); t.getLocationInWindow(tl); val pl = IntArray(2); pane.getLocationInWindow(pl)
        assertTrue("inside the pane: ${tl[0]}+${t.width} (${t.totalPaddingLeft},${t.totalPaddingRight}) / ${pl[0]}+${pane.width}-${pane.paddingRight}",
            tl[0] + t.width - t.totalPaddingRight <= pl[0] + pane.width - pane.paddingRight)
        assertSame(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), t.typeface)
        shot("v0426/06-preview-pass-one-line.png")
        t.performClick(); idle(); layoutHost()
        assertEquals(LONG_PASS, shown(t)); assertTrue(t.lineCount >= 2)
        shot("v0426/07-preview-pass-whole.png")
        t.performClick(); idle(); layoutHost()
        assertEquals("表示する", shown(t))
        // the tap target is the text only: right of it = an ordinary preview tap
        val l = IntArray(2); t.getLocationInWindow(l)
        val x = l[0] + t.width - W.dp(14).toFloat(); val y = l[1] + t.height / 2f
        touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y); idle()
        assertEquals("表示する", shown(t)); assertTrue(ex.previewSelected)
        // short password: 表示する ↔ shown
        setGmail("u", "abc"); ex.refresh(); ex.select(ids["Amazon"]!!); ex.select(ids["Gmail"]!!); idle(); layoutHost()
        val s2 = findTag<View>(ex.view, "pass") as android.widget.TextView
        s2.performClick(); idle(); layoutHost(); assertEquals("abc", shown(s2))
        s2.performClick(); idle(); assertEquals("表示する", shown(s2))
    }

    @Test fun url_isNotMonospace_anyMore() {
        assertFalse(KeyFields.monospace(Order.F_URL))
        assertTrue(KeyFields.monospace(Order.F_USER) && KeyFields.monospace(Order.F_PASS))
        val ex = a.explorer!!; setGmail("u", "p"); ex.refresh(); ex.select(ids["Gmail"]!!); idle(); layoutHost()
        val u = allViews(ex.view).filterIsInstance<android.widget.TextView>().first { it.text.toString() == "https://mail.google.com" }
        assertNotSame(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), u.typeface)
        val ks = openKey(ids["Gmail"]!!)
        assertNotSame(io.github.zmlworks.kpexplorer.ui.Mono.typeface(app()), ed(ks, "https://mail.google.com").typeface)
    }

    @Test fun restoreLocation_inPreviewAndMenu_allCases() {
        val ex = a.explorer!!
        val old = ids["old"]!!
        ex.navigate(bin()); idle(); ex.select(old); idle(); layoutHost()
        assertEquals("元の場所: /仕事", (findTag<View>(ex.view, "restore-to") as android.widget.TextView).text.toString())
        assertTrue(labels(ex.itemMenu(row(old))).contains("元に戻す (→ /仕事)"))
        shot("v0426/08-preview-restore-to.png")
        longPressAt(icons().let { grid() }, old)
        shot("v0426/09-restore-menu.png", menuAt = Menus.lastPlace!!.left to Menus.lastPlace!!.top)
        Menus.dismiss(); idle()
        // kept in the file (KDBX 4.1 PreviousParentGroup)
        val back = Kdbx.open(Kdbx.encodeVerified(Session.db!!, keepSalt = true), key)
        assertEquals(ids["G"]!!, Tree.restoreTarget(back, old).target)
        // the original folder in the bin too → /
        Session.update { Tree.moveToBin(it, ids["G"]!!, "ごみ箱") }; ex.refresh(); ex.select(old); idle()
        assertEquals(Tree.RestoreTarget.Reason.IN_BIN, Tree.restoreTarget(Session.db!!, old).reason)
        assertEquals("元のフォルダーもごみ箱の中です → / に戻します", (findTag<View>(ex.view, "restore-to") as android.widget.TextView).text.toString())
        // gone → /
        Session.update { Tree.deletePermanently(it, ids["G"]!!) }; ex.refresh(); ex.select(old); idle()
        assertEquals("元のフォルダーはありません → / に戻します", (findTag<View>(ex.view, "restore-to") as android.widget.TextView).text.toString())
        assertTrue(labels(ex.itemMenu(row(old))).contains("元に戻す (→ /)"))
        // no record (e.g. put there by another app without PreviousParentGroup)
        val e = Tree.newEntry(Tree.Fields("記録なし", "", "", "", "", emptyList()))
        Session.update { Tree.addEntry(it, bin(), e) }; ex.refresh(); ex.select(e.uuid); idle()
        assertEquals("元の場所の記録がありません → / に戻します", (findTag<View>(ex.view, "restore-to") as android.widget.TextView).text.toString())
        // restoring goes exactly where it said
        ex.restoreItem(old); idle()
        assertEquals(Tree.root(Session.db!!).uuid, Tree.parentOf(Tree.root(Session.db!!), old)!!.uuid)
        assertEquals("/ に戻しました", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        // not shown outside the bin
        ex.navigate(Tree.root(Session.db!!).uuid); ex.select(ids["Gmail"]!!); idle()
        assertNull(findTag<View>(ex.view, "restore-to"))
    }

    @Test fun restore_toTheOriginalFolder_says_so() {
        val ex = a.explorer!!
        Session.update { Tree.moveToBin(it, ids["会社メール"]!!, "ごみ箱") }
        ex.navigate(bin()); idle()
        ex.restoreItem(ids["会社メール"]!!); idle()
        assertEquals(ids["メール"]!!, Tree.parentOf(Tree.root(Session.db!!), ids["会社メール"]!!)!!.uuid)
        assertEquals("/仕事/メール に戻しました", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test fun autoDelete_warnsWithTheCount_offToDays_andFewerDays() {
        val now = java.time.Instant.now()
        Session.update { Tree.moveToBin(it, ids["Gmail"]!!, "ごみ箱", now.minus(java.time.Duration.ofDays(10))) }
        Session.update { Tree.moveToBin(it, ids["Amazon"]!!, "ごみ箱", now.minus(java.time.Duration.ofDays(20))) }
        BinSettings.show(a); idle()
        val d = Dlg.last!!
        fun pick(days: Int) { findTag<View>(d.frame, "bin-days")!!.performClick(); idle(); radio(BinSettings.DAYS.indexOf(days)).performClick(); idle() }
        // しない → 30: nothing that old → applied without asking
        pick(30); assertEquals(30, Tree.binAutoDeleteDays(Session.db!!)); assertEquals(3, Tree.recycleBinChildCount(Session.db!!))
        // 30 → 14: Amazon (20 days) → warning with the count; いいえ keeps everything
        pick(14)
        val w = latestDialog()
        assertTrue(texts(w.frame).joinToString(), texts(w.frame).joinToString().contains("1 個の項目は 14 日より前"))
        shot("v0426/10-autodelete-warning.png", dialog = true)
        w.buttonViews[1].performClick(); idle()
        assertEquals(30, Tree.binAutoDeleteDays(Session.db!!)); assertEquals(3, Tree.recycleBinChildCount(Session.db!!))
        // 30 → 7: two items → count 2; はい deletes them
        pick(7)
        assertTrue(texts(latestDialog().frame).joinToString().contains("2 個の項目は 7 日より前"))
        latestDialog().buttonViews.first().performClick(); idle()
        assertEquals(7, Tree.binAutoDeleteDays(Session.db!!)); assertEquals(1, Tree.recycleBinChildCount(Session.db!!))
        // more days: never asks
        pick(365); assertEquals(365, Tree.binAutoDeleteDays(Session.db!!))
        // しない → 7 with an old item: asks too
        pick(0)
        Session.update { Tree.moveToBin(it, ids["Steam"]!!, "ごみ箱", now.minus(java.time.Duration.ofDays(9))) }
        pick(7)
        assertTrue(texts(latestDialog().frame).joinToString().contains("1 個の項目は 7 日より前"))
        latestDialog().buttonViews[1].performClick(); idle()
        assertEquals(0, Tree.binAutoDeleteDays(Session.db!!))
        d.dismiss()
    }

    @Test fun folderMenus_moveUpOneLevel() {
        val ex = a.explorer!!
        val mail = ids["メール"]!!; val g = ids["G"]!!
        ex.navigate(g); idle(); icons()
        val m = ex.itemMenu(row(mail))
        assertTrue(m.first { it.label == "一つ上の階層へ移動" }.enabled)
        longPressAt(grid(), mail)
        shot("v0426/11-folder-menu-move-up.png", menuAt = Menus.lastPlace!!.left to Menus.lastPlace!!.top)
        Menus.dismiss(); idle()
        // tree: same item; top-level folders and the root / bin have none or disabled
        assertTrue(ex.treeMenu(mail).first { it.label == "一つ上の階層へ移動" }.enabled)
        assertFalse(ex.treeMenu(g).first { it.label == "一つ上の階層へ移動" }.enabled)
        assertFalse(labels(ex.treeMenu(Tree.root(Session.db!!).uuid)).contains("一つ上の階層へ移動"))
        assertFalse(labels(ex.treeMenu(bin())).contains("一つ上の階層へ移動"))
        ex.navigate(Tree.root(Session.db!!).uuid); idle()
        assertFalse(ex.itemMenu(row(g)).first { it.label == "一つ上の階層へ移動" }.enabled)
        assertFalse(labels(ex.itemMenu(row(bin()))).contains("一つ上の階層へ移動"))
        // do it: メール goes to the root, at the front, and is shown there
        ex.treeMenu(mail).first { it.label == "一つ上の階層へ移動" }.action(); idle()
        val root = Tree.root(Session.db!!)
        assertEquals(root.uuid, Tree.parentOf(root, mail)!!.uuid)
        assertEquals(root.uuid, ex.current); assertEquals(mail, ex.rows.first().uuid); assertEquals(mail, ex.selection)
        assertEquals("its contents moved along", 2, Tree.findGroup(Session.db!!, mail)!!.entries.size)
        // keys / memos already had it
        assertTrue(labels(ex.itemMenu(row(ids["Gmail"]!!))).contains("一つ上の階層へ移動"))
    }

    @Test fun english() {
        L.setLang(app(), L.Lang.EN)
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val act = ctl.get(); if (act.explorer == null) act.showExplorer(); idle()
        val ex = act.explorer!!
        ex.navigate(bin()); idle(); ex.select(ids["old"]!!); idle()
        assertEquals("Original location: /仕事", (findTag<View>(ex.view, "restore-to") as android.widget.TextView).text.toString())
        assertTrue(labels(ex.itemMenu(ex.rows.first { it.uuid == ids["old"]!! })).contains("Restore (→ /仕事)"))
        assertTrue(labels(ex.treeMenu(ids["メール"]!!)).contains("Move up one level"))
        ex.navigate(Tree.root(Session.db!!).uuid); ex.select(ids["Gmail"]!!); idle()
        assertEquals("Show", shown(findTag<View>(ex.view, "pass") as android.widget.TextView))
    }
}
