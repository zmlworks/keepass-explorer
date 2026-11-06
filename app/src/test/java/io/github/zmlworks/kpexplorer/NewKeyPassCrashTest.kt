package io.github.zmlworks.kpexplorer

import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Stars
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
import java.util.Locale
import android.content.res.Configuration

/**
 * t521u: NewKeyScreen crashed when Title/etc. empty and user typed into masked Pass.
 * Root cause: FieldBox.Stars returns fixed "******" (len 6) ≠ source length → EditText
 * cursor mapping IndexOutOfBounds. KeyScreen unmasks before edit; NewKey did not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewKeyPassCrashTest {
    private val key = Kdbx.compositeKey("newkey-crash-test")
    private lateinit var activity: MainActivity

    private fun app() = RuntimeEnvironment.getApplication()
    private fun idle() { repeat(30) { ShadowLooper.idleMainLooper(); Thread.sleep(3) } }

    @Before fun setUp() {
        Session.lock()
        Session.store = null
        Session.debounceMs = 50
        Vault(app()).clear()
        App.prefs(app()).edit().clear().commit()
        app().getSharedPreferences("vault", 0).edit().clear().commit()
        Vault.forceAvailableForTest = true
        App.store(app()).let { s -> s.delete(); s.backups().forEach { it.delete() } }
        File(app().filesDir, "data-version").writeText(App.DATA_VERSION.toString())
        val db = Kdbx.create("t", key, rootName = "")
        App.store(app()).write(Kdbx.encodeVerified(db, keepSalt = true))
        Session.open(db)
        Session.store = App.store(app())
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        // Ensure explorer exists (MainActivity may show unlock first in some paths)
        if (activity.explorer == null && Session.isOpen) activity.showExplorer()
        idle()
    }

    @After fun tearDown() {
        Session.lock(); Vault.forceAvailableForTest = null
    }

    private fun openNewKey(): NewKeyScreen {
        assertTrue("session open", Session.isOpen)
        val parent = Tree.root(Session.db!!).uuid
        val nk = NewKeyScreen(activity, parent)
        activity.show(nk)
        idle()
        nk.view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        nk.view.layout(0, 0, 1080, 1920)
        idle()
        return nk
    }

    private fun tapFocus(e: EditText) {
        e.isFocusableInTouchMode = true
        e.requestFocus()
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 2f, 2f, 0)
        val up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 2f, 2f, 0)
        e.dispatchTouchEvent(down); e.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
        idle()
    }

    private fun typeChars(e: EditText, s: String) {
        for (ch in s) {
            val start = e.selectionStart.coerceAtLeast(0)
            val end = e.selectionEnd.coerceAtLeast(0)
            e.text.replace(minOf(start, e.text.length), minOf(end, e.text.length), ch.toString())
            idle()
        }
    }

    @Test fun starsDisplayStillHidesLength_butIsUnsafeForEdit() {
        // Document the display contract that caused the crash when used on editable Pass.
        val t = Stars.getTransformation("ab", View(app()))
        assertEquals(6, t.length)
        assertEquals("******", t.toString())
    }

    @Test fun newKeyPassStartsVisible_noStarsOnEditable() {
        val nk = openNewKey()
        val pass = nk.editors[Order.F_PASS]!!
        assertTrue("starts visible 0.4.2", nk.passShown)
        assertNull(pass.transformationMethod)
        nk.togglePass(show = false)
        assertTrue(
            "when remasked, length-preserving PTM not Stars",
            pass.transformationMethod is PasswordTransformationMethod && pass.transformationMethod !is Stars
        )
    }

    @Test fun allEmpty_tapPass_typeDoesNotCrash() {
        val nk = openNewKey()
        assertFalse(nk.dirty)
        assertTrue(nk.values.values.all { it.isEmpty() })
        val pass = nk.editors[Order.F_PASS]!!
        tapFocus(pass)
        assertTrue(pass.hasFocus())
        typeChars(pass, "abc")
        assertEquals("abc", nk.values["Password"])
        assertEquals("abc", pass.text.toString())
        assertEquals(0, Tree.root(Session.db!!).entries.size)
        assertTrue(activity.screen is NewKeyScreen)
    }

    @Test fun titleOnly_thenTypePass_masked() {
        val nk = openNewKey()
        nk.editors[Order.F_TITLE]!!.setText("OnlyTitle")
        idle()
        val pass = nk.editors[Order.F_PASS]!!
        tapFocus(pass)
        typeChars(pass, "x1!")
        assertEquals("x1!", nk.values["Password"])
        assertTrue(nk.passShown)
    }

    @Test fun passwordFirst_thenTitle_noAutosave() {
        val nk = openNewKey()
        val pass = nk.editors[Order.F_PASS]!!
        tapFocus(pass)
        typeChars(pass, "secret")
        nk.editors[Order.F_TITLE]!!.setText("Later")
        idle()
        assertEquals(0, Tree.root(Session.db!!).entries.size)
        assertTrue(nk.dirty)
        assertTrue(activity.screen is NewKeyScreen)
        // create still explicit
        assertTrue(nk.create()); idle()
        assertTrue(activity.screen is KeyScreen)
    }

    @Test fun showHide_emptyAndFocused_thenType() {
        val nk = openNewKey()
        val pass = nk.editors[Order.F_PASS]!!
        tapFocus(pass)
        nk.togglePass(show = true)
        assertNull(pass.transformationMethod)
        typeChars(pass, "vis")
        assertEquals("vis", pass.text.toString())
        nk.togglePass(show = false)
        assertTrue(pass.transformationMethod is PasswordTransformationMethod)
        typeChars(pass, "ible")
        assertEquals("visible", nk.values["Password"])
    }

    @Test fun englishLocale_emptyForm_typePass() {
        val cfg = Configuration(app().resources.configuration)
        cfg.setLocale(Locale.ENGLISH)
        // Activity stays ja-qualified config from @Config; still type into Pass with empty form
        val nk = openNewKey()
        val pass = nk.editors[Order.F_PASS]!!
        tapFocus(pass)
        typeChars(pass, "en1")
        assertEquals("en1", nk.values["Password"])
        assertEquals(0, Tree.root(Session.db!!).entries.size)
    }

    @Test fun generateStillWorksOnEmptyForm() {
        val nk = openNewKey()
        nk.genButton!!.performClick(); idle()
        assertTrue(nk.passShown)
        assertTrue(nk.values["Password"]!!.length >= 16)
        assertEquals(0, Tree.root(Session.db!!).entries.size)
    }
}
