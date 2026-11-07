package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.TagEdit
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.FieldBox
import io.github.zmlworks.kpexplorer.ui.FieldEditSession
import io.github.zmlworks.kpexplorer.ui.FolderTree
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.Px
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux045Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { mkdirs() }
    private val key = Kdbx.compositeKey("sample")
    private lateinit var a: MainActivity
    private val ids = HashMap<String, UUID>()
    private val W = 411 * 3; private val H = 891 * 3

    private fun app() = RuntimeEnvironment.getApplication()
    private fun idle() { repeat(30) { ShadowLooper.idleMainLooper(); Thread.sleep(3) } }

    @Before fun setUp() {
        Session.lock(); Session.store = null; Session.debounceMs = 50
        Vault(app()).clear(); App.prefs(app()).edit().clear().commit()
        Vault.forceAvailableForTest = true
        App.store(app()).let { s -> s.delete(); s.backups().forEach { it.delete() } }
        File(app().filesDir, "data-version").writeText(App.DATA_VERSION.toString())
        var db = Kdbx.create("t", key, light = true)
        val g = Tree.newGroup("Work"); db = Tree.addGroup(db, Tree.root(db).uuid, g); ids["G"] = g.uuid
        val e = Tree.newEntry(Tree.Fields("Site", "u", "p", "https://example.com/login", "", listOf("仕事", "web")))
        db = Tree.addEntry(db, g.uuid, e); ids["E"] = e.uuid
        db = Tree.ensureRecycleBin(db, "ごみ箱")
        App.store(app()).write(Kdbx.encodeVerified(db, keepSalt = true))
        Session.open(db); Session.store = App.store(app())
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        if (!Session.isOpen) Session.open(db)
        Session.store = a.store
        if (a.explorer == null) a.showExplorer(); idle()
    }
    @After fun tearDown() { runCatching { FieldEditSession.commitActive() }; Session.lock(); Vault.forceAvailableForTest = null; Menus.dismiss(); Dlg.last = null }

    private fun layoutHost() {
        a.host.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        a.host.layout(0, 0, W, H); idle()
    }
    private fun openKey(): KeyScreen { val ks = KeyScreen(a, ids["E"], ids["G"]!!); a.show(ks); idle(); layoutHost(); return ks }
    private fun entry() = Tree.findEntry(Session.db!!, ids["E"]!!)!!.second
    private fun shot(name: String, dialog: Boolean = false) {
        layoutHost()
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(b); a.host.draw(c)
        if (dialog) Dlg.last?.frame?.let { v ->
            v.measure(View.MeasureSpec.makeMeasureSpec(W - 72, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H - 300, View.MeasureSpec.AT_MOST))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            c.save(); c.translate(36f, ((H - v.measuredHeight) / 2).toFloat()); v.draw(c); c.restore()
        }
        FileOutputStream(File(out, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun swipe(box: FieldBox, dx: Float, cancel: Boolean = false) {
        val y = 40f; val x0 = 600f
        box.dispatchTouchEvent(MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, x0, y, 0))
        for (i in 1..8) box.dispatchTouchEvent(MotionEvent.obtain(0, 1L + i, MotionEvent.ACTION_MOVE, x0 + dx * i / 8, y, 0))
        box.dispatchTouchEvent(MotionEvent.obtain(0, 20, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x0 + dx, y, 0))
        idle()
    }

    // ---------- 1 tree bin icon ----------
    @Test fun treeShowsBinGlyphByUuid() {
        val d = Session.db!!
        val bin = Tree.recycleBinId(d)!!
        // rename to something unrelated: identity must be UUID, not name
        Session.update { Tree.renameGroup(it, bin, "Trash-X") }
        a.explorer!!.refresh(); idle(); layoutHost()
        val tree = a.explorer!!.folderTree!!
        assertSame(Px.bin16, tree.iconFor(bin))
        assertSame(Px.folder16, tree.iconFor(ids["G"]!!))
        // selected/current = bin
        a.explorer!!.navigate(bin); idle(); layoutHost()
        assertSame(Px.bin16, a.explorer!!.folderTree!!.iconFor(bin))
        shot("21-tree-bin-selected.png")
        a.explorer!!.navigate(Tree.root(Session.db!!).uuid); idle()
        shot("21-tree-bin.png")
        // move-to dialog tree too
        val t2 = FolderTree(a, Tree.root(Session.db!!), null, Tree.recycleBinId(Session.db!!), onToggle = { _, _ -> }, onSelect = {})
        assertSame(Px.bin16, t2.iconFor(bin))
    }

    // ---------- 3 OTP ----------
    @Test fun otpCountdownSmallNoParens() {
        val ks = openKey()
        ks.setOtp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"); idle(); layoutHost()
        val o = findOtp(ks.boxes["@OTP"]!!)!!
        o.update()
        val s = o.text
        assertTrue(s.toString(), Regex("""^\d{3} \d{3}  \d+s$""").matches(s.toString()))
        assertFalse(s.contains("("))
        val sp = s as android.text.Spanned
        val rs = sp.getSpans(0, s.length, android.text.style.RelativeSizeSpan::class.java)
        assertEquals(1, rs.size); assertTrue(rs[0].sizeChange < 1f)
        assertEquals(s.toString().indexOf("s") - (s.toString().substringAfterLast(' ').length - 1), sp.getSpanStart(rs[0]))
        assertEquals(6, o.current()!!.length) // copy = token only
        shot("22-key-otp-tags.png")
    }
    private fun findOtp(v: View): KeyFields.OtpView? {
        if (v is KeyFields.OtpView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findOtp(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun smallLabelsInsetRight() {
        val ks = openKey()
        assertTrue(ks.boxes["UserName"]!!.labelView.paddingLeft >= io.github.zmlworks.kpexplorer.ui.W.dp(6))
        assertEquals(0, ks.boxes["UserName"]!!.field.paddingLeft)
    }

    // ---------- 4 URL ----------
    @Test fun urlCheckOnlyHttpHttps() {
        assertEquals("https://example.com/a?b=1", UrlCheck.safe(" https://example.com/a?b=1 "))
        assertNotNull(UrlCheck.safe("http://x.jp"))
        for (bad in listOf("javascript:alert(1)", "file:///sdcard/x", "content://a/b", "intent://x#Intent;end", "example.com", "", "https://", "https://a b.com", "ftp://x.com"))
            assertNull(bad, UrlCheck.safe(bad))
    }

    @Test fun urlLeftSwipeConfirmsThenOpensOnceRightCopies() {
        val ks = openKey()
        val box = ks.boxes["URL"]!!
        val th = box.copyThreshold()
        val sa = Shadows.shadowOf(a)
        // under threshold → nothing
        Dlg.last = null; swipe(box, -(th - 40f)); assertNull(Dlg.last)
        // cancel event → nothing
        swipe(box, -(th + 40f), cancel = true); assertNull(Dlg.last)
        // MOVE only, no UP → nothing
        box.dispatchTouchEvent(MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 600f, 40f, 0))
        box.dispatchTouchEvent(MotionEvent.obtain(0, 2, MotionEvent.ACTION_MOVE, 600f - th - 60f, 40f, 0)); idle()
        assertNull(Dlg.last); assertNull(sa.nextStartedActivity)
        box.dispatchTouchEvent(MotionEvent.obtain(0, 3, MotionEvent.ACTION_CANCEL, 600f - th - 60f, 40f, 0)); idle()
        // left swipe → confirm with URL, cancel → no launch
        swipe(box, -(th + 40f))
        assertNotNull(Dlg.last); shot("23-url-open-confirm.png", dialog = true)
        assertNull(sa.nextStartedActivity)
        Dlg.last!!.buttonViews.last().performClick(); idle()
        assertNull(sa.nextStartedActivity)
        // left swipe → confirm → launch exactly once
        Dlg.last = null; swipe(box, -(th + 40f))
        Dlg.last!!.buttonViews.first().performClick(); idle()
        val i = sa.nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, i.action)
        assertEquals("https://example.com/login", i.dataString)
        assertNull(sa.nextStartedActivity)
        // right swipe → copy only, no dialog
        Dlg.last = null; swipe(box, th + 40f)
        assertNull(Dlg.last); assertNull(sa.nextStartedActivity)
        val cm = app().getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("https://example.com/login", cm.primaryClip!!.getItemAt(0).text.toString())
        // invalid URL → validation message, no launch
        ks.confirmOpenUrl("javascript:alert(1)"); idle()
        assertTrue(Dlg.last!!.isShowing); Dlg.last!!.dismiss()
        assertNull(sa.nextStartedActivity)
    }

    // ---------- 5 order + no tag swipe ----------
    @Test fun tagDefaultBelowMemoAndOldDefaultMigratesOnRead() {
        assertEquals(Order.F_TAGS, Order.DEFAULT_LAYOUT.last())
        val ks = openKey()
        val keys = ks.layout.map { it.first }
        assertTrue(keys.indexOf(Order.F_TAGS) > keys.indexOf(Order.F_NOTES))
        // exactly-old-default stored → new default; custom order preserved
        val old = Order.encodeLayout(Order.OLD_DEFAULT_LAYOUT.map { it to true })
        Session.update { Order.saveEntry(it, ids["E"]!!, entry().fields.entries.associate { (k, v) -> k to v.content }, entry().tags, Order.OLD_DEFAULT_LAYOUT.map { it to true }) }
        assertEquals(old, entry().customData[Order.CD_FIELDS]!!.value)
        assertEquals(Order.DEFAULT_LAYOUT, Order.layout(entry()).map { it.first })
        val custom = listOf(Order.F_TITLE, Order.F_TAGS, Order.F_USER, Order.F_PASS, Order.F_URL, Order.F_OTP, Order.F_NOTES)
        Session.update { Order.saveEntry(it, ids["E"]!!, entry().fields.entries.associate { (k, v) -> k to v.content }, entry().tags, custom.map { it to true }) }
        assertEquals(custom, Order.layout(entry()).map { it.first })
    }

    @Test fun tagRowHasNoSwipeCopy() {
        val ks = openKey()
        val box = ks.boxes[Order.F_TAGS]!!
        val cm = app().getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("x", "before"))
        swipe(box, box.copyThreshold() + 50f); swipe(box, -(box.copyThreshold() + 50f))
        assertEquals("before", cm.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(0f, box.translationX)
        assertNull(box.onSwipe)
    }

    // ---------- 6 tag bugs ----------
    @Test fun tagEditKeepsTypedTagWhenChipTapped_regression() {
        // pre-0.4.5: typed partial "a" was dropped when tapping chip "仕事" → "仕事 "
        assertEquals("a 仕事 ", TagEdit.toggle("a", listOf("仕事", "web"), "仕事"))
        assertEquals("仕事 ", TagEdit.toggle("仕", listOf("仕事", "web"), "仕事"))  // completion
        assertEquals("x web 仕事 ", TagEdit.toggle("x web", listOf("web"), "仕事"))
        assertEquals("x ", TagEdit.toggle("x web ", listOf("web"), "web"))
        assertEquals(listOf("a", "あ"), Tree.parseTags("a あ a"))  // dedup, 1-char kept
    }

    @Test fun existingKeySingleCharTagsEditSaveReopen() {
        val ks = openKey()
        val box = ks.boxes[Order.F_TAGS]!!
        box.startEdit()               // (long press path; tap no longer edits — 0.4.7)
        assertTrue(box.editing)
        box.field.setText("仕事 a あ web ")
        layoutHost()
        // chip while editing must work (was swallowed by outside-tap interceptor)
        val chips = ks.view.findViewWithTagChip("✓ web")!!
        val loc = IntArray(2); chips.getLocationOnScreen(loc)
        val x = loc[0] + chips.width / 2f; val y = loc[1] + chips.height / 2f
        ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, x, y, 0))
        ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 2, MotionEvent.ACTION_UP, x, y, 0)); idle()
        assertEquals(listOf("仕事", "a", "あ"), Tree.parseTags(ks.tags))  // web removed via chip, a/あ kept
        // outside tap → draft only
        ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 3, MotionEvent.ACTION_DOWN, 20f, 20f, 0)); ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 3, MotionEvent.ACTION_UP, 20f, 20f, 0)); idle()
        assertFalse(box.editing)
        assertEquals(listOf("仕事", "web"), entry().tags)
        assertTrue(ks.save()); idle()
        assertEquals(listOf("仕事", "a", "あ"), entry().tags)
        // KDBX roundtrip
        val re = Kdbx.open(Kdbx.encodeVerified(Session.db!!), key)
        assertEquals(listOf("仕事", "a", "あ"), Tree.findEntry(re, ids["E"]!!)!!.second.tags)
        // reopen shows them; search sees new tag
        val ks2 = openKey()
        assertEquals(listOf("仕事", "a", "あ"), Tree.parseTags(ks2.tags))
        assertTrue(Tree.search(Session.db!!, "#あ").any { it.second.uuid == ids["E"] })
        // discard path: edit then back + 保存しない → unchanged
        val b2 = ks2.boxes[Order.F_TAGS]!!
        b2.startEdit(); b2.field.setText("z"); a.onBackPressed(); idle(); a.onBackPressed(); idle()
        Dlg.last!!.buttonViews[1].performClick(); idle()
        assertEquals(listOf("仕事", "a", "あ"), entry().tags)
    }

    @Test fun newKeyTitleAndSingleCharTagCreate() {
        a.explorer!!.newKey(); idle()
        val nk = a.screen as NewKeyScreen
        nk.editors["Title"]!!.setText("T")
        nk.editors[Order.F_TAGS]!!.setText("x")
        nk.toggleTag("web"); idle()         // chip after typing 1-char tag keeps it
        assertEquals(listOf("x", "web"), Tree.parseTags(nk.tags))
        assertTrue(nk.create()); idle()
        val e = Tree.root(Session.db!!).entries.first { Tree.title(it) == "T" }
        assertEquals(listOf("x", "web"), e.tags)
        assertEquals(Order.F_TAGS, Order.layout(e).last().first)
    }

    /** 0.4.6: real root MotionEvents — chip tap keeps edit+chips; outside tap ends edit, collapses ALL chips, draft only. */
    @Test fun tagOutsideTapCollapsesChips_draftOnly() {
        val ks = openKey()
        val box = ks.boxes[Order.F_TAGS]!!
        val loc0 = IntArray(2); box.getLocationOnScreen(loc0)
        val tx = loc0[0] + box.width / 2f; val ty = loc0[1] + box.height / 2f
        ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, tx, ty, 0))
        ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 2, MotionEvent.ACTION_UP, tx, ty, 0)); idle()
        assertFalse("single tap must NOT edit (0.4.7)", box.editing); assertFalse(ks.tagsShown)
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(box.editing)
        val t0 = android.os.SystemClock.uptimeMillis()
        ks.view.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, tx, ty, 0))
        ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse("not before ~320ms", box.editing)
        ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("long press edits", box.editing)
        ks.view.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 420, MotionEvent.ACTION_UP, tx, ty, 0)); idle()
        assertTrue(box.editing)
        assertTrue(ks.tagsShown); assertEquals(View.VISIBLE, ks.chips!!.visibility)
        box.field.setText("a あ "); layoutHost()
        val chip = ks.view.findViewWithTagChip("web")!!
        val l = IntArray(2); chip.getLocationOnScreen(l)
        val cx = l[0] + chip.width / 2f; val cy = l[1] + chip.height / 2f
        ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 3, MotionEvent.ACTION_DOWN, cx, cy, 0))
        ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 4, MotionEvent.ACTION_UP, cx, cy, 0)); idle()
        assertTrue("chip keeps editing", box.editing); assertEquals(View.VISIBLE, ks.chips!!.visibility)
        assertEquals(listOf("a", "あ", "web"), Tree.parseTags(ks.tags))
        val before = entry().tags
        layoutHost()
        val consumed = ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 5, MotionEvent.ACTION_DOWN, 20f, 20f, 0)) &&
            ks.view.dispatchTouchEvent(MotionEvent.obtain(0, 6, MotionEvent.ACTION_UP, 20f, 20f, 0)); idle()
        assertTrue("outside tap consumed", consumed)
        assertFalse(box.editing); assertFalse(box.field.isFocused)
        assertFalse(ks.tagsShown); assertEquals(View.GONE, ks.chips!!.visibility)
        assertEquals(listOf("a", "あ", "web"), Tree.parseTags(ks.tags))
        assertEquals("no DB write from outside tap", before, entry().tags)
        idle(); assertEquals("no stale reopen", View.GONE, ks.chips!!.visibility)
        assertTrue(ks.save()); idle()
        assertEquals(listOf("a", "あ", "web"), entry().tags)
        assertEquals(listOf("a", "あ", "web"), Tree.parseTags(openKey().tags))
    }

    /** Ending Tag edit by Back (pre-IME key event) or Done also collapses chips. */
    @Test fun tagBackAndDoneCollapseChips() {
        val ks = openKey(); val box = ks.boxes[Order.F_TAGS]!!
        box.startEdit(); idle(); assertEquals(View.VISIBLE, ks.chips!!.visibility)
        val d = a.window.decorView
        d.dispatchKeyEventPreIme(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK, 0))
        d.dispatchKeyEventPreIme(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK, 0)); idle()
        assertFalse(box.editing); assertEquals(View.GONE, ks.chips!!.visibility); assertTrue(a.screen === ks)
        assertTrue(Dlg.last == null || !Dlg.last!!.isShowing)
        box.startEdit(); idle(); assertEquals(View.VISIBLE, ks.chips!!.visibility)
        box.field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE); idle()
        assertFalse(box.editing); assertEquals(View.GONE, ks.chips!!.visibility)
    }

    private fun View.findViewWithTagChip(text: String): View? {
        if (this is android.widget.TextView && this.text.toString() == text && isClickable) return this
        if (this is android.view.ViewGroup) for (i in 0 until childCount) getChildAt(i).findViewWithTagChip(text)?.let { return it }
        return null
    }
}
