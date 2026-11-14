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

/** 0.4.21: hidden path-tree rows (the recycle bin) no longer catch drops; added fields leave a Title-only history copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0421Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0421").mkdirs() }
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


    private fun binId() = Tree.recycleBinId(Session.db!!)!!
    private fun pathBox(): android.widget.ScrollView = a.explorer!!.pathLines.first().let { v -> var p = v.parent; while (p !is android.widget.ScrollView) p = p.parent; p }
    private fun screenRect(v: View): android.graphics.Rect { val l = IntArray(2); v.getLocationOnScreen(l); return android.graphics.Rect(l[0], l[1], l[0] + v.width, l[1] + v.height) }
    private fun hasFrame(g: android.widget.AbsListView, id: UUID): Boolean {
        val pos = a.explorer!!.rows.indexOfFirst { it.uuid == id }
        return g.getChildAt(pos - g.firstVisiblePosition)?.foreground != null
    }
    private fun firstKey() = a.explorer!!.rows.first { it.kind == Order.Kind.KEY }.uuid

    // ---------------------------------------------------------------- 1 hidden path rows
    @Test fun hiddenPathRow_recycleBin_doesNotCatchDropsOverTheList() {
        val ex = icons(); val g = grid()
        val binLine = ex.pathLines.first { it.tag == binId() }
        val box = screenRect(pathBox()); val bin = screenRect(binLine)
        assertFalse("the bin row is scrolled out of the path box", binLine.getGlobalVisibleRect(android.graphics.Rect()))
        assertTrue("…and its screen position lies over the list: $bin vs ${screenRect(g)}", bin.top > box.bottom && screenRect(g).contains(bin.centerX(), bin.centerY()))
        val id = firstKey()
        val (x, y) = cellCenter(g, id)
        val ty = bin.exactCenterY()
        // the cell under the finger there
        val under = ex.rows.indices.drop(g.firstVisiblePosition).first { p -> g.getChildAt(p - g.firstVisiblePosition)?.let { c -> val r = screenRect(c); ty >= r.top && ty < r.bottom && x + W.dp(70) >= r.left && x + W.dp(70) < r.right } == true }
        val target = ex.rows[under]
        val tr = cellRect(g, target.uuid); val tx = tr.exactCenterX()
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        touch(MotionEvent.ACTION_MOVE, tx, ty); touch(MotionEvent.ACTION_MOVE, tx + 1, ty)
        assertTrue(ex.dragging)
        val d = ex.hit(id, tx + 1, ty)!!
        assertFalse("not the hidden path row: $d", d.pathFolder)
        idle(); layoutHost()
        assertEquals(target.uuid, d.target)
        assertFalse("the recycle bin tile has no frame", hasFrame(g, binId()))
        assertTrue("the cell under the finger is marked", hasFrame(g, target.uuid))
        shot("v0421/01-drag-correct-target.png")
        touch(MotionEvent.ACTION_UP, tx + 1, ty); idle()
        val parent = Tree.parentOf(Tree.root(Session.db!!), id)!!.uuid
        assertNotEquals("not moved into the recycle bin", binId(), parent)
        if (target.kind == Order.Kind.FOLDER && d.mode == M.INTO) assertEquals(target.uuid, parent)
    }

    @Test fun visiblePathRows_stillTakeDrops_bothEndsOfTheBox() {
        val ex = icons(); val g = grid()
        val id = firstKey()
        val work = ids["G"]!!
        val wl = screenRect(ex.pathLines.first { it.tag == work })
        assertTrue(screenRect(pathBox()).contains(wl.centerX(), wl.centerY()))
        val (x, y) = cellCenter(g, id)
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        touch(MotionEvent.ACTION_MOVE, wl.exactCenterX(), wl.exactCenterY()); touch(MotionEvent.ACTION_MOVE, wl.exactCenterX() + 1, wl.exactCenterY())
        val d = ex.hit(id, wl.exactCenterX() + 1, wl.exactCenterY())!!
        assertEquals(ExplorerScreen.Drop(M.INTO, work, pathFolder = true), d)
        idle(); layoutHost()
        assertFalse("the list tile of the same folder is not framed (the path row is highlighted)", hasFrame(g, work))
        touch(MotionEvent.ACTION_UP, wl.exactCenterX() + 1, wl.exactCenterY()); idle()
        assertEquals(work, Tree.parentOf(Tree.root(Session.db!!), id)!!.uuid)
        // scroll the path tree to the bottom: now the bin row is visible and takes the drop
        val ex2 = icons(); val g2 = grid()
        pathBox().scrollTo(0, 100000); layoutHost()
        val bl = screenRect(ex2.pathLines.first { it.tag == binId() })
        assertTrue(screenRect(pathBox()).contains(bl.centerX(), bl.centerY()))
        val k2 = firstKey()
        val (x2, y2) = cellCenter(g2, k2)
        touch(MotionEvent.ACTION_DOWN, x2, y2); hold()
        touch(MotionEvent.ACTION_MOVE, bl.exactCenterX(), bl.exactCenterY()); touch(MotionEvent.ACTION_MOVE, bl.exactCenterX() + 1, bl.exactCenterY())
        assertEquals(ExplorerScreen.Drop(M.INTO, binId(), pathFolder = true), ex2.hit(k2, bl.exactCenterX() + 1, bl.exactCenterY()))
        touch(MotionEvent.ACTION_UP, bl.exactCenterX() + 1, bl.exactCenterY()); idle()
        assertEquals(binId(), Tree.parentOf(Tree.root(Session.db!!), k2)!!.uuid)
    }

    // ---------------------------------------------------------------- 2 added fields → Title-only copy
    private fun cur(id: UUID) = entry(id).fields.entries.associate { it.key to it.value.content }

    @Test fun addingFields_leavesTitleOnlyCopy_noEmptyPlaceholders() {
        val id = ids["Gmail"]!!
        Session.update { Order.saveEntry(it, id, cur(id) + ("PIN" to "1234"), listOf(), null) }; idle()
        val h1 = hist(id).last()
        assertEquals(setOf("Title"), h1.fields.keys); assertEquals("Gmail", Tree.title(h1)); assertTrue(h1.tags.isEmpty())
        assertTrue("still a normal KDBX history entry", Order.isChangedOnly(h1))
        // addition + a real change: only the real change is stored
        later(); Session.update { Order.saveEntry(it, id, cur(id) + ("Password" to "p-2") + ("秘密の質問" to "犬の名前") + ("Notes" to "メモを追加"), listOf(), null) }; idle()
        val h2 = hist(id).last()
        assertEquals(setOf("Title", "Password"), h2.fields.keys)
        assertEquals("p", h2.fields["Password"]!!.content)
        // a field emptied keeps its old value (not an addition)
        later(); Session.update { Order.saveEntry(it, id, cur(id) + ("PIN" to ""), listOf(), null) }; idle()
        assertEquals("1234", hist(id).last().fields["PIN"]!!.content)
        // the file stays valid KDBX
        Session.flush(); idle()
        val disk = Tree.findEntry(Kdbx.open(a.store.read(), key), id)!!.second
        assertEquals(hist(id).map { it.fields.keys }, disk.history.map { it.fields.keys })
    }

    @Test fun viewer_showsFieldsAdded_forTitleOnlyCopy_andNamesForEmptyPreviousValues() {
        val id = ids["Gmail"]!!
        Session.update { Order.saveEntry(it, id, cur(id) + ("PIN" to "1234"), listOf(), null) }; idle()
        later(); Session.update { Order.renameEntry(it, id, "Gmail 個人") }; idle()
        // a copy whose previous values are empty (as 0.4.19–0.4.20 or another app may store): names are known
        val e = entry(id)
        val older = e.copy(history = emptyList(), customData = emptyMap(), fields = app.keemobile.kotpass.models.EntryFields.of(
            "Title" to app.keemobile.kotpass.models.EntryValue.Plain("Gmail"),
            "URL" to app.keemobile.kotpass.models.EntryValue.Plain(""),
            "暗証番号" to app.keemobile.kotpass.models.EntryValue.Encrypted(app.keemobile.kotpass.cryptography.EncryptedValue.fromString(""))))
        Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map { if (it.uuid == id) it.copy(history = listOf(older) + it.history) else it }) } }; idle()
        val ks = openKey(id)
        ks.historyLink!!.performClick(); idle()
        val v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.current!!
        // #1 = rename (old → new), #2 = PIN added (Title only, unchanged) → 「項目の追加」, #3 = names from empty values
        val c1 = findTag<View>(v.frame, "history-changed-1")!!
        assertTrue(texts(c1).contains("Gmail → Gmail 個人")); assertNull(findTag<View>(c1, "changed-added"))
        val c2 = findTag<View>(v.frame, "history-changed-2")!!
        assertEquals("項目の追加", (findTag<View>(c2, "changed-added") as android.widget.TextView).text.toString())
        assertTrue(texts(c2).none { it == "Title" || it.contains("(空)") })
        val c3 = findTag<View>(v.frame, "history-changed-3")!!
        assertEquals("項目の追加: URL、暗証番号", (findTag<View>(c3, "changed-added") as android.widget.TextView).text.toString())
        assertTrue(texts(c3).none { it.contains("(空)") || it == "******" })
        Dlg.last = v; shot("v0421/02-history-fields-added.png", dialog = true)
        v.dismiss()
        // English
        L.setLang(app(), L.Lang.EN)
        val v2 = io.github.zmlworks.kpexplorer.ui.HistoryViewer.show(a, id) {}!!; idle()
        assertEquals("Fields added", (findTag<View>(findTag<View>(v2.frame, "history-changed-2")!!, "changed-added") as android.widget.TextView).text.toString())
        assertEquals("Fields added: URL, 暗証番号", (findTag<View>(findTag<View>(v2.frame, "history-changed-3")!!, "changed-added") as android.widget.TextView).text.toString())
        v2.dismiss()
    }

    @Test fun keyPage_fillingEmptyNotes_titleOnlyCopy() {
        val id = ids["Amazon"]!!
        val ks = openKey(id)
        ks.boxes["Notes"]!!.field.setText("初めてのメモ")
        assertTrue(ks.save(silent = true)); idle()
        assertEquals("notes filled in from empty = an addition", setOf("Title"), hist(id).last().fields.keys)
    }

    // ---------------------------------------------------------------- 3 no one-time HistoryMaxItems rewrite
    @Test fun historyMaxItems_storedFive_isKept_onOpen() {
        Security.opened(a, Session.db!!.modifyContent { copy(meta = meta.copy(historyMaxItems = 5)) }); idle()
        assertEquals(5, Session.db!!.content.meta.historyMaxItems)
        assertFalse(App.prefs(app()).contains("historyMaxRestored"))
        assertEquals(10, Kdbx.create("x", key).content.meta.historyMaxItems)
    }
}
