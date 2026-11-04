package io.github.zmlworks.kpexplorer

import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OrderTest {
    private val key = Kdbx.compositeKey("t")

    private fun names(db: KeePassDatabase, g: UUID) = Order.children(Tree.findGroup(db, g)!!).map { it.name }

    @Test fun mixedOrderMoveAndPersist() {
        var db = Kdbx.create("t", key)
        val root = Tree.root(db).uuid
        val a = Tree.newGroup("A"); db = Order.appendGroup(db, root, a)
        val m = Order.newMemo("メモ1"); db = Order.appendEntry(db, root, m)
        val b = Tree.newGroup("B"); db = Order.appendGroup(db, root, b)
        val k = Order.newKey(); db = Order.appendEntry(db, root, k)
        db = Order.saveEntry(db, k.uuid, mapOf("Title" to "鍵", "Password" to "pw"), emptyList(), null)
        assertEquals(listOf("A", "メモ1", "B", "鍵"), names(db, root))
        // reorder: key to the front
        db = Order.moveTo(db, k.uuid, root, 0)
        assertEquals(listOf("鍵", "A", "メモ1", "B"), names(db, root))
        // drop memo into folder B, then a folder into another
        db = Order.moveTo(db, m.uuid, b.uuid)
        db = Order.moveTo(db, a.uuid, b.uuid, 0)
        assertEquals(listOf("鍵", "B"), names(db, root))
        assertEquals(listOf("A", "メモ1"), names(db, b.uuid))
        // order survives a KDBX round trip
        val disk = Kdbx.open(Kdbx.encodeVerified(db), key)
        assertEquals(listOf("鍵", "B"), names(disk, root))
        assertEquals(listOf("A", "メモ1"), names(disk, b.uuid))
        assertTrue(Order.isMemo(Tree.findEntry(disk, m.uuid)!!.second))
        assertFalse(Order.isMemo(Tree.findEntry(disk, k.uuid)!!.second))
    }

    @Test fun numberedNames() {
        var db = Kdbx.create("t", key)
        val root = Tree.root(db).uuid
        repeat(3) { val g = Tree.newGroup(Order.uniqueChildName(Tree.findGroup(db, root)!!, "新しいフォルダ")); db = Order.appendGroup(db, root, g) }
        assertEquals(listOf("新しいフォルダ", "新しいフォルダ2", "新しいフォルダ3"), names(db, root).filter { it.startsWith("新しい") })
    }

    @Test fun customFieldsLayoutAndOtp() {
        var db = Kdbx.create("t", key)
        val root = Tree.root(db).uuid
        val k = Order.newKey(); db = Order.appendEntry(db, root, k)
        val layout = listOf("Title" to true, "PIN" to true, "UserName" to true, "Password" to true, "URL" to false,
            Order.F_TAGS to true, Order.F_OTP to true, "Notes" to true)
        db = Order.saveEntry(db, k.uuid, mapOf("Title" to "銀行", "PIN" to "9999", "otp" to "otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"),
            listOf("銀行"), layout)
        var e = Tree.findEntry(Kdbx.open(Kdbx.encodeVerified(db), key), k.uuid)!!.second
        assertEquals(listOf("PIN"), Order.customFieldNames(e))
        assertEquals(layout, Order.layout(e))
        assertTrue(Order.otpUri(e)!!.startsWith("otpauth://"))
        assertTrue(e.fields["otp"] is app.keemobile.kotpass.models.EntryValue.Encrypted)
        // removing the custom field and the otp
        db = Order.saveEntry(db, k.uuid, mapOf("Title" to "銀行"), listOf("銀行"), layout.filter { it.first != "PIN" })
        e = Tree.findEntry(db, k.uuid)!!.second
        assertEquals(emptyList<String>(), Order.customFieldNames(e))
        assertEquals(null, Order.otpUri(e))
        assertEquals(2, e.history.size)
    }
}
