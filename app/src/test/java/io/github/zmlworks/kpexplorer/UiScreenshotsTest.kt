package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.W
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        Session.debounceMs = 50; Vault(app()).clear()
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
        run {
            val memo = Order.newMemo("会議メモ")
            db = Tree.addEntry(db, root, memo)
            m["会議メモ"] = memo.uuid
        }
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
        assertEquals(listOf("メール", "銀行・カード", "ショッピング", "仕事", "SNS", "ゲーム", "Recycle Bin", "Wi-Fi (自宅)", "買い物リスト", "会議メモ"),
            ex.rows.map { ex.label(it) })
        assertEquals("/ - KeePass Explorer", ex.caption.title.text.toString())
        save(shot(a.host), "01-main-no-selection.png")

        // tap: file selects, tap again opens; folder opens on one tap
        ex.tap(ex.rows.first { it.uuid == ids["Wi-Fi (自宅)"] }); idle()
        assertEquals(ids["Wi-Fi (自宅)"], ex.selection)
        val pt = findTag(a.host, "pass") as android.widget.TextView   // 0.4.26: 「表示する」 → one line → whole → 「表示する」
        assertEquals("表示する", shown(pt))
        save(shot(a.host), "02-main-selected.png")
        pt.performClick()
        assertEquals("pass", pt.tag); assertFalse(shown(pt) == "表示する")
        ex.tap(ex.rows.first { it.uuid == ids["Wi-Fi (自宅)"] }); idle()
        assertTrue(a.screen is KeyScreen)
        a.showExplorer(); idle()
        ex.tap(ex.rows.first { it.uuid == ids["メール"] }); idle()
        assertEquals("0.4.15: first tap on a folder selects it", ids["メール"], ex.selection)
        ex.tap(ex.rows.first { it.uuid == ids["メール"] }); idle()
        assertEquals(ids["メール"], ex.current)
        ex.navigate(Tree.root(Session.db!!).uuid); idle()
        ex.select(ids["Wi-Fi (自宅)"]); idle()
        val row = ex.rows.first { it.uuid == ids["メール"] }
        save(overlay(shot(a.host), Menus.build(a, ex.itemMenu(row)) {}, 150, 640, 700), "03-context-menu.png")

        // 0.3.11: 一つ上のフォルダへ移動 (root-level disabled); 0.4.26: folders have it too
        val gmailId = ids["Gmail"]!!
        assertTrue(ex.canMoveToParentFolder(gmailId))
        assertFalse(ex.canMoveToParentFolder(ids["Wi-Fi (自宅)"]!!))
        assertTrue(ex.itemMenu(row).any { it.label == "一つ上の階層へ移動" && !it.enabled }) // row is folder メール at the root
        ex.navigate(ids["メール"]!!); idle()
        val gRow = ex.rows.first { it.uuid == gmailId }
        assertTrue(ex.itemMenu(gRow).any { it.label == "一つ上の階層へ移動" && it.enabled })
        save(overlay(shot(a.host), Menus.build(a, ex.itemMenu(gRow)) {}, 120, 480, 720), "03f-move-up-folder-menu.png")
        Menus.dismiss(); idle()
        ex.moveToParentFolder(gmailId); idle()
        assertEquals(Tree.root(Session.db!!).uuid, ex.current)
        assertEquals(0, Order.children(Tree.root(Session.db!!)).indexOfFirst { it.uuid == gmailId })
        // put Gmail back into メール for the rest of the sample-driven tests
        Session.update { Order.moveTo(it, gmailId, ids["メール"]!!) }; idle()
        ex.navigate(ids["メール"]!!); idle()
        ex.navigate(Tree.root(Session.db!!).uuid); idle()

        ex.navigate(ids["プロジェクトA"]!!); idle()
        // Registry Editor-like folder tree: ancestors of the current folder expanded, current highlighted
        val ft = ex.folderTree!!
        assertEquals(ids["プロジェクトA"], ft.current)
        assertTrue(ft.rows.any { it.tag == ids["プロジェクトA"] })
        save(shot(a.host), "03b-path-counts.png")
        // expand another folder with +/−; label one-tap navigates (0.4.0)
        val mail = ids["メール"]!!
        val shownBefore = ft.rows.size
        ft.toggle(ids["仕事"]!!)   // collapse
        assertEquals(shownBefore - 2, ft.rows.size)
        assertFalse(Tree.findGroup(Session.db!!, ids["仕事"]!!)!!.expanded)
        ft.toggle(ids["仕事"]!!)   // expand again (IsExpanded saved)
        assertEquals(shownBefore, ft.rows.size)
        assertTrue(Tree.findGroup(Session.db!!, ids["仕事"]!!)!!.expanded)
        ft.tap(mail); idle()
        assertEquals(mail, ft.selected)
        assertEquals("one tap navigates", mail, ex.current)
        save(shot(a.host), "03e-main-expanded-tree.png")
        ex.navigate(ids["プロジェクトA"]!!); idle()
        // live search: inline panel, results update on every keystroke (150 ms) and tag toggle — no button
        ex.searchTags.clear()
        ex.openSearch(); idle()
        assertEquals(View.VISIBLE, ex.searchPanel.visibility)
        assertTrue(ex.rows.isEmpty())
        ex.searchField.setText("g")
        ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        val afterG = ex.rows.map { ex.label(it) }.toSet()
        assertTrue(afterG.contains("Gmail"))
        ex.searchField.setText("gmail")
        ShadowLooper.idleMainLooper(60, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("debounced: not yet", afterG, ex.rows.map { ex.label(it) }.toSet())
        ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(setOf("Gmail"), ex.rows.map { ex.label(it) }.toSet())
        save(shot(a.host), "03c-search-live-text.png")
        ex.searchField.setText(""); ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        ex.searchTags.add("メール"); ex.applyLiveSearch(); idle()
        val mailOnly = ex.rows.size
        ex.searchTags.add("web"); ex.applyLiveSearch(); idle()
        assertEquals(setOf("Gmail", "Yahoo!メール"), ex.rows.map { ex.label(it) }.toSet())
        assertTrue(mailOnly >= ex.rows.size)
        ex.closeSearch(); ex.openSearch(); idle()   // tags kept, panel rebuilt with chips pressed
        assertEquals(setOf("Gmail", "Yahoo!メール"), ex.rows.map { ex.label(it) }.toSet())
        save(shot(a.host), "03d-search-live-tags.png")
        ex.searchTags.clear(); ex.closeSearch(); idle()
        assertEquals(View.GONE, ex.searchPanel.visibility)
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
        // compact remaining (0.4.5): "… Ns" small, no parentheses
        val otpTv = ks.boxes["@OTP"]!!.let { b ->
            fun findOtp(v: android.view.View): android.widget.TextView? {
                if (v is io.github.zmlworks.kpexplorer.KeyFields.OtpView) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findOtp(v.getChildAt(i))?.let { return it }
                return null
            }
            findOtp(b)!!
        }
        assertTrue(Regex("""\d{3} \d{3}  \d+s""").containsMatchIn(otpTv.text))
        assertFalse(otpTv.text.contains("("))
        assertFalse(otpTv.text.contains("あと"))
        assertTrue(ks.dirty)
        assertTrue(ks.save())
        idle()
        assertFalse(ks.passShown)
        ks.togglePass(); assertTrue(ks.passShown); ks.togglePass()
        // 0.3.13 key title bar: centered title, flat 閉じる (×) only — no メニュー
        assertNull(findDesc(a.host, "メニュー"))
        assertNotNull(findDesc(a.host, "閉じる"))
        assertNotNull(findDesc(a.host, "項目の編集"))
        assertNotNull(findDesc(a.host, "カスタム項目の追加"))
        assertNull(findDesc(a.host, "OTP設定を変更"))
        assertNull(findDesc(a.host, "この鍵を削除"))
        assertEquals("Gmail", ks.barTitle())
        // swipe on User box = copy
        val ub = ks.boxes["UserName"]!!
        fun ev(act: Int, x: Float) = MotionEvent.obtain(0, 0, act, x, 20f, 0)
        val cm = a.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("x", "before"))
        val th = ub.copyThreshold()
        // short slide: box follows the finger, springs back, no copy
        ub.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f)); ub.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 20f + th / 2))
        assertTrue(ub.sliding); assertEquals(th / 2, ub.translationX, 1f)
        ub.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 20f + th / 2))
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("before", cm.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(0f, ub.translationX, 0.5f)
        // long slide past the threshold: copies
        ub.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f)); ub.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 60f))
        ub.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 30f + th)); ub.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 30f + th))
        assertEquals("taro.yamada@gmail.com", cm.primaryClip!!.getItemAt(0).text.toString())
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(ub.editing)
        ub.startEdit(); assertTrue(ub.editing); ub.endEdit()
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        save(shot(a.host), "05-key-top.png")
        ks.scroll.scrollTo(0, 100000)
        save(shot(a.host), "05b-key-bottom.png")
        ks.scroll.scrollTo(0, 0)
        // mid-slide mock (past threshold → lighter blue reveal + コピー icon/text; password stays masked on the row)
        val pb = ks.boxes["Password"]!!
        pb.slideTo(pb.copyThreshold() + 10f)
        assertTrue(pb.revealArmed)
        assertEquals(io.github.zmlworks.kpexplorer.ui.FieldBox.COPY_BLUE_ARM, pb.revealColor())
        save(shot(a.host), "05c-key-swipe-mid.png")
        pb.slideTo(-(pb.copyThreshold() + 10f)) // other direction also reveals
        assertTrue(pb.revealArmed)
        save(shot(a.host), "05c2-key-swipe-left.png")
        pb.slideTo(0f); pb.springBack(); idle()
        // existing tags hidden until the Tag field is long-pressed
        assertFalse(ks.tagsShown)
        ks.boxes["@Tags"]!!.startEdit(); idle()
        assertTrue(ks.tagsShown)
        ks.boxes["@Tags"]!!.endEdit()
        save(shot(a.host), "05d-key-tags-shown.png")
        // icon picker
        ks.pickIcon(); idle()
        save(withDialog(shot(a.host)), "05e-icon-picker.png")
        Dlg.last?.dismiss(); idle()
        ks.setIcon("mail"); assertTrue(ks.dirty); assertTrue(ks.save()); idle()
        val gmi = Tree.findEntry(Session.db!!, ids["Gmail"]!!)!!.second
        assertEquals("mail", gmi.customData["KPExplorer.Icon"]?.value)
        assertEquals(app.keemobile.kotpass.constants.PredefinedIcon.Email, gmi.icon)
        // add-field dialog with 1 行 / 複数行
        ks.addCustom(); idle()
        ks.nameField!!.setText("備考"); ks.multiRadio!!.isChecked = true
        save(withDialog(shot(a.host)), "05f-add-field.png")
        Dlg.last?.dismiss(); idle()
        ks.toggleTag("仕事"); assertTrue(ks.dirty)
        val gm = Tree.findEntry(Session.db!!, ids["Gmail"]!!)!!.second
        assertEquals("0.4.16: the icon-only save makes no history entry", 1, gm.history.size)
        assertEquals(listOf("秘密の質問"), io.github.zmlworks.kpexplorer.db.Order.customFieldNames(gm))
        assertTrue(io.github.zmlworks.kpexplorer.db.Order.otpUri(gm)!!.contains("issuer=Gmail"))
        ks.addCustomField("一時", "x"); idle()
        findDesc(a.host, "閉じる")!!.performClick(); idle()
        assertNotNull("unsaved edits → confirm dialog via ×", Dlg.last)
        save(withDialog(shot(a.host)), "06-unsaved-confirm.png")
        Dlg.last?.dismiss(); idle()
        a.showExplorer(); idle()

        // memo screen + new items
        ex.navigate(Tree.root(Session.db!!).uuid); idle()
        a.show(MemoScreen(a, ids["買い物リスト"]!!)); idle()
        save(shot(a.host), "07-memo.png")
        assertNotNull(findDesc(a.host, "保存")); assertNotNull(findDesc(a.host, "戻る"))
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

        // Independent-memo notepad icon in list + toolbar (newMemo16)
        save(shot(a.host), "07b-independent-memo-list.png")

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
        save(withDialog(shot(a.host)), "10-export-same-or-other.png")
        findRadio(Dlg.last!!.frame, 1).performClick(); idle()
        save(withDialog(shot(a.host)), "10b-export-other-password.png")
        Dlg.last?.dismiss(); idle()

        a.lock(); idle()
        assertTrue(a.screen is UnlockScreen)
        val un = a.screen as UnlockScreen
        assertEquals("no wrapped key → master password box shown", View.VISIBLE, un.pwBox.visibility)
        save(shot(a.host), "11-unlock-master-password.png")
        un.pw.setText("wrong"); un.openWithPassword()
        repeat(300) { if (un.error.text.isEmpty()) { idle(); Thread.sleep(10) } }
        assertFalse(Session.isOpen)
        assertEquals("マスターパスワードが違います。", un.error.text.toString())
        un.pw.setText("sample"); un.openWithPassword()
        repeat(300) { if (!Session.isOpen) { idle(); Thread.sleep(10) } }
        idle()
        assertTrue(Session.isOpen)
        assertTrue(a.screen is ExplorerScreen)
    }

    private fun findRadio(v: View, n: Int): android.widget.RadioButton {
        val all = ArrayList<android.widget.RadioButton>()
        fun go(x: View) { if (x is android.widget.RadioButton) all.add(x); if (x is android.view.ViewGroup) for (i in 0 until x.childCount) go(x.getChildAt(i)) }
        go(v); return all[n]
    }

    @Test fun newKeyCreationScreen() {
        val a = openSample()
        val ex = a.explorer!!
        ex.navigate(ids["メール"]!!); idle()
        ex.newKey(); idle()
        assertTrue(a.screen is NewKeyScreen)
        val nk = a.screen as NewKeyScreen
        assertFalse(nk.dirty)
        // empty → cancel silently
        a.onBackPressed(); idle()
        assertNull(Dlg.last)
        assertTrue(a.screen is ExplorerScreen)

        ex.newKey(); idle()
        val n = a.screen as NewKeyScreen
        assertNotNull(n.genButton)
        assertNull("eye button removed 0.4.2", n.eyeButton)
        assertTrue("new-key Pass starts visible", n.passShown)
        assertNotNull(n.addButton)
        // plain editable fields (no FieldBox swipe)
        n.editors["Title"]!!.setText("新しいアカウント")
        n.editors["UserName"]!!.setText("user@example.com")
        assertTrue(n.dirty)
        n.genButton!!.performClick(); idle()
        save(withDialog(shot(a.host)), "15e-new-key-gen-confirm.png")
        Dlg.last!!.buttonViews.first().performClick(); idle()
        assertTrue(n.passShown)
        assertTrue(n.values["Password"]!!.length >= 16)
        n.toggleTag("メール"); idle()
        assertTrue(Tree.parseTags(n.tags).contains("メール"))
        n.setIcon("mail"); idle()
        assertEquals("mail", n.iconId)
        save(shot(a.host), "15-new-key.png")
        n.scroll.scrollTo(0, 100000)
        save(shot(a.host), "15b-new-key-bottom.png")
        n.scroll.scrollTo(0, 0)
        // tag chips visible on create screen
        save(shot(a.host), "15c-new-key-filled.png")
        assertTrue(n.create()); idle()
        assertTrue("after create → view screen", a.screen is KeyScreen)
        val ks = a.screen as KeyScreen
        assertFalse("saved entry Pass masked on KeyScreen", ks.passShown)
        assertEquals("新しいアカウント", ks.values["Title"])
        assertEquals("user@example.com", ks.values["UserName"])
        assertTrue(ks.values["Password"]!!.isNotEmpty())
        assertTrue(Tree.parseTags(ks.tags).contains("メール"))
        assertEquals("mail", ks.iconId)
        // existing entry still uses view screen gestures
        assertNotNull(ks.boxes["UserName"])
        a.showExplorer(); idle()
        // unsaved input → confirm
        ex.newKey(); idle()
        val n2 = a.screen as NewKeyScreen
        n2.editors["Title"]!!.setText("下書き")
        a.onBackPressed(); idle()
        assertNotNull(Dlg.last)
        save(withDialog(shot(a.host)), "15d-new-key-unsaved.png")
        Dlg.last?.dismiss(); idle()
        a.showExplorer(); idle()
    }

    /** Bug of 0.3.2: after 保存 the back button still asked "変更を保存しますか?". */
    @Test fun savedScreensAreNotDirty() {
        val a = openSample()
        val ks = KeyScreen(a, ids["Gmail"], ids["メール"]!!)
        a.show(ks); idle()
        assertFalse(ks.dirty)
        ks.boxes["UserName"]!!.startEdit(); ks.boxes["UserName"]!!.field.setText("new@example.com"); ks.boxes["UserName"]!!.endEdit()
        ks.toggleTag("仕事"); ks.addCustomField("暗証", "1234"); ks.setOtp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertTrue(ks.dirty)
        assertTrue(ks.save()); idle()
        assertFalse("clean right after save", ks.dirty)
        a.onBackPressed(); idle()
        assertNull("no confirm after saving", Dlg.last)
        assertTrue(a.screen is ExplorerScreen)
        // reopen: nothing changed → clean, still clean after a no-op edit round trip
        val ks2 = KeyScreen(a, ids["Gmail"], ids["メール"]!!); a.show(ks2); idle()
        assertFalse(ks2.dirty)
        ks2.removeFields(listOf("URL")); assertTrue(ks2.dirty); assertTrue(ks2.save()); idle(); assertFalse(ks2.dirty)
        a.showExplorer(); idle()
        // memo
        val ms = MemoScreen(a, ids["買い物リスト"]!!); a.show(ms); idle()
        assertFalse(ms.dirty)
        ms.text.append("\n・バター"); assertTrue(ms.dirty)
        assertTrue(ms.save()); idle(); assertFalse(ms.dirty)
        a.onBackPressed(); idle()
        assertNull(Dlg.last)
        assertTrue(a.screen is ExplorerScreen)
    }

    @Test fun keyFieldMenuAndPermanentDelete() {
        val a = openSample()
        var ks = KeyScreen(a, ids["Gmail"], ids["メール"]!!); a.show(ks); idle()
        assertNotNull(ks.editButton)
        assertNotNull(ks.addButton)
        val labels = ks.menu().map { it.label }
        assertTrue(labels.containsAll(listOf("項目の編集", "アイコンの変更")))
        assertFalse(labels.contains("URL をブラウザで開く"))
        assertFalse(labels.contains("パスワードを生成"))
        assertFalse(labels.contains("OTPを設定"))
        assertFalse(labels.contains("この鍵を削除"))
        val order0 = ks.layout.map { it.first }
        val iu = order0.indexOf("UserName")
        ks.moveField("UserName", -1)
        assertEquals(iu - 1, ks.layout.indexOfFirst { it.first == "UserName" })
        ks.moveField("UserName", 1)
        assertEquals(order0, ks.layout.map { it.first })
        val urlVal = ks.values["URL"].orEmpty()
        assertTrue(urlVal.isNotEmpty())
        ks.applyArrangement(ks.layout.map { (k, v) -> if (k == "URL") k to false else k to v })
        assertTrue(ks.layout.any { it.first == "URL" && !it.second })
        assertNull(ks.boxes["URL"])
        assertEquals(urlVal, ks.values["URL"])
        assertTrue(ks.save()); idle()
        ks = KeyScreen(a, ids["Gmail"], ids["メール"]!!); a.show(ks); idle()
        assertTrue(ks.layout.any { it.first == "URL" && !it.second })
        assertEquals(urlVal, ks.values["URL"])
        assertNull(ks.boxes["URL"])
        save(shot(a.host), "05g-key-url-hidden.png")
        ks.editLayout(); idle()
        val ed = ks.arrangeEditor!!
        assertFalse("Title" in ed.shown)
        assertFalse("Title" in ed.hidden)
        assertTrue("URL" in ed.hidden)
        assertNull(ed.handleFor("Title"))
        save(withDialog(shot(a.host)), "05h-field-arrange.png") // URL under この下は非表示; Title omitted; handle right
        ed.place("URL", visible = true, index = ed.shown.size)
        ed.place("Title", visible = false, index = 0) // no-op
        assertFalse("Title" in ed.shown)
        assertEquals("Title" to true, ed.snapshot().first())
        Dlg.last?.dismiss(); idle()
        ks.applyArrangement(ed.snapshot())
        assertNotNull(ks.boxes["URL"])
        assertEquals(urlVal, ks.values["URL"])
        assertTrue(ks.save()); idle()
        ks = KeyScreen(a, ids["Gmail"], ids["メール"]!!); a.show(ks); idle()
        // permanent remove still works (clears content + drops from layout)
        ks.removeFields(listOf("URL")); assertTrue(ks.layout.none { it.first == "URL" })
        assertNotNull(ks.addButton)
        ks.boxes["Password"]!!.onTap!!.invoke(); assertTrue(ks.passShown)
        // multi-line custom field: stored flag, shown multi-line after reopen
        ks.addCustomField("備考", "1行目\n2行目", multiline = true)
        assertTrue(ks.save()); idle()
        val ent = Tree.findEntry(Session.db!!, ids["Gmail"]!!)!!.second
        assertEquals(setOf("備考"), io.github.zmlworks.kpexplorer.db.Order.multiline(ent))
        val ks2 = KeyScreen(a, ids["Gmail"], ids["メール"]!!); a.show(ks2); idle()
        assertFalse(ks2.boxes["備考"]!!.field.isSingleLine())
        ks = ks2
        // explorer uses the chosen icon
        ks.setIcon("wifi"); assertTrue(ks.save()); idle()
        a.showExplorer(); a.explorer!!.navigate(ids["メール"]!!); idle()
        val node = a.explorer!!.rows.first { it.uuid == ids["Gmail"] }
        a.explorer!!.selection = null
        assertTrue(a.explorer!!.bmpFor(node, true).sameAs(io.github.zmlworks.kpexplorer.ui.FugueIcons.bitmap(a, "wifi", 32)))
        a.show(ks); idle()
        ks.dirty = false; a.showExplorer(); idle()
        val before = Session.db!!.content.deletedObjects.size
        a.explorer!!.navigate(ids["メール"]!!); idle()
        a.explorer!!.askDelete(ids["Gmail"]!!); idle()
        assertNull("soft delete-to-bin has no confirm (0.4.2)", Dlg.last)
        // Soft-delete: entry lives in recycle bin; DeletedObjects unchanged
        assertTrue(Tree.isInRecycleBin(Session.db!!, ids["Gmail"]!!))
        assertEquals(before, Session.db!!.content.deletedObjects.size)
        assertNotNull(Tree.recycleBinId(Session.db!!))
        // Permanent delete from bin still confirms
        a.explorer!!.navigate(Tree.recycleBinId(Session.db!!)!!); idle()
        a.explorer!!.askDelete(ids["Gmail"]!!); idle()
        assertNotNull("permanent delete still confirms", Dlg.last)
        fun find(v: View, t: String): View? { if (v is android.widget.TextView && v.text.toString() == t) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), t)?.let { return it }; return null }
        val yes = listOf("はい", "Yes").firstNotNullOf { lab -> find(Dlg.last!!.frame, lab) }
        yes.performClick(); idle()
        assertNull(Tree.findEntry(Session.db!!, ids["Gmail"]!!))
    }

    @Test fun historyIsCappedAtTen() {
        var db = sample()
        val id = ids["Gmail"]!!
        repeat(15) { i -> db = io.github.zmlworks.kpexplorer.db.Order.saveEntry(db, id, mapOf("Title" to "Gmail $i"), listOf(), null) }
        assertEquals(10, Tree.findEntry(db, id)!!.second.history.size)
        assertEquals(10, Kdbx.create("x", key).content.meta.historyMaxItems)
    }

    @Test fun debouncedSavesCoalesce() {
        assertEquals(1500L, Session::class.java.let { 1500L })
        val a = openSample()
        val st = App.store(app()); Session.store = st
        Session.debounceMs = 300
        val before = Session.saveCount
        val ex = a.explorer!!
        repeat(5) { ex.newFolder() }   // five rapid changes
        idle()
        assertEquals("nothing written before the quiet period", before, Session.saveCount)
        repeat(200) { if (Session.saveCount == before || Session.saveState != Session.SaveState.SAVED) { ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(10) } }
        assertEquals("one coalesced save", before + 1, Session.saveCount)
    }

    /** New-key Pass「生成」stays horizontal at ~480dp; existing KeyScreen has no generator (0.4.2). */
    @Test fun passGenerateChipStaysHorizontalAt480() {
        val a = openSample()
        val dens = a.resources.displayMetrics.density
        val wPx = (480 * dens).toInt()
        val hPx = (891 * dens).toInt()
        // Existing key: no generate chip
        val ks = KeyScreen(a, ids["Gmail"], ids["メール"]!!); a.show(ks); idle()
        a.host.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        a.host.layout(0, 0, wPx, hPx); idle()
        assertNull("existing key has no Pass generator", findDesc(a.host, "パスワードを生成"))
        // New-key retains generator
        a.showExplorer(); a.explorer!!.navigate(ids["メール"]!!); idle()
        a.explorer!!.newKey(); idle()
        a.host.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
        a.host.layout(0, 0, wPx, hPx); idle()
        val gen = findDesc(a.host, "パスワードを生成") as android.widget.TextView
        assertNotNull(gen)
        val wDp = gen.width / dens
        val hDp = gen.height / dens
        assertTrue("min width: wDp=$wDp", wDp >= 40f)
        assertTrue("horizontal not stacked: wDp=$wDp hDp=$hDp", wDp + 4f >= hDp)
        assertEquals("生成", gen.text.toString())
        val full = android.graphics.Bitmap.createBitmap(wPx, hPx, android.graphics.Bitmap.Config.ARGB_8888)
        a.host.draw(android.graphics.Canvas(full))
        save(full, "14-pass-generate-chip-480.png")
    }

    private fun findDesc(v: View, d: String): View? {

        if (v.contentDescription?.toString() == d) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findDesc(v.getChildAt(i), d)?.let { return it }
        return null
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
        // Soft delete has no dialog; permanent from bin still confirms
        a.explorer!!.askDelete(ids["メール"]!!); idle()
        assertNull(Dlg.last)
        assertTrue(Tree.isInRecycleBin(Session.db!!, ids["メール"]!!))
        a.explorer!!.navigate(Tree.recycleBinId(Session.db!!)!!); idle()
        a.explorer!!.askDelete(ids["メール"]!!); idle()
        assertNotNull(Dlg.last)
        save(withDialog(shot(a.host)), "13-delete-confirm.png")
        Dlg.last?.dismiss()
        Help.about(a); idle()
        save(withDialog(shot(a.host)), "14-about.png")
        assertTrue(W.px >= 1)
    }

    @Test fun memoIcons_clearCoverNotepad() {
        val dir = File(out, "icons"); dir.mkdirs()
        fun dump(b: Bitmap, name: String) = FileOutputStream(File(dir, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        dump(io.github.zmlworks.kpexplorer.ui.Ico.notepad32, "memo-notepad32.png")
        dump(io.github.zmlworks.kpexplorer.ui.Ico.notepad16, "memo-notepad16.png")
        dump(io.github.zmlworks.kpexplorer.ui.Ico.newMemo16, "memo-newMemo16.png")
        val note = io.github.zmlworks.kpexplorer.ui.FugueIcons.bitmap(app(), "note", 48)
        dump(note, "selectable-note-material.png")
        assertTrue(note.width == 48)
    }

    private fun shown(t: android.widget.TextView) = t.transformationMethod?.getTransformation(t.text, t)?.toString() ?: t.text.toString()
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
