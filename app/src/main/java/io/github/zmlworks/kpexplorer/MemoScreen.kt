package io.github.zmlworks.kpexplorer

import android.text.SpannableString
import android.text.TextUtils
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import io.github.zmlworks.kpexplorer.db.MemoDiff
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.HistoryViewer
import io.github.zmlworks.kpexplorer.ui.MemoDiffViewer
import io.github.zmlworks.kpexplorer.ui.Parts
import io.github.zmlworks.kpexplorer.ui.W
import java.util.UUID

/**
 * Memo window. 0.4.17: no back button in the bar; the title is shown in bold and is not edited here (名前の変更
 * in the file menu renames); 「- 差分」 at the right end opens the diff history (MemoDiffViewer) of a memo made by
 * this app, 「- 履歴」 the standard History viewer of other memos. A ruled notebook page, 戻る / 保存 at the bottom.
 */
class MemoScreen(private val a: MainActivity, private val entryId: UUID) : MainActivity.Screen {
    private val ctx = a
    private val root = W.vbox(ctx).apply { setBackgroundColor(W.FACE) }
    override val view: View get() = root
    val title: TextView = W.text(ctx, "", 18f, true, W.HILITE).apply {
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(W.dp(8), 0, W.dp(4), 0)
    }
    val text = Parts.RuledText(ctx, editable = true)
    /** This app's independent memo (diff history) or a memo-like entry of another app (standard History). */
    val appMemo: Boolean
    /** 「- 差分」 / 「- 履歴」 at the right end of the title bar. */
    val historyLink: TextView
    /** Unsaved = the text differs from what was loaded or last saved (see [KeyScreen.dirty]). */
    var dirty: Boolean
        get() = text.text.toString() != baseline
        set(v) { if (!v) baseline = text.text.toString() }
    private var baseline: String? = null

    init {
        val e = Session.db?.let { Tree.findEntry(it, entryId)?.second }
        appMemo = e != null && MemoDiff.isAppMemo(e)
        title.text = e?.let { Tree.title(it) }.orEmpty().ifEmpty { "(無題)" }
        text.setText(e?.let { Tree.notes(it) }.orEmpty())
        val bar = Parts.appBar(ctx, "", onBack = { onBack() }, titleView = title, back = false)
        val word = if (appMemo) "差分" else "履歴"
        historyLink = W.text(ctx, "", 15f, true, W.HILITE).apply {
            text = SpannableString("- $word").apply { setSpan(UnderlineSpan(), 2, length, 0) }
            contentDescription = word; isClickable = true; gravity = Gravity.CENTER
            setPadding(W.dp(10), W.dp(8), W.dp(10), W.dp(8))
            setOnClickListener { openHistory() }
        }
        bar.bar.addView(historyLink, W.lp(W.WRAP, W.WRAP, l = 4))
        root.addView(bar.bar, W.lp())
        root.addView(text, W.lp(W.MATCH, 0, 1f))
        root.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px))
        root.addView(Parts.footer(ctx, onBack = { onBack() }, onSave = { save() }), W.lp())
        dirty = false
    }

    fun openHistory(): ClassicDialog? =
        if (appMemo) MemoDiffViewer.show(ctx, entryId) else HistoryViewer.show(ctx, entryId)

    fun save(): Boolean {
        val e = Session.db?.let { Tree.findEntry(it, entryId)?.second } ?: return false
        val vals = HashMap<String, String>()
        e.fields.forEach { (k, v) -> vals[k] = v.content }
        vals["Notes"] = text.text.toString()
        try { Session.update { Order.saveEntry(it, entryId, vals, e.tags, null) }; Session.flush() }
        catch (t: Throwable) { Dlg.message(ctx, "保存", "保存できませんでした。\n$t", 'x'); return false }
        dirty = false
        Toast.makeText(ctx, "保存しました", Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onBack(): Boolean {
        if (!dirty) { a.showExplorer(); return true }
        KeyScreen.confirmUnsaved(ctx, { if (save()) a.showExplorer() }, { dirty = false; a.showExplorer() })
        return true
    }
}
