package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import java.time.Instant
import java.util.UUID

/**
 * Pure, order-preserving tree operations on a [KeePassDatabase] (immutable kotpass model).
 *
 * Unlike some kotpass modifiers these never re-append a modified item at the end of its list, so the
 * XML document order (= the manual order shown in KeePass / KeePassXC / Keepass2Android) is kept.
 * KeePass semantics: moves set Times/LocationChanged and PreviousParentGroup, permanent deletes append
 * DeletedObjects (for the item and every descendant), deletes go to the Recycle Bin when it is enabled,
 * edits push the old version to the entry History.
 */
object Tree {
    /** At most this many history copies per entry (KeePass default; also written to Meta.HistoryMaxItems). */
    const val HISTORY_MAX = 10

    /** Group CustomData key for the per-folder view override (icons / list / auto). */
    const val CD_VIEW = "KPExplorer.View"

    data class Fields(
        val title: String,
        val userName: String,
        val password: String,
        val url: String,
        val notes: String,
        val tags: List<String>
    )

    sealed class Item {
        abstract val uuid: UUID
        data class G(val group: Group) : Item() { override val uuid get() = group.uuid }
        data class E(val entry: Entry) : Item() { override val uuid get() = entry.uuid }
    }

    class TreeException(msg: String) : IllegalArgumentException(msg)

    // ---------------------------------------------------------------- lookup

    fun root(db: KeePassDatabase): Group = db.content.group

    fun findGroup(g: Group, id: UUID): Group? {
        if (g.uuid == id) return g
        for (c in g.groups) findGroup(c, id)?.let { return it }
        return null
    }

    fun findGroup(db: KeePassDatabase, id: UUID): Group? = findGroup(root(db), id)

    /** (parent, entry) */
    fun findEntry(g: Group, id: UUID): Pair<Group, Entry>? {
        g.entries.firstOrNull { it.uuid == id }?.let { return g to it }
        for (c in g.groups) findEntry(c, id)?.let { return it }
        return null
    }

    fun findEntry(db: KeePassDatabase, id: UUID): Pair<Group, Entry>? = findEntry(root(db), id)

    /** Parent group of a group or entry, null for the root / unknown ids. */
    fun parentOf(g: Group, id: UUID): Group? {
        if (g.groups.any { it.uuid == id } || g.entries.any { it.uuid == id }) return g
        for (c in g.groups) parentOf(c, id)?.let { return it }
        return null
    }

    /** Groups from the root down to [id] (inclusive); empty if not found. */
    fun path(g: Group, id: UUID): List<Group> {
        if (g.uuid == id) return listOf(g)
        for (c in g.groups) {
            val p = path(c, id)
            if (p.isNotEmpty()) return listOf(g) + p
        }
        return emptyList()
    }

    fun recycleBinId(db: KeePassDatabase): UUID? {
        val m = db.content.meta
        val id = m.recycleBinUuid ?: return null
        if (id == ZERO) return null
        return id
    }

    fun isInRecycleBin(db: KeePassDatabase, id: UUID): Boolean {
        val bin = recycleBinId(db) ?: return false
        if (id == bin) return true
        val p = parentOf(root(db), id) ?: return false
        return path(root(db), p.uuid).any { it.uuid == bin }
    }

    fun isDescendantOrSelf(ancestor: Group, id: UUID): Boolean = findGroup(ancestor, id) != null

    fun field(e: Entry, f: BasicField): String = e.fields[f]?.content.orEmpty()
    fun title(e: Entry) = field(e, BasicField.Title)
    fun userName(e: Entry) = field(e, BasicField.UserName)
    fun password(e: Entry) = field(e, BasicField.Password)
    fun url(e: Entry) = field(e, BasicField.Url)
    fun notes(e: Entry) = field(e, BasicField.Notes)

    fun fieldsOf(e: Entry) = Fields(title(e), userName(e), password(e), url(e), notes(e), e.tags)

