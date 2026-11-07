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
class Ux049Test {
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
        val root = Tree.root(db).uuid
        db = Tree.addGroup(db, root, Tree.newGroup("銀行"))
        db = Tree.addEntry(db, root, Order.newMemo("買い物メモ"))
        for ((t, ic) in listOf("Gmail" to "mail", "家のWi-Fi" to "wifi", "ネット銀行" to "bank", "通販" to "cart", "ゲーム" to "game", "メモ鍵" to "note", "既定" to null)) {
            var en = Tree.newEntry(Tree.Fields(t, "u", "p", "", "", emptyList()))
            if (ic != null) en = en.copy(customData = en.customData + (io.github.zmlworks.kpexplorer.db.EntryIcon.CD to app.keemobile.kotpass.models.CustomDataValue(ic, java.time.Instant.now())))
            db = Tree.addEntry(db, root, en)
        }
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


    @Test fun persistedIconIdsMapToFugue_stable() {
        val ids = io.github.zmlworks.kpexplorer.db.EntryIcon.ALL.map { it.id }
        assertEquals(listOf("key","mail","web","wifi","food","user","card","bank","cart","game","phone","lock","home","star","note"), ids)
        val res = ids.map { io.github.zmlworks.kpexplorer.ui.FugueIcons.resId(it) }
        assertEquals("one distinct Fugue asset per id", ids.size, res.toSet().size)
        for (id in ids) {
            val b = io.github.zmlworks.kpexplorer.ui.FugueIcons.source(app(), id)
            assertEquals(16, b.width); assertEquals(16, b.height)
            assertTrue(app().resources.getResourceEntryName(io.github.zmlworks.kpexplorer.ui.FugueIcons.resId(id)) == "fugue_$id")
        }
        assertEquals(io.github.zmlworks.kpexplorer.ui.FugueIcons.resId("key"), io.github.zmlworks.kpexplorer.ui.FugueIcons.resId("unknown"))
        // integer nearest-neighbour: every 2x2 block of the 32px render is one source pixel
        val s = io.github.zmlworks.kpexplorer.ui.FugueIcons.source(app(), "mail"); val big = io.github.zmlworks.kpexplorer.ui.FugueIcons.bitmap(app(), "mail", 32)
        for (y in 0 until 16) for (x in 0 until 16) assertEquals(s.getPixel(x, y), big.getPixel(2 * x + 1, 2 * y + 1))
        // memo art is its own, not the selectable note
        assertFalse(io.github.zmlworks.kpexplorer.ui.Ico.notepad16.sameAs(io.github.zmlworks.kpexplorer.ui.FugueIcons.source(app(), "note")))
    }

    @Test fun screens_explorerChooserMemo() {
        val dir = File(out, "v049").apply { mkdirs() }
        val ex = a.explorer!!
        if (!ex.iconMode) ex.toggleView(); idle(); layoutHost()
        shot("v049/01-explorer-icons.png")
        ex.toggleView(); idle(); shot("v049/02-explorer-details.png")
        val ks = openKey(); ks.pickIcon(); idle(); shot("v049/03-icon-chooser.png", dialog = true); Dlg.last?.dismiss(); idle()
        shot("v049/04-key-screen.png")
        // actual-size comparison strip: folder32 / memo32 / several Fugue at 32, and 16px row
        val z = 4; val items32 = listOf(Px.folder32, io.github.zmlworks.kpexplorer.ui.Ico.notepad32) +
            listOf("key","mail","wifi","bank","note").map { io.github.zmlworks.kpexplorer.ui.FugueIcons.bitmap(app(), it, 32) }
        val items16 = listOf(Px.folder16, io.github.zmlworks.kpexplorer.ui.Ico.notepad16) +
            listOf("key","mail","wifi","bank","note").map { io.github.zmlworks.kpexplorer.ui.FugueIcons.source(app(), it) }
        val strip = Bitmap.createBitmap(items32.size * 40 * z, 64 * z, Bitmap.Config.ARGB_8888)
        val c = Canvas(strip); c.drawColor(0xFFFFFFFF.toInt())
        val p = android.graphics.Paint().apply { isFilterBitmap = false }
        items32.forEachIndexed { i, b -> c.drawBitmap(b, null, android.graphics.Rect(i * 40 * z + 4 * z, 2 * z, i * 40 * z + 36 * z, 34 * z), p) }
        items16.forEachIndexed { i, b -> c.drawBitmap(b, null, android.graphics.Rect(i * 40 * z + 12 * z, 44 * z, i * 40 * z + 28 * z, 60 * z), p) }
        FileOutputStream(File(dir, "05-compare-x4.png")).use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val memo = Bitmap.createScaledBitmap(io.github.zmlworks.kpexplorer.ui.Ico.notepad32, 256, 256, false)
        FileOutputStream(File(dir, "06-memo-closeup-x8.png")).use { memo.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File(dir, "01-explorer-icons.png").length() > 0)
    }
}
