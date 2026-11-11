package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import java.time.Instant
import java.util.UUID

/**
 * 0.3.0 model on top of plain KDBX (stays readable by KeePass / KeePassXC):
 *  - kinds: folder (group), key (entry), memo (entry with CustomData KPExplorer.Kind=memo)
 *  - mixed folder/key/memo order per folder: Group CustomData "KPExplorer.Order" = UUIDs (desktop.ini-like,
 *    one per folder, travels with the folder). Unlisted items follow in KDBX order (groups, then entries).
 *  - per-key field layout: Entry CustomData "KPExplorer.Fields" = field names in display order,
 *    hidden ones prefixed with '-'.
 */
object Order {
    const val CD_ORDER = "KPExplorer.Order"
    const val CD_KIND = "KPExplorer.Kind"
    const val CD_FIELDS = "KPExplorer.Fields"
    const val OTP = "otp"
    /** Entry CustomData: custom field names shown as multi-line (memo-like), one per line. */
    const val CD_MULTI = "KPExplorer.Multiline"

    fun multiline(e: Entry): Set<String> = e.customData[CD_MULTI]?.value.orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()

    /** Field keys of the key screen; custom string fields use their own name. */
    const val F_TITLE = "Title"; const val F_USER = "UserName"; const val F_PASS = "Password"
    const val F_URL = "URL"; const val F_TAGS = "@Tags"; const val F_OTP = "@OTP"; const val F_NOTES = "Notes"
    /** 0.4.5: Tag sits below Memo by default. */
    val DEFAULT_LAYOUT = listOf(F_TITLE, F_USER, F_PASS, F_URL, F_OTP, F_NOTES, F_TAGS)
    /** Pre-0.4.5 default; a stored layout exactly equal to it (all visible) is read as the new default. */
    val OLD_DEFAULT_LAYOUT = listOf(F_TITLE, F_USER, F_PASS, F_URL, F_TAGS, F_OTP, F_NOTES)
    val STANDARD = setOf("Title", "UserName", "Password", "URL", "Notes")

    enum class Kind { FOLDER, KEY, MEMO }

    sealed class Node {
        abstract val uuid: UUID
        abstract val kind: Kind
        abstract val name: String
        data class F(val g: Group) : Node() { override val uuid get() = g.uuid; override val kind get() = Kind.FOLDER; override val name get() = g.name }
        data class E(val e: Entry) : Node() {
            override val uuid get() = e.uuid
            override val kind get() = if (isMemo(e)) Kind.MEMO else Kind.KEY
            override val name get() = Tree.title(e)
        }
    }

    fun isMemo(e: Entry): Boolean = when (e.customData[CD_KIND]?.value) {
        "memo" -> true
        "key" -> false
        else -> e.icon == PredefinedIcon.Notepad && Tree.password(e).isEmpty() && Tree.userName(e).isEmpty()
    }

