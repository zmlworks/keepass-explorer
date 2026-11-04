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
        Order.F_TITLE -> "title:"; Order.F_USER -> "User:"; Order.F_PASS -> "Pass:"; Order.F_URL -> "URL:"
        Order.F_TAGS -> "Tag:"; Order.F_OTP -> "ワンタイムパスワード:"; Order.F_NOTES -> "Memo:"; else -> "$k:"
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
 * layout (title / User / Pass / URL / Tag / ワンタイムパスワード / Memo / custom). Passwords are shown in clear.
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
    private var otpView: KeyFields.OtpView? = null
    private lateinit var bar: Parts.AppBar
    private val body = W.vbox(ctx, 8)

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
        sv.addView(body)
        root.addView(sv, W.lp(W.MATCH, 0, 1f))
        build()
    }

    private fun titleText() = values["Title"].orEmpty().ifEmpty { if (entryId == null) "新しい鍵" else "(無題)" }

    private fun build() {
        body.removeAllViews(); editors.clear(); otpView = null
        val d = Session.db ?: return
        val path = Tree.path(Tree.root(d), parent)
        body.addView(Parts.pathTree(ctx, path), W.lp(W.MATCH, W.WRAP))
        body.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 4, b = 6))
        body.addView(W.text(ctx, KeyFields.lastModified(entry()), 14f), W.lp(W.MATCH, W.WRAP, b = 8))
        for ((k, vis) in layout) {
            if (!vis) continue
            when (k) {
                Order.F_OTP -> if (!values[Order.OTP].isNullOrBlank()) otpRow()
                Order.F_TAGS -> fieldRow(k, tags, false) { tags = it }
                Order.F_NOTES -> fieldRow(k, values["Notes"].orEmpty(), true) { values["Notes"] = it }
                else -> {
                    val name = if (k == Order.F_URL) "URL" else k
                    fieldRow(k, values[name].orEmpty(), false) { values[name] = it; if (name == "Title") bar.title.text = titleText() }
                }
            }
        }
    }

    private fun small(s: String, f: () -> Unit) = W.button(ctx, s) { f() }.apply {
        minWidth = W.dp(52); minHeight = W.dp(36); setPadding(W.dp(6), W.dp(4), W.dp(6), W.dp(4)); textSize = 13f
    }

    private fun fieldRow(k: String, value: String, multi: Boolean, set: (String) -> Unit) {
        body.addView(W.text(ctx, KeyFields.label(k), 14f, true), W.lp(W.MATCH, W.WRAP, t = 4))
        val row = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val f = W.field(ctx, multi = multi)
        if (multi) f.minLines = 4
        if (k == Order.F_PASS) f.typeface = android.graphics.Typeface.MONOSPACE
        f.setText(value)
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { set(s.toString()); dirty = true }
        })
        editors[k] = f
        row.addView(f, W.lp(0, W.WRAP, 1f))
        when (k) {
            Order.F_USER -> row.addView(small("コピー") { copy("ユーザー名", f.text.toString(), false) }, W.lp(W.WRAP, W.WRAP, l = 4))
            Order.F_PASS -> {
                row.addView(small("コピー") { copy("パスワード", f.text.toString(), true) }, W.lp(W.WRAP, W.WRAP, l = 4))
                row.addView(small("生成") { f.setText(generate()) }, W.lp(W.WRAP, W.WRAP, l = 4))
            }
            Order.F_TITLE, Order.F_TAGS, Order.F_NOTES, Order.F_URL -> {}
            else -> row.addView(small("コピー") { copy(k, f.text.toString(), true) }, W.lp(W.WRAP, W.WRAP, l = 4))
        }
        body.addView(row, W.lp(W.MATCH, W.WRAP, t = 2, b = 4))
    }

    private fun otpRow() {
        body.addView(W.text(ctx, KeyFields.label(Order.F_OTP), 14f, true), W.lp(W.MATCH, W.WRAP, t = 4))
        val row = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val o = KeyFields.OtpView(ctx) { values[Order.OTP] }.apply {
            background = W.Bevel(W.Style.FIELD); setPadding(W.dp(8), W.dp(6), W.dp(8), W.dp(6))
        }
        otpView = o
        row.addView(o, W.lp(0, W.WRAP, 1f))
        row.addView(small("コピー") { o.current()?.let { copy("ワンタイムパスワード", it, true) } }, W.lp(W.WRAP, W.WRAP, l = 4))
        body.addView(row, W.lp(W.MATCH, W.WRAP, t = 2, b = 4))
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
        Menus.Item("欄の編集 (表示・順番・削除)...") { editLayout() },
        Menus.Item(if (values[Order.OTP].isNullOrBlank()) "ワンタイムパスワードを設定..." else "ワンタイムパスワードの設定を変更...") { setupOtp() },
        Menus.Item("パスワードを生成") { editors[Order.F_PASS]?.setText(generate()) ?: run { values["Password"] = generate(); dirty = true; build() } },
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
                if (k !in Order.DEFAULT_LAYOUT) r.addView(small("削除") { work.removeAt(i); render() }, W.lp(W.WRAP, W.WRAP, l = 2))
                list.addView(r, W.lp(W.MATCH, W.WRAP, b = 2))
            }
        }
        render()
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, "チェックを外した欄は表示しません (内容は残ります)。", 13f), W.lp(W.MATCH, W.WRAP, b = 8))
        col.addView(list)
        ClassicDialog(ctx, "欄の編集", col, listOf(
            ClassicDialog.Btn("OK", true) {
                captureEditors()
                val removed = layout.map { it.first }.filter { k -> work.none { it.first == k } }
                removed.forEach { values.remove(it) }
                layout = work; dirty = true; build(); true
            },
            ClassicDialog.Btn("キャンセル")), scroll = true).show()
    }

    private fun captureEditors() { /* values are kept live by the TextWatchers */ }

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
