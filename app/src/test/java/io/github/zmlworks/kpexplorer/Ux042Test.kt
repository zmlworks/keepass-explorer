package io.github.zmlworks.kpexplorer

import android.view.View
import android.widget.TextView
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux042Test {
    private val key = Kdbx.compositeKey("ux042")
    private lateinit var ids: MutableMap<String, UUID>
    private lateinit var activity: MainActivity

    private fun app() = RuntimeEnvironment.getApplication()
    private fun idle() { repeat(30) { ShadowLooper.idleMainLooper(); Thread.sleep(3) } }

    @Before fun setUp() {
        Session.lock(); Session.store = null; Session.debounceMs = 50
        Vault(app()).clear(); Vault.forceAvailableForTest = true
        App.prefs(app()).edit().clear().commit()
        app().getSharedPreferences("vault", 0).edit().clear().commit()
        App.store(app()).let { s -> s.delete(); s.backups().forEach { it.delete() } }
        File(app().filesDir, "data-version").writeText(App.DATA_VERSION.toString())
        ids = LinkedHashMap()
        var db = Kdbx.create("ux", key, light = true)
        db = Tree.ensureRecycleBin(db, "ごみ箱")
        val g = Tree.newGroup("Folder"); db = Tree.addGroup(db, Tree.root(db).uuid, g); ids["Folder"] = g.uuid
        val e = Tree.newEntry(Tree.Fields("Key", "u", "secret", "", "", emptyList()))
        db = Tree.addEntry(db, g.uuid, e); ids["Key"] = e.uuid
        val st = App.store(app())
        st.write(Kdbx.encodeVerified(db))
        Session.store = st
        Session.open(db)
        App.prefs(app()).edit().putString("dbName", db.content.meta.name).commit()
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        if (!Session.isOpen) Session.open(db)
        Session.store = activity.store
        if (activity.explorer == null) activity.showExplorer()
        idle()
    }

    @After fun tearDown() { Session.lock(); Vault.forceAvailableForTest = null; Dlg.last = null }

    @Test fun keyScreenPathHasNoParenCounts() {
        val ks = KeyScreen(activity, ids["Key"], ids["Folder"]!!); activity.show(ks); idle()
        fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
            if (v is TextView) out.add(v.text.toString())
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
            return out
        }
        val all = texts(ks.view)
        assertTrue(all.any { it == "/" || it.startsWith("└") })
        assertTrue("no (n,m) counts on key path", all.none { Regex("""\(\d+,\d+\)""").containsMatchIn(it) })
    }

    @Test fun softDeleteNoConfirm_andRestore() {
        activity.showExplorer(); idle()
        activity.explorer!!.navigate(ids["Folder"]!!); idle()
        activity.explorer!!.askDelete(ids["Key"]!!); idle()
        assertNull(Dlg.last)
        assertTrue(Tree.isInRecycleBin(Session.db!!, ids["Key"]!!))
        activity.explorer!!.restoreItem(ids["Key"]!!); idle()
        assertFalse(Tree.isInRecycleBin(Session.db!!, ids["Key"]!!))
    }

    @Test fun savedKeyPassMaskedWithStars_tapReveals() {
        val ks = KeyScreen(activity, ids["Key"], ids["Folder"]!!); activity.show(ks); idle()
        assertFalse(ks.passShown)
        val f = ks.boxes[Order.F_PASS]!!.field
        assertSame(Stars, f.transformationMethod)
        ks.togglePass(); assertTrue(ks.passShown); assertNull(f.transformationMethod)
        val ks2 = KeyScreen(activity, ids["Key"], ids["Folder"]!!); activity.show(ks2); idle()
        assertFalse(ks2.passShown)
    }

    @Test fun newKeyPassVisible_noEye_generatorKept() {
        val nk = NewKeyScreen(activity, ids["Folder"]!!); activity.show(nk); idle()
        assertTrue(nk.passShown)
        assertNull(nk.eyeButton)
        assertNotNull(nk.genButton)
        assertNull(nk.editors[Order.F_PASS]!!.transformationMethod)
    }

    @Test fun mainMenuStillHasPassGen() {
        activity.showExplorer(); idle()
        val labels = activity.explorer!!.mainMenu().map { it.label }
        assertTrue(labels.any { it.contains("Password generator") || it.contains("パスワード生成") })
    }
}
