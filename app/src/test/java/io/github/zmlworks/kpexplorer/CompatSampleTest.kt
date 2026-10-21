package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Tree
import org.junit.Test
import java.io.File

/**
 * Writes build/compat/kpx-sample.kdbx (password "kpx-sample") so other KeePass implementations can be checked
 * from the shell, e.g. `keepassxc-cli ls -R -f build/compat/kpx-sample.kdbx`. No real data.
 */
class CompatSampleTest {
    @Test fun writeSample() {
        val key = Kdbx.compositeKey("kpx-sample")
        var db = Kdbx.create("互換テスト", key)
        val root = Tree.root(db).uuid
        val a = Tree.newGroup("フォルダーA"); val b = Tree.newGroup("フォルダーB"); val c = Tree.newGroup("子フォルダー")
        db = Tree.addGroup(db, root, a); db = Tree.addGroup(db, root, b); db = Tree.addGroup(db, a.uuid, c)
        val e1 = Tree.newEntry(Tree.Fields("ログイン1", "user1", "pass1", "https://example.com", "メモ1", listOf("tag1", "共通")))
        val e2 = Tree.newEntry(Tree.Fields("メモだけ", "", "", "", "本文\n2行目", listOf("共通")))
        val e3 = Tree.newEntry(Tree.Fields("消すもの", "", "", "", "x", emptyList()))
        db = Tree.addEntry(db, a.uuid, e1); db = Tree.addEntry(db, a.uuid, e2); db = Tree.addEntry(db, b.uuid, e3)
        db = Tree.move(db, e2.uuid, b.uuid)          // LocationChanged + PreviousParentGroup
        db = Tree.reorder(db, b.uuid, 0)             // B before A
        db = Tree.updateEntry(db, e1.uuid, Tree.fieldsOf(Tree.findEntry(db, e1.uuid)!!.second).copy(password = "pass1-new"))
        db = Tree.delete(db, e3.uuid).first          // → recycle bin
        db = Tree.delete(db, e3.uuid).first          // → permanent, DeletedObjects
        db = Tree.setExpanded(db, a.uuid, false)
        db = Tree.setViewMode(db, b.uuid, "list")
        val out = File("build/compat").apply { mkdirs() }
        File(out, "kpx-sample.kdbx").writeBytes(Kdbx.encodeVerified(db))
        File(out, "ids.txt").writeText("e1=${e1.uuid}\ne2=${e2.uuid}\ne3=${e3.uuid}\na=${a.uuid}\nb=${b.uuid}\n")
    }
}
