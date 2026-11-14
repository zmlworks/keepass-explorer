package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue

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
 */
object MemoDiff {
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
        return old.copy(history = listOf(),
            fields = old.fields + ("Notes" to EntryValue.Plain(changeText(on, newNotes))))
    }
}
