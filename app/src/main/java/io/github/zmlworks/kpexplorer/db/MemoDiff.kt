package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import java.security.MessageDigest
import java.time.Instant

/**
 * 0.4.18: history of an independent memo made by this app (Entry CustomData KPExplorer.Kind = memo).
 *
 * Every content edit adds an ordinary KDBX <History> copy of the memo as it was (same times, title, other
 * fields), except that its Notes hold only what changed instead of the whole text ([changeText]): per change one
 * line `- <before>` and one line `+ <after>`, each with one character of the unchanged text around it, newlines
 * shown as ↵, several changes separated by an empty line. No marker and no restoring: other KeePass apps just
 * see a history copy whose notes are that text.
 *
 * The viewer shows such copies as changes when the memo is an app memo and the copy has no field value besides
 * Title / Notes ([isChangeEntry]); anything else (other apps' copies, memos of other apps) is shown like a key's.
 *
 * The 0.4.17 format (CustomData KPExplorer.MemoDiffs, reverse line diffs) is converted once by [migrate].
 */
object MemoDiff {
    const val LEGACY_CD = "KPExplorer.MemoDiffs"
    private const val MAGIC = "KPXD1"
    const val NL = "↵"
    /** Characters of unchanged text kept on each side of a change. */
    const val CONTEXT = 1
    /** Above this (middle length product) the middle part is one change (no character diff). */
    private const val LCS_LIMIT = 2_000_000L

    fun isAppMemo(e: Entry): Boolean = e.customData[Order.CD_KIND]?.value == "memo"

    /** A history copy of an app memo that holds only Title / Notes (shown as a change, not as a key-like copy). */
    fun isChangeEntry(memo: Entry, snap: Entry): Boolean =
        isAppMemo(memo) && snap.fields.all { (k, v) -> k == "Title" || k == "Notes" || v.content.isEmpty() }

    private fun cps(s: String): IntArray = s.codePoints().toArray()
    private fun str(a: IntArray, from: Int, to: Int): String = String(a, from, to - from)
    private fun vis(s: String) = s.replace("\r\n", "\n").replace("\n", NL)

    /**
     * What changed from [old] to [new], e.g. 「卵3個」→「卵2個」 = "- 卵3個\n+ 卵2個". Empty when nothing changed.
     * Character diff (code points) of the part between the common head and tail; changes whose unchanged gap is at
     * most 2 × [CONTEXT] characters are joined. A side that is empty (text added to / removed from an empty memo)
     * has no line.
     */
    fun changeText(old: String, new: String): String {
        if (old == new) return ""
        val a = cps(old); val b = cps(new)
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        // edits as (old range, new range) in full coordinates
        val edits = ArrayList<IntArray>()
        val n = a.size - p - s; val m = b.size - p - s
        if (n.toLong() * m > LCS_LIMIT || n == 0 || m == 0) edits.add(intArrayOf(p, p + n, p, p + m))
        else {
            val L = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
                L[i][j] = if (a[p + i] == b[p + j]) L[i + 1][j + 1] + 1 else maxOf(L[i + 1][j], L[i][j + 1])
            var i = 0; var j = 0; var oi = -1; var oj = -1
            fun close() { if (oi >= 0) { edits.add(intArrayOf(p + oi, p + i, p + oj, p + j)); oi = -1 } }
            while (i < n || j < m) {
                if (i < n && j < m && a[p + i] == b[p + j]) { close(); i++; j++; continue }
                if (oi < 0) { oi = i; oj = j }
                if (j < m && (i == n || L[i][j + 1] >= L[i + 1][j])) j++ else i++
            }
            close()
        }
        // join edits that are close together
        val hunks = ArrayList<IntArray>()
        for (e in edits) {
            val l = hunks.lastOrNull()
            if (l != null && e[0] - l[1] <= 2 * CONTEXT) { l[1] = e[1]; l[3] = e[3] } else hunks.add(e.copyOf())
        }
        return hunks.joinToString("\n\n") { h ->
            val before = str(a, maxOf(0, h[0] - CONTEXT), minOf(a.size, h[1] + CONTEXT))
            val after = str(b, maxOf(0, h[2] - CONTEXT), minOf(b.size, h[3] + CONTEXT))
            listOfNotNull(before.takeIf { it.isNotEmpty() }?.let { "- " + vis(it) }, after.takeIf { it.isNotEmpty() }?.let { "+ " + vis(it) })
                .joinToString("\n")
        }
    }

