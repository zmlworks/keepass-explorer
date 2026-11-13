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

/** 0.4.18: memo history = ordinary History copies holding only the change; folder counts (folders,keys,memos). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0418Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0418").mkdirs() }
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
    private val D = io.github.zmlworks.kpexplorer.db.MemoDiff
    private fun openMemo(id: UUID): MemoScreen { val ms = MemoScreen(a, id); a.show(ms); idle(); layoutHost(); return ms }
    private fun saveMemo(ms: MemoScreen, s: String) { ms.text.setText(s); assertTrue(ms.save()); idle(); later() }
    private val V = listOf("牛乳\n卵2個\nパン", "牛乳\n卵3個\nパン", "牛乳\n卵3個\nパン\nバター", "豆乳\n卵3個\nバター", "豆乳\n卵3個\nバター\n\nメモ",
        "トマト\n豆乳\n卵3個\nバター\n\nメモ", "トマト\n豆乳\n卵4個\nバター\n\nメモ")

    // ---------------------------------------------------------------- the change text
    @Test fun changeText_format() {
        assertEquals("- 卵2個\n+ 卵3個", D.changeText("牛乳\n卵2個\nパン", "牛乳\n卵3個\nパン"))
        assertEquals("insert", "- ab\n+ aXYb", D.changeText("ab", "aXYb"))
        assertEquals("delete", "- aXYb\n+ ab", D.changeText("aXYb", "ab"))
        assertEquals("at the start", "- Xa\n+ Ya", D.changeText("Xabc", "Yabc"))
        assertEquals("at the end", "- cX\n+ cY", D.changeText("abcX", "abcY"))
        assertEquals("added at the end", "- c\n+ cde", D.changeText("abc", "abcde"))
        assertEquals("into an empty memo", "+ abc", D.changeText("", "abc"))
        assertEquals("all removed", "- abc", D.changeText("abc", ""))
        assertEquals("two changes", "- a1b\n+ a9b\n\n- b2c\n+ b8c", D.changeText("aaa1bbbbb2ccc", "aaa9bbbbb8ccc"))
        assertEquals("close changes are one", "- a1b2c\n+ a9b8c", D.changeText("aa1b2cc", "aa9b8cc"))
        assertEquals("newline shown as ↵", "- ↵b\n+ ↵c", D.changeText("a\nb", "a\nc"))
        assertEquals("line added", "- ン\n+ ン↵バター", D.changeText("パン", "パン\nバター"))
        assertEquals("emoji is one character", "- x😀y\n+ x😃y", D.changeText("x😀y", "x😃y"))
        assertEquals("", D.changeText("same", "same"))
        // a huge rewrite is one change (no character diff), quickly
        val big1 = (0 until 3000).joinToString("") { "a" + (it % 10) }; val big2 = (0 until 3000).joinToString("") { "b" + (it % 7) }
        val t0 = System.currentTimeMillis()
        val big = D.changeText("<" + big1 + ">", "<" + big2 + ">")
        assertTrue(System.currentTimeMillis() - t0 < 5000)
        assertEquals(2, big.split("\n").size); assertTrue(big.startsWith("- <a0")); assertTrue(big.endsWith("b3>"))
    }

    // ---------------------------------------------------------------- memo history = History copies with the change
    @Test fun memoHistory_isStandardHistoryWithChangesOnly_max5() {
        val id = memoId()
        saveMemo(openMemo(id), V[0])
        assertEquals("the first text of an empty memo leaves nothing", 0, hist(id).size)
        saveMemo(openMemo(id), V[1])
        val h = hist(id)
        assertEquals(1, h.size)
        assertEquals("買い物メモ", Tree.title(h[0])); assertEquals("- 卵2個\n+ 卵3個", Tree.notes(h[0]))
        assertTrue(D.isChangeEntry(entry(id), h[0]))
        assertTrue("no special marker", h[0].customData.keys.none { it.contains("Diff") })
        assertNull(entry(id).customData[D.LEGACY_CD])
        for (s in V.drop(2)) saveMemo(openMemo(id), s)
        assertEquals(5, hist(id).size)
        assertEquals("the newest", "- 卵3個\n+ 卵4個", Tree.notes(hist(id).last()))
        assertEquals("oldest trimmed", "- ン\n+ ン↵バター", Tree.notes(hist(id).first()))
        // an unchanged save adds nothing; a rename adds a copy with the old title and no text change
        assertTrue(openMemo(id).save()); assertEquals(5, hist(id).size)
        Session.update { Order.renameEntry(it, id, "買い物リスト") }; idle()
        assertEquals("買い物メモ", Tree.title(hist(id).last())); assertEquals("", Tree.notes(hist(id).last()))
        // a valid KDBX file that keeps all of it
        Session.flush(); idle()
        val disk = Tree.findEntry(Kdbx.open(a.store.read(), key), id)!!.second
        assertEquals(hist(id).map { Tree.notes(it) }, disk.history.map { Tree.notes(it) })
        assertEquals(V.last(), Tree.notes(disk))
    }

    @Test fun memoViewer_showsChanges_colours_deleteWithConfirm() {
        val id = memoId()
        for (s in V.take(5)) saveMemo(openMemo(id), s)
        // a copy left by another app (has a user name) is shown like a key's
        val other = entry(id).copy(history = listOf()).let { it.copy(fields = it.fields + ("UserName" to app.keemobile.kotpass.models.EntryValue.Plain("someone"))) }
        Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map { if (it.uuid == id) it.copy(history = listOf(other) + it.history) else it }) } }; idle()
        assertEquals(5, hist(id).size)
        val t0 = mtime(id)
        val ms = openMemo(id)
        assertEquals("- 差分", ms.historyLink.text.toString())
        ms.historyLink.performClick(); idle()
        var v = dv.current!!
        val t1 = findTag<View>(v.frame, "change-text-1") as android.widget.TextView
        assertEquals("- ー\n+ ー↵↵メモ", t1.text.toString())
        val sp = t1.text as android.text.Spanned
        val cols = sp.getSpans(0, sp.length, android.text.style.ForegroundColorSpan::class.java).map { it.foregroundColor }
        assertEquals(listOf(dv.MINUS, dv.PLUS), cols)
        assertNotNull("other app's copy shown like a key's", findTag<View>(v.frame, "history-snap-5"))
        assertNull(findTag<View>(v.frame, "change-5"))
        Dlg.last = v; shot("v0418/01-memo-diff-history.png", dialog = true)
        // delete #2 with confirmation (キャンセル first)
        findTag<View>(v.frame, "history-delete-2")!!.performClick(); idle()
        var c = latestDialog(); assertTrue(texts(c.frame).any { it.contains("履歴 #2") })
        Dlg.last = c; shot("v0418/02-memo-confirm-delete.png", dialog = true)
        clickButton(c, "キャンセル"); assertEquals(5, hist(id).size)
        val gone = Tree.notes(hist(id)[hist(id).size - 2])
        findTag<View>(v.frame, "history-delete-2")!!.performClick(); idle(); clickButton(latestDialog(), "削除")
        assertEquals(4, hist(id).size); assertTrue(hist(id).none { Tree.notes(it) == gone })
        assertEquals(t0, mtime(id))
        v = dv.current!!
        clickButton(v, "全て削除"); c = latestDialog(); assertTrue(texts(c.frame).any { it.contains("全て (4 件)") }); clickButton(c, "削除")
        assertEquals(0, hist(id).size); assertEquals(t0, mtime(id)); assertFalse(ms.dirty)
        assertTrue(texts(dv.current!!.frame).any { it.startsWith("履歴はありません") })
    }

    @Test fun memoViewer_readOnly_noDelete() {
        val id = memoId()
        for (s in V.take(3)) saveMemo(openMemo(id), s)
        Session.beginTempView(Session.db!!, "t")
        try {
            val v = dv.show(a, id)!!
            assertNotNull(findTag<View>(v.frame, "change-1"))
            assertNull(findTag<View>(v.frame, "history-delete-1"))
            assertTrue(v.buttonViews.none { it.text.toString() == "全て削除" })
            v.dismiss()
        } finally { Session.exitTempView() }
    }

    @Test fun foreignMemo_historyLikeKeys() {
        val id = ids["foreign"]!!
        val ms = openMemo(id); assertEquals("- 履歴", ms.historyLink.text.toString())
        saveMemo(ms, "一行目\n変更")
        // 0.4.19: like a key, the copy holds only what changed (Notes as the change text)
        assertEquals("- ↵二行目\n+ ↵変更", Tree.notes(hist(id).single()))
    }

    // ---------------------------------------------------------------- 0.4.17 data
    @Test fun migration_from0417_convertsToChangeCopies_andRemovesCustomData() {
        val id = memoId()
        fun legacy(e: app.keemobile.kotpass.models.Entry, recs: List<io.github.zmlworks.kpexplorer.db.MemoDiff.Rec>) =
            e.copy(customData = e.customData + (D.LEGACY_CD to app.keemobile.kotpass.models.CustomDataValue(D.encode(recs), java.time.Instant.now())))
        val c = "A\nB2\nC"; val v1 = "A\nB1\nC"; val v0 = "A\nB0"
        val recs = listOf(io.github.zmlworks.kpexplorer.db.MemoDiff.Rec(3_000_000L, D.hash(c), null, D.diff(c, v1)), io.github.zmlworks.kpexplorer.db.MemoDiff.Rec(2_000_000L, D.hash(v1), "旧題", D.diff(v1, v0)))
        Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map {
            if (it.uuid == id) legacy(it.copy(fields = it.fields + ("Notes" to app.keemobile.kotpass.models.EntryValue.Plain(c))), recs) else it }) } }; idle()
        val m = Order.migrateMemoDiffs(Session.db!!)
        val e = Tree.findEntry(m, id)!!.second
        assertNull(e.customData[D.LEGACY_CD])
        assertEquals(listOf(D.changeText(v0, v1), D.changeText(v1, c)), e.history.map { Tree.notes(it) })
        assertEquals(listOf("旧題", "買い物メモ"), e.history.map { Tree.title(it) })
        assertEquals(java.time.Instant.ofEpochMilli(2_000_000L), e.history[1].times!!.lastModificationTime)
        assertEquals(c, Tree.notes(e))
        assertSame("nothing left to do", m, Order.migrateMemoDiffs(m))
        // a chain that no longer fits the text (edited elsewhere, no copy) is dropped, the CustomData removed
        Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map {
            if (it.uuid == id) it.copy(fields = it.fields + ("Notes" to app.keemobile.kotpass.models.EntryValue.Plain("別の本文"))) else it }) } }; idle()
        val e2 = Tree.findEntry(Order.migrateMemoDiffs(Session.db!!), id)!!.second
        assertNull(e2.customData[D.LEGACY_CD]); assertEquals(0, e2.history.size); assertEquals("別の本文", Tree.notes(e2))
    }

    // ---------------------------------------------------------------- folder counts
    @Test fun folderCounts_foldersKeysMemos() {
        val db = Session.db!!
        val root = Tree.root(db)
        assertEquals("(3,8,2)", Order.counts(root))   // 仕事, 銀行, ごみ箱 / 8 keys / 買い物メモ + 他アプリのメモ
        assertEquals("(1,2,1)", Order.counts(Tree.findGroup(db, ids["G"]!!)!!))
        assertEquals("(0,0,0)", Order.counts(root.groups.first { it.name == "銀行" }))
        assertEquals("bin", "(0,1,0)", Order.counts(Tree.findGroup(db, Tree.recycleBinId(db)!!)!!))
        val p = io.github.zmlworks.kpexplorer.ui.Parts.pathTree(a, listOf(root, Tree.findGroup(db, ids["G"]!!)!!), showCounts = true)
        val counts = ArrayList<String>()
        fun walk(v: View) { if (v.tag == "count") counts.add((v as android.widget.TextView).text.toString()); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(p)
        assertEquals(listOf(" (3,8,2)", " (1,2,1)"), counts)
        val ex = icons(); ex.navigate(ids["G"]!!); idle(); layoutHost()
        shot("v0418/03-folder-counts.png")
    }
}
