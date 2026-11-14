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

/** 0.4.10: recycle bin in the path tree, detail column widths / touch, backup rename, empty new-key fields. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0410Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0410").mkdirs() }
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
            v.measure(View.MeasureSpec.makeMeasureSpec(W.dp(230), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_, View.MeasureSpec.AT_MOST))
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

    // ---------------------------------------------------------------- Q1: where restore remembers the folder

    @Test fun previousParentGroup_survivesSaveAndReopen_andMissingFolderGoesToRoot() {
        var d = Session.db!!
        val e = Tree.findEntry(d, ids["old"]!!)!!.second
        assertEquals("deleting stores the folder in the standard KeePass field", ids["G"], e.previousParentGroup)
        assertEquals(app.keemobile.kotpass.models.FormatVersion(4, 1), d.header.version)
        // written to the file as <PreviousParentGroup> (KDBX 4.1) and read back after reopen
        d = Kdbx.open(Kdbx.encode(d), key)
        assertEquals(ids["G"], Tree.findEntry(d, ids["old"]!!)!!.second.previousParentGroup)
        val back = Tree.restoreFromBin(d, ids["old"]!!)
        assertEquals(ids["G"], Tree.parentOf(Tree.root(back), ids["old"]!!)!!.uuid)
        assertNull("cleared after restore", Tree.findEntry(back, ids["old"]!!)!!.second.previousParentGroup)
        // original folder gone (permanently deleted) -> root
        val gone = Tree.deletePermanently(d, ids["G"]!!)
        val r2 = Tree.restoreFromBin(gone, ids["old"]!!)
        assertEquals(Tree.root(r2).uuid, Tree.parentOf(Tree.root(r2), ids["old"]!!)!!.uuid)
    }

    // ---------------------------------------------------------------- 2: bin in the path tree

    @Test fun pathTreeBinLongPress_showsEmptyBinMenu() {
        val ex = a.explorer!!; layoutHost()
        val bin = Tree.recycleBinId(Session.db!!)!!
        val tree = ex.folderTree!!
        Menus.lastView = null
        // 0.4.23: other folders have a menu too (開く / 下部ファイルをすべて表示), without the bin item
        assertTrue(tree.longPress(ids["G"]!!)); idle()
        assertFalse(texts(Menus.lastView!!).any { it.startsWith(app().getString(R.string.empty_recycle_bin)) })
        Menus.dismiss(); Menus.lastView = null
        assertTrue(tree.longPress(bin)); idle()
        val items = texts(Menus.lastView!!)
        assertTrue(items.toString(), items.any { it.startsWith(app().getString(R.string.empty_recycle_bin)) })
        assertEquals("long press does not navigate", Tree.root(Session.db!!).uuid, ex.current)
        val row = tree.rows.first { it.tag == bin }
        val loc = IntArray(2); row.getLocationInWindow(loc)
        shot("v0410/02-path-bin-menu.png", menuAt = (loc[0] + W.dp(90)) to (loc[1] + row.height / 2))
        // the menu's "empty" asks, then empties
        Menus.dismiss()
        ex.pathLongPress(bin, 0, 0)
        ex.askEmptyBin(); idle()
        Dlg.last!!.buttonViews.first().performClick(); idle()
        assertEquals(0, Tree.recycleBinChildCount(Session.db!!))
    }

    @Test fun pathTreeBinRow_realLongPressThenRelease_doesNotNavigate() {
        val ex = a.explorer!!; layoutHost()
        val bin = Tree.recycleBinId(Session.db!!)!!
        val row = ex.folderTree!!.rows.first { it.tag == bin }
        Menus.lastView = null
        row.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, row.width - 10f, row.height / 2f, 0))
        ShadowLooper.idleMainLooper(android.view.ViewConfiguration.getLongPressTimeout() + 50L, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertNotNull("menu shown after the hold", Menus.lastView)
        row.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, row.width - 10f, row.height / 2f, 700)); idle()
        assertEquals(Tree.root(Session.db!!).uuid, ex.current)
        // short tap still opens the bin
        Menus.dismiss()
        row.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, row.width - 10f, row.height / 2f, 1000))
        row.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, row.width - 10f, row.height / 2f, 1050)); idle()
        assertEquals(bin, ex.current)
    }

    // ---------------------------------------------------------------- 3: column widths

    @Test fun detailColumns_dragHeaderDivider_resizesAndPersists() {
        val ex = details()
        val n0 = ex.colName; val u0 = ex.colUser
        assertTrue(n0 > 0 && u0 > 0)
        shot("v0410/01-details-default.png")
        val list = find(ex.view, ListView::class.java)!!
        val header = (list.parent as ViewGroup).getChildAt(0) as LinearLayout
        assertEquals(1, ex.headerDividerAt(n0.toFloat()))
        assertEquals(2, ex.headerDividerAt((n0 + u0).toFloat()))
        assertEquals(0, ex.headerDividerAt(n0 / 2f))
        // drag the 名前 | ユーザー名 divider 60dp to the left
        val y = header.height / 2f
        assertTrue(header.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, n0.toFloat(), y, 0)))
        header.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, n0 - W.dp(30).toFloat(), y, 30))
        header.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, n0 - W.dp(60).toFloat(), y, 60))
        header.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, n0 - W.dp(60).toFloat(), y, 90)); idle()
        assertEquals(n0 - W.dp(60), ex.colName)
        // then widen the user column by 40dp
        val b2 = (ex.colName + ex.colUser).toFloat()
        header.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, b2, y, 100))
        header.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, b2 + W.dp(40), y, 130))
        header.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, b2 + W.dp(40), y, 160)); idle()
        assertEquals(u0 + W.dp(40), ex.colUser)
        layoutHost()
        assertEquals("header follows", ex.colName, header.getChildAt(0).width)
        val firstRow = list.getChildAt(0) as LinearLayout
        assertEquals("rows follow", ex.colName, firstRow.getChildAt(0).width)
        assertEquals(ex.colUser, firstRow.getChildAt(1).width)
        shot("v0410/03-details-resized.png")
        // persisted: a new explorer starts with the same widths
        val keepN = ex.colName; val keepU = ex.colUser
        a.explorer = null; a.showExplorer(); idle(); layoutHost()
        val ex2 = a.explorer!!
        assertFalse(ex2.iconMode)
        assertTrue(kotlin.math.abs(ex2.colName - keepN) <= W.dp(1)); assertTrue(kotlin.math.abs(ex2.colUser - keepU) <= W.dp(1))
        // limits: cannot squeeze the name column below the minimum
        ex2.setColumnWidths(1, 1)
        assertEquals(W.dp(ExplorerScreen.MIN_NAME_DP), ex2.colName); assertEquals(W.dp(ExplorerScreen.MIN_USER_DP), ex2.colUser)
        ex2.resetColumnWidths()
        assertFalse(App.prefs(app()).contains(ExplorerScreen.PREF_COL_NAME))
    }

    // ---------------------------------------------------------------- 4: touch outside the name scrolls

    @Test fun detailTouch_swipeNeverStartsDrag() {
        val ex = details()
        val list = find(ex.view, ListView::class.java)!!
        val row = list.getChildAt(4)
        val y = row.top + row.height / 2f
        // user-name column: vertical move does not start a drag
        val xUser = ex.colName + ex.colUser / 2f
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, xUser, y, 0))
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, xUser, y - W.dp(40), 40))
        assertFalse(ex.dragging)
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, xUser, y - W.dp(40), 80)); idle()
        // date column: same
        val xDate = ex.colName + ex.colUser + W.dp(20).toFloat()
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, xDate, y, 100))
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, xDate, y + W.dp(40), 140))
        assertFalse(ex.dragging)
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, xDate, y + W.dp(40), 180)); idle()
        // tap on the user column still selects
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, xUser, y, 200))
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, xUser, y, 240)); idle()
        assertEquals(ex.rows[4].uuid, ex.selection)
        // 0.4.27: the name column needs a long press again (0.4.15–0.4.26 dragged at once there)
        val xName = W.dp(60).toFloat()
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, xName, y, 300))
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, xName, y + W.dp(30), 340))
        assertFalse(ex.dragging)
        list.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, xName, y + W.dp(30), 380)); idle()
        assertFalse(ex.dragging)
    }

    // ---------------------------------------------------------------- 5: rename internal backups

    @Test fun store_renameBackup_validates() {
        val s = a.store
        val f1 = s.backup(java.time.LocalDate.of(2026, 11, 1)); val f2 = s.backup(java.time.LocalDate.of(2026, 11, 2))
        f1.setLastModified(1_700_000_000_000L)
        val r = s.renameBackup(f1, "  銀行を整理する前.kdbx ")
        assertEquals("銀行を整理する前.kdbx", r.name); assertTrue(r.isFile); assertFalse(f1.exists())
        assertEquals("mtime (list order) kept", 1_700_000_000_000L, r.lastModified())
        for (bad in listOf("", "  ", ".kdbx", "a/b", "..", "../x", "a\\b", ".hidden", "x:y", "a*b", "a\nb", "x".repeat(101)))
            assertThrows(bad, Store.RenameException::class.java) { s.renameBackup(r, bad) }
        assertThrows(Store.RenameException::class.java) { s.renameBackup(r, f2.nameWithoutExtension) }
        assertThrows("case-insensitive clash", Store.RenameException::class.java) { s.renameBackup(r, f2.name.uppercase()) }
        assertEquals(r, s.renameBackup(r, "銀行を整理する前"))
        val c = s.renameBackup(r, "Before")
        val c2 = s.renameBackup(c, "BEFORE"); assertEquals("BEFORE.kdbx", c2.name); assertTrue(c2.isFile)
        assertEquals(setOf("BEFORE.kdbx", f2.name), s.backups().map { it.name }.toSet())
        assertEquals("2026-11-01", Store.backupBaseName("2026-11-01.KDBX"))
    }

    @Test fun backupScreen_renameDialog() {
        a.store.backup(java.time.LocalDate.of(2026, 11, 3))
        val bs = BackupScreen(a); a.show(bs); idle()
        val f = bs.files.first()
        assertTrue(bs.menuFor(f).any { it.label == "名前の変更..." })
        bs.rename(f); idle()
        val d = Dlg.last!!
        val field = find(d.frame, android.widget.EditText::class.java)!!
        assertEquals("2026-11-03", field.text.toString())
        shot("v0410/04-backup-rename.png", dialog = true)
        field.setText("a/b"); d.buttonViews.first().performClick(); idle()
        assertTrue("refused, prompt stays", d.isShowing)
        Dlg.last?.takeIf { it !== d }?.dismiss(); idle()
        field.setText("移行前"); d.buttonViews.first().performClick(); idle()
        assertFalse(d.isShowing)
        assertEquals(listOf("移行前.kdbx"), bs.files.map { it.name })
        assertEquals("移行前.kdbx", bs.selected?.name)
    }

    // ---------------------------------------------------------------- 6: empty new-key fields = deleted

    @Test fun createdFields_emptyOnesAreLeftOut() {
        val v = linkedMapOf("Title" to "Bank", "UserName" to "", "Password" to "pw", "URL" to "", "Notes" to "", "PIN" to "1234", "Q" to "")
        val (vals, layout) = NewKeyScreen.createdFields(v, listOf("PIN", "Q"), emptyList())
        assertEquals(listOf(Order.F_TITLE, Order.F_PASS, "PIN"), layout.map { it.first })
        assertTrue(layout.all { it.second })
        assertFalse("empty custom not created", vals.containsKey("Q"))
        assertEquals("1234", vals["PIN"])
        val (v2, l2) = NewKeyScreen.createdFields(linkedMapOf("Title" to "", "UserName" to "", "Password" to "", "URL" to "u", "Notes" to "n"), emptyList(), listOf("仕事"))
        assertEquals("(無題)", v2["Title"])
        assertEquals(listOf(Order.F_TITLE, Order.F_URL, Order.F_NOTES, Order.F_TAGS), l2.map { it.first })
    }

    @Test fun newKey_emptyFieldsNotInTheCreatedKey_andBackInEditor() {
        val nk = NewKeyScreen(a, Tree.root(Session.db!!).uuid); a.show(nk); idle()
        nk.editors[Order.F_TITLE]!!.setText("ポイントカード")
        nk.editors[Order.F_PASS]!!.setText("9876")
        nk.addCustomField("会員番号", "A-1"); nk.addCustomField("空の欄")
        assertTrue(nk.create()); idle()
        val ks = a.screen as KeyScreen
        val e = Tree.root(Session.db!!).entries.first { Tree.title(it) == "ポイントカード" }
        assertEquals(listOf(Order.F_TITLE, Order.F_PASS, "会員番号"), Order.layout(e).map { it.first })
        assertFalse(e.fields.containsKey("空の欄"))
        assertEquals("", Order.string(e, "URL"))
        // reopened: still deleted (not shown), and the editor offers them in the hidden zone
        val ks2 = KeyScreen(a, e.uuid, Tree.root(Session.db!!).uuid); a.show(ks2); idle()
        assertEquals(listOf(Order.F_TITLE, Order.F_PASS, "会員番号"), ks2.layout.map { it.first })
        ks2.editLayout(); idle()
        val snap = ks2.arrangeEditor!!.snapshot()
        assertTrue(snap.contains(Order.F_URL to false)); assertTrue(snap.contains(Order.F_USER to false))
        Dlg.last?.dismiss(); idle()
        assertNotNull(ks)
    }

    @Test fun newKey_nothingTyped_isNotRegistered() {
        val before = Tree.root(Session.db!!).entries.size
        val nk = NewKeyScreen(a, Tree.root(Session.db!!).uuid); a.show(nk); idle()
        nk.setIcon("mail"); nk.addCustomField("空の欄")
        assertTrue(nk.dirty); assertFalse(nk.hasInput)
        assertFalse(nk.create()); idle()
        assertEquals(before, Tree.root(Session.db!!).entries.size)
        assertTrue("Back leaves without asking", nk.onBack()); idle()
        assertNull(Dlg.last)
        assertEquals(before, Tree.root(Session.db!!).entries.size)
    }
}