    fun countEntries(g: Group): Int = g.entries.size + g.groups.sumOf { countEntries(it) }

    // ---------------------------------------------------------------- generic rewrite

    private fun mapGroup(g: Group, id: UUID, f: (Group) -> Group): Group =
        if (g.uuid == id) f(g) else {
            var changed = false
            val ng = g.groups.map { c -> mapGroup(c, id, f).also { if (it !== c) changed = true } }
            if (changed) g.copy(groups = ng) else g
        }

    private fun KeePassDatabase.withRoot(f: (Group) -> Group): KeePassDatabase =
        modifyContent { copy(group = f(group)) }

    fun modifyGroup(db: KeePassDatabase, id: UUID, f: (Group) -> Group): KeePassDatabase {
        if (findGroup(db, id) == null) throw TreeException("group not found")
        return db.withRoot { mapGroup(it, id, f) }
    }

    private fun touch(t: TimeData?, now: Instant): TimeData =
        t?.copy(lastModificationTime = now, lastAccessTime = now) ?: TimeData.create(now)

    // ---------------------------------------------------------------- create

    fun newGroup(name: String, now: Instant = Instant.now()) =
        Group(uuid = UUID.randomUUID(), name = name, icon = PredefinedIcon.Folder, times = TimeData.create(now))

    fun addGroup(db: KeePassDatabase, parent: UUID, group: Group): KeePassDatabase =
        modifyGroup(db, parent) { it.copy(groups = it.groups + group) }

    fun newEntry(f: Fields, now: Instant = Instant.now()): Entry {
        val e = Entry(
            uuid = UUID.randomUUID(),
            icon = if (f.password.isEmpty() && f.userName.isEmpty()) PredefinedIcon.Notepad else PredefinedIcon.Key,
            times = TimeData.create(now),
            fields = EntryFields.createDefault()
        )
        return applyFields(e, f)
    }

    fun addEntry(db: KeePassDatabase, parent: UUID, entry: Entry): KeePassDatabase =
        modifyGroup(db, parent) { it.copy(entries = it.entries + entry) }

    private fun applyFields(e: Entry, f: Fields): Entry {
        val protectPw = true
        val fields = e.fields + listOf(
            BasicField.Title() to EntryValue.Plain(f.title),
            BasicField.UserName() to EntryValue.Plain(f.userName),
            BasicField.Password() to (if (protectPw) EntryValue.Encrypted(EncryptedValue.fromString(f.password))
            else EntryValue.Plain(f.password)),
            BasicField.Url() to EntryValue.Plain(f.url),
            BasicField.Notes() to EntryValue.Plain(f.notes)
        )
        return e.copy(fields = fields, tags = normalizeTags(f.tags))
    }

    // ---------------------------------------------------------------- update

    /**
     * Replace the standard fields + tags of an entry in place. When something changed, the previous
     * version is pushed to History (trimmed to Meta.HistoryMaxItems) and LastModificationTime is set.
     */
    fun updateEntry(db: KeePassDatabase, id: UUID, f: Fields, now: Instant = Instant.now()): KeePassDatabase {
        val (parent, old) = findEntry(db, id) ?: throw TreeException("entry not found")
        if (fieldsOf(old) == f.copy(tags = normalizeTags(f.tags))) return db
        val max = db.content.meta.historyMaxItems.let { if (it < 0 || it > Tree.HISTORY_MAX) Tree.HISTORY_MAX else it }
        val snapshot = old.copy(history = listOf())
        var hist = old.history + snapshot
        if (max >= 0) hist = hist.takeLast(max)
        val updated = applyFields(old, f).copy(history = hist, times = touch(old.times, now))
        return db.withRoot { r ->
            mapGroup(r, parent.uuid) { g -> g.copy(entries = g.entries.map { if (it.uuid == id) updated else it }) }
        }
    }

