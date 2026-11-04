package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ~3000 entries, real Argon2id parameters (32 MiB × 3, p = 2). The first encode runs Argon2 once; later saves
 * keep the salt, so the cached transformed key is reused (no KDF) — only XML + AES + HMAC + verify-decode remain.
 */
class SaveTimingTest {
    @Test fun saveOf3000EntriesReusesArgon2() {
        val key = Kdbx.compositeKey("timing")
        var db = Kdbx.create("t", key, rootName = "")
        val root = Tree.root(db).uuid
        var g = root
        for (i in 0 until 3000) {
            if (i % 100 == 0) { val x = Tree.newGroup("F$i"); db = Tree.addGroup(db, root, x); g = x.uuid }
            db = Tree.addEntry(db, g, Tree.newEntry(Tree.Fields("Entry $i", "user$i@example.com", "pw-$i-xxxxxxxx",
                "https://example.com/$i", "memo $i\nline 2", listOf("tag${i % 7}"))))
        }
        Kdbx.KdfCache.clear()
        val m0 = Kdbx.KdfCache.misses
        val t0 = System.nanoTime()
        val first = Kdbx.encodeVerified(db, keepSalt = true)
        val firstMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals("Argon2 ran once (encode; verify hit the cache)", m0 + 1, Kdbx.KdfCache.misses)
        val times = ArrayList<Long>()
        repeat(3) {
            val t = System.nanoTime()
            Kdbx.encodeVerified(db, keepSalt = true)
            times.add((System.nanoTime() - t) / 1_000_000)
        }
        assertEquals("no Argon2 on later saves", m0 + 1, Kdbx.KdfCache.misses)
        val back = Kdbx.open(first, key)
        assertEquals(3000, Tree.countEntries(back.content.group))
        // a cold open (cache cleared) must still work = file is a normal KDBX with that password
        Kdbx.KdfCache.clear()
        val tc = System.nanoTime(); Kdbx.open(first, key); val coldOpen = (System.nanoTime() - tc) / 1_000_000
        val report = "entries=3000 size=${first.size / 1024}KB firstSave(with Argon2)=${firstMs}ms laterSaves=${times}ms coldOpen=${coldOpen}ms"
        println("SAVE-TIMING $report")
        File("build/save-timing.txt").writeText(report + "\n")
        assertTrue(times.min() < firstMs)
    }
}
