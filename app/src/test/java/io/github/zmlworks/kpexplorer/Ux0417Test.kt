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

/** 0.4.17: memo page (bold title, 「- 差分」), memo diff history, folder preview icons, no empty history on new keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0417Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0417").mkdirs() }
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
        for (n in listOf("給与明細", "年末調整")) { val k = Tree.newEntry(Tree.Fields(n, "u", "p", "", "", emptyList())); db = Tree.addEntry(db, g.uuid, k) }
        db = Tree.addEntry(db, g.uuid, Order.newMemo("議事録"))
        db = Tree.addGroup(db, g.uuid, Tree.newGroup("2026年"))
        // a memo-like entry made by another app (no KPExplorer.Kind): notepad icon, no user / password
        val foreign = Tree.newEntry(Tree.Fields("他アプリのメモ", "", "", "", "一行目\n二行目", emptyList()))
            .copy(icon = app.keemobile.kotpass.constants.PredefinedIcon.Notepad)
        db = Tree.addEntry(db, root, foreign); ids["foreign"] = foreign.uuid
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


    private val dv get() = io.github.zmlworks.kpexplorer.ui.MemoDiffViewer
    private fun openMemo(id: UUID): MemoScreen { val ms = MemoScreen(a, id); a.show(ms); idle(); layoutHost(); return ms }
    private fun saveMemo(ms: MemoScreen, s: String) { ms.text.setText(s); assertTrue(ms.save()); idle(); later() }
    private val V = listOf("牛乳\n卵\nパン", "牛乳\n卵2個\nパン\nバター", "牛乳\n卵2個\nバター", "豆乳\n卵2個\nバター\n\nメモ", "豆乳\n卵2個\nバター\n\nメモ\n追記",
        "豆乳\n卵3個\nバター\n\nメモ\n追記", "トマト\n豆乳\n卵3個\nバター\n\nメモ\n追記")

    // ---------------------------------------------------------------- 1 memo page
    @Test fun memoPage_noBackButton_boldTitleNotEditable_diffLinkAtRightEnd() {
        val id = memoId()
        saveMemo(openMemo(id), V[0]); saveMemo(openMemo(id), V[1]); saveMemo(openMemo(id), V[2])
        val ms = openMemo(id)
        val bar = ms.title.parent as ViewGroup
        assertTrue("no ← in the bar", texts(bar).none { it == "←" })
        assertFalse(ms.title is android.widget.EditText)
        assertEquals("買い物メモ", ms.title.text.toString())
        assertTrue(ms.title.typeface?.isBold == true)
        assertEquals("- 履歴", ms.historyLink.text.toString())
        assertSame(bar, ms.historyLink.parent)
        assertEquals("at the right end", bar.childCount - 1, bar.indexOfChild(ms.historyLink))
        val tl = IntArray(2); ms.title.getLocationInWindow(tl); val ll = IntArray(2); ms.historyLink.getLocationInWindow(ll)
        assertTrue(ll[0] >= tl[0] + ms.title.width)
        assertTrue("near the right edge", ll[0] + ms.historyLink.width > W_ - W.dp(20))
        shot("v0417/01-memo-page-diff-link.png")
        ms.historyLink.performClick(); idle()
        val v = dv.current!!; assertTrue(v.isShowing)
        assertNotNull(findTag<View>(v.frame, "change-1")); v.dismiss()
        // the back key still leaves the page (bottom 戻る too)
        a.onBackPressed(); idle(); assertTrue(a.screen is ExplorerScreen)
    }

    // ---------------------------------------------------------------- 2 foreign memo
    @Test fun foreignMemo_showsStandardHistoryLikeKeys() {
        val id = ids["foreign"]!!
        val ms = openMemo(id)
        assertFalse(ms.appMemo); assertEquals("- 履歴", ms.historyLink.text.toString())
        saveMemo(ms, "一行目\n変更")
        assertEquals(1, hist(id).size)
        val v = openMemo(id).openHistory()!!; idle()
        assertSame(io.github.zmlworks.kpexplorer.ui.HistoryViewer.current, v)
        assertNotNull(findTag<View>(v.frame, "history-delete-1")); v.dismiss()
    }

    // ---------------------------------------------------------------- 3 folder preview icons
    @Test fun folderPreview_showsIcons() {
        val ex = icons()
        ex.select(ids["G"]!!); idle(); layoutHost()
        val lines = ArrayList<View>()
        fun walk(v: View) { if (v.tag == "preview-child") lines.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(ex.view)
        assertEquals(4, lines.size)
        assertTrue(lines.all { (it as ViewGroup).getChildAt(0) is android.widget.ImageView })
        assertEquals(listOf("2026年", "給与明細", "年末調整", "議事録"), lines.map { ((it as ViewGroup).getChildAt(1) as android.widget.TextView).text.toString() }.sortedBy { listOf("2026年", "給与明細", "年末調整", "議事録").indexOf(it) })
        shot("v0417/04-folder-preview-icons.png")
    }

    // ---------------------------------------------------------------- 4 new key: no empty history
    @Test fun newKey_hasNoEmptyHistory_andOldArtifactsAreDropped() {
        a.explorer!!.newKey(); idle()
        val nk = a.screen as NewKeyScreen
        nk.editors["Title"]!!.setText("新しい鍵"); nk.editors["Password"]!!.setText("pw")
        assertTrue(nk.create()); idle()
        val id = Tree.root(Session.db!!).entries.first { Tree.title(it) == "新しい鍵" }.uuid
        assertEquals("cause: the empty entry made before the first save was copied to History", 0, hist(id).size)
        // the first real edit keeps exactly the created content
        val ks = openKey(id); ks.boxes["Password"]!!.field.setText("pw2"); assertTrue(ks.save(silent = true)); idle()
        assertEquals(listOf("pw"), hist(id).map { Tree.password(it) })
        // the key screen's own "new key" path (no NewKeyScreen) also leaves none
        val k2 = KeyScreen(a, null, Tree.root(Session.db!!).uuid); a.show(k2); idle()
        k2.values["Title"] = "別の鍵"; k2.values["Password"] = "x"; k2.dirty = true
        assertTrue(k2.save(silent = true)); idle()
        assertEquals(0, Tree.root(Session.db!!).entries.first { Tree.title(it) == "別の鍵" }.history.size)
    }
}
