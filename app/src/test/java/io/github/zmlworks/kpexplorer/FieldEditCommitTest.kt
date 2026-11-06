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
        // Activity lifecycle may have locked; re-open like a warm session before showing the key
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

    @Test fun outsideTouchCommitsAndPersists() {
        val (a, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        box.startEdit()
        assertTrue(box.editing)
        box.field.setText("new-user")
        assertTrue(ks.dirty)
        val histBefore = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size
        a.host.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f))
        idle()
        assertFalse(box.editing)
        assertFalse("autosave clears dirty", ks.dirty)
        val e = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second
        assertEquals("new-user", Tree.userName(e))
        assertEquals(histBefore + 1, e.history.size)
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
        val (a, ks) = openKey()
        val box = ks.boxes["UserName"]!!
        box.startEdit()
        val histBefore = Tree.findEntry(Session.db!!, ids["Item"]!!)!!.second.history.size
        a.host.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f))
        idle()
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

    @Test fun renderKeyAndArrangeScreenshots() {
        val (a, ks) = openKey()
        val b = android.graphics.Bitmap.createBitmap(a.host.width, a.host.height, android.graphics.Bitmap.Config.ARGB_8888)
        a.host.draw(android.graphics.Canvas(b))
        FileOutputStream(File(out, "15-key-detail.png")).use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertNullDesc(a.host, "OTP設定を変更")
        assertNullDesc(a.host, "この鍵を削除")
        ks.addCustomField("暗証", "1")
        ks.editLayout(); idle()
        val ed = ks.arrangeEditor!!
        assertFalse(ed.scroll.isVerticalScrollBarEnabled)
        assertNotNull(ed.handleFor(Order.F_USER))
        assertNotNull(ed.deleteFor("暗証"))
        assertTrue(ed.deleteFor("暗証")!!.minimumWidth >= W.dp(48))
        Dlg.last?.dismiss(); idle()
        // Fresh editor instance for standalone shot (not the dialog-attached one)
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

    @Test fun autosaveIsSilentButExplicitSaveToasts() {
        val (a, ks) = openKey()
        ShadowToast.reset()
        val box = ks.boxes["UserName"]!!
        box.startEdit(); box.field.setText("silent-user"); assertTrue(ks.dirty)
        a.host.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, 20f)); idle()
        assertFalse(ks.dirty)
        assertEquals("no success toast on outside-tap autosave", 0, ShadowToast.shownToastCount())
        // Explicit save still toasts when dirty
        box.startEdit(); box.field.setText("explicit-user"); assertTrue(ks.dirty)
        assertTrue(ks.save()); idle()
        assertTrue("explicit save may toast", ShadowToast.shownToastCount() >= 1)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("保存"))
    }

}