    /**
     * The History copy for a save of app memo [old] to [newTitle] / [newNotes], or null when nothing is worth
     * keeping: no change of title or text, or a first text written into an empty memo (same title).
     */
    fun changeEntry(old: Entry, newTitle: String, newNotes: String): Entry? {
        val on = Tree.notes(old); val ot = Tree.title(old)
        if (on == newNotes && ot == newTitle) return null
        if (on.isEmpty() && ot == newTitle) return null
        return old.copy(history = listOf(), customData = old.customData - LEGACY_CD,
            fields = old.fields + ("Notes" to EntryValue.Plain(changeText(on, newNotes))))
    }

    /**
     * Converts 0.4.17 data (CustomData [LEGACY_CD]) of [e] into change copies in <History> and removes the
     * CustomData. Versions that can't be rebuilt are dropped. null = nothing to do.
     */
    fun migrate(e: Entry): Entry? {
        if (e.customData[LEGACY_CD] == null) return null
        val recs = records(e)
        val vs = runCatching { versions(e) }.getOrDefault(emptyList())
        val made = ArrayList<Entry>()
        for ((k, v) in vs.withIndex()) {
            val text = v.text ?: continue; val newer = v.newer ?: continue
            // version k was written by the save of record k+1 (the oldest one: just before its replacement)
            val at = recs.getOrNull(k + 1)?.at?.let { Instant.ofEpochMilli(it) } ?: Instant.ofEpochMilli(v.at - 1000)
            val t = e.times?.copy(lastModificationTime = at, lastAccessTime = at)
            made.add(e.copy(history = listOf(), customData = e.customData - LEGACY_CD, times = t,
                fields = e.fields + ("Title" to EntryValue.Plain(v.title)) + ("Notes" to EntryValue.Plain(changeText(text, newer)))))
        }
        val all = (e.history + made).sortedBy { it.times?.lastModificationTime ?: Instant.MIN }.takeLast(Tree.HISTORY_MAX)
        return e.copy(customData = e.customData - LEGACY_CD, history = all)
    }

    // ------------------------------------------------------------ 0.4.17 format (read only, for [migrate])

    fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(16)
    private fun lines(s: String): List<String> = s.split("\n")

    sealed class Op {
        data class Keep(val n: Int) : Op()
        data class Del(val n: Int) : Op()
        data class Ins(val line: String) : Op()
    }

    data class Rec(val at: Long, val base: String, val oldTitle: String?, val ops: List<Op>)

    /** One past version for the viewer, newest first. [text] null = cannot be rebuilt (chain broken). */
    data class Version(
        val index: Int, val at: Long, val title: String, val newerTitle: String,
        val text: String?, val newer: String?, val anchored: Boolean, val rec: Rec,
    )

    /** Ops that turn [from] into [to] (line based; common head / tail trimmed, LCS in between). */
    fun diff(from: String, to: String): List<Op> {
        val a = lines(from); val b = lines(to)
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val am = a.subList(p, a.size - s); val bm = b.subList(p, b.size - s)
        val ops = ArrayList<Op>()
        fun keep(n: Int) { if (n <= 0) return; val l = ops.lastOrNull(); if (l is Op.Keep) ops[ops.size - 1] = Op.Keep(l.n + n) else ops.add(Op.Keep(n)) }
        fun del(n: Int) { if (n <= 0) return; val l = ops.lastOrNull(); if (l is Op.Del) ops[ops.size - 1] = Op.Del(l.n + n) else ops.add(Op.Del(n)) }
        keep(p)
        if (am.size.toLong() * bm.size > 4_000_000L) {
            del(am.size); bm.forEach { ops.add(Op.Ins(it)) }
        } else {
            val n = am.size; val m = bm.size
            val L = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
                L[i][j] = if (am[i] == bm[j]) L[i + 1][j + 1] + 1 else maxOf(L[i + 1][j], L[i][j + 1])
            var i = 0; var j = 0
            while (i < n || j < m) {
                when {
                    i < n && j < m && am[i] == bm[j] -> { keep(1); i++; j++ }
                    j < m && (i == n || L[i][j + 1] >= L[i + 1][j]) -> { ops.add(Op.Ins(bm[j])); j++ }
                    else -> { del(1); i++ }
                }
            }
        }
        keep(s)
        return ops
    }

