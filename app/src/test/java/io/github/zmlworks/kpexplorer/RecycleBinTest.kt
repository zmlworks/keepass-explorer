package io.github.zmlworks.kpexplorer

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyContent
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

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

    @Test fun cannotMoveRecycleBinViaTreeOrOrder() {
        var d = db()
        d = Tree.ensureRecycleBin(d, "Bin")
        val bin = Tree.recycleBinId(d)!!
        val folder = Tree.newGroup("Work")
        d = Tree.addGroup(d, Tree.root(d).uuid, folder)
        try {
            Tree.move(d, bin, folder.uuid)
            fail("Tree.move should refuse")
        } catch (e: Tree.TreeException) {
            assertTrue(e.message!!.contains("recycle bin"))
        }
        try {
            Order.moveTo(d, bin, folder.uuid)
            fail("Order.moveTo should refuse")
        } catch (e: Tree.TreeException) {
            assertTrue(e.message!!.contains("recycle bin"))
        }
        assertEquals(Tree.root(d).uuid, Tree.parentOf(Tree.root(d), bin)!!.uuid)
    }

    /** Relocate recycle bin under [parent] without going through Tree.move (clears uuid briefly). */
    private fun nestBinUnder(d: KeePassDatabase, binId: UUID, parent: UUID): KeePassDatabase {
        val uuid = d.content.meta.recycleBinUuid
        var tmp = d.modifyContent { copy(meta = meta.copy(recycleBinUuid = null)) }
        tmp = Tree.move(tmp, binId, parent)
        return tmp.modifyContent { copy(meta = meta.copy(recycleBinUuid = uuid, recycleBinEnabled = true)) }
    }

    @Test fun nestedBinReparentsToRootPreservingContents() {
        var d = db()
        d = Tree.ensureRecycleBin(d, "Bin")
        val bin = Tree.recycleBinId(d)!!
        val folder = Tree.newGroup("Nest")
        d = Tree.addGroup(d, Tree.root(d).uuid, folder)
        val child = Tree.newEntry(Tree.Fields("Kept", "u", "p", "", "", emptyList()))
        d = Tree.addEntry(d, Tree.root(d).uuid, child)
        d = Tree.moveToBin(d, child.uuid, "Bin")
        assertTrue(Tree.isInRecycleBin(d, child.uuid))
        d = nestBinUnder(d, bin, folder.uuid)
        assertEquals(folder.uuid, Tree.parentOf(Tree.root(d), bin)!!.uuid)
        assertTrue(Tree.findGroup(d, bin)!!.entries.any { it.uuid == child.uuid })
        d = Tree.ensureRecycleBinAtRoot(d, "Bin")
        assertEquals("repaired to root", Tree.root(d).uuid, Tree.parentOf(Tree.root(d), bin)!!.uuid)
        assertEquals(bin, Tree.recycleBinId(d))
        assertTrue("contents preserved", Tree.findGroup(d, bin)!!.entries.any { it.uuid == child.uuid })
        assertEquals(1, Tree.root(d).groups.count { it.uuid == bin })
    }

    @Test fun reparentNoOpWhenAlreadyAtRoot() {
        var d = db()
        d = Tree.ensureRecycleBin(d, "Bin")
        val before = Tree.recycleBinId(d)!!
        val same = Tree.reparentRecycleBinToRoot(d)
        assertEquals(Tree.root(same).uuid, Tree.parentOf(Tree.root(same), before)!!.uuid)
        assertEquals(before, Tree.recycleBinId(same))
    }
}
