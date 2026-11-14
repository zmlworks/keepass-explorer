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

/** 0.4.23: one-row settings with pickers, recycle bin settings / opening, tree long-press menu, 「下部ファイルをすべて表示」. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0423Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0423").mkdirs() }
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

    @Test fun menu_oneRowPerSetting_tapOpensPicker() {
        val ex = a.explorer!!
        val m = labels(ex.mainMenu())
        assertEquals(m.toString(), listOf("自動ロック: 5 分後", "Language: 端末に合わせる", "日付: YYYY/MM/DD"),
            m.filter { it.startsWith("自動ロック") || it.startsWith("Language") || it.startsWith("日付") })
        assertTrue(m.containsAll(listOf("ごみ箱を開く", "ごみ箱の設定")))
        assertTrue("no checked radio-like rows left", ex.mainMenu().none { it.checked && (it.label.startsWith("Language") || it.label.startsWith("日付") || it.label.startsWith("自動ロック")) })
        showMainMenu(); shot("v0423/01-main-menu.png", menuAt = (W_ - W.dp(240)) to W.dp(60)); Menus.dismiss()
        // date picker: options with today's example, current one checked; tap applies and closes
        item(ex.mainMenu(), "日付: YYYY/MM/DD").action(); idle()
        val today = java.time.LocalDate.now()
        assertEquals("YYYY/MM/DD  (%d/%02d/%02d)".format(today.year, today.monthValue, today.dayOfMonth), radio(0).text.toString())
        assertTrue(radio(0).isChecked); assertFalse(radio(1).isChecked)
        shot("v0423/02-date-picker.png", dialog = true)
        val d = Dlg.last!!
        radio(1).performClick(); idle()
        assertFalse(d.isShowing)
        assertEquals(L.DateOrder.MDY, L.dateOrder)
        assertTrue(labels(ex.mainMenu()).contains("日付: MM/DD/YYYY"))
        // auto-lock picker
        item(ex.mainMenu(), "自動ロック: 5 分後").action(); idle()
        assertTrue(radio(1).isChecked)
        shot("v0423/03-autolock-picker.png", dialog = true)
        radio(2).performClick(); idle()
        assertEquals(-1, a.autoLockMinutes())
        assertTrue(labels(ex.mainMenu()).contains("自動ロック: しない"))
        // language picker (system / 日本語 / English); choosing the current one changes nothing
        item(ex.mainMenu(), "Language: 端末に合わせる").action(); idle()
        assertEquals(listOf("端末に合わせる", "日本語", "English"), (0..2).map { radio(it).text.toString() })
        assertTrue(radio(0).isChecked)
        radio(0).performClick(); idle()
        assertEquals(L.Lang.SYSTEM, L.lang)
    }

    @Test fun languagePicker_switchesLanguage_binKeepsItsName() {
        Session.update { Tree.renameGroup(it, bin(), "ゴミ") }
        App.prefs(app()).edit().putInt("autolock", 0).commit()
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val act = ctl.get(); if (act.explorer == null) act.showExplorer(); idle()
        act.explorer!!.pickLanguage(); idle()
        radio(2).performClick(); idle()
        assertEquals(L.Lang.EN, L.lang)
        assertTrue(Session.isOpen)
        // re-opening the database (English UI) does not rename the bin either
        Security.opened(act, Session.db!!)
        assertEquals("ゴミ", binGroup().name)
    }

    @Test fun opening_doesNotRenameBin_norTurnTheBinBackOn() {
        Session.update { Tree.renameGroup(it, bin(), "Trash") }
        Security.opened(a, Session.db!!)
        assertEquals("Trash", binGroup().name)
        // KeePass "use recycle bin" off (another app, or 一度で完全削除) stays off
        Session.update { Tree.setRecycleBinEnabled(it, false, "ごみ箱") }
        Security.opened(a, Session.db!!)
        assertFalse(Session.db!!.content.meta.recycleBinEnabled)
        // with the bin off, delete is permanent and asks first
        val ex = a.explorer!!; ex.refresh(); idle()
        val id = ids["Gmail"]!!
        ex.askDelete(id); idle()
        val t = texts(latestDialog().frame).joinToString()
        assertTrue(t, t.contains("完全に削除"))
        latestDialog().buttonViews.first().performClick(); idle()
        assertNull(Tree.findEntry(Session.db!!, id))
        assertEquals("the bin keeps only the earlier item", 1, Tree.recycleBinChildCount(Session.db!!))
    }

    @Test fun binSettings_rename_empty_once_hide_days() {
        val ex = a.explorer!!
        item(ex.mainMenu(), "ごみ箱の設定").action(); idle()
        val d = Dlg.last!!
        fun t(tag: String) = (findTag<View>(d.frame, tag) as android.widget.TextView)
        assertEquals("名前: ごみ箱", t("bin-rename-text").text.toString())
        assertEquals("中身: 1 個", t("bin-empty-text").text.toString())
        assertEquals("日数で自動削除: しない", t("bin-days-text").text.toString())
        assertFalse((findTag<View>(d.frame, "bin-once") as android.widget.CheckBox).isChecked)
        assertFalse((findTag<View>(d.frame, "bin-hide") as android.widget.CheckBox).isChecked)
        shot("v0423/04-bin-settings.png", dialog = true)
        // rename
        t("bin-rename").performClick(); idle()
        val p = latestDialog()
        find(p.frame, android.widget.EditText::class.java)!!.setText("不要なもの")
        p.buttonViews.first().performClick(); idle()
        assertEquals("不要なもの", binGroup().name)
        assertEquals("名前: 不要なもの", t("bin-rename-text").text.toString())
        assertTrue("tree shows the new name", texts(ex.view).any { it.startsWith("不要なもの") })
        // hide → not in the tree / list / counts / move dialog; the menu still opens it
        val hide = findTag<View>(d.frame, "bin-hide") as android.widget.CheckBox
        hide.performClick(); idle()
        assertTrue(Tree.binHidden(Session.db!!))
        assertFalse(treeIds().contains(bin()))
        assertFalse(ex.rows.any { it.uuid == bin() })
        assertEquals("(16,38,1)", Order.counts(Tree.root(Session.db!!), Tree.hiddenGroups(Session.db!!)))
        // once: KeePass flag off
        val once = findTag<View>(d.frame, "bin-once") as android.widget.CheckBox
        once.performClick(); idle()
        assertFalse(Session.db!!.content.meta.recycleBinEnabled)
        once.performClick(); idle()
        assertTrue(Session.db!!.content.meta.recycleBinEnabled)
        // days: picker; an item older than the limit asks, then goes
        val oldId = ids["old"]!!
        val gm = ids["Gmail"]!!
        Session.update { Tree.moveToBin(it, gm, "ごみ箱", java.time.Instant.now().minus(java.time.Duration.ofDays(40))) }
        t("bin-days").performClick(); idle()
        assertEquals(BinSettings.DAYS.map { BinSettings.daysLabel(it) }, (0 until BinSettings.DAYS.size).map { radio(it).text.toString() })
        shot("v0423/05-bin-days-picker.png", dialog = true)
        radio(3).performClick(); idle()   // 30 days
        val c = latestDialog()
        assertTrue(texts(c.frame).joinToString().contains("1 個"))
        c.buttonViews.first().performClick(); idle()
        assertEquals(30, Tree.binAutoDeleteDays(Session.db!!))
        assertEquals(1, Tree.recycleBinChildCount(Session.db!!))
        assertNull(Tree.findEntry(Session.db!!, gm))
        assertNotNull("moved to the bin today: stays", Tree.findEntry(Session.db!!, oldId))
        assertEquals("日数で自動削除: 30 日後", t("bin-days-text").text.toString())
        d.dismiss()
    }

    @Test fun openBinFromMenu_whileHidden_thenEmptyFromSettings() {
        val ex = a.explorer!!
        Session.update { Tree.setBinHidden(it, true) }; ex.refresh(); idle()
        assertFalse(treeIds().contains(bin()))
        item(ex.mainMenu(), "ごみ箱を開く").action(); idle()
        assertEquals(bin(), ex.current)
        assertTrue("inside the bin it is shown", treeIds().contains(bin()))
        assertEquals(listOf(ids["old"]!!), ex.rows.map { it.uuid })
        shot("v0423/06-hidden-bin-opened.png")
        ex.goUp(); idle()
        assertFalse(treeIds().contains(bin()))
        BinSettings.show(a); idle()
        val d = Dlg.last!!
        findTag<View>(d.frame, "bin-empty")!!.performClick(); idle()
        latestDialog().buttonViews.first().performClick(); idle()
        assertEquals(0, Tree.recycleBinChildCount(Session.db!!))
        assertFalse(findTag<View>(d.frame, "bin-empty")!!.isEnabled)
        d.dismiss()
    }

    @Test fun binSettings_travelWithTheFile_andPurgeOnOpen() {
        var db = Session.db!!
        db = Tree.setBinHidden(db, true)
        db = Tree.setBinAutoDeleteDays(db, 7)
        val gm = ids["Gmail"]!!
        db = Tree.moveToBin(db, gm, "ごみ箱", java.time.Instant.now().minus(java.time.Duration.ofDays(8)))
        val back = Kdbx.open(Kdbx.encodeVerified(db, keepSalt = true), key)
        assertTrue(Tree.binHidden(back)); assertEquals(7, Tree.binAutoDeleteDays(back))
        assertEquals(2, Tree.recycleBinChildCount(back))
        Security.opened(a, back)
        assertEquals("opened → the 8-day-old item is gone", 1, Tree.recycleBinChildCount(Session.db!!))
        assertNull(Tree.findEntry(Session.db!!, gm))
        assertNotNull(Tree.findEntry(Session.db!!, ids["old"]!!))
        assertTrue("recorded as deleted (sync)", Session.db!!.content.deletedObjects.any { it.id == gm })
        // nothing old left → same database; off = never
        assertSame(Session.db!!, Tree.purgeOldInBin(Session.db!!))
        val off = Tree.setBinAutoDeleteDays(Session.db!!, 0)
        assertSame(off, Tree.purgeOldInBin(off, java.time.Instant.now().plus(java.time.Duration.ofDays(999))))
        assertEquals(0, Tree.recycleBinChildCount(Tree.purgeOldInBin(Session.db!!, java.time.Instant.now().plus(java.time.Duration.ofDays(8)))))
    }

    @Test fun treeLongPress_menu_openAndShowAllBelow() {
        val ex = a.explorer!!; layoutHost()
        val g = ids["G"]!!
        assertEquals(listOf("開く", "下部ファイルをすべて表示"), labels(ex.treeMenu(g)).take(2))   // 0.4.24: more below
        assertEquals(listOf("開く", "下部ファイルをすべて表示", "|", "ごみ箱を空にする"), labels(ex.treeMenu(bin())).take(4))
        // a real long press on the 仕事 row: menu, no navigation, no drag
        val row = ex.folderTree!!.rows.first { it.tag == g }
        val l = IntArray(2); row.getLocationInWindow(l)
        val x = l[0] + row.width - W.dp(30).toFloat(); val y = l[1] + row.height / 2f
        Menus.lastView = null
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        touch(MotionEvent.ACTION_UP, x, y); idle()
        assertTrue(menuShowing())
        assertEquals(Tree.root(Session.db!!).uuid, ex.current)
        assertTrue(texts(Menus.lastView!!).containsAll(listOf("開く", "下部ファイルをすべて表示")))
        shot("v0423/07-tree-context-menu.png", menuAt = (l[0] + W.dp(90)) to (l[1] + row.height / 2))
        Menus.dismiss()
        // 開く = one tap
        item(ex.treeMenu(g), "開く").action(); idle()
        assertEquals(g, ex.current); assertFalse(ex.flat)
        assertEquals(listOf(ids["メール"]!!, ids["勤怠"]!!), ex.rows.map { it.uuid })
    }

    @Test fun showAllBelow_flatList_binExcluded_noReorder_noNewFolder() {
        val ex = a.explorer!!
        val root = Tree.root(Session.db!!).uuid
        item(ex.treeMenu(ids["G"]!!), "下部ファイルをすべて表示").action(); idle()
        assertTrue(ex.flat); assertEquals(ids["G"]!!, ex.current)
        assertEquals("display order, depth first (the sub folder comes first, as in the folder)", listOf(ids["会社メール"]!!, ids["議事録"]!!, ids["勤怠"]!!), ex.rows.map { it.uuid })
        val band = findTag<View>(ex.view, "flat-band") as android.widget.TextView
        assertEquals("「仕事」以下の鍵・メモをすべて表示中 (3 件)  ×", band.text.toString())
        assertTrue(ex.caption.title.text.toString().startsWith("仕事 以下すべて"))   // window title (accessibility)
        assertTrue("tree: 仕事 highlighted", ex.folderTree!!.rows.any { it.tag == ids["G"]!! })
        icons(); shot("v0423/08-flat-view-icons.png")
        if (ex.iconMode) ex.toggleView(); idle(); layoutHost(); layoutHost(); shot("v0423/09-flat-view-details.png")
        // tools: no new folder; up / back leave the mode
        assertFalse(ex.backgroundMenu().first { it.label == s(R.string.tool_new_folder) }.enabled)
        assertNull(ex.askNewFolder())
        // reorder off: dropping on the list does nothing (no drop target); the tree still takes drops
        val list = find(ex.view, ListView::class.java)!!
        val r = cellRect(list, ids["議事録"]!!)
        assertNull(ex.hit(ids["勤怠"]!!, r.exactCenterX(), r.exactCenterY()))
        val m = ex.folderTree!!.rows.first { it.tag == ids["メール"]!! }
        val ml = IntArray(2); m.getLocationOnScreen(ml)
        assertEquals("tree drop still works", M.INTO, ex.hit(ids["勤怠"]!!, ml[0] + m.width - W.dp(20).toFloat(), ml[1] + m.height / 2f)?.mode)
        // root: everything except the bin
        item(ex.treeMenu(root), "下部ファイルをすべて表示").action(); idle()
        assertEquals(42, ex.rows.size)
        assertFalse(ex.rows.any { it.uuid == ids["old"]!! })
        // the bin itself: its contents
        item(ex.treeMenu(bin()), "下部ファイルをすべて表示").action(); idle()
        assertEquals(listOf(ids["old"]!!), ex.rows.map { it.uuid })
        // back leaves the mode first (stays in the folder)
        ex.goBack(); idle()
        assertFalse(ex.flat); assertEquals(bin(), ex.current)
        // tapping the highlighted folder in flat mode returns to its normal view
        ex.showAllBelow(ids["G"]!!); idle()
        ex.folderTree!!.tap(ids["G"]!!); idle()
        assertFalse(ex.flat); assertEquals(ids["G"]!!, ex.current)
        // the band's × too
        ex.showAllBelow(ids["G"]!!); idle()
        findTag<View>(ex.view, "flat-band")!!.performClick(); idle()
        assertFalse(ex.flat)
    }

    @Test fun english_labels() {
        L.setLang(app(), L.Lang.EN)
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val act = ctl.get(); if (act.explorer == null) act.showExplorer(); idle()
        val ex = act.explorer!!
        assertEquals(listOf("Open", "Show all files below"), labels(ex.treeMenu(ids["G"]!!)).take(2))
        ex.showAllBelow(ids["G"]!!); idle()
        assertEquals("All keys and memos under \"仕事\" (3)  ×", (findTag<View>(ex.view, "flat-band") as android.widget.TextView).text.toString())
        BinSettings.show(act); idle()
        val tx = texts(Dlg.last!!.frame)
        assertTrue(tx.toString(), tx.containsAll(listOf("Name: ごみ箱", "Rename", "Contains: 1", "Empty", "Auto-delete: Off", "Change",
            "Delete permanently at once (no recycle bin)", "Hide the recycle bin (open it with \"Open Recycle Bin\" in the menu)", "Close")))
        assertTrue("no Japanese in the settings box (bin name aside)", tx.none { s -> s.any { it in '\u3040'..'\u30ff' } && !s.contains("ごみ箱") })
        Dlg.last!!.dismiss()
        ex.pickAutoLock(); idle()
        val tl = texts(Dlg.last!!.frame)
        assertTrue(tl.toString(), tl.containsAll(listOf("Immediately (when leaving the app)", "After 5 minutes", "Never", "Cancel")))
    }
}
