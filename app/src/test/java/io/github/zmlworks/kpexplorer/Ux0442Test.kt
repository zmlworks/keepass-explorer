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

/** 0.4.42: the encrypted side file for uses (lock and leaving the app no longer save the database for them), 開くときに指紋認証を求める for a database that is not encrypted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0442Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0442").mkdirs() }
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
    private fun ioDone() { Session.io.submit {}.get(); idle() }
    private fun fileDb() = Kdbx.open(a.store.read(), key)
    private fun uses(d: app.keemobile.kotpass.database.KeePassDatabase, id: UUID) = Tree.findEntry(d, id)!!.second.times!!.usageCount
    private fun reopen(k: ByteArray = key) { val d = Kdbx.open(a.store.read(), k); Session.open(d) }

    // ---------------------------------------------------------------- A: the side file

    @Test fun lock_writesTheSideFileNotTheDatabase_unlockMergesBack_nextSaveEmptiesIt() {
        ioDone()
        val n0 = Session.saveCount
        val g = ids["Gmail"]!!; val am = ids["Amazon"]!!
        openKey(g); a.showExplorer(); idle(); openKey(g); a.showExplorer(); openKey(am); a.showExplorer(); idle()
        assertEquals(2, uses(Session.db!!, g))
        assertEquals(setOf(g, am), Session.pendingUses)
        // leaving the app: no save of the database, the side file instead
        Session.leave(); ioDone()
        assertEquals(n0, Session.saveCount)
        val f = a.store.usageFile
        assertTrue(f.isFile)
        assertEquals(0, uses(fileDb(), g))
        // encrypted: no id or count in the clear
        val raw = String(f.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(g.toString())); assertTrue(raw.startsWith("KPXU1"))
        // the lock: still no save
        Session.lock(); ioDone()
        assertEquals(n0, Session.saveCount)
        // unlock: merged back (shown in 最近使用 / 頻繁に使用 at once), the file stays until a real save
        reopen()
        assertEquals(2, uses(Session.db!!, g)); assertEquals(1, uses(Session.db!!, am))
        assertEquals(listOf(g, am), io.github.zmlworks.kpexplorer.db.Recent.frequent(Session.db!!).map { it.entry.uuid })
        assertTrue(Session.hasQuietUnsaved); assertFalse(Session.hasUnsaved)
        assertTrue(f.isFile)
        // merging again (the same file once more) changes nothing
        assertSame(Session.db, io.github.zmlworks.kpexplorer.db.UsageLog.merge(Session.db!!, io.github.zmlworks.kpexplorer.db.UsageLog.read(f, key, Session.db!!)))
        // an edit: its save writes the uses and removes the file
        Session.update { Order.renameEntry(it, ids["Steam"]!!, "Steam2") }; Session.flush(); ioDone(); idle()
        assertEquals(2, uses(fileDb(), g)); assertEquals(1, uses(fileDb(), am))
        assertFalse(f.isFile)
        assertTrue(Session.pendingUses.isEmpty())
        // with nothing pending, leaving writes nothing
        Session.leave(); ioDone(); assertFalse(f.isFile)
    }

    @Test fun sideFile_otherKey_orDamaged_isDiscardedSilently() {
        val g = ids["Gmail"]!!
        openKey(g); a.showExplorer(); idle()
        Session.lock(); ioDone()
        val f = a.store.usageFile
        assertTrue(f.isFile)
        // the database re-keyed (a password change made elsewhere): the old file cannot be read, nothing merged, no error
        val other = Kdbx.compositeKey("another password")
        val rekeyed = Kdbx.withHeader(Kdbx.withCredentials(fileDb(), other), light = true)
        a.store.write(Kdbx.encodeVerified(rekeyed, keepSalt = true))
        reopen(other)
        assertEquals(0, uses(Session.db!!, g)); assertFalse(Session.hasQuietUnsaved)
        Session.lock(); ioDone()
        // damaged: nothing, no error
        a.store.write(Kdbx.encodeVerified(Kdbx.withHeader(Kdbx.withCredentials(fileDb(other), key), light = true), keepSalt = true))
        f.writeBytes("KPXU1 not really".toByteArray())
        reopen()
        assertEquals(0, uses(Session.db!!, g))
        f.writeBytes(ByteArray(0)); Session.lock(); reopen()
        assertEquals(0, uses(Session.db!!, g))
        // the same key but another database (another salt): unreadable too
        val db1 = Session.db!!
        Session.recordUse(g); val good = io.github.zmlworks.kpexplorer.db.UsageLog.encode(io.github.zmlworks.kpexplorer.db.UsageLog.of(Session.db!!, listOf(g)), key, Session.db!!)
        assertEquals(1, io.github.zmlworks.kpexplorer.db.UsageLog.decode(good, key, Session.db!!).size)
        assertTrue(io.github.zmlworks.kpexplorer.db.UsageLog.decode(good, key, Kdbx.withHeader(db1, light = true)).isEmpty())
        assertTrue(io.github.zmlworks.kpexplorer.db.UsageLog.decode(good, other, Session.db!!).isEmpty())
    }
    private fun fileDb(k: ByteArray) = Kdbx.open(a.store.read(), k)

    @Test fun replacingTheDatabase_carriesTheUses_whereTheEntriesStillExist() {
        val g = ids["Gmail"]!!; val am = ids["Amazon"]!!
        openKey(g); openKey(am); a.showExplorer(); idle()
        // a restore / import: the new database has Gmail, not Amazon
        val next = Tree.findEntry(fileDb(), am)!!.let { (p, _) -> Tree.modifyGroup(fileDb(), p.uuid) { it.copy(entries = it.entries.filter { e -> e.uuid != am }) } }
        Session.open(next)
        assertEquals(1, uses(Session.db!!, g))
        assertNull(Tree.findEntry(Session.db!!, am))
        assertEquals(setOf(g), Session.pendingUses)
    }

    @Test fun saveOnOpen_on_savesTheDatabase_noSideFile() {
        App.prefs(app()).edit().putBoolean(FeatureFolders.PREF_SAVE_ON_OPEN, true).commit()
        openKey(ids["Gmail"]!!); a.showExplorer(); idle()
        assertTrue(Session.pendingUses.isEmpty())
        Session.lock(); ioDone()
        assertEquals(1, uses(fileDb(), ids["Gmail"]!!))
        assertFalse(a.store.usageFile.isFile)
    }

    // ---------------------------------------------------------------- B: the fingerprint check for 暗号化せずに使う

    private fun securityPage(): io.github.zmlworks.kpexplorer.ui.ClassicDialog { SecuritySettings.show(a); idle(); return Dlg.last!! }
    private fun asUnencrypted() {
        val none = Kdbx.compositeKey("")
        a.store.write(Kdbx.encodeVerified(Kdbx.withHeader(Kdbx.withCredentials(fileDb(), none), light = true), keepSalt = true))
        a.store.setInfo(FileInfo(FileInfo.Protection.NONE, FileInfo.Strength.UNKNOWN))
        Session.lock(); Session.open(Kdbx.open(a.store.read(), none)); idle()
    }

    @Test fun noneGate_onlyForTheUnencryptedMode_turnedOnAfterOnePrompt() {
        // master password: not there
        var d = securityPage()
        assertNull(findTag<View>(d.frame, "sec-none-gate"))
        shot("v0442/02-security-password-mode.png", dialog = true); d.dismiss()
        asUnencrypted()
        d = securityPage()
        val box = findTag<View>(d.frame, "sec-none-gate") as android.widget.CheckBox
        assertEquals("開くときに指紋認証を求める", box.text.toString()); assertFalse(box.isChecked)
        assertTrue((findTag<View>(d.frame, "sec-none-gate-note") as android.widget.TextView).text.contains("ファイルは暗号化されないまま"))
        // a cancelled prompt: stays off
        Vault.testBackend = Vault.TestBackend("")
        box.performClick(); idle()
        assertFalse(box.isChecked); assertFalse(Security.noneGate(a))
        // no fingerprint / screen lock on the device: a message, stays off
        Vault.forceAvailableForTest = false
        box.performClick(); idle()
        assertFalse(box.isChecked); assertFalse(Security.noneGate(a))
        assertTrue(texts(Dlg.last!!.frame).any { it.contains("設定されていないため使えません") }); Dlg.last!!.dismiss()
        // confirmed: on
        Vault.forceAvailableForTest = true; Vault.testBackend = Vault.TestBackend(null)
        d = securityPage()
        val box2 = findTag<View>(d.frame, "sec-none-gate") as android.widget.CheckBox
        box2.performClick(); idle()
        assertTrue(box2.isChecked); assertTrue(Security.noneGate(a))
        shot("v0442/01-security-unencrypted-mode.png", dialog = true); d.dismiss()
        // another protection: hidden, and no longer applied
        a.store.setInfo(FileInfo(FileInfo.Protection.PASSWORD, FileInfo.Strength.UNKNOWN))
        assertFalse(Security.noneGate(a))
        d = securityPage(); assertNull(findTag<View>(d.frame, "sec-none-gate")); d.dismiss()
    }

    @Test fun noneGate_unlockScreen_asksFirst_cancelKeepsItLocked_noLockScreenOpensWithANote() {
        asUnencrypted()
        App.prefs(app()).edit().putBoolean(Security.PREF_NONE_GATE, true).commit()
        a.lock(); idle()
        val u = UnlockScreen(a, prompt = false); a.show(u); idle()
        assertEquals("指紋認証で開く (暗号化なし)", texts(u.bio).first())
        shot("v0442/03-unlock-fingerprint-gate.png")
        Vault.testBackend = Vault.TestBackend("")
        u.bio.performClick(); ioDone()
        assertFalse(Session.isOpen); assertEquals("確認がキャンセルされました。", u.error.text.toString())
        Vault.testBackend = Vault.TestBackend(null)
        u.bio.performClick(); ioDone(); idle()
        assertTrue(Session.isOpen)
        // the device lost its screen lock: opens anyway (the file is not encrypted), with a note
        a.lock(); idle()
        val u2 = UnlockScreen(a, prompt = false); a.show(u2); idle()
        Vault.testBackend = Vault.TestBackend("would fail"); Vault.forceAvailableForTest = false
        u2.bio.performClick(); ioDone(); idle()
        assertTrue(Session.isOpen)
        // off: no prompt at all
        App.prefs(app()).edit().putBoolean(Security.PREF_NONE_GATE, false).commit()
        a.lock(); idle(); Vault.forceAvailableForTest = true
        val u3 = UnlockScreen(a, prompt = false); a.show(u3); idle()
        assertEquals("開く (暗号化なし)", texts(u3.bio).first())
        u3.bio.performClick(); ioDone(); idle()
        assertTrue(Session.isOpen)
    }
}
