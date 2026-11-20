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
import io.github.zmlworks.kpexplorer.db.FileInfo
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

/** 0.4.41: shorter names of the 機能フォルダ, 内部バックアップの一覧 back in the menu, its three top buttons, resizable columns, 強度 with * and #. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0441Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0441").mkdirs() }
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
    @After fun tearDown() { runCatching { FieldEditSession.commitActive() }; Menus.dismiss(); Session.lock(); L.setLang(app(), L.Lang.SYSTEM); L.setDateOrder(app(), L.DateOrder.YMD); Vault.forceAvailableForTest = null; Vault.testBackend = null; Menus.dismiss(); Dlg.last = null }

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
        Menus.at(ex.view, W_ - W.dp(240), W.dp(20), ex.mainMenu()); idle()
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
    private val PS = io.github.zmlworks.kpexplorer.sec.PasswordStrength
    private val STRONG_PW = "vGy7-Kq2p-9Zx4-Rt8w"
    private fun waitChecks() { BackupScreen.CHECKER.submit {}.get(); idle() }
    private fun backupPage(): BackupScreen { val bs = BackupScreen(a); a.show(bs); idle(); layoutHost(); waitChecks(); layoutHost(); return bs }
    private fun topButtons(bs: BackupScreen) = texts(find(bs.view, android.widget.HorizontalScrollView::class.java)!!)
    private fun headerOf(bs: BackupScreen) = findTag<View>(bs.view, "sort-name")!!.parent as LinearLayout

    // ---------------------------------------------------------------- item 1: the names

    @Test fun featureFolders_newNames_everywhere() {
        assertEquals(listOf("最近の更新", "最近使用", "頻繁に使用"), FeatureFolders.Kind.entries.map { it.label })
        assertEquals(listOf("Recently updated", "Recently used", "Frequently used"), FeatureFolders.Kind.entries.map { it.nameEn })
        val ex = a.explorer!!
        ex.navigate(Tree.root(Session.db!!).uuid); idle()
        if (!ex.iconMode) ex.toggleView()
        idle(); layoutHost(); layoutHost()
        assertEquals(listOf("最近の更新", "最近使用", "頻繁に使用"), ex.rows.take(3).map { it.name })
        assertEquals(listOf("最近の更新", "最近使用", "頻繁に使用"), ex.folderTree!!.topRows.map { it.contentDescription.toString() })
        shot("v0441/01-root-renamed.png")
        val m = labels(ex.mainMenu())
        assertTrue(m.toString(), m.containsAll(listOf("ごみ箱", "最近の更新", "最近使用", "頻繁に使用", "機能フォルダの設定")))
        // the settings page: 「…」を表示する
        FeatureFolders.show(a); idle()
        val t = texts(Dlg.last!!.frame)
        assertTrue(t.toString(), t.containsAll(listOf("「最近の更新」を表示する", "「最近使用」を表示する", "「頻繁に使用」を表示する")))
        Dlg.last!!.dismiss(); idle()
        L.setLang(app(), L.Lang.EN)
        assertEquals(listOf("Recently updated", "Recently used", "Frequently used"), FeatureFolders.Kind.entries.map { it.label })
    }

    // ---------------------------------------------------------------- item 2: the menu entry

    @Test fun backupList_inTheMainMenu_afterImport() {
        val ex = a.explorer!!
        val m = labels(ex.mainMenu())
        val i = m.indexOf("内部バックアップの一覧")
        assertTrue(m.toString(), i > 0)
        assertEquals("外部データベースからインポートする", m[i - 1]); assertEquals("|", m[i + 1])
        showMainMenu(); shot("v0441/02-menu.png", menuAt = (W_ - W.dp(240)) to W.dp(20)); Menus.dismiss()
        ex.mainMenu().first { it.label == "内部バックアップの一覧" }.action(); idle()
        assertTrue(a.screen is BackupScreen)
        // not while browsing another file read-only
        Session.beginTempView(Session.db!!, "外部.kdbx")
        try { assertFalse(ex.mainMenu().first { it.label == "内部バックアップの一覧" }.enabled) } finally { Session.exitTempView(); idle() }
    }

    // ---------------------------------------------------------------- item 3: three buttons; the rest in the long press

    @Test fun backupPage_threeTopButtons_restInTheLongPressMenu() {
        val f = a.store.backup()
        val bs = backupPage()
        assertEquals(listOf("今すぐ作成", "閲覧", "エクスポート"), topButtons(bs))
        assertEquals(listOf("名前の変更", "|", "閲覧 (読み取り専用)", "外部に書き出す", "|", "このバックアップから復元", s(R.string.import_menu), "|", "削除"), labels(bs.menuFor(f)))
        // nothing selected: a note, not an action
        allViews(find(bs.view, android.widget.HorizontalScrollView::class.java)!!).filterIsInstance<android.widget.TextView>().first { it.text.toString() == "エクスポート" }.let { (if (it.isClickable) it else it.parent as View).performClick() }; idle()
        assertTrue(texts(Dlg.last!!.frame).contains("先に一覧からバックアップを選んでください。"))
        Dlg.last!!.dismiss()
    }

    // ---------------------------------------------------------------- item 4: resizable columns, 強度

    @Test fun backupPage_columnsResizeByDraggingTheBorders_widthsKept() {
        a.store.backup()
        val bs = backupPage()
        val h = headerOf(bs)
        assertEquals(listOf("名前", "サイズ", "更新日時 ▼", "強度"), (0 until h.childCount).map { (h.getChildAt(it) as android.widget.TextView).text.toString() })
        val w0 = h.getChildAt(0).width; val s0 = h.getChildAt(1).width
        assertTrue("name column wide: $w0", w0 > W.dp(100))
        // drag the border after 名前 20 dp to the left
        var t = 0L
        fun ev(act: Int, x: Float) { t += 16; val e = MotionEvent.obtain(0, t, act, x, h.height / 2f, 0); h.dispatchTouchEvent(e); e.recycle() }
        ev(MotionEvent.ACTION_DOWN, w0.toFloat()); ev(MotionEvent.ACTION_MOVE, w0 - W.dp(10).toFloat()); ev(MotionEvent.ACTION_MOVE, w0 - W.dp(20).toFloat()); ev(MotionEvent.ACTION_UP, w0 - W.dp(20).toFloat())
        layoutHost()
        assertEquals(w0 - W.dp(20), h.getChildAt(0).width)
        assertEquals(s0, h.getChildAt(1).width)
        val list = find(bs.view, ListView::class.java)!!
        assertEquals("rows follow", w0 - W.dp(20), (list.getChildAt(0) as ViewGroup).getChildAt(0).width)
        assertNotNull(App.prefs(app()).getString(BackupScreen.PREF_COLS, null))
        // a tap on a header still sorts; the widths stay on the next visit
        val bs2 = backupPage()
        assertEquals(w0 - W.dp(20), headerOf(bs2).getChildAt(0).width)
        // no column narrower than its minimum, the last one keeps its room
        val h2 = headerOf(bs2); val sw = h2.getChildAt(1).width
        t = 0L
        fun ev2(act: Int, x: Float) { t += 16; val e = MotionEvent.obtain(0, t, act, x, h2.height / 2f, 0); h2.dispatchTouchEvent(e); e.recycle() }
        val edge = (h2.getChildAt(0).width + sw).toFloat()
        ev2(MotionEvent.ACTION_DOWN, edge); ev2(MotionEvent.ACTION_MOVE, edge + 5000f); ev2(MotionEvent.ACTION_UP, edge + 5000f); layoutHost()
        assertTrue("strength keeps 48 dp: ${h2.getChildAt(3).width}", h2.getChildAt(3).width >= W.dp(48) - 2)
    }

    // ---------------------------------------------------------------- items 5 and 6: * and #

    @Test fun backupPage_strengthColumn_asteriskExportable_sharpSamePassword_checkedOnce() {
        a.store.setInfo(PS.info(STRONG_PW))
        val same = a.store.backup(java.time.LocalDate.of(2026, 11, 1))
        // another password (strong too), no password, and a copy nobody judged
        val other = File(a.store.backupDir, "other.kdbx").apply { writeBytes(Kdbx.encodeVerified(Kdbx.withHeader(Kdbx.withCredentials(Session.db!!, Kdbx.compositeKey("other-pass")), light = true))) }
        a.store.setInfo(other, PS.info(STRONG_PW))
        val none = File(a.store.backupDir, "nopass.kdbx").apply { writeBytes(Kdbx.encodeVerified(Kdbx.withHeader(Kdbx.withCredentials(Session.db!!, Kdbx.compositeKey("")), light = true))) }
        a.store.setInfo(none, FileInfo(FileInfo.Protection.NONE, FileInfo.Strength.UNKNOWN))
        val copied = File(a.store.backupDir, "copied.kdbx").apply { writeBytes(a.store.read()) }
        listOf(same, other, none, copied).forEachIndexed { i, f -> f.setLastModified(1_794_500_000_000L - i * 86_400_000L) }
        Security.setExportMinLog10(a, 12)
        val bs = backupPage()
        val word = SecuritySettings.strengthLabel(PS.info(STRONG_PW))
        assertEquals("*#$word", bs.strengthText(same))
        assertEquals("*$word", bs.strengthText(other))
        assertEquals("パスワードなし", bs.strengthText(none))
        assertEquals("#未判定", bs.strengthText(copied))
        // shown in the 強度 column, no green any more
        val list = find(bs.view, ListView::class.java)!!
        val cells = (0 until list.childCount).flatMap { allViews(list.getChildAt(it)) }.filter { (it.tag as? String)?.startsWith("strength-") == true }.map { (it as android.widget.TextView).text.toString() }
        assertEquals(listOf("*#$word", "*$word", "パスワードなし", "#未判定"), cells)
        assertTrue((0 until list.childCount).flatMap { allViews(list.getChildAt(it)).filterIsInstance<android.widget.TextView>() }.none { it.currentTextColor == 0xFF00802A.toInt() })
        val legend = (findTag<View>(bs.view, "backup-legend") as android.widget.TextView).text.toString()
        assertEquals("* 認証なしで書き出せる (セキュリティの設定)　# 今のデータベースと同じパスワード", legend)
        shot("v0441/03-backup-list.png")
        // remembered: the next visit runs no check (cached per backup and key; no key material in the record)
        val token = Kdbx.keyToken(Session.db!!)
        assertEquals("+$token", a.store.info(same).keyCheck); assertEquals("-$token", a.store.info(other).keyCheck)
        val props = File(a.store.dbFile.parentFile, "files.properties").readText()
        assertFalse("no key bytes", props.contains(key.joinToString("") { "%02x".format(it) }))
        val bs2 = BackupScreen(a); a.show(bs2); idle()
        assertFalse((findTag<View>(bs2.view, "backup-legend") as android.widget.TextView).text.contains("確認しています"))
        // 無効: no *; a new key: checked again (the old answers no longer count)
        Security.setExportMinLog10(a, 0)
        assertEquals("#$word", bs2.strengthText(same))
        Session.update { Kdbx.withCredentials(it, Kdbx.compositeKey("new-pass")) }; idle()
        assertNotEquals(token, Kdbx.keyToken(Session.db!!))
        assertNull(a.store.info(same).sameKey(Kdbx.keyToken(Session.db!!)))
        assertEquals(word, bs2.strengthText(same))
        val bs3 = backupPage()
        assertEquals("the old backup is not the new password", word, bs3.strengthText(same))
        shot("v0441/04-backup-list-after-password-change.png")
        // English legend
        L.setLang(app(), L.Lang.EN)
        val bs4 = backupPage()
        assertEquals(listOf("Create now", "Browse", "Export"), topButtons(bs4))
        assertTrue((findTag<View>(bs4.view, "backup-legend") as android.widget.TextView).text.startsWith("* can be exported without unlocking"))
    }

    @Test fun fileInfo_keyCheck_encodesAndReadsOldRecords() {
        val i = FileInfo(FileInfo.Protection.PASSWORD, FileInfo.Strength.STRONG, 13, "+abc")
        assertEquals(i, FileInfo.decode(i.encode()))
        assertEquals("password,strong,13", i.copy(keyCheck = null).encode())
        assertEquals(FileInfo(FileInfo.Protection.PASSWORD, FileInfo.Strength.UNKNOWN, null, "-x"), FileInfo.decode("password,unknown,,-x"))
        assertEquals(FileInfo(FileInfo.Protection.PASSWORD, FileInfo.Strength.WEAK, 8), FileInfo.decode("password,weak,8"))
        assertEquals(true, i.sameKey("abc")); assertNull(i.sameKey("abd"))
    }
}
