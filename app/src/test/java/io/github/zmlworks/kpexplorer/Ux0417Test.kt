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
    private fun versions(id: UUID) = io.github.zmlworks.kpexplorer.db.MemoDiff.versions(entry(id))
    private val V = listOf("牛乳\n卵\nパン", "牛乳\n卵2個\nパン\nバター", "牛乳\n卵2個\nバター", "豆乳\n卵2個\nバター\n\nメモ", "豆乳\n卵2個\nバター\n\nメモ\n追記",
        "豆乳\n卵3個\nバター\n\nメモ\n追記", "トマト\n豆乳\n卵3個\nバター\n\nメモ\n追記")

    // ---------------------------------------------------------------- diff engine
    @Test fun memoDiff_roundTrip_andEncoding() {
        val D = io.github.zmlworks.kpexplorer.db.MemoDiff
        val rnd = java.util.Random(7)
        repeat(300) {
            fun r() = (0 until rnd.nextInt(8)).joinToString("\n") { listOf("a", "b", "c", "", "\\x", "日本\r", "+=-@T").random(kotlin.random.Random(rnd.nextInt())) }
            val x = r(); val y = r()
            assertEquals(y, D.apply(x, D.diff(x, y)))
            val rec = io.github.zmlworks.kpexplorer.db.MemoDiff.Rec(123L, D.hash(x), if (rnd.nextBoolean()) "t\\i\ntle" else null, D.diff(x, y))
            assertEquals(listOf(rec), D.decode(D.encode(listOf(rec))))
        }
        assertEquals(emptyList<Any>(), D.decode("garbage")); assertEquals(emptyList<Any>(), D.decode(null))
        // a diff that does not fit is refused, not applied wrongly
        assertTrue(runCatching { D.apply("a", D.diff("a\nb\nc", "a\nc")) }.isFailure)
    }

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
        assertEquals("- 差分", ms.historyLink.text.toString())
        assertSame(bar, ms.historyLink.parent)
        assertEquals("at the right end", bar.childCount - 1, bar.indexOfChild(ms.historyLink))
        val tl = IntArray(2); ms.title.getLocationInWindow(tl); val ll = IntArray(2); ms.historyLink.getLocationInWindow(ll)
        assertTrue(ll[0] >= tl[0] + ms.title.width)
        assertTrue("near the right edge", ll[0] + ms.historyLink.width > W_ - W.dp(20))
        shot("v0417/01-memo-page-diff-link.png")
        ms.historyLink.performClick(); idle()
        val v = dv.current!!; assertTrue(v.isShowing)
        assertNotNull(findTag<View>(v.frame, "diff-version-1")); v.dismiss()
        // the back key still leaves the page (bottom 戻る too)
        a.onBackPressed(); idle(); assertTrue(a.screen is ExplorerScreen)
    }

    // ---------------------------------------------------------------- 2 diffs only, max 5
    @Test fun memoHistory_diffsOnly_max5_rebuildsVersions_highlights() {
        val id = memoId()
        // the first save of a new (empty) memo leaves nothing: an empty page is nothing to go back to
        saveMemo(openMemo(id), V[0])
        assertTrue(versions(id).isEmpty()); assertEquals(0, hist(id).size)
        for (s in V.drop(1)) saveMemo(openMemo(id), s)
        assertEquals("no standard History copies for app memos", 0, hist(id).size)
        val vs = versions(id)
        assertEquals(5, vs.size)
        assertEquals(V.drop(1).dropLast(1).reversed(), vs.map { it.text })   // newest first; the oldest (V[0]) trimmed
        val cd = entry(id).customData[io.github.zmlworks.kpexplorer.db.MemoDiff.CD]!!.value
        assertFalse("not full copies", cd.contains("卵3個\nバター"))
        run { openMemo(id).openHistory(); idle()
            Dlg.last = dv.current; shot("v0417/02-memo-diff-viewer.png", dialog = true); dv.current!!.dismiss(); a.showExplorer(); idle() }
        // a layout-free, unchanged save adds nothing; a rename adds a title-only version
        assertTrue(openMemo(id).save()); assertEquals(5, versions(id).size)
        val t0 = mtime(id)
        Session.update { Order.renameEntry(it, id, "買い物リスト") }; idle()
        val r = versions(id)[0]
        assertEquals("買い物メモ", r.title); assertEquals("買い物リスト", r.newerTitle); assertEquals(V.last(), r.text)
        assertNotEquals(t0, mtime(id))
        // highlighting: in V[5] vs V[6] nothing of V[5] was removed, 1 line was added
        val v5 = versions(id)[1]
        assertEquals(V[5], v5.text)
        val hl = dv.highlighted(v5) as android.text.Spanned
        assertEquals(0, hl.getSpans(0, hl.length, android.text.style.BackgroundColorSpan::class.java).size)
        assertTrue(hl.toString().contains("1 行が加わりました"))
        val v4 = versions(id)[2]   // V[4] → V[5]: 卵2個 became 卵3個
        val h4 = dv.highlighted(v4) as android.text.Spanned
        val sp = h4.getSpans(0, h4.length, android.text.style.BackgroundColorSpan::class.java).single()
        assertEquals("卵2個", h4.subSequence(h4.getSpanStart(sp), h4.getSpanEnd(sp)).toString())
        // reopened from the file
        Session.flush(); idle()
        val disk = Kdbx.open(a.store.read(), key)
        assertEquals(versions(id).map { it.text }, io.github.zmlworks.kpexplorer.db.MemoDiff.versions(Tree.findEntry(disk, id)!!.second).map { it.text })
    }

    @Test fun memoHistory_deleteOneAndAll_confirm_noNewHistory() {
        val id = memoId()
        for (s in V.take(5)) saveMemo(openMemo(id), s)
        val texts0 = versions(id).map { it.text }   // V3, V2, V1, V0
        assertEquals(listOf(V[3], V[2], V[1], V[0]), texts0)
        val t0 = mtime(id)
        val ms = openMemo(id); ms.openHistory(); idle()
        var v = dv.current!!
        findTag<View>(v.frame, "history-delete-2")!!.performClick(); idle()
        var c = latestDialog(); assertTrue(texts(c.frame).any { it.contains("差分 #2") })
        Dlg.last = c; shot("v0417/03-memo-confirm-delete.png", dialog = true)
        clickButton(c, "キャンセル"); assertEquals(texts0, versions(id).map { it.text })
        findTag<View>(v.frame, "history-delete-2")!!.performClick(); idle(); clickButton(latestDialog(), "削除")
        assertEquals("middle one removed, the rest still rebuild", listOf(V[3], V[1], V[0]), versions(id).map { it.text })
        assertEquals(t0, mtime(id)); assertEquals(0, hist(id).size); assertEquals(V[4], Tree.notes(entry(id)))
        v = dv.current!!
        findTag<View>(v.frame, "history-delete-1")!!.performClick(); idle(); clickButton(latestDialog(), "削除")
        assertEquals(listOf(V[1], V[0]), versions(id).map { it.text })
        findTag<View>(dv.current!!.frame, "history-delete-2")!!.performClick(); idle(); clickButton(latestDialog(), "削除")
        assertEquals(listOf(V[1]), versions(id).map { it.text })
        clickButton(dv.current!!, "全て削除"); c = latestDialog(); assertTrue(texts(c.frame).any { it.contains("全て (1 件)") }); clickButton(c, "削除")
        assertTrue(versions(id).isEmpty()); assertNull(entry(id).customData[io.github.zmlworks.kpexplorer.db.MemoDiff.CD])
        assertTrue(texts(dv.current!!.frame).any { it.startsWith("履歴はありません") })
        assertEquals(t0, mtime(id)); assertFalse(ms.dirty)
    }

    @Test fun memoHistory_editedByAnotherApp_reanchorsOrDegrades() {
        val id = memoId()
        for (s in V.take(3)) saveMemo(openMemo(id), s)   // versions: V1, V0
        // KeePassXC-like edit: keeps a full copy of our version in <History>, changes Notes
        fun external(notes: String, keepCopy: Boolean) = Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map {
            if (it.uuid != id) it else it.copy(history = if (keepCopy) it.history + it.copy(history = listOf()) else it.history,
                fields = it.fields + ("Notes" to app.keemobile.kotpass.models.EntryValue.Plain(notes))) }) } }
        external("他アプリで編集", keepCopy = true); idle()
        var vs = versions(id)
        assertEquals(listOf(V[1], V[0]), vs.map { it.text }); assertTrue(vs[0].anchored)
        // our next save continues the chain from the other app's text
        saveMemo(openMemo(id), "また編集")
        vs = versions(id)
        assertEquals(listOf("他アプリで編集", V[1], V[0]), vs.map { it.text })
        // the viewer also lists the other app's full copy
        openMemo(id).openHistory(); idle()
        assertNotNull(findTag<View>(dv.current!!.frame, "std-delete-1")); dv.current!!.dismiss()
        // without a copy to anchor on, older versions are reported as not restorable (never applied to the wrong text)
        external("履歴なしで編集", keepCopy = false); idle()
        Session.update { Order.deleteHistory(it, id, null) }; idle()
        vs = versions(id)
        assertTrue(vs.all { it.text == null })
        assertTrue(dv.highlighted(vs[0]).toString().contains("復元できません"))
    }

    @Test fun foreignMemo_showsStandardHistoryLikeKeys() {
        val id = ids["foreign"]!!
        val ms = openMemo(id)
        assertFalse(ms.appMemo); assertEquals("- 履歴", ms.historyLink.text.toString())
        saveMemo(ms, "一行目\n変更")
        assertEquals(1, hist(id).size); assertNull(entry(id).customData[io.github.zmlworks.kpexplorer.db.MemoDiff.CD])
        val v = openMemo(id).openHistory()!!; idle()
        assertSame(io.github.zmlworks.kpexplorer.ui.HistoryViewer.current, v)
        assertNotNull(findTag<View>(v.frame, "history-delete-1")); v.dismiss()
    }

    @Test fun memoViewer_readOnly_noDelete() {
        val id = memoId()
        for (s in V.take(3)) saveMemo(openMemo(id), s)
        Session.beginTempView(Session.db!!, "t")
        try {
            val v = dv.show(a, id)!!
            assertNotNull(findTag<View>(v.frame, "diff-version-1"))
            assertNull(findTag<View>(v.frame, "history-delete-1"))
            assertTrue(v.buttonViews.none { it.text.toString() == "全て削除" })
            v.dismiss()
        } finally { Session.exitTempView() }
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
        // an empty copy left by 0.4.16 or earlier on an app-made key is removed on open; real copies, and
        // entries of other apps, are left alone
        val made = entry(id)
        val emptyCopy = Order.newKey().copy(uuid = id)
        val foreignId = ids["Gmail"]!!
        Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map {
            when (it.uuid) { id -> it.copy(history = listOf(emptyCopy) + it.history); foreignId -> it.copy(history = listOf(Order.newKey().copy(uuid = foreignId, customData = emptyMap()))); else -> it } }) } }
        assertEquals(2, hist(id).size)
        val cleaned = Order.dropEmptyCreationHistory(Session.db!!)
        assertEquals(listOf("pw"), Tree.findEntry(cleaned, id)!!.second.history.map { Tree.password(it) })
        assertEquals(1, Tree.findEntry(cleaned, foreignId)!!.second.history.size)
        assertSame("nothing to drop → same database", cleaned, Order.dropEmptyCreationHistory(cleaned))
        assertEquals(made.fields.entries.associate { it.key to it.value.content }, Tree.findEntry(cleaned, id)!!.second.fields.entries.associate { it.key to it.value.content })
        // the key screen's own "new key" path (no NewKeyScreen) also leaves none
        val k2 = KeyScreen(a, null, Tree.root(Session.db!!).uuid); a.show(k2); idle()
        k2.values["Title"] = "別の鍵"; k2.values["Password"] = "x"; k2.dirty = true
        assertTrue(k2.save(silent = true)); idle()
        assertEquals(0, Tree.root(Session.db!!).entries.first { Tree.title(it) == "別の鍵" }.history.size)
    }
}
