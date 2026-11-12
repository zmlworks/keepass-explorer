package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import app.keemobile.kotpass.models.Entry
import io.github.zmlworks.kpexplorer.KeyFields
import io.github.zmlworks.kpexplorer.Session
import io.github.zmlworks.kpexplorer.db.MemoDiff
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import java.util.UUID

/**
 * 0.4.18: History of an app memo, newest first. Copies that hold only Title / Notes are this app's change records
 * ([MemoDiff.changeText]) and are shown as the change (「- 」 lines red, 「+ 」 lines green); any other copy (e.g. one
 * left by another app) is shown like a key's. Per-item 「削除」 and 「全て削除」, confirmed every time; deleting
 * makes no history and keeps Last Modified. Read-only: no deleting.
 */
object MemoDiffViewer {
    const val MINUS = 0xFFB00000.toInt()
    const val PLUS = 0xFF006400.toInt()

    var current: ClassicDialog? = null
        private set

    /** The change text of a copy, coloured (tests read it). */
    fun colored(notes: String): CharSequence {
        if (notes.isEmpty()) return "(本文の変更なし)"
        val sb = SpannableStringBuilder()
        val ls = notes.split("\n")
        for ((i, l) in ls.withIndex()) {
            val st = sb.length
            sb.append(l)
            when {
                l.startsWith("- ") -> sb.setSpan(ForegroundColorSpan(MINUS), st, sb.length, 0)
                l.startsWith("+ ") -> sb.setSpan(ForegroundColorSpan(PLUS), st, sb.length, 0)
            }
            if (i < ls.size - 1) sb.append('\n')
        }
        return sb
    }

    fun show(ctx: Context, entryId: UUID, onChanged: () -> Unit = {}): ClassicDialog? {
        val e = Tree.findEntry(Session.db ?: return null, entryId)?.second ?: return null
        val hist = e.history
        val ro = Session.readOnlyTemp
        val title = "差分 — ${Tree.title(e).ifEmpty { "(無題)" }}"
        val body = W.vbox(ctx)
        lateinit var dlg: ClassicDialog
        fun delete(index: Int?) {
            val what = if (index == null) "このメモの履歴を全て (${hist.size} 件) 削除します。\n元に戻せません。よろしいですか?"
                else "履歴 #$index (${time(hist[hist.size - index])}) を削除します。\n元に戻せません。よろしいですか?"
            Dlg.confirm(ctx, if (index == null) "履歴を全て削除" else "履歴の削除", what, '!', yes = "削除", no = "キャンセル") {
                Session.update { Order.deleteHistory(it, entryId, index) }
                Session.flush()
                dlg.dismiss(); onChanged(); show(ctx, entryId, onChanged)
            }
        }
        body.addView(W.text(ctx,
            if (hist.isEmpty()) "履歴はありません (本文やタイトルを変えて保存したときに、変わった所だけを最大 ${Tree.HISTORY_MAX} 件まで残します)。"
            else "新しい順 (最大 ${Tree.HISTORY_MAX} 件)。変更前 (−、赤) と変更後 (＋、緑) を、前後 1 文字と一緒に表示します。↵ は改行です。日時は変更前の内容を保存した日時です。" +
                if (ro) "読取専用です。" else "",
            13f).apply { setPadding(0, 0, 0, W.dp(8)) }, W.lp(W.MATCH, W.WRAP))
        val buttons = ArrayList<ClassicDialog.Btn>()
        if (hist.isNotEmpty() && !ro) buttons.add(ClassicDialog.Btn("全て削除") { delete(null); false })
        buttons.add(ClassicDialog.Btn("閉じる", true))
        if (hist.isEmpty()) {
            dlg = ClassicDialog(ctx, title, body, buttons)
            return dlg.also { current = it; it.show() }
        }
        val list = W.vbox(ctx)
        val rows = HistoryViewer.rows(e)
        for ((k, snap) in hist.asReversed().withIndex()) {
            val index = k + 1
            val onDelete: (() -> Unit)? = if (ro) null else ({ delete(index) })
            // the title it was renamed to: the next newer copy's, or the memo's own
            val newerTitle = if (k == 0) Tree.title(e) else Tree.title(hist[hist.size - k])
            val card = if (MemoDiff.isChangeEntry(e, snap)) changeCard(ctx, index, snap, newerTitle, onDelete)
                else HistoryViewer.snapCard(ctx, rows[k], onDelete)
            list.addView(card, W.lp(W.MATCH, W.WRAP, b = 8))
        }
        val sv = ScrollView(ctx).apply { W.classicScrollbars(this); addView(list) }
        body.addView(sv, W.lp(W.MATCH, W.dp(400)))
        dlg = ClassicDialog(ctx, title, body, buttons, scroll = false)
        return dlg.also { current = it; it.show() }
    }

    private fun time(e: Entry) = e.times?.lastModificationTime?.let { KeyFields.fmt.format(it) } ?: "-"

    private fun changeCard(ctx: Context, index: Int, snap: Entry, newerTitle: String, onDelete: (() -> Unit)?): LinearLayout {
        val card = W.vbox(ctx).apply {
            background = W.Bevel(W.Style.THIN_RAISED, W.FACE)
            setPadding(W.dp(10), W.dp(8), W.dp(10), W.dp(8))
            tag = "change-$index"
        }
        val head = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(W.text(ctx, "#$index  ${time(snap)}", 14f, true, W.NAVY), W.lp(0, W.WRAP, 1f))
        if (onDelete != null) head.addView(W.text(ctx, "削除", 13f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON); isClickable = true
            contentDescription = "履歴 #$index を削除"; tag = "history-delete-$index"
            setPadding(W.dp(14), W.dp(6), W.dp(14), W.dp(6)); setOnClickListener { onDelete() }
        }, W.lp(W.WRAP, W.WRAP, l = 8))
        card.addView(head, W.lp(W.MATCH, W.WRAP))
        val t = Tree.title(snap)
        if (t != newerTitle) card.addView(W.text(ctx, "タイトル: $t → $newerTitle", 13f, color = W.LABEL_DARK), W.lp(W.MATCH, W.WRAP, t = 4))
        val notes = Tree.notes(snap)
        if (notes.isNotEmpty() || t == newerTitle)
            card.addView(W.text(ctx, "", 14f).apply { text = colored(notes); tag = "change-text-$index"; setPadding(0, W.dp(4), 0, 0) },
                W.lp(W.MATCH, W.WRAP, t = 4))
        return card
    }
}
