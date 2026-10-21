package io.github.zmlworks.kpexplorer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.FlowLayout
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W
import java.security.SecureRandom
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Entry "property sheet". Plain memos (title + notes only) are first-class: for them the notes come first and
 * the login fields sit below. Leaving the window (OK, back, ×, app switch) saves automatically.
 */
class EditorScreen(
    private val a: MainActivity,
    private var entryId: UUID?,
    private val parent: UUID?,
    memo: Boolean,
    startTab: Int = 0
) : MainActivity.Screen {
    private val ctx = a
    private val root = W.vbox(ctx)
    override val view: View get() = root

    private val original: Tree.Fields
    private val memoFirst: Boolean
    val title: EditText = W.field(ctx, "タイトル")
    val user: EditText = W.field(ctx, "(なくてもかまいません)")
    val pass: EditText = W.field(ctx, "(なくてもかまいません)", password = true)
    val url: EditText = W.field(ctx, "https://")
    val notes: EditText = W.field(ctx, "ここにメモを書きます", multi = true)
    val tags: EditText = W.field(ctx, "例: 仕事 銀行 重要")
    val chips = FlowLayout(ctx)
    private val allTags: List<String> = Tree.allTags(Session.db!!)
    private val pages = ArrayList<View>()
    private val tabViews = ArrayList<TextView>()
    private lateinit var caption: W.Caption
    private var closed = false
    /** Tags from other apps may contain spaces; keep them untouched unless the tag field is edited. */
    private var initialTagText = ""

    init {
        val e = entryId?.let { Tree.findEntry(Session.db!!, it)?.second }
        original = e?.let { Tree.fieldsOf(it) } ?: Tree.Fields(if (memo) "新しいメモ" else "", "", "", "", "", emptyList())
        memoFirst = if (e != null) original.userName.isEmpty() && original.password.isEmpty() else memo
        title.setText(original.title); user.setText(original.userName); pass.setText(original.password)
        url.setText(original.url); notes.setText(original.notes)
        initialTagText = Tree.formatTags(original.tags).let { if (it.isEmpty()) it else "$it " }
        tags.setText(initialTagText)
        build(startTab)
    }

    private fun build(startTab: Int) {
        caption = W.titleBar(ctx, captionText(), Px.file16, onClose = { close(save = true) })
        root.addView(caption.bar, W.lp())
        title.addTextChangedListener(watcher { caption.title.text = captionText() })

        // tab strip
        val strip = W.hbox(ctx).apply { setPadding(W.dp(6), W.dp(6), 0, 0); gravity = Gravity.BOTTOM }
        val holder = FrameLayout(ctx).apply { background = TabPanel(); setPadding(3 * W.px + W.dp(8), 3 * W.px + W.dp(8), 3 * W.px + W.dp(8), 3 * W.px + W.dp(8)) }
        for ((i, name) in listOf("全般", "詳細").withIndex()) {
            val t = W.text(ctx, name, 15f).apply {
                setPadding(W.dp(16), W.dp(8), W.dp(16), W.dp(8))
                background = TabDrawable()
                setOnClickListener { select(i) }
            }
            tabViews.add(t); strip.addView(t)
        }
        root.addView(strip, W.lp())
        pages.add(general()); pages.add(details())
        pages.forEach { holder.addView(it, FrameLayout.LayoutParams(W.MATCH, W.MATCH)) }
        root.addView(holder, W.lp(W.MATCH, 0, 1f, l = 6, r = 6))

        val buttons = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(W.dp(6), W.dp(8), W.dp(6), W.dp(8)) }
        buttons.addView(W.button(ctx, "OK", true) { close(save = true) }, W.lp(W.WRAP, W.WRAP, l = 6))
        buttons.addView(W.button(ctx, "キャンセル") { cancel() }, W.lp(W.WRAP, W.WRAP, l = 6))
        buttons.addView(W.button(ctx, "適用") { apply(); Toast.makeText(ctx, "保存しました", Toast.LENGTH_SHORT).show() }, W.lp(W.WRAP, W.WRAP, l = 6))
        root.addView(buttons, W.lp())
        select(startTab)
    }

    private fun captionText() = "${title.text.toString().ifEmpty { "(無題)" }} のプロパティ"

    private fun select(i: Int) {
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { k, t ->
            t.isSelected = k == i
            (t.layoutParams as LinearLayout.LayoutParams).apply { topMargin = if (k == i) 0 else W.dp(4); bottomMargin = if (k == i) -2 * W.px else 0 }
            t.typeface = if (k == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            t.requestLayout()
        }
    }

    private fun label(s: String) = W.text(ctx, s, 14f)

    private fun general(): View {
        val col = W.vbox(ctx)
        val head = W.hbox(ctx)
        head.addView(W.icon(ctx, if (original.password.isNotEmpty()) Px.keyfile32 else Px.file32), W.lp(W.WRAP, W.WRAP, r = 10))
        head.addView(title, W.lp(0, W.WRAP, 1f))
        col.addView(head)
        col.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 10, b = 8))

        val login = loginBox()
        val notesBlock = W.vbox(ctx).apply {
            addView(label("メモ(M):"))
            notes.minLines = if (memoFirst) 10 else 6
            notes.setLineSpacing(0f, 1.1f)
            addView(notes, W.lp(W.MATCH, W.WRAP, t = 4))
        }
        val tagBlock = W.vbox(ctx).apply {
            addView(label("タグ(T): スペースで区切ります"))
            addView(tags, W.lp(W.MATCH, W.WRAP, t = 4))
            addView(W.text(ctx, if (allTags.isEmpty()) "(まだタグはありません)" else "使われているタグ (タップで追加/削除):", 13f, color = W.SHADOW), W.lp(W.MATCH, W.WRAP, t = 6))
            addView(chips, W.lp(W.MATCH, W.WRAP, t = 4))
        }
        tags.addTextChangedListener(watcher { renderChips() })
        renderChips()
        if (memoFirst) {
            col.addView(notesBlock); col.addView(tagBlock, W.lp(W.MATCH, W.WRAP, t = 12)); col.addView(login, W.lp(W.MATCH, W.WRAP, t = 14))
        } else {
            col.addView(login); col.addView(notesBlock, W.lp(W.MATCH, W.WRAP, t = 12)); col.addView(tagBlock, W.lp(W.MATCH, W.WRAP, t = 12))
        }
        val sv = ScrollView(ctx); W.classicScrollbars(sv); sv.addView(col)
        return sv
    }

    private fun loginBox(): View {
        val g = W.vbox(ctx)
        fun row(lbl: String, f: EditText, vararg extra: View) {
            g.addView(label(lbl), W.lp(W.MATCH, W.WRAP, t = 4))
            val r = W.hbox(ctx)
            r.addView(f, W.lp(0, W.WRAP, 1f))
            extra.forEach { r.addView(it, W.lp(W.WRAP, W.WRAP, l = 4)) }
            g.addView(r, W.lp(W.MATCH, W.WRAP, t = 3))
        }
        fun small(s: String, f: () -> Unit) = W.button(ctx, s) { f() }.apply { minWidth = W.dp(56); setPadding(W.dp(8), W.dp(6), W.dp(8), W.dp(6)) }
        row("ユーザー名(U):", user, small("コピー") {
            Clip.copy(ctx, "ユーザー名", user.text.toString(), false); Toast.makeText(ctx, "ユーザー名をコピーしました", Toast.LENGTH_SHORT).show()
        })
        pass.typeface = Typeface.MONOSPACE
        row("パスワード(P):", pass, small("コピー") {
            Clip.copy(ctx, "パスワード", pass.text.toString(), true); Toast.makeText(ctx, "パスワードをコピーしました", Toast.LENGTH_SHORT).show()
        })
        val opts = W.hbox(ctx)
        val show = W.check(ctx, "パスワードを表示する")
        show.setOnCheckedChangeListener { _, on ->
            val sel = pass.selectionEnd
            pass.inputType = InputType.TYPE_CLASS_TEXT or if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            pass.transformationMethod = if (on) null else android.text.method.PasswordTransformationMethod.getInstance()
            pass.typeface = Typeface.MONOSPACE
            pass.setSelection(sel.coerceIn(0, pass.length()))
        }
        opts.addView(show, W.lp(0, W.WRAP, 1f))
        opts.addView(small("生成...") { generate(show) })
        g.addView(opts, W.lp(W.MATCH, W.WRAP, t = 2))
        row("URL(L):", url)
        return W.groupBox(ctx, "ログイン情報", g)
    }

    private fun generate(show: android.widget.CheckBox) {
        val doIt = {
            val cs = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#%+-=?@_"
            val r = SecureRandom()
            pass.setText(String(CharArray(20) { cs[r.nextInt(cs.length)] }))
            show.isChecked = true
        }
        if (pass.text.isEmpty()) doIt() else Dlg.confirm(ctx, "パスワードの生成", "現在のパスワードを新しいランダムなパスワード (20 文字) に置き換えますか?") { doIt() }
    }

    /** Tokens of the tag field, and the unfinished last word (if the text does not end with a space). */
    private fun tagState(): Pair<List<String>, String> {
        val s = tags.text.toString()
        val toks = Tree.parseTags(s)
        val partial = if (s.isEmpty() || s.last().isWhitespace()) "" else toks.lastOrNull().orEmpty()
        val complete = if (partial.isEmpty()) toks else toks.dropLast(1)
        return complete to partial
    }

    private fun renderChips() {
        chips.removeAllViews()
        val (have, partial) = tagState()
        val list = if (partial.isEmpty()) allTags else
            allTags.filter { it.startsWith(partial, true) } + allTags.filter { !it.startsWith(partial, true) && it.contains(partial, true) }
        for (t in list.take(60)) {
            val on = t in have
            val b = W.text(ctx, if (on) "✓ $t" else t, 14f).apply {
                val bev = W.Bevel(W.Style.BUTTON, if (on) W.LIGHT else W.FACE)
                bev.forcePressed = on
                background = bev
                setPadding(W.dp(10), W.dp(7), W.dp(10), W.dp(7))
                isClickable = true
                setOnClickListener { toggleTag(t) }
            }
            chips.addView(b)
        }
    }

    private fun toggleTag(t: String) {
        val (have, partial) = tagState()
        val next = when {
            t in have -> have - t
            else -> have + t
        }
        val text = Tree.formatTags(next).let { if (it.isEmpty()) "" else "$it " }
        tags.setText(text)
        tags.setSelection(tags.length())
    }

    private fun details(): View {
        val col = W.vbox(ctx)
        val f = DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm:ss").withZone(ZoneId.systemDefault())
        val d = Session.db!!
        val found = entryId?.let { Tree.findEntry(d, it) }
        val e = found?.second
        fun kv(k: String, v: String) {
            val r = W.hbox(ctx).apply { gravity = Gravity.TOP }
            r.addView(W.text(ctx, k, 14f), W.lp(W.dp(110), W.WRAP))
            r.addView(W.text(ctx, v, 14f).apply { setTextIsSelectable(true) }, W.lp(0, W.WRAP, 1f))
            col.addView(r, W.lp(W.MATCH, W.WRAP, b = 8))
        }
        val parentGroup = found?.first ?: parent?.let { Tree.findGroup(d, it) }
        kv("種類:", if (original.password.isEmpty() && original.userName.isEmpty()) "テキスト文書 (メモ)" else "ログイン情報")
        kv("場所:", parentGroup?.let { p -> Tree.path(Tree.root(d), p.uuid).joinToString("\\") { it.name } } ?: "-")
        col.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, b = 8))
        val t = e?.times
        kv("作成日時:", t?.creationTime?.let { f.format(it) } ?: "(未保存)")
        kv("更新日時:", t?.lastModificationTime?.let { f.format(it) } ?: "-")
        kv("場所の変更:", t?.locationChanged?.let { f.format(it) } ?: "-")
        col.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, b = 8))
        kv("履歴:", "${e?.history?.size ?: 0} 件")
        kv("添付ファイル:", "${e?.binaries?.size ?: 0} 個 (保持されます)")
        val std = setOf("Title", "UserName", "Password", "URL", "Notes")
        val extra = e?.fields?.keys?.filter { it !in std }.orEmpty()
        kv("その他のフィールド:", if (extra.isEmpty()) "なし" else extra.joinToString(", ") + " (この版では編集できませんが保持されます)")
        kv("UUID:", e?.uuid?.toString() ?: "-")
        val sv = ScrollView(ctx); W.classicScrollbars(sv); sv.addView(col)
        return sv
    }

    private fun current() = Tree.Fields(title.text.toString(), user.text.toString(), pass.text.toString(),
        url.text.toString(), notes.text.toString(),
        if (tags.text.toString() == initialTagText) original.tags else Tree.parseTags(tags.text.toString()))

    private fun isBlankNew(f: Tree.Fields) = entryId == null && f.userName.isEmpty() && f.password.isEmpty() &&
        f.url.isEmpty() && f.notes.isEmpty() && f.tags.isEmpty() && (f.title.isEmpty() || f.title == original.title)

    /** Commit the form to the database (auto-saved). */
    fun apply() {
        if (!Session.isOpen) return
        val f = current()
        val id = entryId
        if (id == null) {
            if (isBlankNew(f)) return
            val e = Tree.newEntry(f)
            Session.update { Tree.addEntry(it, parent ?: Tree.root(it).uuid, e) }
            entryId = e.uuid
            a.explorer?.selection = e.uuid
        } else {
            if (Tree.findEntry(Session.db!!, id) == null) return
            Session.update { Tree.updateEntry(it, id, f) }
        }
    }

    private fun close(save: Boolean) {
        if (closed) return
        if (save) apply()
        closed = true
        a.showExplorer()
    }

    private fun cancel() {
        val f = current()
        val dirty = f != original.copy(tags = Tree.normalizeTags(original.tags))
        if (!dirty) { closed = true; a.showExplorer(); return }
        Dlg.confirm(ctx, "KeePass Explorer", "変更を破棄しますか?", '!', yes = "破棄", no = "戻る") { closed = true; a.showExplorer() }
    }

    override fun onBack(): Boolean { close(save = true); return true }

    override fun onPause() { if (!closed) apply() }

    private fun watcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { f() }
    }

    /** Property-sheet tab: raised on three sides, open at the bottom when selected. */
    private class TabDrawable : Drawable() {
        private val p = Paint()
        private var sel = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val s = state.contains(android.R.attr.state_selected)
            val ch = s != sel; sel = s; if (ch) invalidateSelf(); return ch
        }
        private fun r(c: Canvas, l: Int, t: Int, rr: Int, b: Int, col: Int) { p.color = col; c.drawRect(l.toFloat(), t.toFloat(), rr.toFloat(), b.toFloat(), p) }
        override fun draw(c: Canvas) {
            val b = bounds; val u = W.px
            r(c, b.left, b.top, b.right, b.bottom, W.FACE)
            r(c, b.left + 2 * u, b.top, b.right - 2 * u, b.top + u, W.HILITE)
            r(c, b.left + u, b.top + u, b.left + 2 * u, b.top + 2 * u, W.HILITE)
            r(c, b.left, b.top + 2 * u, b.left + u, b.bottom, W.HILITE)
            r(c, b.right - 2 * u, b.top + u, b.right - u, b.top + 2 * u, W.DARK)
            r(c, b.right - u, b.top + 2 * u, b.right, b.bottom, W.DARK)
            r(c, b.right - 2 * u, b.top + 2 * u, b.right - u, b.bottom, W.SHADOW)
            if (!sel) r(c, b.left, b.bottom - u, b.right, b.bottom, W.HILITE)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** Panel under the tabs: like a raised window frame. */
    private class TabPanel : Drawable() {
        private val inner = W.Bevel(W.Style.RAISED)
        override fun draw(c: Canvas) { inner.bounds = bounds; inner.draw(c) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
