package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import org.junit.Assert.*
import org.junit.Test

class RecycleBinTest {
    private fun db() = Kdbx.create("t", Kdbx.compositeKey("pw-recycle-bin-test"), rootName = "")

    @Test fun ensureCreatesStableUuidAcrossRename() {
        var d = db()
        d = Tree.ensureRecycleBin(d, "ごみ箱")
        val id1 = Tree.recycleBinId(d)!!
        d = Tree.ensureRecycleBin(d, "Recycle Bin")
        val id2 = Tree.recycleBinId(d)!!
        assertEquals(id1, id2)
        assertEquals("Recycle Bin", Tree.findGroup(d, id2)!!.name)
        // only one bin under root
        val bins = Tree.root(d).groups.filter { it.uuid == id2 }
        assertEquals(1, bins.size)
    }

    @Test fun softDeleteThenRestoreUsesPreviousParent() {
        var d = db()
        d = Tree.ensureRecycleBin(d, "Recycle Bin")
        val folder = Tree.newGroup("Work")
        d = Tree.addGroup(d, Tree.root(d).uuid, folder)
        val e = Tree.newEntry(Tree.Fields("Title", "u", "p", "", "", emptyList()))
        d = Tree.addEntry(d, folder.uuid, e)
        d = Tree.moveToBin(d, e.uuid, "Recycle Bin")
        assertTrue(Tree.isInRecycleBin(d, e.uuid))
        assertEquals(0, Tree.findGroup(d, folder.uuid)!!.entries.size)
        d = Tree.restoreFromBin(d, e.uuid)
        assertFalse(Tree.isInRecycleBin(d, e.uuid))
        assertEquals(folder.uuid, Tree.parentOf(Tree.root(d), e.uuid)!!.uuid)
    }

    @Test fun permanentFromBinRecordsDeletedObjects() {
        var d = db()
        d = Tree.ensureRecycleBin(d, "Recycle Bin")
        val e = Tree.newEntry(Tree.Fields("X", "", "", "", "", emptyList()))
        d = Tree.addEntry(d, Tree.root(d).uuid, e)
        d = Tree.moveToBin(d, e.uuid, "Recycle Bin")
        val before = d.content.deletedObjects.size
        d = Tree.deletePermanently(d, e.uuid)
        assertTrue(d.content.deletedObjects.size > before)
        assertNull(Tree.findEntry(d, e.uuid))
    }

    @Test fun emptyBinClearsChildren() {
        var d = db()
        d = Tree.ensureRecycleBin(d, "Bin")
        val e = Tree.newEntry(Tree.Fields("Y", "", "", "", "", emptyList()))
        d = Tree.addEntry(d, Tree.root(d).uuid, e)
        d = Tree.moveToBin(d, e.uuid, "Bin")
        assertEquals(1, Tree.recycleBinChildCount(d))
        d = Tree.emptyRecycleBin(d)
        assertEquals(0, Tree.recycleBinChildCount(d))
    }

    @Test fun cannotDeleteBinItself() {
        var d = db()
        d = Tree.ensureRecycleBin(d, "Bin")
        val bin = Tree.recycleBinId(d)!!
        try {
            Tree.deletePermanently(d, bin)
            fail("expected")
        } catch (e: Tree.TreeException) {
            assertTrue(e.message!!.contains("recycle bin"))
        }
    }

    @Test fun historyMaxRemains10() {
        var d = db()
        d = Tree.ensureRecycleBin(d, "Bin")
        assertEquals(Tree.HISTORY_MAX, d.content.meta.historyMaxItems)
    }
}
