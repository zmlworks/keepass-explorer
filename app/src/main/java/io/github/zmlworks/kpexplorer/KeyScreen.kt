package io.github.zmlworks.kpexplorer

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.keemobile.kotpass.models.Entry
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Totp
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.Parts
import io.github.zmlworks.kpexplorer.ui.W
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Shared field labels / formatting for the key screen and the explorer preview pane. */
object KeyFields {
    val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())
    fun label(k: String) = when (k) {
        Order.F_TITLE -> "Title:"; Order.F_USER -> "User:"; Order.F_PASS -> "Pass:"; Order.F_URL -> "URL:"
        Order.F_TAGS -> "Tag:"; Order.F_OTP -> "OTP:"; Order.F_NOTES -> "Memo:"; else -> "$k:"
    }
    fun lastModified(e: Entry?): String = "Last Modified: " + (e?.times?.lastModificationTime?.let { fmt.format(it) } ?: "-")

    /** Live TOTP text: "123 456  (あと 12 秒)", refreshed every second while attached. */
    class OtpView(ctx: Context, private val uri: () -> String?) : TextView(ctx) {
        private val tick = object : Runnable { override fun run() { update(); postDelayed(this, 1000) } }
        init { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f); typeface = android.graphics.Typeface.MONOSPACE; setTextColor(W.NAVY); update() }
        fun current(): String? = runCatching { uri()?.let { Totp.code(Totp.parse(it), Instant.now().epochSecond) } }.getOrNull()
        fun update() {
            val u = uri()
            text = if (u.isNullOrBlank()) "" else runCatching {
                val p = Totp.parse(u); val now = Instant.now().epochSecond
                val c = Totp.code(p, now)
                "${c.chunked(3).joinToString(" ")}  (あと ${Totp.remaining(p, now)} 秒)"
            }.getOrElse { "⚠ 設定が不正です" }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    }
}

/**
 * Key (password entry) window: ← + title, folder path tree, Last Modified, then the fields in the per-key
 * layout (Title / User / Pass / URL / Tag / OTP / Memo / custom), each "Label: [input]" on one line except Memo.
 * Passwords are shown in clear. 戻る / 保存 are repeated at the bottom of the page.
 * [entryId] null = new key in [parent], created on the first save.
 */
class KeyScreen(private val a: MainActivity, private var entryId: UUID?, private val parent: UUID) : MainActivity.Screen {
    private val ctx = a
    private val root = W.vbox(ctx).apply { setBackgroundColor(W.FACE) }
    override val view: View get() = root
    val values = LinkedHashMap<String, String>()
    var tags = ""
    var layout: MutableList<Pair<String, Boolean>> = Order.DEFAULT_LAYOUT.map { it to true }.toMutableList()
    var dirty = false
        private set
    private val editors = HashMap<String, EditText>()
    /** Framed field boxes, by layout key (tests / Pass toggle). */
    val boxes = LinkedHashMap<String, io.github.zmlworks.kpexplorer.ui.FieldBox>()
    var passShown = false
        private set
    private var otpView: KeyFields.OtpView? = null
    private lateinit var bar: Parts.AppBar
    private val body = W.vbox(ctx, 8)
    private val allTags: List<String> = Session.db?.let { Tree.allTags(it) } ?: emptyList()
    private var chips: android.widget.FrameLayout? = null
    lateinit var scroll: ScrollView
        private set

    private fun entry(): Entry? = entryId?.let { id -> Session.db?.let { Tree.findEntry(it, id)?.second } }