    fun renameGroup(db: KeePassDatabase, id: UUID, name: String, now: Instant = Instant.now()): KeePassDatabase =
        modifyGroup(db, id) { it.copy(name = name, times = touch(it.times, now)) }

    fun setExpanded(db: KeePassDatabase, id: UUID, expanded: Boolean): KeePassDatabase {
        val g = findGroup(db, id) ?: return db
        if (g.expanded == expanded) return db
        return modifyGroup(db, id) { it.copy(expanded = expanded) }
    }

    /** View override stored in group CustomData (null = automatic). */
    fun viewMode(g: Group): String? = g.customData[CD_VIEW]?.value

    fun setViewMode(db: KeePassDatabase, id: UUID, mode: String?, now: Instant = Instant.now()): KeePassDatabase =
        modifyGroup(db, id) {
            it.copy(customData = if (mode == null) it.customData - CD_VIEW
            else it.customData + (CD_VIEW to CustomDataValue(mode, now)))
        }

    // ---------------------------------------------------------------- move / reorder

    private fun detach(g: Group, id: UUID): Pair<Group, Item?> {
        g.groups.firstOrNull { it.uuid == id }?.let { found ->
            return g.copy(groups = g.groups.filter { it.uuid != id }) to Item.G(found)
        }
        g.entries.firstOrNull { it.uuid == id }?.let { found ->
            return g.copy(entries = g.entries.filter { it.uuid != id }) to Item.E(found)
        }
        var item: Item? = null
        val ng = g.groups.map { c ->
            if (item != null) c else {
                val (nc, it) = detach(c, id)
                if (it != null) { item = it; nc } else c
            }
        }
        return if (item != null) g.copy(groups = ng) to item else g to null
    }

    /**
     * Move a group or entry to [target] (appended, or inserted at [index] among its kind).
     * Sets LocationChanged and PreviousParentGroup. Moving into itself or a descendant is refused.
     */
    fun move(db: KeePassDatabase, id: UUID, target: UUID, index: Int? = null, now: Instant = Instant.now()): KeePassDatabase {
        val r = root(db)
        if (id == r.uuid) throw TreeException("cannot move the root")
        val bin = recycleBinId(db)
        if (bin != null && id == bin) throw TreeException("cannot move the recycle bin")
        val from = parentOf(r, id) ?: throw TreeException("item not found")
        findGroup(r, target) ?: throw TreeException("target not found")
        findGroup(r, id)?.let { if (isDescendantOrSelf(it, target)) throw TreeException("cannot move a folder into itself") }
        if (from.uuid == target && index == null) return db
        val (detached, item) = detach(r, id)
        item ?: throw TreeException("item not found")
        val sameParent = from.uuid == target
        val moved: Item = when (item) {
            is Item.G -> Item.G(if (sameParent) item.group else item.group.copy(
                times = (item.group.times ?: TimeData.create(now)).copy(locationChanged = now),
                previousParentGroup = from.uuid))
            is Item.E -> Item.E(if (sameParent) item.entry else item.entry.copy(
                times = (item.entry.times ?: TimeData.create(now)).copy(locationChanged = now),
                previousParentGroup = from.uuid))
        }
        val nr = mapGroup(detached, target) { g ->
            when (moved) {
                is Item.G -> g.copy(groups = insert(g.groups, moved.group, index))
                is Item.E -> g.copy(entries = insert(g.entries, moved.entry, index))
            }
        }
        return db.modifyContent { copy(group = nr) }
    }

    private fun <T> insert(list: List<T>, item: T, index: Int?): List<T> {
        val m = list.toMutableList()
        m.add((index ?: m.size).coerceIn(0, m.size), item)
        return m
    }

    /** Position of an item among its siblings of the same kind, or -1. */
    fun indexInParent(db: KeePassDatabase, id: UUID): Int {
        val p = parentOf(root(db), id) ?: return -1
        val gi = p.groups.indexOfFirst { it.uuid == id }
        return if (gi >= 0) gi else p.entries.indexOfFirst { it.uuid == id }
    }

