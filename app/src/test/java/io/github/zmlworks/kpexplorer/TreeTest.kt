package io.github.zmlworks.kpexplorer

import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class TreeTest {
    private val key = Kdbx.compositeKey("テスト用パスワード")

    private fun fields(title: String, user: String = "", pw: String = "", notes: String = "", tags: String = "") =
        Tree.Fields(title, user, pw, "", notes, Tree.parseTags(tags))

    /** root ─ メール(Gmail, Yahoo) ─ 銀行 ─ memo */
    private fun sample(): Triple<KeePassDatabase, Map<String, java.util.UUID>, Map<String, java.util.UUID>> {
        var db = Kdbx.create("テストDB", key)
        val root = Tree.root(db).uuid
        val mail = Tree.newGroup("メール"); val bank = Tree.newGroup("銀行")
        db = Tree.addGroup(db, root, mail); db = Tree.addGroup(db, root, bank)
        val gmail = Tree.newEntry(fields("Gmail", "me@example.com", "pw1", tags = "web mail"))
        val yahoo = Tree.newEntry(fields("Yahoo", "me", "pw2", tags = "mail"))
        val memo = Tree.newEntry(fields("買い物メモ", notes = "牛乳\n卵", tags = "memo"))
        db = Tree.addEntry(db, mail.uuid, gmail); db = Tree.addEntry(db, mail.uuid, yahoo)
        db = Tree.addEntry(db, root, memo)
        return Triple(db, mapOf("root" to root, "mail" to mail.uuid, "bank" to bank.uuid),
            mapOf("gmail" to gmail.uuid, "yahoo" to yahoo.uuid, "memo" to memo.uuid))
    }

    private fun roundTrip(db: KeePassDatabase) = Kdbx.open(Kdbx.encodeVerified(db), key)

    @Test fun roundTrip_keepsTreeOrderFieldsAndTags() {
        val (db0, g, e) = sample()
        val db = roundTrip(db0)
        val root = Tree.root(db)
        assertEquals(listOf("メール", "銀行"), root.groups.map { it.name })
        val mail = Tree.findGroup(db, g["mail"]!!)!!
        assertEquals(listOf("Gmail", "Yahoo"), mail.entries.map { Tree.title(it) })
        val gm = Tree.findEntry(db, e["gmail"]!!)!!.second
        assertEquals("pw1", Tree.password(gm))
        assertEquals(listOf("web", "mail"), gm.tags)
        val memo = Tree.findEntry(db, e["memo"]!!)!!.second
        assertEquals("", Tree.password(memo)); assertEquals("", Tree.userName(memo))
        assertEquals("牛乳\n卵", Tree.notes(memo))
        assertTrue(db is KeePassDatabase.Ver4x)
        assertFalse(db.content.meta.recycleBinEnabled)
    }

    @Test fun wrongPassword_isReported() {
        val (db, _, _) = sample()
        val bytes = Kdbx.encode(db)
        try { Kdbx.open(bytes, Kdbx.compositeKey("違う")); fail() } catch (_: Kdbx.WrongPasswordException) {}
    }

    @Test fun update_inPlace_withHistory() {
        val (db0, g, e) = sample()
        val db = Tree.updateEntry(db0, e["gmail"]!!, fields("Gmail 2", "me@example.com", "new", tags = "web"))
        val mail = Tree.findGroup(db, g["mail"]!!)!!
        assertEquals(listOf("Gmail 2", "Yahoo"), mail.entries.map { Tree.title(it) }) // order kept
        val gm = mail.entries[0]
        assertEquals(1, gm.history.size)
        assertEquals("pw1", Tree.password(gm.history[0]))
        // unchanged update is a no-op
        assertTrue(Tree.updateEntry(db, gm.uuid, Tree.fieldsOf(gm)) === db)
        val rt = roundTrip(db)
        assertEquals(1, Tree.findEntry(rt, gm.uuid)!!.second.history.size)
    }

    @Test fun move_setsLocationChangedAndPreviousParent() {
        val (db0, g, e) = sample()
        val t = Instant.parse("2030-01-02T03:04:05Z")
        val db = Tree.move(db0, e["gmail"]!!, g["bank"]!!, now = t)
        assertEquals(listOf("Yahoo"), Tree.findGroup(db, g["mail"]!!)!!.entries.map { Tree.title(it) })
        val (parent, gm) = Tree.findEntry(db, e["gmail"]!!)!!
        assertEquals(g["bank"], parent.uuid)
        assertEquals(t, gm.times!!.locationChanged)
        assertEquals(g["mail"], gm.previousParentGroup)
        // folder move
        val db2 = Tree.move(db, g["bank"]!!, g["mail"]!!, now = t)
        assertEquals(listOf("メール"), Tree.root(db2).groups.map { it.name })
        assertEquals(t, Tree.findGroup(db2, g["bank"]!!)!!.times!!.locationChanged)
        val rt = roundTrip(db2)
        assertEquals(listOf("メール", "銀行"), Tree.path(Tree.root(rt), g["bank"]!!).drop(1).map { it.name })
        assertEquals(t, Tree.findEntry(rt, e["gmail"]!!)!!.second.times!!.locationChanged)
    }

    @Test fun move_intoOwnDescendant_isRefused() {
        val (db0, g, _) = sample()
        val db = Tree.move(db0, g["bank"]!!, g["mail"]!!)
        try { Tree.move(db, g["mail"]!!, g["bank"]!!); fail() } catch (_: Tree.TreeException) {}
        try { Tree.move(db, g["mail"]!!, g["mail"]!!); fail() } catch (_: Tree.TreeException) {}
    }

    @Test fun reorder_changesDocumentOrder() {
        val (db0, g, e) = sample()
        val db = Tree.reorder(db0, e["yahoo"]!!, 0)
        assertEquals(listOf("Yahoo", "Gmail"), Tree.findGroup(db, g["mail"]!!)!!.entries.map { Tree.title(it) })
        val db2 = Tree.reorder(db, g["bank"]!!, 0)
        assertEquals(listOf("銀行", "メール"), Tree.root(db2).groups.map { it.name })
        // same-parent reorder does not count as a location change
        val yahoo = Tree.findEntry(db, e["yahoo"]!!)!!.second
        assertNull(yahoo.previousParentGroup)
        val rt = roundTrip(db2)
        assertEquals(listOf("銀行", "メール"), Tree.root(rt).groups.map { it.name })
        assertEquals(listOf("Yahoo", "Gmail"), Tree.findGroup(rt, g["mail"]!!)!!.entries.map { Tree.title(it) })
    }

    @Test fun delete_isPermanentWithDeletedObjects_noRecycleBin() {
        val (db0, g, e) = sample()
        val (db1, toBin) = Tree.delete(db0, g["mail"]!!)
        assertFalse(toBin)
        assertNull(Tree.recycleBinId(db1))
        assertEquals(setOf(g["mail"], e["gmail"], e["yahoo"]), db1.content.deletedObjects.map { it.id }.toSet())
        assertNull(Tree.findEntry(db1, e["gmail"]!!))
        assertTrue(Tree.root(db1).groups.none { it.name == "ごみ箱" })
        val (db2, _) = Tree.delete(db1, e["memo"]!!)
        val rt = roundTrip(db2)
        assertEquals(4, rt.content.deletedObjects.size)
        assertFalse(rt.content.meta.recycleBinEnabled)
        assertNull(Tree.recycleBinId(rt))
    }

    @Test fun tags_parseAndSuggest() {
        assertEquals(listOf("a", "b", "c"), Tree.parseTags(" a  b\u3000c a "))
        assertEquals(listOf("x", "y"), Tree.parseTags("x;y,"))
        val (db, _, e) = sample()
        assertEquals(listOf("mail", "memo", "web"), Tree.allTags(db))
        // tags of deleted entries disappear
        val (db2, _) = Tree.delete(db, e["memo"]!!)
        assertEquals(listOf("mail", "web"), Tree.allTags(db2))
        assertEquals(listOf("Gmail"), Tree.search(db, "#web").map { Tree.title(it.second) })
        assertEquals(listOf("買い物メモ"), Tree.search(db, "牛乳").map { Tree.title(it.second) })
    }

    @Test fun expandedAndViewMode_persist() {
        val (db0, g, _) = sample()
        var db = Tree.setExpanded(db0, g["mail"]!!, false)
        db = Tree.setViewMode(db, g["mail"]!!, "list")
        val rt = roundTrip(db)
        val mail = Tree.findGroup(rt, g["mail"]!!)!!
        assertFalse(mail.expanded)
        assertEquals("list", Tree.viewMode(mail))
    }

    @Test fun renameAndUniqueName() {
        val (db0, g, _) = sample()
        val db = Tree.renameGroup(db0, g["bank"]!!, "銀行・カード")
        assertEquals(listOf("メール", "銀行・カード"), Tree.root(db).groups.map { it.name })
        assertEquals("新しいフォルダー (3)", Tree.uniqueName(listOf("新しいフォルダー", "新しいフォルダー (2)"), "新しいフォルダー"))
    }

    @Test fun changeMasterPassword() {
        val (db0, _, _) = sample()
        val k2 = Kdbx.compositeKey("new pass")
        val bytes = Kdbx.encodeVerified(Kdbx.withCredentials(db0, k2))
        Kdbx.open(bytes, k2)
        try { Kdbx.open(bytes, key); fail() } catch (_: Kdbx.WrongPasswordException) {}
    }
}
