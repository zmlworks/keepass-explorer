package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStreamReader
import java.util.Properties

/**
 * Optional, machine-local KDBX files (never in the repo): env/system property KCK_KDBX_FIXTURES points to a
 * UTF-8 .properties file with `fixtures = a, b`, `a.db = /path`, `a.password = …` (`a.keyfile` → skipped,
 * this app is password-only). Opens each file, edits, moves, saves and reopens it — in memory only.
 */
class FixturesTest {
    private fun load(): List<Triple<String, File, String>> {
        val path = System.getProperty("KCK_KDBX_FIXTURES") ?: System.getenv("KCK_KDBX_FIXTURES") ?: return emptyList()
        val f = File(path); if (!f.isFile) return emptyList()
        val p = Properties(); InputStreamReader(f.inputStream(), Charsets.UTF_8).use { p.load(it) }
        return p.getProperty("fixtures", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .filter { p.getProperty("$it.db") != null && p.getProperty("$it.keyfile").isNullOrEmpty() }
            .map { Triple(it, File(p.getProperty("$it.db")), p.getProperty("$it.password", "")) }
    }

    @Test fun openEditSaveReopen() {
        val fx = load()
        assumeTrue("KCK_KDBX_FIXTURES not set", fx.isNotEmpty())
        for ((name, file, pw) in fx) {
            val key = Kdbx.compositeKey(pw)
            try { Kdbx.open(file.readBytes(), Kdbx.compositeKey(pw + "x")); org.junit.Assert.fail(name) }
            catch (_: Kdbx.WrongPasswordException) {}
            var db = Kdbx.convertToAes(Kdbx.open(file.readBytes(), key))
            val before = Tree.countEntries(Tree.root(db))
            val root = Tree.root(db).uuid
            val g = Tree.newGroup("KPX テスト")
            db = Tree.addGroup(db, root, g)
            val e = Tree.newEntry(Tree.Fields("メモ", "", "", "", "本文", listOf("kpx", "test")))
            db = Tree.addEntry(db, root, e)
            db = Tree.move(db, e.uuid, g.uuid)
            val rt = Kdbx.open(Kdbx.encodeVerified(db), key)
            assertEquals(name, before + 1, Tree.countEntries(Tree.root(rt)))
            assertEquals(name, g.uuid, Tree.findEntry(rt, e.uuid)!!.first.uuid)
            assertTrue(name, Tree.allTags(rt).containsAll(listOf("kpx", "test")))
            System.getenv("KPX_FIXTURE_OUT")?.let { File(it, "$name.kdbx").writeBytes(Kdbx.encodeVerified(db)) }
            println("fixture $name: ${Kdbx.describe(rt)} entries=$before OK")
        }
    }
}
