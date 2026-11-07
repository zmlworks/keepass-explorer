package io.github.zmlworks.kpexplorer

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.FieldBox
import io.github.zmlworks.kpexplorer.ui.FieldEditSession
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
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FieldEditCommitTest {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens")
    private lateinit var ids: MutableMap<String, UUID>
    private val key = Kdbx.compositeKey("sample")

    @Before fun setUp() {
        out.mkdirs()
        Session.lock(); Session.store = null; Session.debounceMs = 50
        Vault(app()).clear()
        App.prefs(app()).edit().clear().commit()
        app().getSharedPreferences("vault", 0).edit().clear().commit()
        Vault.forceAvailableForTest = true
        ids = LinkedHashMap()
    }
    @After fun tearDown() {
        FieldEditSession.onCommitted = null
        runCatching { FieldEditSession.commitActive() }
        Session.lock(); Vault.forceAvailableForTest = null; Menus.dismiss(); Dlg.last = null
    }

    private fun app() = RuntimeEnvironment.getApplication()
    private fun idle() { repeat(40) { ShadowLooper.idleMainLooper(); Thread.sleep(5) } }

    private fun openKey(): Pair<MainActivity, KeyScreen> {
        var db = Kdbx.create("edit", key, light = true)
        val g = Tree.newGroup("G"); db = Tree.addGroup(db, Tree.root(db).uuid, g); ids["G"] = g.uuid
        val e = Tree.newEntry(Tree.Fields("Item", "u", "pw0", "https://x", "", emptyList()))
        db = Tree.addEntry(db, g.uuid, e); ids["Item"] = e.uuid
        val store = App.store(app())
        store.write(Kdbx.encodeVerified(db))
        Session.store = store
        Session.open(db)
        App.prefs(app()).edit().putString("dbName", db.content.meta.name).commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        if (!Session.isOpen) Session.open(db)
        Session.store = a.store
        val ks = KeyScreen(a, e.uuid, g.uuid); a.show(ks); idle()
        a.host.measure(
            View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(891 * 3, View.MeasureSpec.EXACTLY))
        a.host.layout(0, 0, 411 * 3, 891 * 3)
        idle()
        assertTrue("boxes=${ks.boxes.keys}", ks.boxes.containsKey("UserName"))
        return a to ks
    }

    private fun ev(action: Int, x: Float, y: Float) =
        MotionEvent.obtain(0L, System.currentTimeMillis(), action, x, y, 0)

    @Test fun longPressDurationIsAbout320ms() {
        assertEquals(320L, FieldBox.EDIT_LONG_PRESS_MS)
        val (_, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        val loc = IntArray(2); box.getLocationOnScreen(loc)
        val x = loc[0] + box.width / 2f; val y = loc[1] + box.height / 2f
        box.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, y))
        assertFalse(box.editing)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertFalse("too early", box.editing)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertTrue("edit after ~320ms", box.editing)
        box.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x, y))
    }

    /** Outside tap ends edit into local draft only — Session/DB unchanged until 保存. */
    @Test fun outsideTouchUpdatesDraftOnlyNotDb() {
        val (_, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        box.startEdit()
        assertTrue(box.editing)
        box.field.setText("new-user")
        assertTrue(ks.dirty)
        assertEquals("new-user", ks.values["UserName"])
        val histBefore = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size
        val beforeUser = Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second)
        // Dispatch on KeyScreen root (not activity host padding) so outside-tap interceptor runs.
        ks.view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f))
        idle()
        assertFalse(box.editing)
        assertEquals("new-user", ks.values["UserName"])
        assertTrue("draft stays dirty until explicit Save; dirty=${ks.dirty} user=${ks.values["UserName"]}", ks.dirty)
        val e = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second
        assertEquals("session entry unchanged", beforeUser, Tree.userName(e))
        assertEquals(histBefore, e.history.size)
    }

    @Test fun backWhileEditingEndsEditStaysThenGuard() {
        val (a, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("typed")
        val before = Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second)
        a.onBackPressed(); idle()
        assertFalse(box.editing)
        assertTrue("still on key page", a.screen === ks)
        assertEquals("typed", ks.values["UserName"])
        assertTrue(ks.dirty)
        assertEquals("no DB write on first back", before, Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second))
        assertTrue(Dlg.last == null || !Dlg.last!!.isShowing)
        a.onBackPressed(); idle()   // second back → unsaved guard
        assertTrue(Dlg.last!!.isShowing)
        assertTrue(a.screen === ks)
        Dlg.last!!.buttonViews[1].performClick(); idle()   // 保存しない
        assertTrue(a.screen is ExplorerScreen)
        assertEquals(before, Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second))
    }

    @Test fun imeHiddenEndsEditIntoDraft() {
        val (a, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("ime")
        ks.onImeHidden(); idle()
        assertFalse(box.editing)
        assertTrue(a.screen === ks)
        assertEquals("ime", ks.values["UserName"])
        assertEquals("u", Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second))
    }

    @Test fun touchInsideEditingFieldDoesNotCommit() {
        val (_, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        box.startEdit()
        val loc = IntArray(2); box.getLocationOnScreen(loc)
        val x = loc[0] + 10f; val y = loc[1] + 10f
        ks.view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, y))
        assertTrue(box.editing)
    }

    @Test fun noopOutsideCommitDoesNotGrowHistory() {
        val (_, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        box.startEdit()
        val histBefore = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size
        ks.view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f))
        idle()
        assertEquals(histBefore, Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size)
        assertFalse(ks.dirty)
    }

    @Test fun explicitSavePersistsAndHistory() {
        val (_, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("saved-user"); assertTrue(ks.dirty)
        FieldEditSession.commitActive(); idle()
        assertTrue(ks.dirty)
        val histBefore = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size
        assertTrue(ks.save()); idle()
        assertFalse(ks.dirty)
        val e = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second
        assertEquals("saved-user", Tree.userName(e))
        assertEquals(histBefore + 1, e.history.size)
    }

    @Test fun discardLeavesDbAndHistoryUnchanged() {
        val (a, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("discard-me"); assertTrue(ks.dirty)
        FieldEditSession.commitActive(); idle()
        assertTrue(ks.dirty)
        val beforeUser = Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second)
        val histBefore = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size
        // Simulate discard path (保存しない): leave without save — Session entry untouched.
        // (encodeVerified uses fresh IV each call, so compare fields/history not ciphertext)
        ks.dirty = false
        a.showExplorer(); idle()
        assertEquals(beforeUser, Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second))
        assertEquals(histBefore, Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size)
    }

    @Test fun noopSaveDoesNotGrowHistory() {
        val (_, ks) = openKey()
        assertFalse(ks.dirty)
        val histBefore = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size
        assertTrue(ks.save())
        assertEquals(histBefore, Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size)
    }

    @Test fun otpDialogCancelLeavesUnchanged() {
        val (_, ks) = openKey()
        val before = ks.values[Order.OTP]
        ks.setupOtp()
        idle()
        assertNotNull(Dlg.last)
        Dlg.last!!.buttonViews.last().performClick(); idle()
        assertEquals(before, ks.values[Order.OTP])
    }

    @Test fun sessionAutosaveDoesNotFlushUnsavedDraft() {
        val (_, ks) = openKey()
        Session.debounceMs = 10
        val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("draft-only"); assertTrue(ks.dirty)
        FieldEditSession.commitActive(); idle()
        // Trigger unrelated Session persistence (folder expand would update DB; here just flush current)
        val beforeUser = Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second)
        Session.flush(); idle()
        Thread.sleep(80); idle()
        assertEquals("draft must not leak via Session.flush", beforeUser,
            Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second))
        assertTrue(ks.dirty)
        assertEquals("draft-only", ks.values["UserName"])
    }

    @Test fun renderKeyAndArrangeScreenshots() {
        val (a, ks) = openKey()
        val b = android.graphics.Bitmap.createBitmap(a.host.width, a.host.height, android.graphics.Bitmap.Config.ARGB_8888)
        a.host.draw(android.graphics.Canvas(b))
        FileOutputStream(File(out, "15-key-detail.png")).use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertNullDesc(a.host, "OTP設定を変更")
        assertNullDesc(a.host, "この鍵を削除")
        assertNullDesc(a.host, "URL をブラウザで開く")
        ks.addCustomField("暗証", "1")
        ks.editLayout(); idle()
        val ed = ks.arrangeEditor!!
        assertFalse(ed.scroll.isVerticalScrollBarEnabled)
        assertNotNull(ed.handleFor(Order.F_USER))
        assertNotNull(ed.deleteFor("暗証"))
        assertTrue(ed.deleteFor("暗証")!!.minimumWidth >= W.dp(48))
        Dlg.last?.dismiss(); idle()
        val ed2 = io.github.zmlworks.kpexplorer.ui.FieldArrangeEditor(a, ks.layout)
        ed2.place(Order.F_URL, visible = false, index = 0)
        val host = FrameLayout(a)
        host.addView(ed2, FrameLayout.LayoutParams(411 * 3, 700))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 411 * 3, 700)
        val ab = android.graphics.Bitmap.createBitmap(host.width, host.height, android.graphics.Bitmap.Config.ARGB_8888)
        host.draw(android.graphics.Canvas(ab))
        FileOutputStream(File(out, "15-field-arrange-grip.png")).use { ab.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertNullDesc(v: View, d: String) {
        fun find(x: View): View? {
            if (x.contentDescription?.toString() == d) return x
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) find(x.getChildAt(i))?.let { return it }
            return null
        }
        org.junit.Assert.assertNull(find(v))
    }

    @Test fun explicitSaveToastsWhenDirty() {
        val (_, ks) = openKey()
        ShadowToast.reset()
        val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("explicit-user"); assertTrue(ks.dirty)
        FieldEditSession.commitActive(); idle()
        assertTrue(ks.dirty)
        assertTrue(ks.save()); idle()
        assertTrue("explicit save may toast", ShadowToast.shownToastCount() >= 1)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("保存"))
    }


    // ---- 0.4.6: Back delivered as real KeyEvents (pre-IME stage + Activity dispatch), not direct calls ----
    private fun key(action: Int, t: Long = 0L) = android.view.KeyEvent(t, t, action, android.view.KeyEvent.KEYCODE_BACK, 0)
    private fun dbUser() = Tree.userName(Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second)
    private fun assertStayedDraft(a: MainActivity, ks: KeyScreen, box: FieldBox, v: String) {
        assertFalse(box.editing); assertTrue(a.screen === ks)
        assertEquals(v, ks.values["UserName"]); assertTrue(ks.dirty)
        assertTrue("no save prompt", Dlg.last == null || !Dlg.last!!.isShowing)
        assertEquals("u", dbUser()); assertFalse(a.isFinishing)
        assertFalse("no stale swallow flag", a.backDownWhileEditing)
    }
    /** Second, distinct Back through the full Activity path must give the normal unsaved prompt. */
    private fun secondBackPrompts(a: MainActivity, ks: KeyScreen) {
        val d = a.window.decorView
        d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_DOWN, 10)); d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_UP, 10)); idle()
        assertTrue("prompt on 2nd back", Dlg.last != null && Dlg.last!!.isShowing)
        assertTrue(a.screen === ks); assertEquals("u", dbUser())
    }

    /** Keyboard up: ViewRootImpl gives the focused EditText onKeyPreIme first; we consume DOWN+UP. */
    @Test fun back_preIme_endsEditInDraft_thenSecondBackPrompts() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("k1"); idle()
        assertTrue("field focused", box.field.isFocused)
        val d = a.window.decorView
        assertTrue(d.dispatchKeyEventPreIme(key(android.view.KeyEvent.ACTION_DOWN)))
        assertTrue("still editing until UP", box.editing)
        assertTrue(d.dispatchKeyEventPreIme(key(android.view.KeyEvent.ACTION_UP))); idle()
        assertStayedDraft(a, ks, box, "k1")
        assertFalse(box.field.isFocused)
        secondBackPrompts(a, ks)
    }

    /** No keyboard (or IME did not take it): Back reaches Activity.dispatchKeyEvent while editing. */
    @Test fun back_activityDispatch_noKeyboard_endsEdit() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("k2"); idle()
        val d = a.window.decorView
        assertTrue(d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_DOWN)))
        assertTrue(d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_UP))); idle()
        assertStayedDraft(a, ks, box, "k2")
        secondBackPrompts(a, ks)
    }

    /** Race: DOWN while editing, then IME-hidden insets callback ends the edit, then UP → must NOT navigate. */
    @Test fun back_imeHiddenBetweenDownAndUp_doesNotNavigate() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("k3"); idle()
        a.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_DOWN))
        ks.onImeHidden(); idle()
        assertFalse(box.editing)
        assertTrue("UP of same press swallowed", a.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_UP))); idle()
        assertStayedDraft(a, ks, box, "k3")
        secondBackPrompts(a, ks)
    }

    /** Opposite order: our Back handling ends edit first, the later IME-hidden callback is a no-op. */
    @Test fun back_thenImeHidden_noop() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("k4"); idle()
        val d = a.window.decorView
        d.dispatchKeyEventPreIme(key(android.view.KeyEvent.ACTION_DOWN)); d.dispatchKeyEventPreIme(key(android.view.KeyEvent.ACTION_UP))
        ks.onImeHidden(); idle()
        assertStayedDraft(a, ks, box, "k4")
        secondBackPrompts(a, ks)
    }

    /** Cancelled Back (e.g. predictive gesture aborted) ends nothing and leaves no stale flag. */
    @Test fun back_canceledUp_keepsEditing_noStaleFlag() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        box.startEdit(); idle()
        a.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_DOWN))
        val up = android.view.KeyEvent.changeFlags(key(android.view.KeyEvent.ACTION_UP), android.view.KeyEvent.FLAG_CANCELED)
        assertTrue(a.dispatchKeyEvent(up)); idle()
        assertTrue(box.editing); assertFalse(a.backDownWhileEditing); assertTrue(a.screen === ks)
    }
}
