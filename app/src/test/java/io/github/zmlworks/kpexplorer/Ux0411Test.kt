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

/** 0.4.11: OTP set-up / display / copy / restore, OTP on the create form, preview tap-select-then-open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0411Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0411").mkdirs() }
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

    private fun entry(id: UUID) = Tree.findEntry(Session.db!!, id)!!.second
    private fun openKey(id: UUID): KeyScreen { val ks = KeyScreen(a, id, Tree.parentOf(Tree.root(Session.db!!), id)!!.uuid); a.show(ks); idle(); layoutHost(); return ks }
    private fun addEntry(e: app.keemobile.kotpass.models.Entry): UUID {
        Session.update { Order.appendEntry(it, Tree.root(it).uuid, e) }; idle(); return e.uuid
    }
    private fun withFields(title: String, extra: Map<String, app.keemobile.kotpass.models.EntryValue>): app.keemobile.kotpass.models.Entry {
        val e = Tree.newEntry(Tree.Fields(title, "u", "p", "", "", emptyList()))
        return e.copy(fields = app.keemobile.kotpass.models.EntryFields(LinkedHashMap(e.fields).apply { putAll(extra) }))
    }
    private fun prot(s: String) = app.keemobile.kotpass.models.EntryValue.Encrypted(app.keemobile.kotpass.cryptography.EncryptedValue.fromString(s))
    private fun plain(s: String) = app.keemobile.kotpass.models.EntryValue.Plain(s)
    private val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"   // RFC 6238 SHA1 test key "12345678901234567890" (public test vector) gitleaks:allow

    // ---------------------------------------------------------------- 1: OTP regression

    @Test fun keyWithoutOtp_hasEmptyOtpRow_longPressSetsUp_andSaves() {
        val ks = openKey(ids["Gmail"]!!)
        val box = ks.boxes[Order.F_OTP]
        assertNotNull("empty OTP row is drawn (was missing since 0.3.15)", box)
        assertTrue(texts(box!!).any { it.contains("長押しで設定") })
        shot("v0411/03-key-empty-otp.png")
        box.startEdit(); idle()   // = long press on a non-editable row
        val d = Dlg.last!!
        assertTrue(texts(d.frame).any { it.contains("otpauth://") })
        find(d.frame, android.widget.EditText::class.java)!!.setText(SECRET)
        d.buttonViews.first().performClick(); idle()
        assertTrue(ks.values[Order.OTP]!!.startsWith("otpauth://totp/Gmail:"))
        assertTrue(ks.save(silent = true)); idle()
        val e = entry(ids["Gmail"]!!)
        assertTrue(e.fields[Order.OTP] is app.keemobile.kotpass.models.EntryValue.Encrypted)
        assertNotNull(Order.otpUri(e))
        // 0.4.13: the list menu no longer copies; the key page's OTP row (swipe) does
        a.showExplorer(); idle()
        // reopen: code shown, not the placeholder
        val ks2 = openKey(ids["Gmail"]!!)
        assertFalse(texts(ks2.boxes[Order.F_OTP]!!).any { it.contains("長押しで設定") })
    }

    @Test fun otpLeftOutOnCreate_canBeDraggedBack_andSetUp() {
        val nk = NewKeyScreen(a, Tree.root(Session.db!!).uuid); a.show(nk); idle()
        nk.editors[Order.F_TITLE]!!.setText("OTPなし")
        assertTrue(nk.create()); idle()
        val id = Tree.root(Session.db!!).entries.first { Tree.title(it) == "OTPなし" }.uuid
        val ks = openKey(id)
        assertNull("left out = deleted from the start", ks.boxes[Order.F_OTP])
        ks.editLayout(); idle()
        val snap = ks.arrangeEditor!!.snapshot()
        assertTrue("restorable from the hidden zone", snap.contains(Order.F_OTP to false))
        Dlg.last?.dismiss(); idle()
        ks.applyArrangement(snap.map { if (it.first == Order.F_OTP) it.first to true else it }); idle()
        assertNotNull("dragged back -> empty OTP row", ks.boxes[Order.F_OTP])
        ks.setOtp(SECRET); assertTrue(ks.save(silent = true)); idle()
        assertTrue(Order.layout(entry(id)).contains(Order.F_OTP to true))
    }

    @Test fun keePass2TimeOtp_isShownCopiedKept_andDeletable() {
        val id = addEntry(withFields("KeePass2形式", mapOf("TimeOtp-Secret-Base32" to prot(SECRET), "TimeOtp-Length" to plain("8"),
            "TimeOtp-Period" to plain("30"))))
        val uri = Order.otpUri(entry(id))!!
        assertEquals("RFC 6238 vector", "94287082", io.github.zmlworks.kpexplorer.db.Totp.code(io.github.zmlworks.kpexplorer.db.Totp.parse(uri), 59))
        assertFalse("not listed as custom fields", Order.customFieldNames(entry(id)).any { it.startsWith("TimeOtp") })
        val ks = openKey(id)
        assertFalse("code shown (was empty: key screen read only the otp field)", texts(ks.boxes[Order.F_OTP]!!).any { it.contains("長押しで設定") })
        // an unrelated edit keeps the KeePass 2 fields and their protection
        ks.values["URL"] = "https://example.com"; ks.setIcon("mail"); assertTrue(ks.save(silent = true)); idle()
        var e = entry(id)
        assertTrue(e.fields["TimeOtp-Secret-Base32"] is app.keemobile.kotpass.models.EntryValue.Encrypted)
        assertEquals(SECRET, e.fields["TimeOtp-Secret-Base32"]!!.content)
        assertNull("no duplicate otp field written", e.fields[Order.OTP])
        // OTP 削除 removes them for real (before: they were re-added on save)
        val ks2 = openKey(id); ks2.setOtp(null); assertTrue(ks2.save(silent = true)); idle()
        e = entry(id)
        assertNull(Order.otpUri(e)); assertTrue(e.fields.keys.none { it.startsWith("TimeOtp-") })
        // survives a file round trip
        assertNull(Order.otpUri(Tree.findEntry(Kdbx.open(Kdbx.encode(Session.db!!), key), id)!!.second))
    }

    @Test fun legacyKeePassXcTotpSeed_isRead() {
        val id = addEntry(withFields("旧KeePassXC", mapOf("TOTP Seed" to prot(SECRET), "TOTP Settings" to plain("30;8"))))
        val uri = Order.otpUri(entry(id))!!
        assertEquals("94287082", io.github.zmlworks.kpexplorer.db.Totp.code(io.github.zmlworks.kpexplorer.db.Totp.parse(uri), 59))
    }

    @Test fun existingOtp_survivesLayoutEditsAndRenames() {
        val ks = openKey(ids["Amazon"]!!); ks.setOtp(SECRET); assertTrue(ks.save(silent = true)); idle()
        val u0 = entry(ids["Amazon"]!!).fields[Order.OTP]!!.content
        // hide OTP: value kept
        val ks2 = openKey(ids["Amazon"]!!)
        ks2.applyArrangement(ks2.layout.map { if (it.first == Order.F_OTP) it.first to false else it }); assertTrue(ks2.save(silent = true)); idle()
        assertEquals(u0, entry(ids["Amazon"]!!).fields[Order.OTP]!!.content)
        Session.update { Order.renameEntry(it, ids["Amazon"]!!, "Amazon.co.jp") }; idle()
        assertEquals(u0, entry(ids["Amazon"]!!).fields[Order.OTP]!!.content)
        assertNotNull("OTP still readable while hidden", Order.otpUri(entry(ids["Amazon"]!!)))
    }

    // ---------------------------------------------------------------- 2: OTP on the create form

    @Test fun newKey_otpField() {
        val nk = NewKeyScreen(a, Tree.root(Session.db!!).uuid); a.show(nk); idle(); layoutHost()
        assertNotNull(nk.editors[Order.F_OTP])
        nk.editors[Order.F_TITLE]!!.setText("銀行アプリ"); nk.editors[Order.F_USER]!!.setText("taro")
        nk.editors[Order.F_OTP]!!.setText(SECRET.lowercase().chunked(4).joinToString(" "))
        shot("v0411/01-newkey-otp.png")
        assertTrue(nk.create()); idle()
        val e = Tree.root(Session.db!!).entries.first { Tree.title(it) == "銀行アプリ" }
        val u = e.fields[Order.OTP]!!
        assertTrue(u is app.keemobile.kotpass.models.EntryValue.Encrypted)
        assertTrue(u.content, u.content.startsWith("otpauth://totp/%E9%8A%80%E8%A1%8C%E3%82%A2%E3%83%97%E3%83%AA:taro?secret=$SECRET"))
        assertEquals(listOf(Order.F_TITLE, Order.F_USER, Order.F_OTP), Order.layout(e).map { it.first })
        // invalid -> message, not created
        val nk2 = NewKeyScreen(a, Tree.root(Session.db!!).uuid); a.show(nk2); idle()
        nk2.editors[Order.F_TITLE]!!.setText("不正"); nk2.editors[Order.F_OTP]!!.setText("not a secret!")
        assertFalse(nk2.create()); idle()
        assertNotNull(Dlg.last); Dlg.last!!.dismiss(); idle()
        assertTrue(Tree.root(Session.db!!).entries.none { Tree.title(it) == "不正" })
        // otpauth URI kept as typed
        nk2.editors[Order.F_OTP]!!.setText("otpauth://totp/X:y?secret=$SECRET&period=60")
        assertTrue(nk2.create()); idle()
        assertEquals("otpauth://totp/X:y?secret=$SECRET&period=60", Tree.root(Session.db!!).entries.first { Tree.title(it) == "不正" }.fields[Order.OTP]!!.content)
        // OTP alone counts as input
        val (v, l) = NewKeyScreen.createdFields(linkedMapOf("Title" to "", "otp" to "   "), emptyList(), emptyList())
        assertFalse(v.containsKey("otp")); assertFalse(l.any { it.first == Order.F_OTP })
    }

    // ---------------------------------------------------------------- 3: preview tap-select-then-open

    @Test fun previewTap_selectsThenOpens() {
        val ex = a.explorer!!; layoutHost()
        val r = ex.rows.first { it.uuid == ids["Gmail"] }
        ex.tap(r); idle(); layoutHost()
        assertEquals(ids["Gmail"], ex.selection)
        assertNull(ex.previewActive)
        val pane = ex.view.findViewWithTag<View>("pass")!!.let { var v: View = it; while (v !is ExplorerScreen.PreviewBox) v = v.parent as View; v }
        // Pass tap = show / hide only (does not count as a preview tap)
        val pass = ex.view.findViewWithTag<View>("pass")!!
        val pl = IntArray(2); pass.getLocationInWindow(pl); val bl = IntArray(2); pane.getLocationInWindow(bl)
        val px = (pl[0] - bl[0] + pass.width / 2).toFloat(); val py = (pl[1] - bl[1] + pass.height / 2).toFloat()
        pane.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, px, py, 0)); pane.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, px, py, 50)); idle()
        assertNull(ex.previewActive)
        pane.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, px, py, 60)); pane.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, px, py, 90)); idle()  // hide again
        assertNull(ex.previewActive)
        // first tap elsewhere in the preview: selected (title highlighted), still on the explorer
        pane.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 40f, 30f, 100)); pane.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 40f, 30f, 150)); idle()
        assertEquals(ids["Gmail"], ex.previewActive)
        assertSame(ex, a.screen)
        shot("v0411/02-preview-selected.png")
        // a scroll / long touch is not a tap
        pane.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 40f, 30f, 200)); pane.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 40f, 130f, 220))
        pane.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 40f, 130f, 240)); idle()
        assertSame(ex, a.screen)
        pane.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 40f, 30f, 300)); pane.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 40f, 30f, 2300)); idle()
        assertSame(ex, a.screen)
        // second tap opens the key
        pane.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 40f, 30f, 3000)); pane.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 40f, 30f, 3050)); idle()
        assertTrue(a.screen is KeyScreen)
        // touching the list resets the preview selection
        a.showExplorer(); idle()
        ex.tap(ex.rows.first { it.uuid == ids["Amazon"] }); idle()
        ex.onPreviewTap(); assertEquals(ids["Amazon"], ex.previewActive)
        ex.tap(ex.rows.first { it.uuid == ids["Steam"] }); idle()
        assertNull(ex.previewActive)
    }
}
