package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Store
import io.github.zmlworks.kpexplorer.db.Tree
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class ImportBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(): Store {
        val data = tmp.newFolder("data")
        val bak = tmp.newFolder("backups")
        return Store(data, bak)
    }

    @Test fun timestampedBackupUniqueAndDurable() {
        val st = store()
        val key = Kdbx.compositeKey("primary-pw")
        val db = Kdbx.create("P", key, rootName = "")
        val bytes = Kdbx.encodeVerified(db, keepSalt = true)
        st.write(bytes)
        val t = LocalDateTime.of(2026, 11, 7, 5, 30, 0)
        val f1 = st.backupTimestamped(t)
        assertTrue(f1.name.startsWith("2026-11-07_05-30-00"))
        assertEquals(bytes.size.toLong(), f1.length())
        val f2 = st.backupTimestamped(t)
        assertNotEquals(f1.name, f2.name)
        assertTrue(f2.name.contains("_2") || f2.name != f1.name)
        // primary unchanged
        assertArrayEquals(bytes, st.read())
    }

    @Test fun backupFailureLeavesPrimary() {
        val st = store()
        // no database written
        try {
            st.backupTimestamped()
            fail("expected")
        } catch (_: IllegalStateException) {}
        assertFalse(st.exists())
    }

    @Test fun replacePrimaryAfterBackup() {
        val st = store()
        val k1 = Kdbx.compositeKey("a")
        val k2 = Kdbx.compositeKey("b")
        val primary = Kdbx.create("A", k1, rootName = "")
        val candidate = run {
            var d = Kdbx.create("B", k2, rootName = "")
            val e = Tree.newEntry(Tree.Fields("Imported", "u", "p", "", "", emptyList()))
            Tree.addEntry(d, Tree.root(d).uuid, e)
        }
        st.write(Kdbx.encodeVerified(primary, keepSalt = true))
        val bak = st.backupTimestamped()
        val candBytes = Kdbx.encodeVerified(candidate, keepSalt = true)
        st.replacePrimary(candBytes)
        assertArrayEquals(candBytes, st.read())
        assertTrue(bak.isFile)
        // source backup retained
        val reopen = Kdbx.open(st.read(), k2)
        assertEquals(1, Tree.root(reopen).entries.size)
        assertEquals("Imported", Tree.title(Tree.root(reopen).entries[0]))
        // old primary recoverable from backup with old key
        val old = Kdbx.open(bak.readBytes(), k1)
        assertEquals("A", old.content.meta.name)
    }

    @Test fun samePasswordCompositeOpensOtherFileWithCompatibleKdf() {
        val pw = "shared-master"
        val k = Kdbx.compositeKey(pw)
        val a = Kdbx.create("A", k.copyOf(), rootName = "a")
        val b = Kdbx.create("B", k.copyOf(), rootName = "b")
        val bytesB = Kdbx.encodeVerified(b, keepSalt = true)
        // composite from A opens B (salt-independent composite; KDF runs per file)
        val opened = Kdbx.open(bytesB, a.credentials.passphrase!!.getBinary().copyOf())
        assertEquals("B", opened.content.meta.name)
    }
}
