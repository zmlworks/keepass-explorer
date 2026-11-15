package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.time.Duration
import java.time.Instant

/**
 * 0.4.36 「最近の鍵」: the keys added or changed most recently, worked out from the file each time (nothing stored).
 * Time = the later of the entry's CreationTime and LastModificationTime. Keys only (not independent memos), not the
 * recycle bin, not History copies (those are not in any folder). This app moves Last Modified only for a change of
 * content (fields, tags) or of the icon; moving, reordering, showing / hiding fields keep it (see Order.saveEntry,
 * Tree.move, Tree.restoreFromBin).
 */
object Recent {
    const val LIMIT = 30
    /** Changed this soon after being created (a new key saved with its first values) still counts as added. */
    val SAME_AS_ADDED: Duration = Duration.ofSeconds(60)

    enum class Kind { ADDED, UPDATED }
    class Item(val entry: Entry, val folder: Group, val kind: Kind, val time: Instant)

    fun list(db: KeePassDatabase, limit: Int = LIMIT): List<Item> {
        val bin = Tree.recycleBinId(db)
        val out = ArrayList<Item>()
        fun walk(g: Group) {
            if (g.uuid == bin) return
            for (e in g.entries) item(e, g)?.let { out.add(it) }
            g.groups.forEach { walk(it) }
        }
        walk(Tree.root(db))
        return out.sortedWith(compareByDescending<Item> { it.time }.thenBy(String.CASE_INSENSITIVE_ORDER) { Tree.title(it.entry) }).take(limit)
    }

    fun item(e: Entry, folder: Group): Item? {
        if (Order.isMemo(e)) return null
        val c = e.times?.creationTime
        val m = e.times?.lastModificationTime
        val t = listOfNotNull(c, m).maxOrNull() ?: return null
        val updated = m != null && (c == null || m.isAfter(c.plus(SAME_AS_ADDED)))
        return Item(e, folder, if (updated) Kind.UPDATED else Kind.ADDED, t)
    }
}
