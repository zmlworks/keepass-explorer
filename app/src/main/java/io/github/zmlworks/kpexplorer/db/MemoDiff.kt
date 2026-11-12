package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.models.Entry
import java.security.MessageDigest

/**
 * 0.4.17: history of an independent memo made by this app (Entry CustomData KPExplorer.Kind = memo), kept as
 * line diffs instead of full copies.
 *
 * Stored in Entry CustomData [CD] ("KPExplorer.MemoDiffs"); no standard KDBX <History> copies are made for these
 * memos by this app. The records are *reverse* diffs, newest first: record 0 turns the current Notes into the
 * text before the last save, record 1 turns that into the one before, and so on (at most [Tree.HISTORY_MAX]).
 * Each record carries a short SHA-256 of the text it applies to, so when another app has edited the memo the
 * chain is not applied to the wrong text: it is re-anchored on a standard History copy with that text (KeePass /
 * KeePassXC keep one when they edit), or else the older versions are shown as 「復元できません」.
 *
 * Text format (plain lines, values escaped with \\ \n \r):
 * ```
 * KPXD1
 * @<epoch ms> <hash16>
 * T<old title>          (only when the title changed in that save)
 * =<n>   keep n lines    -<n>  drop n lines    +<line>  insert a line
 * ```
 */
object MemoDiff {
    const val CD = "KPExplorer.MemoDiffs"
    private const val MAGIC = "KPXD1"

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

    fun isAppMemo(e: Entry): Boolean = e.customData[Order.CD_KIND]?.value == "memo"

    fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(16)

    private fun lines(s: String): List<String> = s.split("\n")

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

    /** For highlighting: indexes of lines of [older] that are not in [newer], and how many lines [newer] added. */
    fun changedLines(newer: String, older: String): Pair<Set<Int>, Int> {
        val ops = diff(newer, older); val changed = HashSet<Int>(); var k = 0; var added = 0
        for (o in ops) when (o) { is Op.Keep -> k += o.n; is Op.Del -> added += o.n; is Op.Ins -> { changed.add(k); k++ } }
        return changed to added
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

    fun records(e: Entry): List<Rec> = decode(e.customData[CD]?.value)

    /** Rebuilds the past versions of [e], newest first (see the class comment for re-anchoring). */
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

    /**
     * The new [CD] value after a save of [old] to [newTitle] / [newNotes] (null = leave as is). An earlier text with
     * an empty body (e.g. a just-created memo) under the same title is nothing to go back to, so it is not kept.
     */
    fun record(old: Entry, newTitle: String, newNotes: String, now: Long, max: Int = Tree.HISTORY_MAX): String? {
        val on = Tree.notes(old); val ot = Tree.title(old)
        if (on == newNotes && ot == newTitle) return null
        if (on.isEmpty() && ot == newTitle) return null
        val rec = Rec(now, hash(newNotes), ot.takeIf { it != newTitle }, if (on == newNotes) listOf(Op.Keep(lines(on).size)) else diff(newNotes, on))
        return encode((listOf(rec) + records(old)).take(max.coerceAtLeast(1)))
    }

    /** The new [CD] value without version [index] (1 = newest) or without any when null; null = remove the key. */
    fun delete(e: Entry, index: Int?): String? {
        if (index == null) return null
        val vs = versions(e)
        require(index in 1..vs.size) { "no such version" }
        val d = vs[index - 1]
        val kept = ArrayList<Rec>()
        for (v in vs) {
            if (v.index == index) continue
            val after = v.index == index + 1
            if (after && v.text != null && d.text != null && d.newer != null && v.newer == d.text) {
                // the deleted one sat between: diff straight from the deleted version's newer text
                val nt = d.newerTitle
                kept.add(Rec(v.at, hash(d.newer), v.title.takeIf { it != nt }, diff(d.newer, v.text)))
            } else kept.add(v.rec)
        }
        return if (kept.isEmpty()) null else encode(kept)
    }
}
