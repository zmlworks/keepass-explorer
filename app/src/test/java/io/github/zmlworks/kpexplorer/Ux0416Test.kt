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

/** 0.4.16: entry History — only for content changes, at most 5, 「- 履歴」 on the key page, delete one / all with confirmation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0416Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0416").mkdirs() }
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

    // ---------------------------------------------------------------- 3 history only for content
    @Test fun history_onlyForContentChanges() {
        val id = ids["Gmail"]!!
        var ks = openKey(id)
        assertEquals(0, hist(id).size)
        val t0 = mtime(id)
        // hide a field, reorder fields → no history, Last Modified kept
        later(); ks.applyArrangement(ks.layout.map { if (it.first == "URL") it.first to false else it }.reversed()); assertTrue(ks.save(silent = true)); idle()
        assertEquals(0, hist(id).size); assertEquals(t0, mtime(id))
        assertTrue(entry(id).customData.containsKey(Order.CD_FIELDS))
        // add an empty custom field → no history
        ks = openKey(id); later(); ks.addCustomField("PIN"); assertTrue(ks.save(silent = true)); idle()
        assertEquals(0, hist(id).size); assertEquals(t0, mtime(id))
        // icon → no history, but it is a modification
        ks = openKey(id); later(); ks.setIcon("star"); assertTrue(ks.save(silent = true)); idle()
        assertEquals(0, hist(id).size)
        val t1 = mtime(id); assertNotEquals(t0, t1)
        // content: a value → one history entry with the previous content
        ks = openKey(id); later(); ks.boxes["UserName"]!!.field.setText("new@example.com"); assertTrue(ks.save(silent = true)); idle()
        assertEquals(1, hist(id).size); assertEquals("taro.yamada@gmail.com", Tree.userName(hist(id)[0]))
        assertNotEquals(t1, mtime(id))
        // the empty custom field gets a value → content
        ks = openKey(id); ks.boxes["PIN"]!!.field.setText("1234"); assertTrue(ks.save(silent = true)); idle()
        assertEquals(2, hist(id).size)
        // tags → content
        ks = openKey(id); ks.tags = "仕事"; assertTrue(ks.save(silent = true)); idle()
        assertEquals(3, hist(id).size)
        // OTP → content
        ks = openKey(id); ks.setOtp("JBSWY3DPEHPK3PXP"); assertTrue(ks.save(silent = true)); idle()   // gitleaks:allow (RFC test secret)
        assertEquals(4, hist(id).size)
        // layout + content in one save → exactly one history entry
        ks = openKey(id)
        ks.applyArrangement(ks.layout.map { if (it.first == "URL") it.first to true else it })
        ks.boxes["Password"]!!.field.setText("p-new"); assertTrue(ks.save(silent = true)); idle()
        assertEquals(5, hist(id).size)
        assertEquals("p", Tree.password(hist(id).last()))
    }

    // ---------------------------------------------------------------- 2 cap 5
    @Test fun history_cappedAtFive_oldOnesTrimmedOnlyOnContentEdit() {
        val id = ids["Amazon"]!!
        withHistory(id, 8)
        assertEquals(5, hist(id).size)
        assertEquals("u6", Tree.userName(hist(id).last()))
        // an existing entry from another app with 8 copies: a layout-only save does not trim it
        val many = entry(id).history.let { h -> h + h.take(3) }
        Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map { if (it.uuid == id) it.copy(history = many) else it }) } }; idle()
        assertEquals(8, hist(id).size)
        var ks = openKey(id); ks.applyArrangement(ks.layout.reversed()); assertTrue(ks.save(silent = true)); idle()
        assertEquals("layout-only save keeps old history", 8, hist(id).size)
        ks = openKey(id); ks.boxes["Title"]!!.field.setText("Amazon.co.jp"); assertTrue(ks.save(silent = true)); idle()
        assertEquals("content edit trims to the newest 5", 5, hist(id).size)
        assertEquals("Amazon", Tree.title(hist(id).last()))
        // Meta.HistoryMaxItems = 5 (also for an old database that said 10)
        var d = Session.db!!
        d = d.modifyContent { copy(meta = meta.copy(historyMaxItems = 10)) }
        assertEquals(5, Tree.ensureRecycleBin(d, "ごみ箱").content.meta.historyMaxItems)
        assertEquals(5, Kdbx.create("x", ByteArray(32)).content.meta.historyMaxItems)
    }

    // ---------------------------------------------------------------- 1 key page label
    @Test fun keyPage_lastModifiedUnderIcon_historyLinkOpensViewer_menuItemGone() {
        val id = ids["Gmail"]!!
        withHistory(id, 3)
        val ks = openKey(id)
        val link = ks.historyLink!!
        assertEquals("- 履歴", link.text.toString())
        val row = link.parent as ViewGroup
        val lm = row.getChildAt(0) as android.widget.TextView
        assertTrue(lm.text.startsWith("Last Modified: "))
        val tile = ks.iconTile!!
        val tl = IntArray(2); tile.getLocationInWindow(tl)
        val ml = IntArray(2); lm.getLocationInWindow(ml)
        val kl = IntArray(2); link.getLocationInWindow(kl)
        assertTrue("under the icon", ml[1] >= tl[1] + tile.height)
        assertTrue("left edge with the icon", kotlin.math.abs(ml[0] - tl[0]) < W.dp(16))
        assertTrue("履歴 to the right", kl[0] >= ml[0] + lm.width)
        shot("v0416/01-key-page-history-link.png")
        link.performClick(); idle()
        val v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.current!!
        assertTrue(v.isShowing)
        assertNotNull(findTag<View>(v.frame, "history-delete-1")); assertNotNull(findTag<View>(v.frame, "history-delete-3"))
        assertTrue(v.buttonViews.any { it.text.toString() == "全て削除" })
        Dlg.last = v; shot("v0416/02-history-viewer.png", dialog = true)
        v.dismiss()
        // the main menu has no 履歴 any more
        a.showExplorer(); idle()
        assertTrue(a.explorer!!.mainMenu().none { it.label.contains("履歴") })
        // a new key (nothing saved yet) has no link
        assertNull(KeyScreen(a, null, Tree.root(Session.db!!).uuid).historyLink)
    }

    // ---------------------------------------------------------------- 4 delete
    @Test fun historyViewer_deleteOne_confirmEveryTime_persists_noNewHistory() {
        val id = ids["Gmail"]!!
        withHistory(id, 4)   // 4 copies, oldest→newest: original, u0, u1, u2 (current = u3)
        val before = hist(id).map { Tree.userName(it) }
        val t0 = mtime(id)
        val ks = openKey(id)
        ks.openHistory(); var v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.current!!
        // delete #1 (newest) → confirmation; キャンセル → nothing
        findTag<View>(v.frame, "history-delete-1")!!.performClick(); idle()
        var c = latestDialog()
        assertNotSame(v, c); assertTrue(texts(c.frame).any { it.contains("履歴 #1") })
        Dlg.last = c; shot("v0416/03-confirm-delete.png", dialog = true)
        clickButton(c, "キャンセル")
        assertEquals(before, hist(id).map { Tree.userName(it) })
        // again → 削除
        findTag<View>(v.frame, "history-delete-1")!!.performClick(); idle()
        c = latestDialog(); clickButton(c, "削除")
        assertEquals(before.dropLast(1), hist(id).map { Tree.userName(it) })
        assertEquals("no Last Modified change", t0, mtime(id))
        // viewer re-opened with 3 items; delete #3 (oldest) also asks
        v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.current!!
        assertTrue(v.isShowing); assertNull(findTag<View>(v.frame, "history-delete-4"))
        findTag<View>(v.frame, "history-delete-3")!!.performClick(); idle()
        c = latestDialog(); assertTrue(texts(c.frame).any { it.contains("履歴 #3") }); clickButton(c, "削除")
        assertEquals(listOf("u0", "u1"), hist(id).map { Tree.userName(it) })
        // saved to the file (re-read from storage)
        Session.flush(); idle()
        val disk = Kdbx.open(a.store.read(), key)
        assertEquals(2, Tree.findEntry(disk, id)!!.second.history.size)
        // the deletion made no history copy of its own
        assertEquals(2, hist(id).size)
    }

    @Test fun historyViewer_deleteAll_withConfirm() {
        val id = ids["Gmail"]!!
        withHistory(id, 3)
        val ks = openKey(id)
        ks.openHistory(); val v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.current!!
        clickButton(v, "全て削除")
        assertTrue("viewer stays until confirmed", v.isShowing)
        var c = latestDialog(); assertTrue(texts(c.frame).any { it.contains("全て (3 件)") })
        clickButton(c, "キャンセル"); assertEquals(3, hist(id).size)
        clickButton(v, "全て削除"); c = latestDialog(); clickButton(c, "削除")
        assertEquals(0, hist(id).size)
        val v2 = io.github.zmlworks.kpexplorer.ui.HistoryViewer.current!!
        assertTrue(texts(v2.frame).any { it.startsWith("履歴はありません") })
        assertTrue(v2.buttonViews.none { it.text.toString() == "全て削除" })
        // the key page is still clean (history deletion is not a draft edit)
        assertFalse(ks.dirty)
    }

    @Test fun historyViewer_readOnly_noDelete() {
        val id = ids["Gmail"]!!
        withHistory(id, 2)
        Session.beginTempView(Session.db!!, "t")
        try {
            val v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.show(a, id)!!
            assertNull(findTag<View>(v.frame, "history-delete-1"))
            assertTrue(v.buttonViews.none { it.text.toString() == "全て削除" })
            v.dismiss()
        } finally { Session.exitTempView() }
    }
}
