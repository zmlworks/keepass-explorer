package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.keemobile.kotpass.models.Entry
import io.github.zmlworks.kpexplorer.KeyFields
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import java.util.UUID

/**
 * Viewer of an entry's KeePass [Entry.history] (newest first); 0.4.16: items can be deleted (with confirmation).
 * Passwords stay masked unless the user taps 「表示」on that row; no clipboard, no logging of secrets.
 */
object HistoryViewer {
    data class Row(
        val indexFromNewest: Int,
        val modified: String,
        val title: String,
        val user: String,
        val passMasked: Boolean = true,
        val password: String,
        val url: String,
        val notes: String,
    )

    /** Newest-first snapshots from the live entry (does not invent history). */
    fun rows(e: Entry): List<Row> {
        // history is oldest→newest in KeePass; reverse for display
        return e.history.asReversed().mapIndexed { i, snap ->
            val t = snap.times?.lastModificationTime?.let { KeyFields.fmt.format(it) } ?: "-"
            Row(
                indexFromNewest = i + 1,
                modified = t,
                title = Tree.title(snap),
                user = Tree.userName(snap),
                password = Tree.password(snap),
                url = Tree.url(snap),
                notes = Tree.notes(snap).replace("\n", " ").take(120),
            )
        }
    }

    /** The viewer last shown (tests). */
    var current: ClassicDialog? = null
        private set

    /**
     * 0.4.16: per-item 「削除」 and 「全て削除」, each behind a confirmation every time. Deleting saves the database
     * (no history entry, Last Modified unchanged) and re-opens the viewer. Disabled in the read-only temporary view.
     */
    fun show(ctx: Context, entryId: UUID, onChanged: () -> Unit = {}): ClassicDialog? {
        val e = Tree.findEntry(io.github.zmlworks.kpexplorer.Session.db ?: return null, entryId)?.second ?: return null
        val snaps = rows(e)
        val ro = io.github.zmlworks.kpexplorer.Session.readOnlyTemp
        val title = "履歴 — ${Tree.title(e).ifEmpty { "(無題)" }}"
        val body = W.vbox(ctx)
        lateinit var dlg: ClassicDialog
        fun delete(index: Int?) {
            val what = if (index == null) "この鍵の履歴を全て (${snaps.size} 件) 削除します。\n元に戻せません。よろしいですか?"
                else "履歴 #$index (${snaps.first { it.indexFromNewest == index }.modified}) を削除します。\n元に戻せません。よろしいですか?"
            Dlg.confirm(ctx, if (index == null) "履歴を全て削除" else "履歴の削除", what, '!', yes = "削除", no = "キャンセル") {
                io.github.zmlworks.kpexplorer.Session.update { Order.deleteHistory(it, entryId, index) }
                io.github.zmlworks.kpexplorer.Session.flush()
                dlg.dismiss()
                onChanged()
                show(ctx, entryId, onChanged)
            }
        }
        body.addView(W.text(ctx,
            if (snaps.isEmpty()) "履歴はありません (中身を変えて保存したときに、最大 ${Tree.HISTORY_MAX} 件まで残ります)。"
            else if (ro) "新しい順。パスワードは隠したままです。読取専用です。"
            else "新しい順 (最大 ${Tree.HISTORY_MAX} 件)。パスワードは隠したままです。",
            13f).apply { setPadding(0, 0, 0, W.dp(8)) }, W.lp(W.MATCH, W.WRAP))
        val buttons = ArrayList<ClassicDialog.Btn>()
        if (snaps.isNotEmpty() && !ro) buttons.add(ClassicDialog.Btn("全て削除") { delete(null); false })
        buttons.add(ClassicDialog.Btn("閉じる", true))
        if (snaps.isEmpty()) {
            dlg = ClassicDialog(ctx, title, body, buttons)
            return dlg.also { current = it; it.show() }
        }
        val list = W.vbox(ctx)
        for (r in snaps) {
            list.addView(snapCard(ctx, r, if (ro) null else ({ delete(r.indexFromNewest) })), W.lp(W.MATCH, W.WRAP, b = 8))
        }
        val sv = ScrollView(ctx).apply { W.classicScrollbars(this); addView(list) }
        body.addView(sv, W.lp(W.MATCH, W.dp(360)))
        dlg = ClassicDialog(ctx, title, body, buttons, scroll = false)
        return dlg.also { current = it; it.show() }
    }

    private fun snapCard(ctx: Context, r: Row, onDelete: (() -> Unit)?): LinearLayout {
        val card = W.vbox(ctx).apply {
            background = W.Bevel(W.Style.THIN_RAISED, W.FACE)
            setPadding(W.dp(10), W.dp(8), W.dp(10), W.dp(8))
            tag = "history-snap-${r.indexFromNewest}"
        }
        val head = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(W.text(ctx, "#${r.indexFromNewest}  ${r.modified}", 14f, true, W.NAVY), W.lp(0, W.WRAP, 1f))
        if (onDelete != null) head.addView(W.text(ctx, "削除", 13f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON); isClickable = true
            contentDescription = "履歴 #${r.indexFromNewest} を削除"; tag = "history-delete-${r.indexFromNewest}"
            setPadding(W.dp(14), W.dp(6), W.dp(14), W.dp(6)); setOnClickListener { onDelete() }
        }, W.lp(W.WRAP, W.WRAP, l = 8))
        card.addView(head, W.lp(W.MATCH, W.WRAP))
        fun line(label: String, value: String, secret: Boolean = false): View {
            val row = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(W.text(ctx, label, 12f, color = W.LABEL_DARK), W.lp(W.dp(56), W.WRAP))
            val tv = W.text(ctx, if (secret) "******" else value.ifEmpty { "—" }, 14f).apply {
                isSingleLine = !secret; tag = if (secret) "hist-pass" else null
            }
            row.addView(tv, W.lp(0, W.WRAP, 1f))
            if (secret && r.password.isNotEmpty()) {
                row.addView(W.text(ctx, "表示", 12f, true, W.NAVY).apply {
                    isClickable = true; contentDescription = "パスワードを一時表示"
                    setPadding(W.dp(8), W.dp(4), W.dp(8), W.dp(4))
                    setOnClickListener {
                        val showing = tv.tag == "shown"
                        if (showing) { tv.text = "******"; tv.tag = "hist-pass"; text = "表示" }
                        else { tv.text = r.password; tv.tag = "shown"; text = "隠す" }
                    }
                }, W.lp(W.WRAP, W.WRAP))
            }
            return row
        }
        card.addView(line("Title", r.title), W.lp(W.MATCH, W.WRAP, t = 4))
        card.addView(line("User", r.user), W.lp(W.MATCH, W.WRAP))
        card.addView(line("Pass", r.password, secret = true), W.lp(W.MATCH, W.WRAP))
        card.addView(line("URL", r.url), W.lp(W.MATCH, W.WRAP))
        if (r.notes.isNotBlank()) card.addView(line("Memo", r.notes), W.lp(W.MATCH, W.WRAP))
        return card
    }
}
