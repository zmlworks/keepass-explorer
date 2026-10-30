package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.Canvas
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
    }

    @After fun tearDown() { Session.lock(); Vault.forceAvailableForTest = null; Menus.dismiss() }

    private fun f(t: String, u: String = "", p: String = "", url: String = "", n: String = "", tags: String = "") =
        Tree.Fields(t, u, p, url, n, Tree.parseTags(tags))

    private fun sample(): KeePassDatabase {
        var db = Kdbx.create("わたしのパスワード", key)
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

    @Test fun explorer_iconsListEditorMoveBackups() {
        val a = openSample()
        val ex = a.explorer!!
        assertTrue("root has folders → icon view", ex.iconMode)
        assertEquals(listOf("メール", "銀行・カード", "ショッピング", "仕事", "SNS", "ゲーム", "ごみ箱", "Wi-Fi (自宅)", "買い物リスト"),
            ex.rows.map { ex.label(it) })
        save(shot(a.host), "01-explorer-icons.png")

        ex.selection = ids["メール"]; ex.refresh(); idle()
        val row = ex.rows.first { it.id == ids["メール"] }
        val base = shot(a.host)
        save(overlay(base, Menus.build(a, ex.itemMenu(row)) {}, 150, 640, 700), "02-context-menu.png")

        ex.navigate(ids["メール"]!!); idle()
        assertFalse(ex.iconMode)
        assertEquals(5, ex.rows.size)
        ex.selection = ids["Gmail"]; ex.refresh(); idle()
        save(shot(a.host), "03-explorer-list.png")
        val entryRow = ex.rows.first { it.id == ids["Gmail"] }
        save(overlay(shot(a.host), Menus.build(a, ex.itemMenu(entryRow)) {}, 200, 560, 720), "04-entry-menu.png")

        val ed = EditorScreen(a, ids["Gmail"], null, false)
        a.show(ed); idle()
        ed.tags.setText("web メール 重"); idle()
        assertTrue(ed.chips.childCount >= 1)
        save(shot(a.host), "05-editor-tags.png")
        ed.tags.setText("web メール "); idle()
        assertTrue("all tags suggested", ed.chips.childCount >= 6)
        ed.notes.setText("2段階認証あり。予備コードは金庫のメモに。\n(テストで追記)")
        ed.apply(); idle()
        val gm = Tree.findEntry(Session.db!!, ids["Gmail"]!!)!!.second
        assertEquals(1, gm.history.size)
        assertEquals(listOf("web", "メール"), gm.tags)
        a.onBackPressed(); idle()

        ex.navigate(Tree.root(Session.db!!).uuid); idle()
        val memo = EditorScreen(a, ids["買い物リスト"], null, true)
        a.show(memo); idle()
        save(shot(a.host), "06-editor-memo.png")
        a.showExplorer(); idle()

        val nm = EditorScreen(a, null, ids["仕事"], true)
        a.show(nm); idle()
        nm.notes.setText("会議室の Wi-Fi は 3F 掲示板"); nm.apply(); a.showExplorer(); idle()
        assertEquals(1, Tree.findGroup(Session.db!!, ids["仕事"]!!)!!.entries.size)

        ex.navigate(ids["メール"]!!); idle()
        ex.selection = ids["Yahoo!メール"]
        ex.moveToDialog(ids["Yahoo!メール"]!!); idle()
        save(withDialog(shot(a.host)), "07-move-tree.png")
        Dlg.last?.dismiss(); idle()

        ex.cut(ids["Yahoo!メール"]!!)
        ex.navigate(ids["仕事"]!!); idle()
        ex.paste(ids["仕事"]!!); idle()
        val (parent, moved) = Tree.findEntry(Session.db!!, ids["Yahoo!メール"]!!)!!
        assertEquals(ids["仕事"], parent.uuid)
        assertNotNull(moved.times!!.locationChanged)
        ex.moveTo(ids["Yahoo!メール"]!!, 0)
        assertEquals(0, Tree.indexInParent(Session.db!!, ids["Yahoo!メール"]!!))

        assertTrue(saveNow().isSuccess)
        val disk = Kdbx.open(App.store(app()).read(), key)
        assertEquals(ids["仕事"], Tree.findEntry(disk, ids["Yahoo!メール"]!!)!!.first.uuid)

        // a failed save is reported to afterSave callers (backup / restore / password change)
        val dbDir = App.store(app()).dbFile.parentFile!!
        dbDir.setWritable(false)
        if (!dbDir.canWrite()) {
            Session.update { Kdbx.withCredentials(it, key.copyOf()) }
            val r = saveNow()
            assertTrue(r.exceptionOrNull() is Session.SaveFailedException)
            assertEquals(Session.SaveState.ERROR, Session.saveState)
        }
        dbDir.setWritable(true)
        assertTrue(saveNow().isSuccess)
        assertEquals(Session.SaveState.SAVED, Session.saveState)

        val st = App.store(app())
        st.backup(LocalDate.of(2026, 10, 12)); st.backup(LocalDate.of(2026, 10, 19)); st.backup(LocalDate.of(2026, 10, 21)); st.backup(LocalDate.of(2026, 10, 21))
        val bs = BackupScreen(a)
        a.show(bs); idle()
        assertEquals(4, bs.files.size)
        assertTrue(bs.files.any { it.name == "2026-10-21_2.kdbx" })
        bs.selected = bs.files.first { it.name == "2026-10-21.kdbx" }; bs.reload(); idle()
        save(shot(a.host), "08-backups.png")
        save(overlay(shot(a.host), Menus.build(a, bs.menuFor(bs.selected!!)) {}, 260, 700, 640), "09-backup-menu.png")

        app().getSharedPreferences("vault", 0).edit().putString("ct", "AA==").putString("iv", "AA==").commit()
        a.lock(); idle()
        assertFalse(Session.isOpen)
        assertTrue(a.screen is UnlockScreen)
        save(shot(a.host), "10-unlock.png")

        val un = a.screen as UnlockScreen
        un.password.setText("sample")
        un.submit()
        repeat(200) { if (!Session.isOpen) { idle(); Thread.sleep(20) } }
        idle()
        assertTrue(Session.isOpen)
        assertTrue(a.screen is ExplorerScreen)
    }

    @Test fun setupWizard() {
        val a = launch()
        assertTrue(a.screen is SetupScreen)
        save(shot(a.host), "11-setup.png")
        val s = a.screen as SetupScreen
        s.show(1); idle()
        save(shot(a.host), "12-setup-create.png")
        s.importMode = true; s.show(1); idle()
        save(shot(a.host), "13-setup-import.png")
    }

    @Test fun dialogs() {
        val a = openSample()
        a.explorer!!.askDelete(ids["メール"]!!); idle()
        save(withDialog(shot(a.host)), "14-delete-confirm.png")
        Dlg.last?.dismiss()
        Help.about(a); idle()
        save(withDialog(shot(a.host)), "15-about.png")
        assertTrue(W.px >= 1)
    }
}
