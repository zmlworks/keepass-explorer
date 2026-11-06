package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.HistoryViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryViewerTest {
    private val key = Kdbx.compositeKey("sample")

    @Test fun historyMaxIsTenAndListingUsesRealSnapshots() {
        var db = Kdbx.create("hist", key)
        assertEquals(10, db.content.meta.historyMaxItems)
        assertEquals(10, Tree.HISTORY_MAX)
        val e = Order.newKey()
        db = Order.appendEntry(db, Tree.root(db).uuid, e)
        val id = e.uuid
        repeat(12) { i ->
            db = Order.saveEntry(db, id, mapOf("Title" to "T$i", "Password" to "secret-$i", "UserName" to "u$i"), emptyList(), null)
        }
        val cur = Tree.findEntry(db, id)!!.second
        assertEquals(10, cur.history.size)
        assertEquals("T11", Tree.title(cur))
        val rows = HistoryViewer.rows(cur)
        assertEquals(10, rows.size)
        // newest first
        assertEquals("T10", rows[0].title)
        assertEquals("T1", rows.last().title)
        // passwords not auto-exposed in default masked path — Row holds value but UI masks
        assertTrue(rows[0].password.startsWith("secret-"))
        // read-only: listing must not mutate
        val again = Tree.findEntry(db, id)!!.second
        assertEquals(10, again.history.size)
        assertEquals(cur.history.map { Tree.title(it) }, again.history.map { Tree.title(it) })
    }

    @Test fun noOpSaveDoesNotGrowHistory() {
        var db = Kdbx.create("hist2", key)
        val e = Order.newKey()
        db = Order.appendEntry(db, Tree.root(db).uuid, e)
        val id = e.uuid
        db = Order.saveEntry(db, id, mapOf("Title" to "A", "Password" to "p"), emptyList(), null)
        val n1 = Tree.findEntry(db, id)!!.second.history.size
        db = Order.saveEntry(db, id, mapOf("Title" to "A", "Password" to "p"), emptyList(), null)
        assertEquals(n1, Tree.findEntry(db, id)!!.second.history.size)
    }

    @Test fun renderHistoryMaskedScreenshot() {
        var db = Kdbx.create("histShot", key)
        val e = Order.newKey()
        db = Order.appendEntry(db, Tree.root(db).uuid, e)
        val id = e.uuid
        db = Order.saveEntry(db, id, mapOf("Title" to "Alpha", "Password" to "hidden-secret", "UserName" to "alice"), emptyList(), null)
        db = Order.saveEntry(db, id, mapOf("Title" to "Beta", "Password" to "hidden-secret-2", "UserName" to "bob"), emptyList(), null)
        Session.open(db)
        val cur = Tree.findEntry(db, id)!!.second
        val rows = HistoryViewer.rows(cur)
        assertTrue(rows.size >= 1)
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val dlg = HistoryViewer.show(ctx, id)
        assertNotNull(dlg)
        // Ensure default UI text does not embed the secret string in view hierarchy labels except after reveal
        fun texts(v: android.view.View, out: MutableList<String>) {
            if (v is android.widget.TextView) out.add(v.text.toString())
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        }
        val all = ArrayList<String>(); texts(dlg!!.frame, all)
        assertFalse(all.any { it.contains("hidden-secret") })
        assertTrue(all.any { it.contains("******") })
        dlg.dismiss()
        Session.lock()
    }
}