    /** Applies [ops] to [from]; throws when they do not fit. */
    fun apply(from: String, ops: List<Op>): String {
        val a = lines(from); val out = ArrayList<String>(); var i = 0
        for (o in ops) when (o) {
            is Op.Keep -> { require(i + o.n <= a.size); out.addAll(a.subList(i, i + o.n)); i += o.n }
            is Op.Del -> { require(i + o.n <= a.size); i += o.n }
            is Op.Ins -> out.add(o.line)
        }
        require(i == a.size) { "diff does not fit" }
        return out.joinToString("\n")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    private fun unesc(s: String): String {
        val b = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { b.append(when (s[i + 1]) { 'n' -> '\n'; 'r' -> '\r'; else -> s[i + 1] }); i += 2 }
            else { b.append(c); i++ }
        }
        return b.toString()
    }

    fun encode(recs: List<Rec>): String = buildString {
        append(MAGIC)
        for (r in recs) {
            append("\n@").append(r.at).append(' ').append(r.base)
            r.oldTitle?.let { append("\nT").append(esc(it)) }
            for (o in r.ops) when (o) {
                is Op.Keep -> append("\n=").append(o.n)
                is Op.Del -> append("\n-").append(o.n)
                is Op.Ins -> append("\n+").append(esc(o.line))
            }
        }
    }

    /** Tolerant: an unreadable value gives no records (the memo itself is never touched). */
    fun decode(s: String?): List<Rec> {
        if (s.isNullOrEmpty()) return emptyList()
        val ls = s.replace("\r\n", "\n").split("\n")
        if (ls.firstOrNull() != MAGIC) return emptyList()
        val out = ArrayList<Rec>()
        var at = 0L; var base = ""; var title: String? = null; var ops = ArrayList<Op>(); var open = false
        fun flush() { if (open) out.add(Rec(at, base, title, ops)) }
        return runCatching {
            for (l in ls.drop(1)) {
                if (l.isEmpty()) continue
                when (l[0]) {
                    '@' -> { flush(); val p = l.substring(1).split(' '); at = p[0].toLong(); base = p.getOrElse(1) { "" }; title = null; ops = ArrayList(); open = true }
                    'T' -> title = unesc(l.substring(1))
                    '=' -> ops.add(Op.Keep(l.substring(1).toInt()))
                    '-' -> ops.add(Op.Del(l.substring(1).toInt()))
                    '+' -> ops.add(Op.Ins(unesc(l.substring(1))))
                }
            }
            flush(); out as List<Rec>
        }.getOrDefault(emptyList())
    }

    fun records(e: Entry): List<Rec> = decode(e.customData[LEGACY_CD]?.value)

    /** Rebuilds the 0.4.17 versions of [e], newest first (re-anchored on a standard History copy when another app edited it). */
    fun versions(e: Entry): List<Version> {
        val out = ArrayList<Version>()
        var cur: String? = Tree.notes(e); var curTitle = Tree.title(e)
        for ((i, r) in records(e).withIndex()) {
            var anchored = false
            if (cur != null && hash(cur) != r.base) {
                val anc = e.history.asReversed().firstOrNull { hash(Tree.notes(it)) == r.base }
                if (anc != null) { cur = Tree.notes(anc); curTitle = Tree.title(anc); anchored = true } else cur = null
            }
            val older = cur?.let { c -> runCatching { apply(c, r.ops) }.getOrNull() }
            val t = r.oldTitle ?: curTitle
            out.add(Version(i + 1, r.at, t, curTitle, older, if (older != null) cur else null, anchored, r))
            cur = older; curTitle = t
        }
        return out
    }

}
