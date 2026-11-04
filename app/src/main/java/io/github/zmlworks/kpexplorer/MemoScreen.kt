package io.github.zmlworks.kpexplorer

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Parts
import io.github.zmlworks.kpexplorer.ui.W
import java.util.UUID

/** Memo window: only the title (in the bar) and a ruled notebook page. */
class MemoScreen(private val a: MainActivity, private val entryId: UUID) : MainActivity.Screen {
    private val ctx = a
    private val root = W.vbox(ctx).apply { setBackgroundColor(W.FACE) }
    override val view: View get() = root
    val title = W.field(ctx, "タイトル").apply { textSize = 17f }
    val text = Parts.RuledText(ctx, editable = true)
    var dirty = false
        private set

    init {
        val e = Session.db?.let { Tree.findEntry(it, entryId)?.second }
        title.setText(e?.let { Tree.title(it) }.orEmpty())
        text.setText(e?.let { Tree.notes(it) }.orEmpty())
        val bar = Parts.appBar(ctx, "", onBack = { onBack() }, titleView = title)
        bar.bar.addView(Parts.barButton(ctx, "保存", "保存") { save() }, W.lp(W.WRAP, W.dp(38), l = 4))
        root.addView(bar.bar, W.lp())
        root.addView(text, W.lp(W.MATCH, 0, 1f))
        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { dirty = true }
        }
        title.addTextChangedListener(w); text.addTextChangedListener(w)
    }

    fun save(): Boolean {
        val e = Session.db?.let { Tree.findEntry(it, entryId)?.second } ?: return false
        val vals = HashMap<String, String>()
        e.fields.forEach { (k, v) -> vals[k] = v.content }
        vals["Title"] = title.text.toString()
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
