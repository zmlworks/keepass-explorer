package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.db.Store
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun backups_areDated_withSuffix() {
        val s = Store(tmp.newFolder("data"), tmp.newFolder("bk"))
        s.write(byteArrayOf(1, 2, 3))
        val d = LocalDate.of(2026, 10, 21)
        assertEquals("2026-10-21.kdbx", s.backup(d).name)
        assertEquals("2026-10-21-2.kdbx", s.backup(d).name)
        assertEquals("2026-10-21-3.kdbx", s.backup(d).name)
        assertEquals(3, s.backups().size)
        assertArrayEquals(byteArrayOf(1, 2, 3), s.backupFile("2026-10-21-2.kdbx")!!.readBytes())
        assertEquals(null, s.backupFile("../data/db/database.kdbx"))
        s.write(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), s.read())
    }
}