    fun siblingCount(db: KeePassDatabase, id: UUID): Int {
        val p = parentOf(root(db), id) ?: return 0
        return if (p.groups.any { it.uuid == id }) p.groups.size else p.entries.size
    }

    /** Reorder within the same parent: newIndex among siblings of the same kind (clamped). */
    fun reorder(db: KeePassDatabase, id: UUID, newIndex: Int): KeePassDatabase {
        val p = parentOf(root(db), id) ?: throw TreeException("item not found")
        val n = siblingCount(db, id)
        val cur = indexInParent(db, id)
        val to = newIndex.coerceIn(0, n - 1)
        if (to == cur) return db
        return move(db, id, p.uuid, to)
    }

    // ---------------------------------------------------------------- delete / recycle bin

    /** All group + entry ids under [g] including g itself. */
    fun allIds(g: Group): List<UUID> = buildList {
        add(g.uuid)
        g.entries.forEach { add(it.uuid) }
        g.groups.forEach { addAll(allIds(it)) }
    }

    /** ensureRecycleBin + reparent to root (writable primary load / import). */
    fun ensureRecycleBinAtRoot(db: KeePassDatabase, displayName: String, now: Instant = Instant.now()): KeePassDatabase =
        reparentRecycleBinToRoot(ensureRecycleBin(db, displayName, now), now)

    /**
     * Ensure Meta.recycleBinEnabled and a real Recycle Bin group exist.
     * Identity is [Meta.recycleBinUuid] (stable across locale renames).
     * [displayName] is applied so the tree shows the localized label without creating a second bin.
     */
    fun ensureRecycleBin(db: KeePassDatabase, displayName: String, now: Instant = Instant.now()): KeePassDatabase {
        var out = db
        val meta = out.content.meta
        if (!meta.recycleBinEnabled || meta.historyMaxItems != HISTORY_MAX) {
            out = out.modifyContent {
                copy(meta = meta.copy(
                    recycleBinEnabled = true,
                    historyMaxItems = HISTORY_MAX,
                    settingsChanged = now
                ))
            }
        }
        val existing = recycleBinId(out)?.let { findGroup(out, it) }
        if (existing != null) {
            if (existing.name != displayName) {
                out = renameGroup(out, existing.uuid, displayName, now)
            }
            return out
        }
        // Create bin under root with a fresh UUID and wire Meta.recycleBinUuid.
        val bin = Group(
            uuid = UUID.randomUUID(),
            name = displayName,
            icon = PredefinedIcon.TrashBin,
            times = TimeData.create(now),
            enableSearching = GroupOverride.Disabled,
            enableAutoType = GroupOverride.Disabled
        )
        out = addGroup(out, root(out).uuid, bin)
        out = out.modifyContent {
            copy(meta = this.meta.copy(recycleBinUuid = bin.uuid, recycleBinChanged = now, recycleBinEnabled = true))
        }
        return out
    }


    /**
     * If the recycle bin group exists but is not a direct child of root, reparent it to root
     * (preserves UUID, children, histories). Used on writable primary open to repair accidental moves.
     * Does not create a second bin by name.
     */
    fun reparentRecycleBinToRoot(db: KeePassDatabase, now: Instant = Instant.now()): KeePassDatabase {
        val binId = recycleBinId(db) ?: return db
        val r = root(db)
        val parent = parentOf(r, binId) ?: return db
        if (parent.uuid == r.uuid) return db
        // Internal relocate: detach bin group and append under root (bypass move()'s bin lock).
        val (detached, item) = detach(r, binId)
        val g = (item as? Item.G)?.group ?: return db
        val relocated = g.copy(
            times = (g.times ?: TimeData.create(now)).copy(locationChanged = now),
            previousParentGroup = parent.uuid
        )
        val nr = mapGroup(detached, r.uuid) { rootG ->
            rootG.copy(groups = rootG.groups + relocated)
        }
        return db.modifyContent { copy(group = nr) }
    }

