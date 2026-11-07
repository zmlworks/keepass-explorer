package io.github.zmlworks.kpexplorer.db

/**
 * Tag text editing helpers shared by the key and new-key screens (pure, unit-testable).
 * Text is space-separated; the last token without a trailing space is "being typed".
 */
object TagEdit {
    /** (committed tags, partial token being typed). A partial equal to a used tag counts as committed. */
    fun state(s: String, used: List<String>): Pair<List<String>, String> {
        val toks = Tree.parseTags(s)
        val last = toks.lastOrNull().orEmpty()
        val partial = if (s.isEmpty() || s.last().isWhitespace() || s.last() in ",;\u3000" || last in used) "" else last
        return (if (partial.isEmpty()) toks else toks.dropLast(1)) to partial
    }

    /**
     * Toggle chip [t]. 0.4.5 fix: a typed-but-unfinished tag is KEPT (previously it was silently
     * dropped, so e.g. typing a 1-char tag and then tapping a chip lost it). Only when [t] starts
     * with the partial text is it treated as completing that partial.
     */
    fun toggle(s: String, used: List<String>, t: String): String {
        val (have, partial) = state(s, used)
        var base = have
        if (partial.isNotEmpty() && !t.startsWith(partial, ignoreCase = true)) base = Tree.normalizeTags(base + partial)
        val next = if (t in base) base - t else base + t
        return Tree.formatTags(next).let { if (it.isEmpty()) "" else "$it " }
    }
}
