package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.zmlworks.kpexplorer.KeyFields
import io.github.zmlworks.kpexplorer.Session
import io.github.zmlworks.kpexplorer.db.MemoDiff
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import java.time.Instant
import java.util.UUID

/**
 * 0.4.17: past versions of an app memo, rebuilt from its line diffs ([MemoDiff]), newest first. Lines that a later
 * save changed are highlighted. Standard History copies (left by other apps editing the memo) follow below.
 * Per-item 「削除」 and 「全て削除」, each confirmed every time; deleting makes no history. Read-only: no deleting.
 */
object MemoDiffViewer {
    const val HIGHLIGHT = 0xFFFFE97A.toInt()

    var current: ClassicDialog? = null
        private set

    fun show(ctx: Context, entryId: UUID, onChanged: () -> Unit = {}): ClassicDialog? {
        val e = Tree.findEntry(Session.db ?: return null, entryId)?.second ?: return null
        val vs = MemoDiff.versions(e)
        val std = HistoryViewer.rows(e)
        val ro = Session.readOnlyTemp
        val title = "差分 — ${Tree.title(e).ifEmpty { "(無題)" }}"
        val body = W.vbox(ctx)
        lateinit var dlg: ClassicDialog
        fun reopen() { dlg.dismiss(); onChanged(); show(ctx, entryId, onChanged) }
        fun delete(index: Int?, standard: Boolean = false) {
            val what = when {
                index == null -> "このメモの履歴を全て (${vs.size + std.size} 件) 削除します。\n元に戻せません。よろしいですか?"
                standard -> "履歴 (他のアプリ) #$index (${std.first { it.indexFromNewest == index }.modified}) を削除します。\n元に戻せません。よろしいですか?"
                else -> "差分 #$index (${fmt(vs[index - 1].at)}) を削除します。\n元に戻せません。よろしいですか?"
            }
            Dlg.confirm(ctx, if (index == null) "履歴を全て削除" else "履歴の削除", what, '!', yes = "削除", no = "キャンセル") {
                Session.update { if (standard) Order.deleteHistory(it, entryId, index) else Order.deleteMemoVersion(it, entryId, index) }
                Session.flush()
                reopen()
            }
        }
        val any = vs.isNotEmpty() || std.isNotEmpty()
        body.addView(W.text(ctx,
            if (!any) "履歴はありません (本文を変えて保存したときに、変わった所だけを最大 ${Tree.HISTORY_MAX} 件まで残します)。"
            else "新しい順 (最大 ${Tree.HISTORY_MAX} 件、変わった所だけを記録)。黄色の行は、その後の保存で書き換えられた行です。" +
                if (ro) "読取専用です。" else "",
            13f).apply { setPadding(0, 0, 0, W.dp(8)) }, W.lp(W.MATCH, W.WRAP))
        val buttons = ArrayList<ClassicDialog.Btn>()
        if (any && !ro) buttons.add(ClassicDialog.Btn("全て削除") { delete(null); false })
        buttons.add(ClassicDialog.Btn("閉じる", true))
        if (!any) {
            dlg = ClassicDialog(ctx, title, body, buttons)
            return dlg.also { current = it; it.show() }
        }
        val list = W.vbox(ctx)
        for (v in vs) list.addView(versionCard(ctx, v, if (ro) null else ({ delete(v.index) })), W.lp(W.MATCH, W.WRAP, b = 8))
        if (std.isNotEmpty()) {
            list.addView(W.text(ctx, "他のアプリが残した履歴 (全体のコピー)", 13f, true, W.LABEL_DARK), W.lp(W.MATCH, W.WRAP, t = 4, b = 4))
            for (r in std) list.addView(HistoryViewer.snapCard(ctx, r, if (ro) null else ({ delete(r.indexFromNewest, standard = true) })).apply {
                tag = "std-snap-${r.indexFromNewest}"
                findViewWithTag<android.view.View>("history-delete-${r.indexFromNewest}")?.tag = "std-delete-${r.indexFromNewest}"
            }, W.lp(W.MATCH, W.WRAP, b = 8))
        }
        val sv = ScrollView(ctx).apply { W.classicScrollbars(this); addView(list) }
        body.addView(sv, W.lp(W.MATCH, W.dp(400)))
        dlg = ClassicDialog(ctx, title, body, buttons, scroll = false)
        return dlg.also { current = it; it.show() }
    }

    private fun fmt(ms: Long) = KeyFields.fmt.format(Instant.ofEpochMilli(ms))

    /** The text of a version with the lines a later save changed highlighted (tests read it). */
    fun highlighted(v: MemoDiff.Version): CharSequence {
        val t = v.text ?: return "この版は、他のアプリでの編集をはさんだため復元できません。"
        val (changed, added) = MemoDiff.changedLines(v.newer.orEmpty(), t)
        val sb = SpannableStringBuilder()
        val ls = t.split("\n")
        for ((i, l) in ls.withIndex()) {
            val st = sb.length
            sb.append(l.ifEmpty { if (i in changed) " " else "" })
            if (i in changed) sb.setSpan(BackgroundColorSpan(HIGHLIGHT), st, sb.length, 0)
            if (i < ls.size - 1) sb.append('\n')
        }
        if (t.isEmpty() && changed.isEmpty()) sb.append("(空)")
        if (added > 0) {
            val st = sb.length
            sb.append("\n(この後の保存で $added 行が加わりました)")
            sb.setSpan(ForegroundColorSpan(W.SHADOW), st, sb.length, 0)
        }
        return sb
    }

    private fun versionCard(ctx: Context, v: MemoDiff.Version, onDelete: (() -> Unit)?): LinearLayout {
        val card = W.vbox(ctx).apply {
            background = W.Bevel(W.Style.THIN_RAISED, W.FACE)
            setPadding(W.dp(10), W.dp(8), W.dp(10), W.dp(8))
            tag = "diff-version-${v.index}"
        }
        val head = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(W.text(ctx, "#${v.index}  ${fmt(v.at)}", 14f, true, W.NAVY), W.lp(0, W.WRAP, 1f))
        if (onDelete != null) head.addView(W.text(ctx, "削除", 13f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON); isClickable = true
            contentDescription = "差分 #${v.index} を削除"; tag = "history-delete-${v.index}"
            setPadding(W.dp(14), W.dp(6), W.dp(14), W.dp(6)); setOnClickListener { onDelete() }
        }, W.lp(W.WRAP, W.WRAP, l = 8))
        card.addView(head, W.lp(W.MATCH, W.WRAP))
        if (v.title != v.newerTitle) card.addView(W.text(ctx, "タイトル: ${v.title} → ${v.newerTitle}", 13f, color = W.LABEL_DARK).apply {
            setBackgroundColor(HIGHLIGHT) }, W.lp(W.WRAP, W.WRAP, t = 4))
        if (v.anchored) card.addView(W.text(ctx, "(この間に他のアプリでの編集があります)", 12f, color = W.SHADOW), W.lp(W.MATCH, W.WRAP, t = 2))
        card.addView(W.text(ctx, "", 14f).apply { text = highlighted(v); tag = "diff-text-${v.index}"; setPadding(0, W.dp(4), 0, 0) },
            W.lp(W.MATCH, W.WRAP, t = 4))
        return card
    }
}
