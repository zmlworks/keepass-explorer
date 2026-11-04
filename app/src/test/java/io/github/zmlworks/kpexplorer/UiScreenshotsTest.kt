package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.W
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import java.time.LocalDate
import java.util.UUID

/** Renders the main screens on the JVM (Robolectric native graphics) and checks the basic flows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshotsTest {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens")
    private val w = 411 * 3
    private val h = 891 * 3
    private val key = Kdbx.compositeKey("sample")
    private lateinit var ids: Map<String, UUID>

    private fun app() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        out.mkdirs()
        Session.lock()
        Session.store = null
        Session.debounceMs = 50
        App.prefs(app()).edit().clear().commit()
        app().getSharedPreferences("vault", 0).edit().clear().commit()
        Vault.forceAvailableForTest = true
        App.store(app()).let { s -> s.delete(); s.backups().forEach { it.delete() } }
        File(app().filesDir, "data-version").writeText(App.DATA_VERSION.toString())
    }

    @After fun tearDown() { Session.lock(); Vault.forceAvailableForTest = null; Menus.dismiss() }

    private fun f(t: String, u: String = "", p: String = "", url: String = "", n: String = "", tags: String = "") =
        Tree.Fields(t, u, p, url, n, Tree.parseTags(tags))

    private fun sample(): KeePassDatabase {
        var db = Kdbx.create("わたしのパスワード", key, rootName = "", light = true)
        val root = Tree.root(db).uuid
        val m = HashMap<String, UUID>()
        fun g(name: String, parent: UUID = root): UUID { val x = Tree.newGroup(name); db = Tree.addGroup(db, parent, x); m[name] = x.uuid; return x.uuid }
        fun e(parent: UUID, fl: Tree.Fields): UUID { val x = Tree.newEntry(fl); db = Tree.addEntry(db, parent, x); m[fl.title] = x.uuid; return x.uuid }
        val mail = g("メール"); val bank = g("銀行・カード"); g("ショッピング"); val work = g("仕事"); g("SNS"); g("ゲーム")
        g("プロジェクトA", work); g("社内システム", work)
        e(mail, f("Gmail", "taro.yamada@gmail.com", "x7#Kq9!vLm2", "https://mail.google.com", "2段階認証あり。予備コードは金庫のメモに。", "web メール 重要"))
        e(mail, f("Yahoo!メール", "taro_y", "pw-yahoo-1", "https://mail.yahoo.co.jp", "", "web メール"))
        e(mail, f("Outlook (会社)", "t.yamada@example.co.jp", "Corp#2026", "", "", "仕事 メール"))
        e(mail, f("プロバイダのメール設定", "", "", "", "POP: pop.example.ne.jp\nSMTP: smtp.example.ne.jp (587)\nアカウントは契約書を参照", "メール メモ"))
        e(mail, f("携帯キャリアメール", "090xxxxxxxx", "1234abcd", "", "", "メール 家族"))
        e(bank, f("ゆうちょ", "1234-5678", "****", "", "", "銀行 重要"))
        e(root, f("Wi-Fi (自宅)", "MyHome-5G", "correct horse battery", "", "ルーターは棚の上", "家族"))
        e(root, f("買い物リスト", n = "・牛乳\n・卵\n・食パン\n・洗剤 (詰め替え)", tags = "メモ 家族"))
        val old = e(root, f("古いメモ", n = "削除予定"))
        db = Tree.delete(db, old).first
        ids = m
        return db
    }

    private fun saveNow(): Result<Unit> {
        var res: Result<Unit>? = null
        Session.afterSave({ Unit }) { res = it }
        repeat(1000) { if (res == null) { ShadowLooper.idleMainLooper(); Thread.sleep(10) } }
        idle()
        return res!!
    }

    private fun idle() { repeat(40) { ShadowLooper.idleMainLooper(); Thread.sleep(5) } }

    private fun shot(v: View): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    private fun overlay(base: Bitmap, v: View, x: Int, y: Int, width: Int, maxH: Int = h - 200): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val c = Canvas(base)
        c.save(); c.translate(x.toFloat(), (if (y < 0) (h - v.measuredHeight) / 2 else y).toFloat()); v.draw(c); c.restore()
        return base
    }

    private fun withDialog(base: Bitmap): Bitmap {
        val d = Dlg.last ?: error("no dialog")
        return overlay(base, d.frame, 36, -1, w - 72, h - 300)
    }

    private fun save(b: Bitmap, name: String) = FileOutputStream(File(out, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun launch(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        return a
    }

    private fun openSample(): MainActivity {
        val db = sample()
        App.store(app()).write(Kdbx.encodeVerified(db))
        Session.open(db)
        App.prefs(app()).edit().putString("dbName", db.content.meta.name).commit()
        return launch()
    }

    @Test fun explorer_keyMemoDragBackupsUnlock() {
        val a = openSample()
        val ex = a.explorer!!
        assertTrue(ex.iconMode)
        assertEquals(listOf("メール", "銀行・カード", "ショッピング", "仕事", "SNS", "ゲーム", "ごみ箱", "Wi-Fi (自宅)", "買い物リスト"),
            ex.rows.map { ex.label(it) })
        assertEquals("/ - KeePass Explorer", ex.caption.title.text.toString())
        save(shot(a.host), "01-main-no-selection.png")

        // tap: file selects, tap again opens; folder opens on one tap
        ex.tap(ex.rows.first { it.uuid == ids["Wi-Fi (自宅)"] }); idle()
        assertEquals(ids["Wi-Fi (自宅)"], ex.selection)
        val pv = findTag(a.host, "pass") as android.widget.TextView
        assertEquals("******", pv.transformationMethod.getTransformation(pv.text, pv).toString())
        save(shot(a.host), "02-main-selected.png")
        pv.performClick()
        assertEquals(null, pv.transformationMethod)
        pv.performClick()
        ex.tap(ex.rows.first { it.uuid == ids["Wi-Fi (自宅)"] }); idle()
        assertTrue(a.screen is KeyScreen)
        a.showExplorer(); idle()
        ex.tap(ex.rows.first { it.uuid == ids["メール"] }); idle()
        assertEquals(ids["メール"], ex.current)
        ex.navigate(Tree.root(Session.db!!).uuid); idle()
        ex.select(ids["Wi-Fi (自宅)"]); idle()
        val row = ex.rows.first { it.uuid == ids["メール"] }
        save(overlay(shot(a.host), Menus.build(a, ex.itemMenu(row)) {}, 150, 640, 700), "03-context-menu.png")

        ex.navigate(ids["プロジェクトA"]!!); idle()
        assertEquals(" (2,0)", (findTag(a.host, "count", 1) as android.widget.TextView).text.toString())
        save(shot(a.host), "03b-path-counts.png")
        ex.searchTags.clear(); ex.searchTags.add("メール"); ex.searchTags.add("web")
        ex.askSearch(); idle()
        save(withDialog(shot(a.host)), "03c-search-tags.png")
        Dlg.last?.dismiss(); idle()
        ex.searchFor(ex.searchQuery("", listOf("メール", "web"))); idle()
        assertEquals(setOf("Gmail", "Yahoo!メール"), ex.rows.map { ex.label(it) }.toSet())
        save(shot(a.host), "03d-search-result.png")
        ex.searchFor(""); idle()
        ex.navigate(ids["メール"]!!); idle()
        ex.toggleView(); idle()
        assertFalse(ex.iconMode)
        assertEquals(5, ex.rows.size)
        ex.select(ids["Gmail"]); idle()
        save(shot(a.host), "04-main-details.png")
        ex.toggleView(); idle()

        // key screen
        val ks = KeyScreen(a, ids["Gmail"], ids["メール"]!!)
        a.show(ks); idle()
        ks.addCustomField("秘密の質問", "初めて飼ったペット: ポチ")
        ks.setOtp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertTrue(ks.dirty)
        assertTrue(ks.save())
        idle()
        assertFalse(ks.passShown)
        ks.togglePass(); assertTrue(ks.passShown); ks.togglePass()
        // swipe on User box = copy
        val ub = ks.boxes["UserName"]!!
        fun ev(act: Int, x: Float) = MotionEvent.obtain(0, 0, act, x, 20f, 0)
        ub.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f)); ub.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 120f))
        ub.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 400f)); ub.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 400f))
        val cm = a.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("taro.yamada@gmail.com", cm.primaryClip!!.getItemAt(0).text.toString())
        assertFalse(ub.editing)
        ub.startEdit(); assertTrue(ub.editing); ub.endEdit()
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        save(shot(a.host), "05-key-top.png")
        ks.scroll.scrollTo(0, 100000)
        save(shot(a.host), "05b-key-bottom.png")
        ks.toggleTag("仕事"); assertTrue(ks.dirty)
        val gm = Tree.findEntry(Session.db!!, ids["Gmail"]!!)!!.second
        assertEquals(1, gm.history.size)
        assertEquals(listOf("秘密の質問"), io.github.zmlworks.kpexplorer.db.Order.customFieldNames(gm))
        assertTrue(io.github.zmlworks.kpexplorer.db.Order.otpUri(gm)!!.contains("issuer=Gmail"))
        ks.addCustomField("一時", "x"); idle()
        a.onBackPressed(); idle()
        assertNotNull("unsaved edits → confirm dialog", Dlg.last)
        save(withDialog(shot(a.host)), "06-unsaved-confirm.png")
        Dlg.last?.dismiss(); idle()
        a.showExplorer(); idle()

        // memo screen + new items
        ex.navigate(Tree.root(Session.db!!).uuid); idle()
        a.show(MemoScreen(a, ids["買い物リスト"]!!)); idle()
        save(shot(a.host), "07-memo.png")
        val rt = findRuled(a.host)!!
        val l = rt.layout
        for (i in 0 until rt.lineCount) assertEquals(rt.getLineBounds(i, android.graphics.Rect()) + W.dp(4), rt.ruleYs[i])
        assertTrue("rules reach the bottom", rt.ruleYs.last() + rt.lineHeight >= rt.scrollY + rt.height)
        assertNotNull(l)
        a.showExplorer(); idle()
        val nf = ex.newFolder(); val nf2 = ex.newFolder(); val nm = ex.newMemo()
        val g = Tree.findGroup(Session.db!!, ex.current)!!
        assertEquals("新しいフォルダ", g.groups.first { it.uuid == nf }.name)
        assertEquals("新しいフォルダ2", g.groups.first { it.uuid == nf2 }.name)
        assertEquals("新しいメモ", g.entries.first { it.uuid == nm }.fields.title?.content)

        // drag: memo into folder, folder reordered before メール, entry onto the path line (root)
        ex.performDrop(nm, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.INTO, nf)); idle()
        assertEquals(nf, Tree.findEntry(Session.db!!, nm)!!.first.uuid)
        ex.performDrop(nf2, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.BEFORE, ids["メール"])); idle()
        assertEquals("新しいフォルダ2", ex.rows.first().name)
        ex.navigate(ids["メール"]!!); idle()
        val root = Tree.root(Session.db!!).uuid
        ex.performDrop(ids["Yahoo!メール"]!!, ExplorerScreen.Drop(ExplorerScreen.Drop.Mode.INTO, root, pathFolder = true)); idle()
        assertEquals(root, Tree.findEntry(Session.db!!, ids["Yahoo!メール"]!!)!!.first.uuid)
        assertTrue(saveNow().isSuccess)
        val disk = Kdbx.open(App.store(app()).read(), key)
        assertEquals("新しいフォルダ2", io.github.zmlworks.kpexplorer.db.Order.children(Tree.root(disk)).first().name)

        val st = App.store(app())
        st.backup(LocalDate.of(2026, 10, 12)); st.backup(LocalDate.of(2026, 10, 21))
        val bs = BackupScreen(a)
        a.show(bs); idle()
        assertEquals(2, bs.files.size)
        save(shot(a.host), "08-backups.png")
        a.showExplorer(); idle()

        // 保存 dialog → internal (dated file) / external export password prompt
        Security.saveDialog(a); idle()
        save(withDialog(shot(a.host)), "09-save-dialog.png")
        Dlg.last?.dismiss(); idle()
        Security.askExportPassword(a, "x.kdbx") { Session.db }; idle()
        save(withDialog(shot(a.host)), "10-export-password.png")
        Dlg.last?.dismiss(); idle()

        a.lock(); idle()
        assertTrue(a.screen is UnlockScreen)
        save(shot(a.host), "11-unlock.png")
    }

    private fun findTag(v: View, t: String, skip: Int = 0): View? {
        var n = skip
        fun go(x: View): View? {
            if (x.tag == t) { if (n == 0) return x; n-- }
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) go(x.getChildAt(i))?.let { return it }
            return null
        }
        return go(v)
    }

    private fun findRuled(v: View): io.github.zmlworks.kpexplorer.ui.Parts.RuledText? {
        if (v is io.github.zmlworks.kpexplorer.ui.Parts.RuledText) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findRuled(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun exportOpensWithPassword() {
        val db = sample()
        val bytes = Kdbx.encodeVerified(Kdbx.exportCopy(db, "エクスポート pw"))
        val back = Kdbx.open(bytes, Kdbx.compositeKey("エクスポート pw"))
        assertEquals(Tree.countEntries(db.content.group), Tree.countEntries(back.content.group))
        assertTrue(runCatching { Kdbx.open(bytes, key) }.isFailure)
    }

    @Test fun setupWizard() {
        val a = launch()
        assertTrue(a.screen is SetupScreen)
        save(shot(a.host), "12-setup.png")
    }

    @Test fun dialogs() {
        val a = openSample()
        a.explorer!!.askDelete(ids["メール"]!!); idle()
        save(withDialog(shot(a.host)), "13-delete-confirm.png")
        Dlg.last?.dismiss()
        Help.about(a); idle()
        save(withDialog(shot(a.host)), "14-about.png")
        assertTrue(W.px >= 1)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataWipeTest {
    @Test fun oldDataIsWiped() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "data-version").delete()
        val st = App.store(app); st.dbFile.parentFile!!.mkdirs(); st.dbFile.writeText("old")
        App.prefs(app).edit().putString("dbName", "x").commit()
        val bk = File(app.filesDir, "backups/2026-01-01.kdbx"); bk.parentFile!!.mkdirs(); bk.writeText("old")
        App.wipeOldData(app)
        assertFalse(st.dbFile.exists())
        assertFalse(bk.exists())
        assertFalse(App.prefs(app).contains("dbName"))
        assertEquals(App.DATA_VERSION.toString(), File(app.filesDir, "data-version").readText())
        st.dbFile.parentFile!!.mkdirs(); st.dbFile.writeText("new")
        App.wipeOldData(app)
        assertTrue(st.dbFile.exists())
    }
}
