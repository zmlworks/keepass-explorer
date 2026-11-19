package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The virtual folders (機能フォルダ) in "/", worked out from the file each time. 0.4.40: keys and independent memos
 * (up to 0.4.39 keys only), not the recycle bin, not History copies (those are not in any folder).
 *
 * - 「最近更新されたファイル」 (0.4.36 「最近の鍵」, 0.4.38 「最近更新した鍵」, [list]): time = the later of the entry's
 *   CreationTime and LastModificationTime. This app moves Last Modified only for a change of content (fields, tags) or
 *   of the icon; moving, reordering, showing / hiding fields keep it (see Order.saveEntry, Tree.move, Tree.restoreFromBin).
 * - 「最近利用したファイル」 (0.4.39 「最近使用した鍵」, [used]): KeePass's own LastAccessTime / UsageCount, kept in the
 *   KDBX like every other KeePass client does (KeePass itself moves them when an entry is used or edited). This app
 *   records a use when a key or memo page is opened or a field is copied ([markUsed]); editing sets the access time
 *   too (an edit is a use, as in KeePass). An entry counts as used once its access time is later than its creation (by
 *   more than [SAME_AS_CREATED]): a new entry's access time equals its creation time.
 * - 0.4.40 「よく利用するファイル」 ([frequent]): UsageCount, highest first (then the most recently used); only entries
 *   used at least once.
 *
 * [clearUsed] sets the access times back to the creation times and the use counts to 0 (modification times and
 * History stay as they are): it empties the last two.
 */
object Recent {
    const val LIMIT = 30
    /**
     * 0.4.40: the ids that stand for the virtual folders — in the back history and, among the real items, in the root
     * folder's own order ([Order.CD_ORDER]; KeePass ignores that CustomData, and no group or entry has these ids).
     */
    val CHANGED_ID: UUID = UUID(0L, 0x0438L)
    val USED_ID: UUID = UUID(0L, 0x0439L)
    val FREQUENT_ID: UUID = UUID(0L, 0x0440L)
    val IDS = listOf(CHANGED_ID, USED_ID, FREQUENT_ID)

    /** Changed this soon after being created (a new key saved with its first values) still counts as added. */
    val SAME_AS_ADDED: Duration = Duration.ofSeconds(60)
    /** Accessed within this of being created = never used (creation sets both times). */
    val SAME_AS_CREATED: Duration = Duration.ofSeconds(1)

    enum class Kind { ADDED, UPDATED, USED }
    /** One listed entry: [time] = its time in that list (changed or used), [count] = its UsageCount. */
    class Item(val entry: Entry, val folder: Group, val kind: Kind, val time: Instant) {
        val count: Int get() = entry.times?.usageCount ?: 0
    }

    private val newestFirst = compareByDescending<Item> { it.time }.thenBy(String.CASE_INSENSITIVE_ORDER) { Tree.title(it.entry) }
    private val mostUsedFirst = compareByDescending<Item> { it.count }.then(newestFirst)

    private fun collect(db: KeePassDatabase, limit: Int, order: Comparator<Item>, item: (Entry, Group) -> Item?): List<Item> {
        val bin = Tree.recycleBinId(db)
        val out = ArrayList<Item>()
        fun walk(g: Group) {
            if (g.uuid == bin) return
            for (e in g.entries) item(e, g)?.let { out.add(it) }
            g.groups.forEach { walk(it) }
        }
        walk(Tree.root(db))
        return out.sortedWith(order).take(limit)
    }

    /** 最近更新されたファイル: newest first. */
    fun list(db: KeePassDatabase, limit: Int = LIMIT): List<Item> = collect(db, limit, newestFirst) { e, g -> item(e, g) }

    /** 最近利用したファイル: most recently used first. */
    fun used(db: KeePassDatabase, limit: Int = LIMIT): List<Item> = collect(db, limit, newestFirst) { e, g -> usedItem(e, g) }

    /** よく利用するファイル: the highest UsageCount first. */
    fun frequent(db: KeePassDatabase, limit: Int = LIMIT): List<Item> =
        collect(db, limit, mostUsedFirst) { e, g -> if ((e.times?.usageCount ?: 0) > 0) Item(e, g, Kind.USED, usedAt(e) ?: e.times?.lastAccessTime ?: Instant.EPOCH) else null }

    fun item(e: Entry, folder: Group): Item? {
        val c = e.times?.creationTime
        val m = e.times?.lastModificationTime
        val t = listOfNotNull(c, m).maxOrNull() ?: return null
        val updated = m != null && (c == null || m.isAfter(c.plus(SAME_AS_ADDED)))
        return Item(e, folder, if (updated) Kind.UPDATED else Kind.ADDED, t)
    }

    /** The time [e] was last used, or null when it never was (see the class comment). */
    fun usedAt(e: Entry): Instant? {
        val t = e.times ?: return null
        val a = t.lastAccessTime ?: return null
        val c = t.creationTime ?: return a
        return a.takeIf { it.isAfter(c.plus(SAME_AS_CREATED)) }
    }

    fun usedItem(e: Entry, folder: Group): Item? = usedAt(e)?.let { Item(e, folder, Kind.USED, it) }

    /** Records a use of entry [id]: LastAccessTime = [now], UsageCount + 1 (nothing else changes; no History copy). */
    fun markUsed(db: KeePassDatabase, id: UUID, now: Instant = Instant.now()): KeePassDatabase {
        val (g, _) = Tree.findEntry(db, id) ?: return db
        return Tree.modifyGroup(db, g.uuid) { p ->
            p.copy(entries = p.entries.map { e ->
                if (e.uuid != id) e else {
                    val t = e.times ?: app.keemobile.kotpass.models.TimeData.create(now)
                    e.copy(times = t.copy(lastAccessTime = now, usageCount = t.usageCount + 1))
                }
            })
        }
    }

    /** Whether anything would show in 最近利用したファイル or よく利用するファイル (the bin aside). */
    fun anyUsed(db: KeePassDatabase): Boolean = used(db, 1).isNotEmpty() || frequent(db, 1).isNotEmpty()

    /** 利用履歴を消去: every entry's (also in the bin) access time back to its creation time, use count 0. */
    fun clearUsed(db: KeePassDatabase): KeePassDatabase {
        fun clear(g: Group): Group = g.copy(
            entries = g.entries.map { e ->
                val t = e.times ?: return@map e
                if (t.lastAccessTime == t.creationTime && t.usageCount == 0) e
                else e.copy(times = t.copy(lastAccessTime = t.creationTime ?: t.lastAccessTime, usageCount = 0))
            },
            groups = g.groups.map { clear(it) })
        return db.modifyContent { copy(group = clear(group)) }
    }
}