    /** Move [id] into the recycle bin (creates bin if needed). Refuses root and the bin itself. */
    fun moveToBin(db: KeePassDatabase, id: UUID, displayName: String, now: Instant = Instant.now()): KeePassDatabase {
        var d = ensureRecycleBin(db, displayName, now)
        val bin = recycleBinId(d) ?: throw TreeException("recycle bin missing")
        if (id == root(d).uuid) throw TreeException("cannot delete the root")
        if (id == bin) throw TreeException("cannot delete the recycle bin")
        if (isInRecycleBin(d, id) && parentOf(root(d), id)?.uuid == bin) {
            // Already direct child of bin — no-op move
            return d
        }
        // Refuse moving bin into itself via descendant check in move()
        return move(d, id, bin, index = null, now = now)
    }

    /**
     * Soft-delete when recycle bin is enabled and item is not already in the bin;
     * otherwise permanent delete. Pair.second = true when moved to bin.
     */
    fun delete(db: KeePassDatabase, id: UUID, displayName: String = "Recycle Bin", now: Instant = Instant.now()): Pair<KeePassDatabase, Boolean> {
        if (db.content.meta.recycleBinEnabled && !isInRecycleBin(db, id)) {
            return moveToBin(db, id, displayName, now) to true
        }
        return deletePermanently(db, id, now) to false
    }

    fun deletePermanently(db: KeePassDatabase, id: UUID, now: Instant = Instant.now()): KeePassDatabase {
        val r = root(db)
        if (id == r.uuid) throw TreeException("cannot delete the root")
        val binId = recycleBinId(db)
        if (id == binId) throw TreeException("cannot delete the recycle bin itself; empty it instead")
        val (detached, item) = detach(r, id)
        item ?: throw TreeException("item not found")
        val ids = when (item) {
            is Item.G -> allIds(item.group)
            is Item.E -> listOf(item.entry.uuid)
        }
        return db.modifyContent {
            copy(
                group = detached,
                deletedObjects = deletedObjects + ids.map { DeletedObject(it, now) }
            )
        }
    }

    /**
     * Restore from recycle bin: prefer PreviousParentGroup when it still exists and is not inside the bin;
     * otherwise place under root. Clears previousParentGroup after restore.
     */
    fun restoreFromBin(db: KeePassDatabase, id: UUID, now: Instant = Instant.now()): KeePassDatabase {
        if (!isInRecycleBin(db, id)) throw TreeException("not in recycle bin")
        val bin = recycleBinId(db) ?: throw TreeException("recycle bin missing")
        if (id == bin) throw TreeException("cannot restore the recycle bin")
        val r = root(db)
        val prev: UUID? = when (val item = findItem(r, id)) {
            is Item.G -> item.group.previousParentGroup
            is Item.E -> item.entry.previousParentGroup
            null -> throw TreeException("item not found")
        }
        val target = when {
            prev == null -> r.uuid
            prev == bin -> r.uuid
            findGroup(r, prev) == null -> r.uuid
            isInRecycleBin(db, prev) -> r.uuid
            else -> prev
        }
        var out = move(db, id, target, index = null, now = now)
        // Clear previousParentGroup so a later delete records the post-restore parent.
        out = when {
            findEntry(out, id) != null -> modifyEntryParentLink(out, id, null, now)
            findGroup(out, id) != null -> modifyGroupParentLink(out, id, null, now)
            else -> out
        }
        return out
    }

    private fun findItem(g: Group, id: UUID): Item? {
        g.groups.firstOrNull { it.uuid == id }?.let { return Item.G(it) }
        g.entries.firstOrNull { it.uuid == id }?.let { return Item.E(it) }
        for (c in g.groups) findItem(c, id)?.let { return it }
        return null
    }

