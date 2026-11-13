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
            else "新しい順 (最大 ${Tree.HISTORY_MAX} 件)。変わった欄だけを、変更前の値で表示します。パスワードは隠したままです。",
            13f).apply { setPadding(0, 0, 0, W.dp(8)) }, W.lp(W.MATCH, W.WRAP))
        val buttons = ArrayList<ClassicDialog.Btn>()
        if (snaps.isNotEmpty() && !ro) buttons.add(ClassicDialog.Btn("全て削除") { delete(null); false })
        buttons.add(ClassicDialog.Btn("閉じる", true))
        if (snaps.isEmpty()) {
            dlg = ClassicDialog(ctx, title, body, buttons)
            return dlg.also { current = it; it.show() }
        }
        val list = W.vbox(ctx)
        val hist = e.history
        for ((k, r) in snaps.withIndex()) {
            val snap = hist[hist.size - 1 - k]
            val onDelete: (() -> Unit)? = if (ro) null else ({ delete(r.indexFromNewest) })
            // 0.4.19: copies that hold only the changed fields get their own card; full copies (older versions,
            // KeePass / KeePassXC) are shown as before
            val card = if (Order.isChangedOnly(snap)) {
                val newerTitle = if (k == 0) Tree.title(e) else Tree.title(hist[hist.size - k])
                changedCard(ctx, r.indexFromNewest, r.modified, snap, newerTitle, onDelete)
            } else snapCard(ctx, r, onDelete)
            list.addView(card, W.lp(W.MATCH, W.WRAP, b = 8))
        }
        val sv = ScrollView(ctx).apply { W.classicScrollbars(this); addView(list) }
        body.addView(sv, W.lp(W.MATCH, W.dp(360)))
        dlg = ClassicDialog(ctx, title, body, buttons, scroll = false)
        return dlg.also { current = it; it.show() }
    }

    /** Display name of a field in the history cards. */
    fun label(k: String): String = when (k) {
        "UserName" -> "User"; "Password" -> "Pass"; "Notes" -> "Memo"; Order.OTP -> "OTP"; else -> k
    }

    /**
     * 0.4.19: card of a changed-only copy ([Order.changedOnlyCopy]): the title (old → new on a rename), then each
     * changed field with its previous value (「(空)」 = it was empty or not there), Memo as a coloured change, Tag.
     */
    internal fun changedCard(ctx: Context, index: Int, modified: String, snap: Entry, newerTitle: String, onDelete: (() -> Unit)?): LinearLayout {
        val card = W.vbox(ctx).apply {
            background = W.Bevel(W.Style.THIN_RAISED, W.FACE)
            setPadding(W.dp(10), W.dp(8), W.dp(10), W.dp(8))
            tag = "history-changed-$index"
        }
        val head = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(W.text(ctx, "#$index  $modified", 14f, true, W.NAVY), W.lp(0, W.WRAP, 1f))
        if (onDelete != null) head.addView(W.text(ctx, "削除", 13f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON); isClickable = true
            contentDescription = "履歴 #$index を削除"; tag = "history-delete-$index"
            setPadding(W.dp(14), W.dp(6), W.dp(14), W.dp(6)); setOnClickListener { onDelete() }
        }, W.lp(W.WRAP, W.WRAP, l = 8))
        card.addView(head, W.lp(W.MATCH, W.WRAP))
        val title = Tree.title(snap)
        val names = ArrayList<String>()
        fun row(label: String, value: CharSequence, secret: String? = null, tagName: String? = null) {
            val r = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(W.text(ctx, label, 12f, color = W.LABEL_DARK).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END },
                W.lp(W.dp(64), W.WRAP))
            val tv = W.text(ctx, "", 14f).apply { text = if (secret != null && secret.isNotEmpty()) "******" else value; tag = tagName }
            r.addView(tv, W.lp(0, W.WRAP, 1f))
            if (secret != null && secret.isNotEmpty()) r.addView(W.text(ctx, "表示", 12f, true, W.NAVY).apply {
                isClickable = true; contentDescription = "一時表示"
                setPadding(W.dp(8), W.dp(4), W.dp(8), W.dp(4))
                setOnClickListener {
                    if (text.toString() == "表示") { tv.text = secret; text = "隠す" } else { tv.text = "******"; text = "表示" }
                }
            }, W.lp(W.WRAP, W.WRAP))
            card.addView(r, W.lp(W.MATCH, W.WRAP, t = 2))
        }
        if (title != newerTitle) { row("Title", "$title → $newerTitle", tagName = "changed-Title"); names.add("Title") }
        else row("Title", title.ifEmpty { "—" })
        val rank = mapOf("UserName" to 0, "Password" to 1, "URL" to 2, "Notes" to 9)
        for ((k, v) in snap.fields.entries.sortedBy { rank[it.key] ?: 5 }) {
            if (k == "Title") continue
            names.add(label(k))
            val c = v.content
            when {
                k == "Notes" -> row(label(k), MemoDiffViewer.colored(c).let { if (c.isEmpty()) "(空)" else it }, tagName = "changed-Notes")
                k == "Password" || k == Order.OTP || v is app.keemobile.kotpass.models.EntryValue.Encrypted ->
                    if (c.isEmpty()) row(label(k), "(空)", tagName = "changed-$k") else row(label(k), "", secret = c, tagName = "changed-$k")
                else -> row(label(k), c.ifEmpty { "(空)" }, tagName = "changed-$k")
            }
        }
        if (snap.tags.isNotEmpty()) { names.add("Tag"); row("Tag", snap.tags.joinToString(", "), tagName = "changed-Tags") }
        card.addView(W.text(ctx, if (names.isEmpty()) "変わった欄: なし (アイコンなど)" else "変わった欄: " + names.joinToString("、"), 12f, color = W.SHADOW).apply {
            tag = "changed-summary-$index" }, W.lp(W.MATCH, W.WRAP, t = 4))
        return card
    }

    internal fun snapCard(ctx: Context, r: Row, onDelete: (() -> Unit)?): LinearLayout {
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
