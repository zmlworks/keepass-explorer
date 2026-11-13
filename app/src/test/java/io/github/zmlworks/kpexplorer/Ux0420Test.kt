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

/** 0.4.20: drag one row up, tighter folder drop, KeePass history limit, viewer clean-up, language and date order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0420Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0420").mkdirs() }
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
        // enough items that the last ones sit at the bottom of the list (behind where the preview pane opens)
        for (i in 1..30) { val k = Tree.newEntry(Tree.Fields("サービス%02d".format(i), "user$i", "p$i", "", "", emptyList())); db = Tree.addEntry(db, root, k); ids["S$i"] = k.uuid }
        val old = Tree.newEntry(Tree.Fields("古いアカウント", "old", "x", "", "", emptyList())); db = Tree.addEntry(db, g.uuid, old); ids["old"] = old.uuid
        db = Tree.moveToBin(db, old.uuid, "ごみ箱")
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


    private fun f(e: app.keemobile.kotpass.models.Entry, k: String) = e.fields[k]

    private fun openMemo(id: UUID): MemoScreen { val ms = MemoScreen(a, id); a.show(ms); idle(); layoutHost(); return ms }
    private fun saveMemo(ms: MemoScreen, s: String) { ms.text.setText(s); assertTrue(ms.save()); idle(); later() }
    private fun setMax(n: Int) { Session.update { it.modifyContent { copy(meta = meta.copy(historyMaxItems = n)) } }; idle() }

    // ---------------------------------------------------------------- 1 drag one row up
    @Test fun drag_longPressKeepsGridStill_dropLandsBeforeItemOneRowUp() {
        val ex = icons()
        val g = grid()
        val cols = g.numColumns
        assertTrue("several columns: $cols", cols >= 3)
        val first0 = g.firstVisiblePosition; val h0 = g.height
        val pos = first0 + cols * 3 + 2
        val dragged = ex.rows[pos].uuid; val target = ex.rows[pos - cols].uuid
        val (x, y) = cellCenter(g, dragged)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        assertTrue(menuShowing())
        assertEquals(dragged, ex.selection)
        assertTrue("the preview waits while the finger is down", ex.previewHeld)
        layoutHost()
        assertEquals("the grid does not scroll under the finger", first0, g.firstVisiblePosition)
        assertEquals("the grid does not shrink under the finger", h0, g.height)
        val tr = cellRect(g, target)
        val tx = tr.left + tr.width() * 0.2f; val ty = tr.exactCenterY()
        assertTrue("one row is far enough to start a drag", kotlin.math.hypot(tx - x, ty - y) > ex.dragStartPx())
        touch(MotionEvent.ACTION_MOVE, tx, ty)
        assertTrue(ex.dragging)
        touch(MotionEvent.ACTION_MOVE, tx, ty)
        assertEquals(ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.BEFORE, target), ex.hit(dragged, tx, ty))
        assertEquals("still not scrolled", first0, g.firstVisiblePosition)
        shot("v0420/05-drag-one-row-up.png")
        touch(MotionEvent.ACTION_UP, tx, ty); idle()
        assertFalse(ex.dragging)
        val o = order()
        assertEquals("dropped just before the item one row up", o.indexOf(target) - 1, o.indexOf(dragged))
        assertFalse(ex.previewHeld)
    }

    @Test fun longPressRelease_keepsMenu_previewOpensWhenMenuCloses() {
        val ex = icons()
        val g = grid(); val h0 = g.height
        val id = ex.rows[g.firstVisiblePosition + g.numColumns * 3].uuid
        val (x, y) = cellCenter(g, id)
        touch(MotionEvent.ACTION_DOWN, x, y); hold(); touch(MotionEvent.ACTION_UP, x, y); idle(); layoutHost()
        assertTrue(menuShowing()); assertTrue(ex.previewHeld); assertEquals(h0, g.height)
        Menus.dismiss(); idle(); layoutHost()
        assertFalse(ex.previewHeld)
        assertTrue("the preview pane opened after the menu closed", g.height < h0)
    }

    @Test fun gapsBetweenCells_useNearestCell_notTheEnd() {
        val ex = icons()
        val g = grid()
        val id = ex.rows[g.firstVisiblePosition + g.numColumns * 2 + 1].uuid
        val other = ex.rows.last().uuid
        val r = cellRect(g, id)
        val d = ex.hit(other, r.exactCenterX(), r.bottom + 1f)
        assertNotNull(d); assertNotEquals(ExplorerScreen.Drop.Mode.END, d!!.mode)
    }

    // ---------------------------------------------------------------- 2 folder drop zone (middle 40 %)
    @Test fun intoFolder_onlyInTheMiddle40Percent_iconAndDetails() {
        assertEquals(0.30f, ExplorerScreen.INTO_FROM); assertEquals(0.70f, ExplorerScreen.INTO_TO)
        val folder = ids["G"]!!; val key = ids["Steam"]!!
        var ex = icons()
        var r = cellRect(grid(), folder)
        fun at(fx: Float, fy: Float = 0.5f) = ex.hit(key, r.left + r.width() * fx, r.top + r.height() * fy)?.mode
        assertEquals(M.BEFORE, at(0.25f)); assertEquals(M.INTO, at(0.32f)); assertEquals(M.INTO, at(0.5f))
        assertEquals(M.INTO, at(0.68f)); assertEquals(M.AFTER, at(0.75f))
        assertEquals("top edge of the cell reorders", M.BEFORE, at(0.45f, 0.04f))
        assertEquals("bottom edge of the cell reorders", M.AFTER, at(0.55f, 0.96f))
        ex = details()
        val list = find(ex.view, ListView::class.java)!!
        r = cellRect(list, folder)
        fun atY(fy: Float) = ex.hit(key, r.left + r.width() * 0.3f, r.top + r.height() * fy)?.mode
        assertEquals(M.BEFORE, atY(0.25f)); assertEquals(M.INTO, atY(0.35f)); assertEquals(M.INTO, atY(0.65f)); assertEquals(M.AFTER, atY(0.75f))
    }

    // ---------------------------------------------------------------- 3 history limit = KeePass
    @Test fun historyLimit_keepassDefault10_databaseValueRespected() {
        assertEquals(10, Tree.HISTORY_MAX)
        assertEquals(10, Kdbx.create("x", key).content.meta.historyMaxItems)
        val id = ids["Amazon"]!!
        withHistory(id, 13)
        assertEquals("fixture database says 10", 10, hist(id).size)
        setMax(3); withHistory(id, 1)
        assertEquals("trimmed to the database's 3 on the next content change", 3, hist(id).size)
        setMax(-1); withHistory(id, 20)
        // (the first of the 20 saves repeats u0 / p0 = no change, so 19 new copies)
        assertEquals("negative = no limit (KeePass)", 3 + 19, hist(id).size)
        setMax(0); withHistory(id, 1)
        assertEquals("0 = keep none", 0, hist(id).size)
        // opening / recycle bin set-up never rewrites the value
        setMax(25)
        assertEquals(25, Tree.ensureRecycleBinAtRoot(Session.db!!, "ごみ箱").content.meta.historyMaxItems)
    }

    @Test fun historyLimit_fiveWrittenBy0416to0419_isPutBackTo10_once() {
        var d = Session.db!!.modifyContent { copy(meta = meta.copy(historyMaxItems = 5)) }
        assertEquals(10, Tree.restoreHistoryMax(d, alreadyRestored = false).content.meta.historyMaxItems)
        assertEquals("after the one-time fix a 5 set in KeePass stays", 5, Tree.restoreHistoryMax(d, alreadyRestored = true).content.meta.historyMaxItems)
        d = d.modifyContent { copy(meta = meta.copy(historyMaxItems = 7)) }
        assertEquals("other values are never touched", 7, Tree.restoreHistoryMax(d, alreadyRestored = false).content.meta.historyMaxItems)
        // the real open path sets the flag
        App.prefs(app()).edit().remove(Security.PREF_HIST_RESTORED).commit()
        Security.opened(a, Session.db!!.modifyContent { copy(meta = meta.copy(historyMaxItems = 5)) }); idle()
        assertEquals(10, Session.db!!.content.meta.historyMaxItems)
        assertTrue(App.prefs(app()).getBoolean(Security.PREF_HIST_RESTORED, false))
        Security.opened(a, Session.db!!.modifyContent { copy(meta = meta.copy(historyMaxItems = 5)) }); idle()
        assertEquals(5, Session.db!!.content.meta.historyMaxItems)
    }

    // ---------------------------------------------------------------- 4 「- 履歴」 at the right edge
    @Test fun keyPage_historyLinkAtRightEdge() {
        val id = ids["Gmail"]!!
        withHistory(id, 2)
        val ks = openKey(id)
        val link = ks.historyLink!!
        val row = link.parent as ViewGroup
        assertEquals("- 履歴", link.text.toString())
        assertTrue("right edge: ${link.right} vs ${row.width - row.paddingRight}", kotlin.math.abs(link.right - (row.width - row.paddingRight)) <= 2)
        assertTrue("far from the date", link.left > row.width / 2)
        shot("v0420/01-key-page-history-right.png")
    }

    // ---------------------------------------------------------------- 5 viewer clean-up
    @Test fun keyHistoryViewer_noHeader_noSummary_titleOnlyWhenChanged() {
        val id = ids["Gmail"]!!
        withHistory(id, 2)
        later(); Session.update { Order.renameEntry(it, id, "Gmail 個人") }; idle()
        later(); withHistory(id, 1)
        val ks = openKey(id)
        ks.historyLink!!.performClick(); idle()
        val v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.current!!
        val all = texts(v.frame)
        assertTrue(all.toString(), all.none { it.startsWith("新しい順") || it.contains("隠したまま") || it.startsWith("変わった欄") })
        // #1 = the last user-name change: title unchanged → no Title row
        val c1 = findTag<View>(v.frame, "history-changed-1")!!
        assertTrue(texts(c1).toString(), texts(c1).none { it == "Title" })
        // #2 = rename → old → new
        val c2 = findTag<View>(v.frame, "history-changed-2")!!
        assertTrue(texts(c2).any { it == "Gmail → Gmail 個人" })
        val c3 = findTag<View>(v.frame, "history-changed-3")!!
        assertTrue(texts(c3).none { it == "Title" })
        assertEquals("the title is still stored in the KDBX history", "Gmail", Tree.title(hist(id)[0]))
        Dlg.last = v; shot("v0420/02-history-viewer-clean.png", dialog = true)
        v.dismiss()
    }

    @Test fun fullCopies_fromOtherApps_hideUnchangedTitle() {
        val id = ids["Steam"]!!
        val e = entry(id)
        // KeePass-style full copies: same title, then a different one
        val h1 = e.copy(history = emptyList(), fields = e.fields)
        val h2 = e.copy(history = emptyList(), fields = app.keemobile.kotpass.models.EntryFields.of(
            *e.fields.entries.map { (k, vv) -> k to (if (k == "Title") app.keemobile.kotpass.models.EntryValue.Plain("Steam (旧)") else vv) }.toTypedArray()))
        Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map { if (it.uuid == id) it.copy(history = listOf(h2, h1)) else it }) } }; idle()
        val v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.show(a, id) {}!!
        idle()
        val s1 = findTag<View>(v.frame, "history-snap-1")!!
        val s2 = findTag<View>(v.frame, "history-snap-2")!!
        assertTrue(texts(s1).toString(), texts(s1).none { it.startsWith("Title") || it.contains("Steam") && it.contains("→") })
        assertTrue(texts(s2).toString(), texts(s2).any { it.contains("Steam (旧) → Steam") })
        v.dismiss()
    }

    @Test fun memoDiffViewer_noHeader() {
        val id = memoId()
        saveMemo(openMemo(id), "牛乳\n卵2個"); saveMemo(openMemo(id), "牛乳\n卵3個")
        val ms = openMemo(id)
        ms.historyLink.performClick(); idle()
        val v = io.github.zmlworks.kpexplorer.ui.MemoDiffViewer.current!!
        assertTrue(texts(v.frame).none { it.startsWith("新しい順") || it.contains("最大") })
        assertTrue(texts(v.frame).none { it.startsWith("タイトル:") })
        Dlg.last = v; shot("v0420/06-memo-diff-clean.png", dialog = true)
        v.dismiss()
    }

    // ---------------------------------------------------------------- 6 language and date order
    private fun reopenActivity() {
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        Session.store = a.store
        if (a.explorer == null) a.showExplorer(); idle()
    }

    @Test fun english_allUi_andMonthDayYear() {
        val id = ids["Gmail"]!!
        withHistory(id, 2)
        L.setLang(app(), L.Lang.EN); L.setDateOrder(app(), L.DateOrder.MDY)
        reopenActivity()
        assertTrue(L.en)
        assertEquals("resources follow the setting", "Lock", a.getString(R.string.menu_lock).let { if (it.contains("Lock")) "Lock" else it })
        val ex = details()
        val t = texts(ex.view)
        assertTrue(t.toString(), t.containsAll(listOf("Up", "View", "Folder", "Key", "Memo", "Save", "Menu", "Search", "Name", "User name", "Modified")))
        assertTrue("no Japanese UI text left: $t", t.none { s -> s.any { it in '\u3040'..'\u30ff' } && !ex.rows.any { r -> s.contains(r.name) } && s != "仕事" && s != "銀行" && s != "ごみ箱" })
        val mdy = Regex("""\d\d/\d\d/20\d\d""")
        assertTrue("details dates M/D/Y: $t", t.any { mdy.matches(it) })
        val today = java.time.LocalDate.now()
        assertTrue(t.any { it == "%02d/%02d/%d".format(today.monthValue, today.dayOfMonth, today.year) })
        val menu = labels(ex.mainMenu())
        assertTrue(menu.toString(), menu.contains("Language: English") && menu.any { it.startsWith("Date: ") })
        shot("v0420/03-english-mdy-details.png")
        val ks = openKey(id)
        assertEquals("- History", ks.historyLink!!.text.toString())
        val lm = texts(ks.view).first { it.startsWith("Last Modified: ") }
        assertTrue(lm, Regex("""Last Modified: \d\d/\d\d/20\d\d \d\d:\d\d:\d\d""").matches(lm))
        assertTrue(lm.contains("%02d/%02d/%d".format(today.monthValue, today.dayOfMonth, today.year)))
        shot("v0420/04-english-key-page.png")
        ks.historyLink!!.performClick(); idle()
        val v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.current!!
        val vt = texts(v.frame)
        assertTrue(vt.toString(), vt.contains("Delete") && vt.any { mdy.containsMatchIn(it) })
        assertTrue(v.buttonViews.map { it.text.toString() }.containsAll(listOf("Delete all", "Close")))
        Dlg.last = v; shot("v0420/07-english-history.png", dialog = true)
        v.dismiss()
        // Help is English too
        Help.usage(a); idle()
        val help = texts(latestDialog().frame).joinToString("\n")
        assertTrue(help, help.contains("Folders = KeePass groups") && help.none { it in '\u3040'..'\u30ff' && !"日本語".contains(it) })
        latestDialog().dismiss()
    }

    @Test fun dayMonthYear_everywhere_butBackupFileNamesStay() {
        L.setDateOrder(app(), L.DateOrder.DMY)
        val id = ids["Gmail"]!!
        withHistory(id, 1)
        val today = java.time.LocalDate.now()
        val dmy = "%02d/%02d/%d".format(today.dayOfMonth, today.monthValue, today.year)
        assertTrue(texts(details().view).contains(dmy))
        val ks = openKey(id)
        assertTrue(texts(ks.view).any { it.startsWith("Last Modified: $dmy ") })
        assertTrue(io.github.zmlworks.kpexplorer.ui.HistoryViewer.rows(entry(id))[0].modified.startsWith(dmy))
        val f = a.store.backupTimestamped()
        assertTrue(f.name, Regex("""\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d\.kdbx""").matches(f.name))
        a.show(BackupScreen(a)); idle(); layoutHost()
        val bt = texts(a.host)
        assertTrue(bt.toString(), bt.any { it.startsWith("$dmy ") && Regex("""\d\d/\d\d/\d{4} \d\d:\d\d""").matches(it) })
        assertTrue("Japanese still", bt.contains("今すぐ作成"))
        // menu: three date entries with today's example, one checked
        val m = a.explorer!!.mainMenu().filter { it.label.startsWith("日付: ") }
        assertEquals(3, m.size); assertEquals(listOf("日付: $dmy"), m.filter { it.checked }.map { it.label })
    }

    @Test fun languageSetting_persists_andSystemDefault() {
        assertEquals(L.Lang.SYSTEM, L.lang)
        assertFalse("this test device is Japanese", L.en)
        val m = labels(a.explorer!!.mainMenu())
        assertTrue(m.toString(), m.containsAll(listOf("言語: 端末に合わせる", "言語: 日本語", "言語: English")))
        L.setLang(app(), L.Lang.EN)
        L.load(app()); assertEquals(L.Lang.EN, L.lang)
        assertEquals("en", App.prefs(app()).getString(L.PREF_LANG, null))
        L.setLang(app(), L.Lang.JA); L.load(app()); assertFalse(L.en)
    }

    @Test fun switchingLanguage_rebuildsWindow_keepsDatabaseOpen_evenWithLockOnLeave() {
        App.prefs(app()).edit().putInt("autolock", 0).commit()
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val act = ctl.get(); if (act.explorer == null) act.showExplorer(); idle()
        act.setLanguage(L.Lang.EN); idle()
        assertEquals(L.Lang.EN, L.lang)
        assertTrue("the database stays open", Session.isOpen)
        assertEquals("en", App.prefs(app()).getString(L.PREF_LANG, null))
    }
}