    private fun modifyEntryParentLink(db: KeePassDatabase, id: UUID, prev: UUID?, now: Instant): KeePassDatabase {
        val (parent, e) = findEntry(db, id) ?: return db
        val updated = e.copy(previousParentGroup = prev, times = touch(e.times, now))
        return db.withRoot { r ->
            mapGroup(r, parent.uuid) { g -> g.copy(entries = g.entries.map { if (it.uuid == id) updated else it }) }
        }
    }

    private fun modifyGroupParentLink(db: KeePassDatabase, id: UUID, prev: UUID?, now: Instant): KeePassDatabase =
        modifyGroup(db, id) { it.copy(previousParentGroup = prev, times = touch(it.times, now)) }

    /** Permanently delete every direct child of the recycle bin (and their descendants). */
    fun emptyRecycleBin(db: KeePassDatabase, now: Instant = Instant.now()): KeePassDatabase {
        val binId = recycleBinId(db) ?: return db
        val bin = findGroup(db, binId) ?: return db
        var out = db
        // Snapshot children first — deleting mutates the tree.
        val childIds = bin.groups.map { it.uuid } + bin.entries.map { it.uuid }
        for (cid in childIds) {
            out = deletePermanently(out, cid, now)
        }
        return out
    }

    fun recycleBinChildCount(db: KeePassDatabase): Int {
        val bin = recycleBinId(db)?.let { findGroup(db, it) } ?: return 0
        return bin.groups.size + bin.entries.size
    }

    // ---------------------------------------------------------------- tags & search

    /** Space-separated user input → tags (KDBX stores them ';'-joined). Order kept, duplicates dropped. */
    fun parseTags(s: String): List<String> = normalizeTags(s.split(Regex("[\\s;,\u3000]+")))

    fun normalizeTags(tags: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (t in tags) {
            val x = t.trim()
            if (x.isNotEmpty()) seen.add(x)
        }
        return seen.toList()
    }

    fun formatTags(tags: List<String>): String = tags.joinToString(" ")

    /** All tags used by entries (not history, not the recycle bin), most used first, then by name. */
    fun allTags(db: KeePassDatabase): List<String> {
        val count = HashMap<String, Int>()
        val bin = recycleBinId(db)
        fun walk(g: Group) {
            if (bin != null && g.uuid == bin) return
            g.entries.forEach { e -> e.tags.forEach { t -> count[t] = (count[t] ?: 0) + 1 } }
            g.tags.forEach { t -> count[t] = (count[t] ?: 0) + 1 }
            g.groups.forEach { walk(it) }
        }
        walk(root(db))
        return count.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    /** Case-insensitive search over title, user name, URL, notes and tags; "#tag" matches a tag exactly. */
    fun search(db: KeePassDatabase, query: String): List<Pair<Group, Entry>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("[\\s\u3000]+")).filter { it.isNotEmpty() }
        val bin = recycleBinId(db)
        val out = ArrayList<Pair<Group, Entry>>()
        fun matches(e: Entry, w: String): Boolean {
            if (w.startsWith("#") && w.length > 1) return e.tags.any { it.equals(w.substring(1), true) }
            return listOf(title(e), userName(e), url(e), notes(e)).any { it.contains(w, true) } ||
                e.tags.any { it.contains(w, true) }
        }
        fun walk(g: Group) {
            if (bin != null && g.uuid == bin) return
            g.entries.forEach { e -> if (words.all { matches(e, it) }) out.add(g to e) }
            g.groups.forEach { walk(it) }
        }
        walk(root(db))
        return out
    }

    /** Unique child name like Explorer: "新しいフォルダー", "新しいフォルダー (2)", … */
    fun uniqueName(existing: Collection<String>, base: String): String {
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    val ZERO: UUID = UUID(0, 0)
}