    fun storedOrder(g: Group): List<UUID> =
        g.customData[CD_ORDER]?.value.orEmpty().split(',').mapNotNull { s -> s.trim().takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() } }

    /** Children of [g] in display order. */
    fun children(g: Group): List<Node> {
        val all = LinkedHashMap<UUID, Node>()
        g.groups.forEach { all[it.uuid] = Node.F(it) }
        g.entries.forEach { all[it.uuid] = Node.E(it) }
        val out = ArrayList<Node>(all.size)
        for (id in storedOrder(g)) all.remove(id)?.let { out.add(it) }
        out.addAll(all.values)
        return out
    }

    private fun withOrder(g: Group, ids: List<UUID>, now: Instant): Group =
        g.copy(customData = g.customData + (CD_ORDER to CustomDataValue(ids.joinToString(","), now)))

    /**
     * Move [id] into folder [target] at display position [index] (null = end). Works within the same folder
     * (reorder) and across folders. Throws [Tree.TreeException] for a folder into itself/descendant.
     */
    fun moveTo(db: KeePassDatabase, id: UUID, target: UUID, index: Int? = null, now: Instant = Instant.now()): KeePassDatabase {
        val root = Tree.root(db)
        val bin = Tree.recycleBinId(db)
        if (bin != null && id == bin) throw Tree.TreeException("cannot move the recycle bin")
        val from = Tree.parentOf(root, id) ?: throw Tree.TreeException("item not found")
        var d = if (from.uuid == target) db else Tree.move(db, id, target, now = now)
        val tg = Tree.findGroup(d, target) ?: throw Tree.TreeException("target not found")
        val ids = children(tg).map { it.uuid }.filter { it != id }.toMutableList()
        // index counts positions in the list without the moved item
        ids.add((index ?: ids.size).coerceIn(0, ids.size), id)
        d = Tree.modifyGroup(d, target) { withOrder(it, ids, now) }
        if (from.uuid != target) {
            val fg = Tree.findGroup(d, from.uuid)
            if (fg != null && fg.customData.containsKey(CD_ORDER))
                d = Tree.modifyGroup(d, from.uuid) { withOrder(it, storedOrder(it).filter { x -> x != id }, now) }
        }
        return d
    }

    /** Insert a new child at the end of the display order. */
    fun appendGroup(db: KeePassDatabase, parent: UUID, g: Group): KeePassDatabase =
        moveTo(Tree.addGroup(db, parent, g), g.uuid, parent)

    fun appendEntry(db: KeePassDatabase, parent: UUID, e: Entry): KeePassDatabase =
        moveTo(Tree.addEntry(db, parent, e), e.uuid, parent)

    fun uniqueChildName(g: Group, base: String): String {
        val names = children(g).map { it.name }.toSet()
        if (base !in names) return base
        var i = 2
        while ("$base$i" in names) i++
        return "$base$i"
    }

    fun newMemo(title: String, now: Instant = Instant.now()): Entry =
        Entry(uuid = UUID.randomUUID(), icon = PredefinedIcon.Notepad, times = TimeData.create(now),
            fields = EntryFields.createDefault() + (BasicField.Title() to EntryValue.Plain(title)),
            customData = mapOf(CD_KIND to CustomDataValue("memo", now)))

    fun newKey(now: Instant = Instant.now()): Entry =
        Entry(uuid = UUID.randomUUID(), icon = PredefinedIcon.Key, times = TimeData.create(now),
            fields = EntryFields.createDefault(), customData = mapOf(CD_KIND to CustomDataValue("key", now)))

    // ---------------------------------------------------------------- fields

    fun customFieldNames(e: Entry): List<String> = e.fields.keys.filter { it !in STANDARD && it != OTP && !isOtpExtra(it) }

    /** (key, visible) for every displayable field, in the stored order; new custom fields are appended. */
    fun layout(e: Entry): List<Pair<String, Boolean>> {
        val stored = e.customData[CD_FIELDS]?.value?.split('\n')?.filter { it.isNotEmpty() }
            ?.let { if (it == OLD_DEFAULT_LAYOUT) DEFAULT_LAYOUT else it }
        val base = stored?.map { if (it.startsWith("-")) it.substring(1) to false else it to true } ?: DEFAULT_LAYOUT.map { it to true }
        val known = base.map { it.first }.toSet()
        // with a stored layout, a standard field the user deleted stays deleted unless it has content (e.g. edited elsewhere)
        val std = if (stored == null) DEFAULT_LAYOUT else DEFAULT_LAYOUT.filter { hasContent(e, it) }
        val extra = (std + customFieldNames(e)).filter { it !in known }.map { it to true }
        val customs = customFieldNames(e).toSet()
        return (base + extra).filter { (k, _) -> k in DEFAULT_LAYOUT || k in customs }
    }

    /** Whether a layout key has something to show (standard field, @Tags, @OTP). */
    fun hasContent(e: Entry, key: String): Boolean = when (key) {
        F_TITLE -> true
        F_TAGS -> e.tags.isNotEmpty()
        F_OTP -> otpUri(e) != null
        else -> string(e, key).isNotEmpty()
    }

    fun encodeLayout(l: List<Pair<String, Boolean>>): String = l.joinToString("\n") { (k, v) -> if (v) k else "-$k" }

    fun string(e: Entry, key: String): String = e.fields[key]?.content.orEmpty()

    fun otpUri(e: Entry): String? = otpUri { string(e, it) }

    /** OTP-related string fields written by other KeePass apps (kept as they are, not shown as custom fields). */
    fun isOtpExtra(k: String) = k.startsWith("TimeOtp-") || k.startsWith("TOTP ")

    /**
     * The TOTP of an entry as an otpauth:// URI, from (in this order) the KeePassXC "otp" field, KeePass 2.47+
     * TimeOtp-Secret-Base32 / -Hex / -Secret (+ -Period, -Length, -Algorithm), or the legacy KeePassXC
     * "TOTP Seed" + "TOTP Settings" ("30;6"). null when the entry has no TOTP.
     */
    fun otpUri(get: (String) -> String): String? {
        get(OTP).trim().takeIf { it.isNotEmpty() }?.let { return it }
        val secret: ByteArray? = runCatching {
            get("TimeOtp-Secret-Base32").trim().takeIf { it.isNotEmpty() }?.let { Totp.base32Decode(it) }
                ?: get("TimeOtp-Secret-Hex").trim().takeIf { it.isNotEmpty() }?.let { h ->
                    val c = h.filter { !it.isWhitespace() }; require(c.length % 2 == 0)
                    ByteArray(c.length / 2) { i -> c.substring(2 * i, 2 * i + 2).toInt(16).toByte() } }
                ?: get("TimeOtp-Secret").takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        }.getOrNull()
        if (secret != null && secret.isNotEmpty()) {
            val period = get("TimeOtp-Period").trim().toIntOrNull() ?: 30
            val digits = get("TimeOtp-Length").trim().toIntOrNull() ?: 6
            val alg = when (get("TimeOtp-Algorithm").trim().uppercase()) { "HMAC-SHA-256" -> "SHA256"; "HMAC-SHA-512" -> "SHA512"; else -> "SHA1" }
            return runCatching { Totp.toUri(Totp.Params(secret, digits, period, alg)) }.getOrNull()
        }
        val seed = get("TOTP Seed").trim().takeIf { it.isNotEmpty() } ?: return null
        val st = get("TOTP Settings").split(';')
        val period = st.getOrNull(0)?.trim()?.toIntOrNull() ?: 30
        val digits = st.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 6..10 } ?: 6
        return runCatching { Totp.toUri(Totp.Params(Totp.base32Decode(seed), digits, period)) }.getOrNull()
    }

    /**
     * Rewrite an entry's editable content in place (history + mtime like [Tree.updateEntry]).
     * [values] maps field names (standard + custom + "otp") to text; missing customs are removed.
     */
    fun saveEntry(db: KeePassDatabase, id: UUID, values: Map<String, String>, tags: List<String>, layout: List<Pair<String, Boolean>>?,
                  now: Instant = Instant.now(), cdSet: Map<String, String?> = emptyMap(), icon: PredefinedIcon? = null,
                  removed: Set<String> = emptySet()): KeePassDatabase {
        val (parent, old) = Tree.findEntry(db, id) ?: throw Tree.TreeException("entry not found")
        val protect = setOf("Password", OTP)
        // OTP fields of other apps are kept unless [removed] names them (OTP 「削除」 on the key screen).
        val keep = old.fields.filterKeys { k -> k in STANDARD || (isOtpExtra(k) && k !in removed) }
        val m = LinkedHashMap<String, EntryValue>()
        keep.forEach { (k, v) -> m[k] = v }
        // a field that was protected in the file stays protected (e.g. KeePass TimeOtp-Secret-*, custom PINs)
        for ((k, v) in values) if (k !in removed)
            m[k] = if (k in protect || old.fields[k] is EntryValue.Encrypted) EntryValue.Encrypted(EncryptedValue.fromString(v)) else EntryValue.Plain(v)
        if (values[OTP].isNullOrEmpty()) m.remove(OTP)
        val newFields = EntryFields(m)
        var cd = if (layout != null) old.customData + (CD_FIELDS to CustomDataValue(encodeLayout(layout), now)) else old.customData
        for ((k, v) in cdSet) cd = if (v.isNullOrEmpty()) cd - k else if (cd[k]?.value == v) cd else cd + (k to CustomDataValue(v, now))
        val newIcon = icon ?: old.icon
        val nt = Tree.normalizeTags(tags)
        val same = old.fields.keys == newFields.keys && old.fields.all { (k, v) -> newFields[k]?.content == v.content } &&
            old.tags == nt && old.customData.mapValues { it.value.value } == cd.mapValues { it.value.value } && old.icon == newIcon
        if (same) return db
        val max = db.content.meta.historyMaxItems.let { if (it < 0 || it > Tree.HISTORY_MAX) Tree.HISTORY_MAX else it }
        var hist = old.history + old.copy(history = listOf())
        if (max >= 0) hist = hist.takeLast(max)
        val t = (old.times ?: TimeData.create(now)).copy(lastModificationTime = now, lastAccessTime = now)
        val upd = old.copy(fields = newFields, tags = nt, customData = cd, history = hist, times = t, icon = newIcon)
        return Tree.modifyGroup(db, parent.uuid) { g -> g.copy(entries = g.entries.map { if (it.uuid == id) upd else it }) }
    }

    fun renameEntry(db: KeePassDatabase, id: UUID, title: String): KeePassDatabase {
        val (_, e) = Tree.findEntry(db, id) ?: throw Tree.TreeException("entry not found")
        val vals = HashMap<String, String>()
        e.fields.forEach { (k, v) -> vals[k] = v.content }
        vals["Title"] = title
        return saveEntry(db, id, vals, e.tags, null)
    }

}