    init {
        val e = entry()
        if (e != null) {
            e.fields.forEach { (k, v) -> values[k] = v.content }
            tags = Tree.formatTags(e.tags)
            layout = Order.layout(e).toMutableList()
        } else listOf("Title", "UserName", "Password", "URL", "Notes").forEach { values[it] = "" }
        bar = Parts.appBar(ctx, titleText(), onBack = { onBack() })
        bar.bar.addView(Parts.barButton(ctx, "保存", "保存") { save() }, W.lp(W.WRAP, W.dp(38), l = 4))
        bar.bar.addView(Parts.barButton(ctx, "≡", "メニュー") { v -> Menus.dropDown(v, menu()) }, W.lp(W.WRAP, W.dp(38), l = 4))
        root.addView(bar.bar, W.lp())
        val sv = ScrollView(ctx).apply { isFillViewport = true; W.classicScrollbars(this) }
        scroll = sv
        sv.addView(body)
        root.addView(sv, W.lp(W.MATCH, 0, 1f))
        build()
    }

    private fun titleText() = values["Title"].orEmpty().ifEmpty { if (entryId == null) "新しい鍵" else "(無題)" }

    private fun build() {
        body.removeAllViews(); editors.clear(); boxes.clear(); otpView = null
        val d = Session.db ?: return
        val path = Tree.path(Tree.root(d), parent)
        body.addView(Parts.pathTree(ctx, path), W.lp(W.MATCH, W.WRAP))
        body.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 4, b = 6))
        body.addView(W.text(ctx, KeyFields.lastModified(entry()), 14f), W.lp(W.MATCH, W.WRAP, b = 8))
        for ((k, vis) in layout) {
            if (!vis) continue
            when (k) {
                Order.F_OTP -> if (!values[Order.OTP].isNullOrBlank()) otpRow()
                Order.F_TAGS -> { fieldRow(k, tags, false) { tags = it; renderChips() }; tagPicker() }
                Order.F_NOTES -> fieldRow(k, values["Notes"].orEmpty(), true) { values["Notes"] = it }
                else -> fieldRow(k, values[k].orEmpty(), false) { values[k] = it; if (k == "Title") bar.title.text = titleText() }
            }
        }
        // 戻る / 保存 again at the very bottom (long keys: no need to scroll back up)
        body.addView(View(ctx), W.lp(W.MATCH, 0, 1f))
        body.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 10, b = 6))
        val foot = W.hbox(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        foot.addView(W.button(ctx, "← 戻る") { onBack() }.apply { minWidth = W.dp(96) }, W.lp(W.WRAP, W.WRAP))
        foot.addView(W.button(ctx, "保存", true) { save() }.apply { minWidth = W.dp(96) }, W.lp(W.WRAP, W.WRAP, l = 8))
        body.addView(foot, W.lp(W.MATCH, W.WRAP, b = 4))
    }

    /** Already-used tags as toggle buttons under the Tag row (tap = add / remove). */
    private fun tagPicker() {
        if (allTags.isEmpty()) return
        val holder = android.widget.FrameLayout(ctx)
        chips = holder
        body.addView(holder, W.lp(W.MATCH, W.WRAP, l = W.dp(4) / maxOf(1, W.px), b = 4))
        renderChips()
    }

    private fun tagState(): Pair<List<String>, String> {
        val s = tags
        val toks = Tree.parseTags(s)
        val last = toks.lastOrNull().orEmpty()
        // still typing a tag (not yet followed by a space and not an existing tag) → filter the chips by it
        val partial = if (s.isEmpty() || s.last().isWhitespace() || last in allTags) "" else last
        return (if (partial.isEmpty()) toks else toks.dropLast(1)) to partial
    }

    private fun renderChips() {
        val h = chips ?: return
        h.removeAllViews()
        val (have, partial) = tagState()
        val list = if (partial.isEmpty()) allTags else
            allTags.filter { it.startsWith(partial, true) } + allTags.filter { !it.startsWith(partial, true) && it.contains(partial, true) }
        h.addView(Parts.tagChips(ctx, list, have.toSet()) { t -> toggleTag(t) })
    }

    fun toggleTag(t: String) {
        val (have, _) = tagState()
        val next = if (t in have) have - t else have + t
        val text = Tree.formatTags(next).let { if (it.isEmpty()) "" else "$it " }
        editors[Order.F_TAGS]?.let { it.setText(text); it.setSelection(text.length) } ?: run { tags = text; dirty = true }
    }

    private fun small(s: String, f: () -> Unit) = W.button(ctx, s) { f() }.apply {
        minWidth = W.dp(52); minHeight = W.dp(36); setPadding(W.dp(6), W.dp(4), W.dp(6), W.dp(4)); textSize = 13f
    }

    private fun labelView(k: String) = W.text(ctx, KeyFields.label(k), 14f, true).apply {
        isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
    }


    private fun copyName(k: String) = when (k) {
        Order.F_TITLE -> "タイトル"; Order.F_USER -> "ユーザー名"; Order.F_PASS -> "パスワード"; Order.F_URL -> "URL"
        Order.F_TAGS -> "タグ"; else -> k
    }

    /**
     * Single-line fields are independent framed boxes ("Label: value"): swipe sideways = copy, long press = edit
     * in place, tap on Pass = show / hide (masked as ****** by default). Memo keeps a plain multi-line box.
     */
    private fun fieldRow(k: String, value: String, multi: Boolean, set: (String) -> Unit) {
        val f = W.field(ctx, multi = multi)
        f.setText(value)
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { set(s.toString()); dirty = true }
        })
        editors[k] = f
        if (multi) {
            body.addView(labelView(k), W.lp(W.MATCH, W.WRAP, t = 6))
            f.minLines = 5
            body.addView(f, W.lp(W.MATCH, W.WRAP, t = 2, b = 4))
            return
        }
        f.isSingleLine = true; f.setHorizontallyScrolling(true)
        val sensitive = k != Order.F_TITLE && k != Order.F_URL && k != Order.F_TAGS && k != Order.F_USER
        val box = io.github.zmlworks.kpexplorer.ui.FieldBox(ctx, KeyFields.label(k), f,
            onSwipe = { copy(copyName(k), f.text.toString(), sensitive) })
        if (k == Order.F_PASS) {
            f.typeface = android.graphics.Typeface.MONOSPACE
            io.github.zmlworks.kpexplorer.ui.FieldBox.mask(f, !passShown)
            box.onTap = { togglePass() }
            box.onLongPress = { passShown = true; io.github.zmlworks.kpexplorer.ui.FieldBox.mask(f, false) }
        }
        boxes[k] = box
        body.addView(box, W.lp(W.MATCH, W.WRAP, t = 2, b = 4))
    }

    fun togglePass() {
        passShown = !passShown
        editors[Order.F_PASS]?.let { io.github.zmlworks.kpexplorer.ui.FieldBox.mask(it, !passShown) }
    }

    private fun otpRow() {
        val o = KeyFields.OtpView(ctx) { values[Order.OTP] }.apply {
            background = W.Bevel(W.Style.FIELD); setPadding(W.dp(8), W.dp(6), W.dp(8), W.dp(6))
        }
        otpView = o
        val box = io.github.zmlworks.kpexplorer.ui.FieldBox(ctx, KeyFields.label(Order.F_OTP), W.field(ctx), view = o,
            onSwipe = { o.current()?.let { copy("ワンタイムパスワード", it, true) } }, onLongPress = { setupOtp() })
        boxes[Order.F_OTP] = box
        body.addView(box, W.lp(W.MATCH, W.WRAP, t = 2, b = 4))
    }

    private fun copy(label: String, s: String, sensitive: Boolean) {
        if (s.isEmpty()) return
        Clip.copy(ctx, label, s, sensitive)
        Toast.makeText(ctx, "${label}をコピーしました", Toast.LENGTH_SHORT).show()
    }

    fun generate(len: Int = 20): String {
        val cs = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#%+-=?@_"
        val r = SecureRandom()
        return (1..len).map { cs[r.nextInt(cs.length)] }.joinToString("")
    }

    fun menu(): List<Menus.Item> = listOf(
        Menus.Item("保存") { save() },
        Menus.sep(),
        Menus.Item("カスタム欄を追加...") { addCustom() },
    ) + Order.DEFAULT_LAYOUT.filter { k -> k != Order.F_OTP && layout.none { it.first == k } }.map { k ->
        Menus.Item("「${KeyFields.label(k).removeSuffix(":")}」欄を戻す") { restoreField(k) }
    } + listOf(
        Menus.Item("欄の編集 (表示・順番・削除)...") { editLayout() },
        Menus.Item(if (values[Order.OTP].isNullOrBlank()) "ワンタイムパスワードを設定..." else "ワンタイムパスワードの設定を変更...") { setupOtp() },
        Menus.Item("パスワードを生成") { editors[Order.F_PASS]?.setText(generate()) ?: run {
            values["Password"] = generate(); if (layout.none { it.first == Order.F_PASS }) layout.add(minOf(1, layout.size), Order.F_PASS to true)
            dirty = true; build() } },
        Menus.sep(),
        Menus.Item("この鍵を削除", entryId != null) { askDelete() }
    )

    fun addCustom() {
        Dlg.prompt(ctx, "カスタム欄を追加", "欄の名前 (例: 暗証番号、秘密の質問):", "") { n ->
            val name = n.trim()
            when {
                name.isEmpty() -> false
                name in Order.STANDARD || name == Order.OTP || values.containsKey(name) || name.startsWith("@") || name.startsWith("-") -> {
                    Dlg.message(ctx, "カスタム欄", "その名前は使えません。", '!'); false
                }
                else -> { addCustomField(name); true }
            }
        }
    }

    /** Put a deleted standard field back (empty), before Memo. */
    fun restoreField(k: String) {
        val at = layout.indexOfFirst { it.first == Order.F_NOTES }.let { if (it < 0) layout.size else it }
        layout.add(at, k to true); dirty = true; build()
    }

    fun addCustomField(name: String, value: String = "") {
        values[name] = value
        layout.add(name to true)
        dirty = true
        build()
    }

    fun editLayout() {
        val work = layout.toMutableList()
        val list = W.vbox(ctx)
        fun render() {
            list.removeAllViews()
            work.forEachIndexed { i, (k, vis) ->
                val r = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                val cb = W.check(ctx, KeyFields.label(k).removeSuffix(":"), vis)
                cb.setOnCheckedChangeListener { _, c -> work[i] = k to c }
                r.addView(cb, W.lp(0, W.WRAP, 1f))
                r.addView(small("↑") { if (i > 0) { work.add(i - 1, work.removeAt(i)); render() } }.apply { minWidth = W.dp(40) }, W.lp(W.WRAP, W.WRAP, l = 2))
                r.addView(small("↓") { if (i < work.size - 1) { work.add(i + 1, work.removeAt(i)); render() } }.apply { minWidth = W.dp(40) }, W.lp(W.WRAP, W.WRAP, l = 2))
                if (k != Order.F_TITLE) r.addView(small("削除") { work.removeAt(i); render() }, W.lp(W.WRAP, W.WRAP, l = 2))
                list.addView(r, W.lp(W.MATCH, W.WRAP, b = 2))
            }
        }
        render()
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, "チェックを外した欄は表示しません (内容は残ります)。「削除」は欄と内容を消します (Title 以外)。", 13f), W.lp(W.MATCH, W.WRAP, b = 8))
        col.addView(list)
        ClassicDialog(ctx, "欄の編集", col, listOf(
            ClassicDialog.Btn("OK", true) {
                captureEditors()
                removeFields(layout.map { it.first }.filter { k -> work.none { it.first == k } })
                layout = work; dirty = true; build(); true
            },
            ClassicDialog.Btn("キャンセル")), scroll = true).show()
    }

    private fun captureEditors() { /* values are kept live by the TextWatchers */ }

    /** Delete fields and their content: customs disappear, standard ones are emptied, Tag / OTP cleared. */
    fun removeFields(keys: List<String>) {
        for (k in keys) when (k) {
            Order.F_TITLE -> {}
            Order.F_TAGS -> tags = ""
            Order.F_OTP -> values.remove(Order.OTP)
            in Order.STANDARD -> values[k] = ""
            else -> values.remove(k)
        }
        layout.removeAll { it.first in keys && it.first != Order.F_TITLE }
        dirty = true
    }

    fun setupOtp() {
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, "otpauth:// で始まる URI (QR コードの中身) か、Base32 の秘密鍵を入力してください。\nKeePassXC と同じ「otp」欄に保存します。", 14f).apply { setLineSpacing(0f, 1.1f) })
        val f = W.field(ctx, "otpauth://totp/...?secret=...").apply { setText(values[Order.OTP].orEmpty()) }
        col.addView(f, W.lp(W.MATCH, W.WRAP, t = 8))
        ClassicDialog(ctx, "ワンタイムパスワード", col, listOf(
            ClassicDialog.Btn("OK", true) {
                val s = f.text.toString().trim()
                if (s.isEmpty()) { setOtp(null); return@Btn true }
                val r = runCatching { setOtp(s) }
                r.exceptionOrNull()?.let { Dlg.message(ctx, "ワンタイムパスワード", "読み取れませんでした: ${it.message}", 'x'); false } ?: true
            },
            ClassicDialog.Btn("削除") { setOtp(null); true },
            ClassicDialog.Btn("キャンセル"))).show()
    }

    /** Validate and store; a bare secret becomes an otpauth URI (issuer = title, account = user). */
    fun setOtp(input: String?) {
        if (input == null) values.remove(Order.OTP)
        else {
            val p = Totp.parse(input)
            values[Order.OTP] = if (input.startsWith("otpauth://", true)) input
                else Totp.toUri(p.copy(issuer = values["Title"].orEmpty(), account = values["UserName"].orEmpty()))
            if (layout.none { it.first == Order.F_OTP }) layout.add(layout.indexOfFirst { it.first == Order.F_NOTES }.coerceAtLeast(0), Order.F_OTP to true)
        }
        dirty = true
        build()
    }

    fun save(): Boolean {
        val d = Session.db ?: return false
        val vals = LinkedHashMap(values)
        val tagList = Tree.parseTags(tags)
        try {
            var id = entryId
            if (id == null) {
                val e = Order.newKey()
                Session.update { Order.appendEntry(it, parent, e) }
                id = e.uuid; entryId = id
            }
            Session.update { Order.saveEntry(it, id, vals, tagList, layout.toList()) }
            Session.flush()
        } catch (t: Throwable) {
            Dlg.message(ctx, "保存", "保存できませんでした。\n$t", 'x'); return false
        }
        if (d !== Session.db || dirty) Toast.makeText(ctx, "保存しました", Toast.LENGTH_SHORT).show()
        dirty = false
        a.explorer?.selection = entryId
        build()
        return true
    }

    private fun askDelete() {
        val id = entryId ?: return
        Dlg.confirm(ctx, "鍵の削除", "「${titleText()}」を削除しますか?", '?') {
            Session.update { Tree.delete(it, id).first }
            dirty = false
            a.showExplorer()
        }
    }

    override fun onBack(): Boolean {
        if (!dirty) { a.showExplorer(); return true }
        confirmUnsaved(ctx, { if (save()) a.showExplorer() }, { dirty = false; a.showExplorer() })
        return true
    }

    companion object {
        /** "変更を保存しますか?" with 保存 / 保存しない / キャンセル. */
        fun confirmUnsaved(ctx: Context, onSave: () -> Unit, onDiscard: () -> Unit): ClassicDialog =
            ClassicDialog(ctx, "KeePass Explorer", W.text(ctx, "変更が保存されていません。\n保存しますか?", 15f), listOf(
                ClassicDialog.Btn("保存する", true) { onSave(); true },
                ClassicDialog.Btn("保存しない") { onDiscard(); true },
                ClassicDialog.Btn("キャンセル"))).also { it.show() }
    }
}
