package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.W
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TempSessionTest {
    @After fun tearDown() {
        try { Session.lock() } catch (_: Throwable) {}
    }

    @Test fun tempViewRestoresPrimaryAndBlocksWrites() {
        val k = Kdbx.compositeKey("primary")
        var primary = Kdbx.create("Primary", k, rootName = "")
        val e = Tree.newEntry(Tree.Fields("Keep", "u", "p", "", "", emptyList()))
        primary = Tree.addEntry(primary, Tree.root(primary).uuid, e)
        val temp = Kdbx.create("TempFile", Kdbx.compositeKey("other"), rootName = "")
        Session.open(primary)
        assertFalse(Session.readOnlyTemp)
        Session.beginTempView(temp, "backup.kdbx")
        assertTrue(Session.readOnlyTemp)
        assertEquals("TempFile", Session.db!!.content.meta.name)
        assertEquals(W.SEPIA1, W.accent1())
        try {
            Session.update { it }
            fail("expected read-only")
        } catch (ex: IllegalStateException) {
            assertTrue(ex.message!!.contains("read-only"))
        }
        Session.exitTempView()
        assertFalse(Session.readOnlyTemp)
        assertEquals("Primary", Session.db!!.content.meta.name)
        assertEquals(1, Tree.root(Session.db!!).entries.size)
        assertEquals(W.NAVY, W.accent1())
    }
}
