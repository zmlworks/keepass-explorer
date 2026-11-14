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
        // 0.4.31: the app's one long press, 80 % of the system delay (Robolectric: 500 ms → 400; Android 12+ default 400 → 320)
        assertEquals(Math.round(android.view.ViewConfiguration.getLongPressTimeout() * 0.8), io.github.zmlworks.kpexplorer.ui.W.longPressMs())
        val (_, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        val loc = IntArray(2); box.getLocationOnScreen(loc)
        val x = loc[0] + box.width / 2f; val y = loc[1] + box.height / 2f
        box.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, y))
        assertFalse(box.editing)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertFalse("too early", box.editing)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertTrue("edit after the app's long press", box.editing)
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
        ks.view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f)); ks.view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 20f, 20f))
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
        ks.view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f)); ks.view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 20f, 20f))
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
        ShadowLooper.idleMainLooper(5, TimeUnit.MILLISECONDS)
        val t = android.os.SystemClock.uptimeMillis()   // a real later press has a later downTime
        d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_DOWN, t)); d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_UP, t)); idle()
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

    // ---- 0.4.7: OS-level Back paths ----
    /** Invoke the window's top OnBackInvokedCallback exactly as the system does for a Back gesture/button
     *  on API 33+ (legacy apps get ViewRootImpl's compat callback, which injects KEYCODE_BACK DOWN/UP
     *  through every input stage incl. pre-IME). Returns the callback class name used. */
    private fun systemBack(a: MainActivity): String {
        val vri = a.window.decorView.javaClass.getMethod("getViewRootImpl").invoke(a.window.decorView)!!
        val disp = vri.javaClass.getMethod("getOnBackInvokedDispatcher").invoke(vri)!!
        val top = disp.javaClass.getDeclaredMethod("getTopCallback").apply { isAccessible = true }.invoke(disp)
            as android.window.OnBackInvokedCallback
        top.onBackInvoked(); idle()
        return top.javaClass.name
    }

    @Test fun manifestKeepsLegacyBackRouting() {
        val ai = app().packageManager.getApplicationInfo(app().packageName, 0)
        val f = ai.javaClass.getMethod("isOnBackInvokedCallbackEnabled").invoke(ai) as Boolean
        assertFalse("enableOnBackInvokedCallback must be false so Back arrives as KeyEvents", f)
        assertEquals(35, ai.targetSdkVersion)
    }

    @Test fun systemBackCallback_firstEndsEdit_secondPrompts() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        a.window.decorView.requestFocus(); box.startEdit(); box.field.setText("s1"); idle()
        val cb = systemBack(a)
        assertFalse("callback=$cb", box.editing); assertTrue(a.screen === ks)
        assertTrue("no prompt", Dlg.last == null || !Dlg.last!!.isShowing)
        assertEquals("s1", ks.values["UserName"]); assertEquals("u", dbUser())
        systemBack(a)
        assertTrue("2nd system back prompts", Dlg.last != null && Dlg.last!!.isShowing)
        assertEquals("u", dbUser())
    }

    /** Keyboard hidden (insets) BEFORE the same press's Back reaches the app: downTime precedes the hide. */
    @Test fun imeHiddenBeforeSamePressBack_doesNotNavigate_distinctBackPrompts() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("r1"); idle()
        val press = android.os.SystemClock.uptimeMillis()
        ShadowLooper.idleMainLooper(5, TimeUnit.MILLISECONDS)
        ks.onImeHidden()                     // insets callback wins the race
        assertFalse(box.editing)
        val d = a.window.decorView
        d.dispatchKeyEvent(android.view.KeyEvent(press, press, 0, android.view.KeyEvent.KEYCODE_BACK, 0))
        d.dispatchKeyEvent(android.view.KeyEvent(press, press + 40, 1, android.view.KeyEvent.KEYCODE_BACK, 0)); idle()
        assertStayedDraft(a, ks, box, "r1")
        ShadowLooper.idleMainLooper(5, TimeUnit.MILLISECONDS)
        val p2 = android.os.SystemClock.uptimeMillis()
        d.dispatchKeyEvent(android.view.KeyEvent(p2, p2, 0, android.view.KeyEvent.KEYCODE_BACK, 0))
        d.dispatchKeyEvent(android.view.KeyEvent(p2, p2, 1, android.view.KeyEvent.KEYCODE_BACK, 0)); idle()
        assertTrue("distinct back prompts", Dlg.last != null && Dlg.last!!.isShowing)
    }

    /** Focus loss alone (e.g. a view rebuild) must not silently end the edit. */
    @Test fun focusLossKeepsEditing_thenBackEnds() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("f1"); box.field.clearFocus(); idle()
        assertTrue(box.editing)
        val d = a.window.decorView
        d.dispatchKeyEvent(key(0, 1)); d.dispatchKeyEvent(key(1, 1)); idle()
        assertStayedDraft(a, ks, box, "f1")
    }

    // ---- 0.4.8: reproduce the real-device order (synthetic timings only) ----
    /** Gesture back: system delivers edge DOWN then CANCEL to the app, then a synthesized Back DOWN/UP with
     *  identical times ~40ms later. Before 0.4.8 the DOWN committed the edit → Back saw ed=false → prompt. */
    private fun gestureBack(a: MainActivity, ks: KeyScreen) {
        val t = android.os.SystemClock.uptimeMillis()
        ks.view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 2f, 1500f, 0))
        ks.view.dispatchTouchEvent(MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_MOVE, 60f, 1500f, 0))
        ks.view.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_CANCEL, 90f, 1500f, 0))
        ShadowLooper.idleMainLooper(40, TimeUnit.MILLISECONDS)
        val k = android.os.SystemClock.uptimeMillis()
        val d = a.window.decorView
        d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_DOWN, k)); d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_UP, k)); idle()
    }

    @Test fun gestureBack_realDeviceOrder_firstEndsEdit_secondPrompts() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("g1"); idle()
        val t = android.os.SystemClock.uptimeMillis()
        ks.view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 2f, 1500f, 0))
        assertTrue("edge DOWN alone must not end the edit", box.editing)
        ks.view.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_CANCEL, 90f, 1500f, 0))
        assertTrue("CANCEL (pilfered by gesture) keeps editing", box.editing)
        ShadowLooper.idleMainLooper(40, TimeUnit.MILLISECONDS)
        val k = android.os.SystemClock.uptimeMillis(); val d = a.window.decorView
        d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_DOWN, k)); d.dispatchKeyEvent(key(android.view.KeyEvent.ACTION_UP, k)); idle()
        assertStayedDraft(a, ks, box, "g1")
        gestureBack(a, ks)
        assertTrue("2nd gesture back prompts", Dlg.last != null && Dlg.last!!.isShowing)
        assertEquals("u", dbUser())
    }

    @Test fun gestureBack_repeatedPairs_neverLoseDraft() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        for (i in 1..3) {
            box.startEdit(); box.field.setText("p$i"); idle()
            gestureBack(a, ks)
            assertStayedDraft(a, ks, box, "p$i")
        }
        gestureBack(a, ks)
        assertTrue(Dlg.last != null && Dlg.last!!.isShowing); assertEquals("u", dbUser())
    }

    @Test fun imeDone_endsEdit_noNavigation() {
        val (a, ks) = openKey(); val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("d1"); idle()
        box.field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE); ks.onImeHidden(); idle()
        assertStayedDraft(a, ks, box, "d1")
    }
}
