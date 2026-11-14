package io.github.zmlworks.kpexplorer.ui

import io.github.zmlworks.kpexplorer.L
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
        val title = L.t("履歴 — ${Tree.title(e).ifEmpty { "(無題)" }}", "History — ${Tree.title(e).ifEmpty { "(Untitled)" }}")
        val body = W.vbox(ctx)
        lateinit var dlg: ClassicDialog
        fun delete(index: Int?) {
            val what = if (index == null) L.t("この鍵の履歴を全て (${snaps.size} 件) 削除します。\n元に戻せません。よろしいですか?", "Delete all history of this key (${snaps.size})?\nThis cannot be undone.")
                else L.t("履歴 #$index (${snaps.first { it.indexFromNewest == index }.modified}) を削除します。\n元に戻せません。よろしいですか?", "Delete history #$index (${snaps.first { it.indexFromNewest == index }.modified})?\nThis cannot be undone.")
            Dlg.confirm(ctx, if (index == null) L.t("履歴を全て削除", "Delete All History") else L.t("履歴の削除", "Delete History"), what, '!', yes = L.t("削除", "Delete"), no = L.t("キャンセル", "Cancel")) {
                io.github.zmlworks.kpexplorer.Session.update { Order.deleteHistory(it, entryId, index) }
                io.github.zmlworks.kpexplorer.Session.flush()
                dlg.dismiss()
                onChanged()
                show(ctx, entryId, onChanged)
            }
        }
        // 0.4.20: no explanatory header any more; only the empty state says something
        if (snaps.isEmpty()) body.addView(W.text(ctx, L.t("履歴はありません。", "No history."), 13f).apply { setPadding(0, 0, 0, W.dp(8)) }, W.lp(W.MATCH, W.WRAP))
        val buttons = ArrayList<ClassicDialog.Btn>()
        if (snaps.isNotEmpty() && !ro) buttons.add(ClassicDialog.Btn(L.t("全て削除", "Delete all")) { delete(null); false })
        buttons.add(ClassicDialog.Btn(L.t("閉じる", "Close"), true))
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
            // 0.4.20: Title only when it differs from the next newer version (the entry itself for #1)
            val newerTitle = newerTitle(e, k)
            val card = if (Order.isChangedOnly(snap)) changedCard(ctx, r.indexFromNewest, r.modified, snap, newerTitle, onDelete)
                else snapCard(ctx, r, onDelete, showTitle = Tree.title(snap) != newerTitle, newerTitle = newerTitle)
            list.addView(card, W.lp(W.MATCH, W.WRAP, b = 8))
        }
        val sv = ScrollView(ctx).apply { W.classicScrollbars(this); addView(list) }
        body.addView(sv, W.lp(W.MATCH, W.dp(360)))
        dlg = ClassicDialog(ctx, title, body, buttons, scroll = false)
        return dlg.also { current = it; it.show() }
    }

    /** Title of the version after history item [k] (0 = newest copy → the entry itself). */
    fun newerTitle(e: Entry, k: Int): String = if (k == 0) Tree.title(e) else Tree.title(e.history[e.history.size - k])

    /** Display name of a field in the history cards. */
    fun label(k: String): String = when (k) {
        "UserName" -> "User"; "Password" -> "Pass"; "Notes" -> "Memo"; Order.OTP -> "OTP"; else -> k
    }

    /**
     * 0.4.19: card of a changed-only copy ([Order.changedOnlyCopy]): the title (old → new on a rename), then each
     * changed field with its previous value, Memo as a coloured change, Tag; 「項目の追加」 for fields that were added.
     */
    internal fun changedCard(ctx: Context, index: Int, modified: String, snap: Entry, newerTitle: String, onDelete: (() -> Unit)?): LinearLayout {
        val card = W.vbox(ctx).apply {
            background = W.Bevel(W.Style.THIN_RAISED, W.FACE)
            setPadding(W.dp(10), W.dp(8), W.dp(10), W.dp(8))
            tag = "history-changed-$index"
        }
        val head = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(W.text(ctx, "#$index  $modified", 14f, true, W.NAVY), W.lp(0, W.WRAP, 1f))
        if (onDelete != null) head.addView(W.text(ctx, L.t("削除", "Delete"), 13f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON); isClickable = true
            contentDescription = L.t("履歴 #$index を削除", "Delete history #$index"); tag = "history-delete-$index"
            setPadding(W.dp(14), W.dp(6), W.dp(14), W.dp(6)); setOnClickListener { onDelete() }
        }, W.lp(W.WRAP, W.WRAP, l = 8))
        card.addView(head, W.lp(W.MATCH, W.WRAP))
        val title = Tree.title(snap)
        fun row(label: String, value: CharSequence, secret: String? = null, tagName: String? = null) {
            val r = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(W.text(ctx, label, 12f, color = W.LABEL_DARK).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END },
                W.lp(W.dp(64), W.WRAP))
            val tv = W.text(ctx, "", 14f).apply { text = if (secret != null && secret.isNotEmpty()) "******" else value; tag = tagName }
            r.addView(tv, W.lp(0, W.WRAP, 1f))
            if (secret != null && secret.isNotEmpty()) r.addView(W.text(ctx, L.t("表示", "View"), 12f, true, W.NAVY).apply {
                isClickable = true; contentDescription = L.t("一時表示", "Show temporarily")
                setPadding(W.dp(8), W.dp(4), W.dp(8), W.dp(4))
                setOnClickListener {
                    if (text.toString() == L.t("表示", "View")) { tv.text = secret; text = L.t("隠す", "Hide") } else { tv.text = "******"; text = L.t("表示", "View") }
                }
            }, W.lp(W.WRAP, W.WRAP))
            card.addView(r, W.lp(W.MATCH, W.WRAP, t = 2))
        }
        if (title != newerTitle) row("Title", "$title → $newerTitle", tagName = "changed-Title")
        val rank = mapOf("UserName" to 0, "Password" to 1, "URL" to 2, "Notes" to 9)
        // 0.4.21: a field whose previous value is empty was added (filled in) by that save. Only the names are
        // shown (the value is on the newer version). A copy with only an unchanged Title = fields were added
        // (since 0.4.21 added fields are not stored, so their names are not known).
        val added = snap.fields.entries.filter { it.key != "Title" && it.value.content.isEmpty() }.sortedBy { rank[it.key] ?: 5 }.map { label(it.key) }
        val onlyTitle = snap.fields.keys.all { it == "Title" } && snap.tags.isEmpty() && title == newerTitle
        if (added.isNotEmpty() || onlyTitle)
            card.addView(W.text(ctx, L.t("項目の追加", "Fields added") + if (added.isEmpty()) "" else ": " + added.joinToString(L.t("、", ", ")), 14f)
                .apply { tag = "changed-added" }, W.lp(W.MATCH, W.WRAP, t = 2))
        for ((k, v) in snap.fields.entries.sortedBy { rank[it.key] ?: 5 }) {
            if (k == "Title") continue
            val c = v.content
            if (c.isEmpty()) continue
            when {
                k == "Notes" -> row(label(k), MemoDiffViewer.colored(c).let { if (c.isEmpty()) L.t("(空)", "(empty)") else it }, tagName = "changed-Notes")
                k == "Password" || k == Order.OTP || v is app.keemobile.kotpass.models.EntryValue.Encrypted ->
                    if (c.isEmpty()) row(label(k), L.t("(空)", "(empty)"), tagName = "changed-$k") else row(label(k), "", secret = c, tagName = "changed-$k")
                else -> row(label(k), c.ifEmpty { L.t("(空)", "(empty)") }, tagName = "changed-$k")
            }
        }
        if (snap.tags.isNotEmpty()) row("Tag", snap.tags.joinToString(", "), tagName = "changed-Tags")
        return card
    }

    internal fun snapCard(ctx: Context, r: Row, onDelete: (() -> Unit)?, showTitle: Boolean = true, newerTitle: String? = null): LinearLayout {
        val card = W.vbox(ctx).apply {
            background = W.Bevel(W.Style.THIN_RAISED, W.FACE)
            setPadding(W.dp(10), W.dp(8), W.dp(10), W.dp(8))
            tag = "history-snap-${r.indexFromNewest}"
        }
        val head = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(W.text(ctx, "#${r.indexFromNewest}  ${r.modified}", 14f, true, W.NAVY), W.lp(0, W.WRAP, 1f))
        if (onDelete != null) head.addView(W.text(ctx, L.t("削除", "Delete"), 13f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON); isClickable = true
            contentDescription = L.t("履歴 #${r.indexFromNewest} を削除", "Delete history #${r.indexFromNewest}"); tag = "history-delete-${r.indexFromNewest}"
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
                row.addView(W.text(ctx, L.t("表示", "View"), 12f, true, W.NAVY).apply {
                    isClickable = true; contentDescription = L.t("パスワードを一時表示", "Show password temporarily")
                    setPadding(W.dp(8), W.dp(4), W.dp(8), W.dp(4))
                    setOnClickListener {
                        val showing = tv.tag == "shown"
                        if (showing) { tv.text = "******"; tv.tag = "hist-pass"; text = L.t("表示", "View") }
                        else { tv.text = r.password; tv.tag = "shown"; text = L.t("隠す", "Hide") }
                    }
                }, W.lp(W.WRAP, W.WRAP))
            }
            return row
        }
        if (showTitle) card.addView(line("Title", if (newerTitle != null) "${r.title} → $newerTitle" else r.title), W.lp(W.MATCH, W.WRAP, t = 4))
        card.addView(line("User", r.user), W.lp(W.MATCH, W.WRAP))
        card.addView(line("Pass", r.password, secret = true), W.lp(W.MATCH, W.WRAP))
        card.addView(line("URL", r.url), W.lp(W.MATCH, W.WRAP))
        if (r.notes.isNotBlank()) card.addView(line("Memo", r.notes), W.lp(W.MATCH, W.WRAP))
        return card
    }
}
