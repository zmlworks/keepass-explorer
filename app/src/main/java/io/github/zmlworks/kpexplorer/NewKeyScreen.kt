package io.github.zmlworks.kpexplorer

import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.zmlworks.kpexplorer.db.EntryIcon
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.FieldBox
import io.github.zmlworks.kpexplorer.ui.Glyph
import io.github.zmlworks.kpexplorer.ui.Parts
import io.github.zmlworks.kpexplorer.ui.W
import java.security.SecureRandom
import java.util.UUID

/**
 * Separate create-key screen: plain editable fields (no swipe-to-copy / long-press-to-edit).
 * After 作成, opens the normal [KeyScreen] for the new entry. Empty form discards silently; empty fields are
 * not added to the new key (see [createdFields]).
 */
class NewKeyScreen(private val a: MainActivity, private val parent: UUID) : MainActivity.Screen {
    private val ctx = a
    private val root = W.vbox(ctx).apply { setBackgroundColor(W.FACE) }
    override val view: View get() = root

    val values = LinkedHashMap<String, String>().also {
        listOf("Title", "UserName", "Password", "URL", Order.OTP, "Notes").forEach { k -> it[k] = "" }
    }
    var tags = ""
    var iconId: String? = null
    val multi = LinkedHashSet<String>()
    /** Custom fields in display order (name → always visible). */
    val customs = ArrayList<String>()
    /** New-key Pass starts visible (no eye button). After 作成 → KeyScreen masks with ******. */
    var passShown = true
        private set

    val editors = LinkedHashMap<String, EditText>()
    private val allTags: List<String> = Session.db?.let { Tree.allTags(it) } ?: emptyList()
    private var chips: android.widget.FrameLayout? = null
    private lateinit var bar: Parts.AppBar
    private val body = W.vbox(ctx, 8)
    lateinit var scroll: ScrollView
        private set
    var iconTile: View? = null
        private set
    var addButton: View? = null
        private set
    var nameField: EditText? = null
    var multiRadio: android.widget.RadioButton? = null
    var genButton: View? = null
        private set
    var eyeButton: View? = null
        private set

    /** Any typed content, custom field, or non-default icon. */
    val dirty: Boolean
        get() {
            if (iconId != null) return true
            if (customs.isNotEmpty()) return true
            if (Tree.parseTags(tags).isNotEmpty()) return true
            return values.any { (_, v) -> v.isNotEmpty() }
        }

    /** 0.4.10: something was actually typed (a value or a tag). An icon or an empty added field alone is not. */
    val hasInput: Boolean
        get() = Tree.parseTags(tags).isNotEmpty() || values.any { (_, v) -> v.isNotEmpty() }

    init {
        bar = Parts.appBar(ctx, L.t("新しい鍵", "New key"), onBack = { onBack() })
        root.addView(bar.bar, W.lp())
        val sv = ScrollView(ctx).apply { isFillViewport = true; W.classicScrollbars(this) }
        scroll = sv
        sv.addView(body)
        root.addView(sv, W.lp(W.MATCH, 0, 1f))
        build()
    }

    private fun build() {
        body.removeAllViews(); editors.clear()
        val d = Session.db ?: return
        val path = Tree.path(Tree.root(d), parent)
        val head = W.hbox(ctx).apply { gravity = Gravity.TOP }
        val tile = android.widget.FrameLayout(ctx).apply {
            background = W.Bevel(W.Style.RAISED); contentDescription = L.t("アイコン (タップで変更)", "Icon (tap to change)")
            isClickable = true; setOnClickListener { pickIcon() }
        }
        tile.addView(ImageView(ctx).apply {
            setImageDrawable(io.github.zmlworks.kpexplorer.ui.FugueIcons.drawable(ctx, iconId))
        }, android.widget.FrameLayout.LayoutParams(W.dp(52), W.dp(52), Gravity.CENTER))
        iconTile = tile
        head.addView(tile, W.lp(W.dp(84), W.dp(84), r = 10, t = 4))
        val info = W.vbox(ctx)
        info.addView(Parts.pathTree(ctx, path).apply { setPadding(0, 0, 0, W.dp(2)) }, W.lp(W.MATCH, W.WRAP))
        info.addView(W.text(ctx, L.t("新しい鍵を作成", "Create a new key"), 13f, false, W.SHADOW).apply {
            isSingleLine = true
        }, W.lp(W.MATCH, W.WRAP, t = 4))
        head.addView(info, W.lp(0, W.WRAP, 1f))
        body.addView(head, W.lp(W.MATCH, W.WRAP, b = 10))

        inputRow(Order.F_TITLE, "Title", Glyph(Glyph.Kind.TITLE), false)
        inputRow(Order.F_USER, "UserName", Glyph(Glyph.Kind.USER), false)
        passRow()
        inputRow(Order.F_URL, "URL", Glyph(Glyph.Kind.URL), false)
        // 0.4.11: OTP on the create form (otpauth:// URI or Base32 secret); empty = not added (restorable later)
        inputRow(Order.F_OTP, Order.OTP, Glyph(Glyph.Kind.OTP), false)
        editors[Order.F_OTP]?.hint = L.t("otpauth://… または Base32 の秘密鍵", "otpauth://… or a Base32 secret")
        inputRow(Order.F_NOTES, "Notes", Glyph(Glyph.Kind.MEMO), true)
        for (name in customs) {
            inputRow(name, name, Glyph(Glyph.Kind.CUSTOM), name in multi)
        }
        // 0.4.5: Tag below Memo
        inputRow(Order.F_TAGS, Order.F_TAGS, Glyph(Glyph.Kind.TAG), false, isTags = true)
        tagPicker()

        val add = W.hbox(ctx).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.FLAT_TOOL); isClickable = true
            contentDescription = L.t("カスタム項目の追加", "Add custom field"); setPadding(W.dp(12), W.dp(6), W.dp(12), W.dp(6))
            setOnClickListener { addCustom() }
        }
        add.addView(ImageView(ctx).apply { setImageDrawable(Glyph(Glyph.Kind.PLUS)) }, W.lp(W.dp(22), W.dp(22), r = 6))
        add.addView(W.text(ctx, L.t("カスタム項目の追加", "Add custom field"), 15f, true, W.NAVY), W.lp(W.WRAP, W.WRAP))
        addButton = add
        body.addView(add, W.lp(W.WRAP, W.WRAP, t = 6).apply { gravity = Gravity.CENTER_HORIZONTAL })

        body.addView(View(ctx), W.lp(W.MATCH, 0, 1f))
        body.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 10))
        body.addView(createFooter(), W.lp(W.MATCH, W.WRAP))
    }

    private fun createFooter(): LinearLayout {
        val foot = W.hbox(ctx).apply { gravity = Gravity.CENTER; setPadding(W.dp(6), W.dp(8), W.dp(6), W.dp(8)) }
        foot.addView(W.text(ctx, L.t("キャンセル", "Cancel"), 17f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON); isClickable = true
            contentDescription = L.t("キャンセル", "Cancel"); setOnClickListener { onBack() }
        }, W.lp(0, W.dp(56), 1f, r = 6))
        val sv = W.hbox(ctx).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.DEFAULT_BUTTON); isClickable = true
            contentDescription = L.t("作成", "Create"); setOnClickListener { create() }
        }
        sv.addView(ImageView(ctx).apply { setImageDrawable(io.github.zmlworks.kpexplorer.ui.FugueIcons.drawable(ctx, "key")) }, W.lp(W.dp(24), W.dp(24), r = 8))
        sv.addView(W.text(ctx, L.t("作成", "Create"), 17f, true), W.lp(W.WRAP, W.WRAP))
        foot.addView(sv, W.lp(0, W.dp(56), 1f, l = 6))
        return foot
    }

    /** Always-editable cream well (no swipe / long-press). */
    private fun inputRow(layoutKey: String, valueKey: String, icon: android.graphics.drawable.Drawable, multiLine: Boolean, isTags: Boolean = false) {
        val f = W.field(ctx, multi = multiLine).apply {
            background = null
            setPadding(0, W.dp(1), 0, W.dp(1))
            textSize = 16f
            if (multiLine) { minLines = 4; gravity = Gravity.TOP or Gravity.START }
            else { isSingleLine = true; setHorizontallyScrolling(true) }
            setText(if (isTags) tags else values[valueKey].orEmpty())
            KeyFields.valueFont(this, layoutKey)
        }
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isTags) { tags = s.toString(); renderChips() }
                else {
                    values[valueKey] = s.toString()
                    if (valueKey == "Title") bar.title.text = s.toString().ifEmpty { L.t("新しい鍵", "New key") }
                }
            }
        })
        editors[layoutKey] = f
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = W.Bevel(W.Style.FIELD, FieldBox.FILL)
            setPadding(W.dp(6), W.dp(4), W.dp(4), W.dp(4))
            contentDescription = KeyFields.name(layoutKey)
        }
        box.addView(ImageView(ctx).apply { setImageDrawable(icon) }, W.lp(W.dp(22), W.dp(22), r = 8))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(W.text(ctx, KeyFields.name(layoutKey), 11f, false, W.NAVY).apply {
            isSingleLine = true
        }, W.lp(W.MATCH, W.WRAP))
        col.addView(f, W.lp(W.MATCH, W.WRAP))
        box.addView(col, W.lp(0, W.WRAP, 1f))
        body.addView(box, W.lp(W.MATCH, W.WRAP, t = 3, b = 3))
    }

    private fun passRow() {
        val f = W.field(ctx).apply {
            background = null; setPadding(0, W.dp(1), 0, W.dp(1)); textSize = 16f
            isSingleLine = true; typeface = android.graphics.Typeface.MONOSPACE
            setText(values["Password"].orEmpty())
            // Visible by default on create. If ever remasked, use length-preserving PTM
            // (never FieldBox.Stars on editable text — 0.4.1 crash).
            transformationMethod = if (passShown) null else PasswordTransformationMethod.getInstance()
        }
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { values["Password"] = s.toString() }
        })
        editors[Order.F_PASS] = f
        // No eye / show-hide button (0.4.2). Tap the field to toggle if remasked after state change.
        f.setOnClickListener { if (!passShown) togglePass(show = true) }
        val gen = W.text(ctx, L.t("生成", "Generate"), 13f, true).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.BUTTON)
            contentDescription = L.t("パスワードを生成", "Generate password"); setPadding(W.dp(8), W.dp(4), W.dp(8), W.dp(4))
            isClickable = true; setOnClickListener { askGenerate() }
        }
        genButton = gen
        eyeButton = null
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = W.Bevel(W.Style.FIELD, FieldBox.FILL)
            setPadding(W.dp(6), W.dp(4), W.dp(4), W.dp(4))
            contentDescription = "Pass"
        }
        box.addView(ImageView(ctx).apply { setImageDrawable(Glyph(Glyph.Kind.PASS)) }, W.lp(W.dp(22), W.dp(22), r = 8))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(W.text(ctx, "Pass", 11f, false, W.NAVY).apply { isSingleLine = true }, W.lp(W.MATCH, W.WRAP))
        col.addView(f, W.lp(W.MATCH, W.WRAP))
        box.addView(col, W.lp(0, W.WRAP, 1f))
        box.addView(gen, W.lp(W.WRAP, W.dp(36), l = 4))
        body.addView(box, W.lp(W.MATCH, W.WRAP, t = 3, b = 3))
    }

    /** 0.4.4: confirm before generating (also when Pass is empty). Cancel leaves Pass unchanged. */
    fun askGenerate() {
        Dlg.confirm(ctx, ctx.getString(R.string.gen_confirm_title), ctx.getString(R.string.gen_confirm_msg), '?',
            yes = ctx.getString(R.string.gen_confirm_yes), no = ctx.getString(R.string.cancel)) {
            editors[Order.F_PASS]?.setText(generate()); togglePass(show = true)
        }
    }

    /** Ends text input (focus + keyboard) without leaving the form. True if something was being edited. */
    fun endTextInput(): Boolean {
        val f = view.findFocus() as? EditText ?: return false
        f.clearFocus()
        (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(f.windowToken, 0)
        return true
    }

    override fun onImeHidden() { view.findFocus()?.let { if (it is EditText) it.clearFocus() } }

    fun togglePass(show: Boolean? = null) {
        passShown = show ?: !passShown
        editors[Order.F_PASS]?.let { f ->
            val start = f.selectionStart
            val end = f.selectionEnd
            f.transformationMethod = if (passShown) null else PasswordTransformationMethod.getInstance()
            val len = f.text?.length ?: 0
            if (len >= 0 && start >= 0) {
                f.setSelection(start.coerceIn(0, len), end.coerceIn(0, len))
            }
        }
    }

    fun generate(len: Int = 20): String {
        val cs = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#%+-=?@_"
        val r = SecureRandom()
        return (1..len).map { cs[r.nextInt(cs.length)] }.joinToString("")
    }

    private fun tagPicker() {
        if (allTags.isEmpty()) return
        val holder = android.widget.FrameLayout(ctx)
        chips = holder
        body.addView(holder, W.lp(W.MATCH, W.WRAP, l = 4, b = 4))
        renderChips()
    }

    private fun tagState(): Pair<List<String>, String> = io.github.zmlworks.kpexplorer.db.TagEdit.state(tags, allTags)

    private fun renderChips() {
        val h = chips ?: return
        h.removeAllViews()
        val (have, partial) = tagState()
        val list = if (partial.isEmpty()) allTags else
            allTags.filter { it.startsWith(partial, true) } +
                allTags.filter { !it.startsWith(partial, true) && it.contains(partial, true) }
        h.addView(Parts.tagChips(ctx, list, have.toSet()) { t -> toggleTag(t) })
    }

    fun toggleTag(t: String) {
        val text = io.github.zmlworks.kpexplorer.db.TagEdit.toggle(tags, allTags, t)
        editors[Order.F_TAGS]?.let { it.setText(text); it.setSelection(text.length) } ?: run { tags = text; renderChips() }
    }

    fun pickIcon(): ClassicDialog {
        lateinit var dlg: ClassicDialog
        val grid = io.github.zmlworks.kpexplorer.ui.FugueIcons.pickerGrid(ctx, iconId) { id -> setIcon(id); dlg.dismiss() }
        dlg = ClassicDialog(ctx, L.t("アイコンの変更", "Change icon"), grid, listOf(ClassicDialog.Btn(L.t("キャンセル", "Cancel"))), scroll = true)
        dlg.show()
        return dlg
    }

    fun setIcon(id: String) { iconId = if (id == "key") null else id; build() }

    fun addCustom(): ClassicDialog {
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, L.t("欄の名前 (例: 暗証番号、秘密の質問):", "Field name (e.g. PIN, security question):"), 14f))
        val f = W.field(ctx).apply { isSingleLine = true }
        col.addView(f, W.lp(W.MATCH, W.WRAP, t = 4))
        col.addView(W.text(ctx, L.t("種類:", "Type:"), 14f), W.lp(W.MATCH, W.WRAP, t = 10))
        val rg = android.widget.RadioGroup(ctx)
        val r1 = W.radio(ctx, L.t("1 行 (ID・番号など)", "Single line (IDs, numbers…)")).apply { id = View.generateViewId() }
        val r2 = W.radio(ctx, L.t("複数行 (メモのように改行・折り返し)", "Multi-line (line breaks and wrapping, like notes)")).apply { id = View.generateViewId() }
        rg.addView(r1); rg.addView(r2); rg.check(r1.id)
        col.addView(rg)
        val dlg = ClassicDialog(ctx, L.t("カスタム項目の追加", "Add custom field"), col, listOf(
            ClassicDialog.Btn(L.t("追加", "Add"), true) {
                val name = f.text.toString().trim()
                when {
                    name.isEmpty() -> false
                    name in Order.STANDARD || name == Order.OTP || values.containsKey(name) ||
                        name in customs || name.startsWith("@") || name.startsWith("-") -> {
                        Dlg.message(ctx, L.t("カスタム項目の追加", "Add custom field"), L.t("その名前は使えません。", "That name cannot be used."), '!'); false
                    }
                    else -> { addCustomField(name, multiline = rg.checkedRadioButtonId == r2.id); true }
                }
            },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel"))))
        dlg.show()
        nameField = f; multiRadio = r2
        return dlg
    }

    fun addCustomField(name: String, value: String = "", multiline: Boolean = false) {
        values[name] = value
        if (multiline) multi.add(name)
        customs.add(name)
        build()
    }

    companion object {
        /**
         * 0.4.10 (t565u): a field left empty on the create form counts as deleted from the start — it is not
         * put in the key's field layout (same state as deleting it with 「項目の編集」 afterwards; it can be
         * brought back from the hidden zone there). Empty custom fields are not created at all. The five
         * KeePass standard strings stay in the KDBX as empty values (KeePass always has them), but are not
         * shown. Title always stays ("(無題)" when empty). An empty OTP leaves @OTP out (0.4.11: the form has an
         * OTP field; it can be added back from 「項目の編集」's hidden zone or set later by long-pressing).
         */
        fun createdFields(values: Map<String, String>, customs: List<String>, tagList: List<String>): Pair<LinkedHashMap<String, String>, List<Pair<String, Boolean>>> {
            val vals = LinkedHashMap<String, String>()
            for ((k, v) in values) if (k in Order.STANDARD || (if (k == Order.OTP) v.isNotBlank() else v.isNotEmpty())) vals[k] = v
            if (vals["Title"].isNullOrBlank()) vals["Title"] = L.t("(無題)", "(Untitled)")
            val layout = Order.DEFAULT_LAYOUT.filter { k ->
                when (k) {
                    Order.F_TITLE -> true
                    Order.F_TAGS -> tagList.isNotEmpty()
                    Order.F_OTP -> values[Order.OTP].orEmpty().isNotBlank()
                    else -> values[k].orEmpty().isNotEmpty()
                }
            }.map { it to true }.toMutableList()
            val notesAt = layout.indexOfFirst { it.first == Order.F_NOTES }.let { if (it < 0) layout.indexOfFirst { p -> p.first == Order.F_TAGS }.let { t -> if (t < 0) layout.size else t } else it }
            customs.filter { values[it].orEmpty().isNotEmpty() }.forEachIndexed { i, c -> layout.add(notesAt + i, c to true) }
            return vals to layout
        }
    }

    /**
     * OTP to store: "" when empty, an otpauth:// URI as typed, or a bare Base32 secret turned into a URI
     * (issuer = title, account = user, like the key screen). null (after a message) when it cannot be read.
     */
    fun otpValue(): String? {
        val s = values[Order.OTP].orEmpty().trim()
        if (s.isEmpty()) return ""
        return try {
            val p = io.github.zmlworks.kpexplorer.db.Totp.parse(s)
            if (s.startsWith("otpauth://", true)) s
            else io.github.zmlworks.kpexplorer.db.Totp.toUri(p.copy(issuer = values["Title"].orEmpty(), account = values["UserName"].orEmpty()))
        } catch (t: Exception) {
            Dlg.message(ctx, L.t("ワンタイムパスワード", "One-time password"), L.t("OTP を読み取れませんでした: ${t.message}\n空欄にするか、otpauth:// の URI か Base32 の秘密鍵を入力してください。", "Could not read the OTP: ${t.message}\nLeave it empty, or enter an otpauth:// URI or a Base32 secret."), 'x')
            null
        }
    }

    /** Create the entry, flush, then open the normal key (view) screen. */
    fun create(): Boolean {
        if (!hasInput) {
            Toast.makeText(ctx, L.t("何も入力されていないため、作成しません", "Nothing entered, so nothing was created"), Toast.LENGTH_SHORT).show()
            return false
        }
        try {
            val e = Order.newKey()
            val id = e.uuid
            val tagList = Tree.parseTags(tags)
            val otp = otpValue() ?: return false
            val (vals, layout) = createdFields(LinkedHashMap(values).apply { this[Order.OTP] = otp }, customs, tagList)
            val ic = EntryIcon.def(iconId)
            Session.update { db ->
                var d = Order.appendEntry(db, parent, e)
                Order.saveEntry(d, id, vals, tagList, layout,
                    cdSet = mapOf(EntryIcon.CD to iconId, Order.CD_MULTI to multi.filter { it in vals }.joinToString("\n")),
                    icon = ic?.std ?: app.keemobile.kotpass.constants.PredefinedIcon.Key)
            }
            Session.flush()
            a.explorer?.selection = id
            a.explorer?.refresh()
            Toast.makeText(ctx, L.t("作成しました", "Created"), Toast.LENGTH_SHORT).show()
            a.show(KeyScreen(a, id, parent))
            return true
        } catch (t: Throwable) {
            Dlg.message(ctx, L.t("作成", "Create"), L.t("作成できませんでした。\n$t", "Could not create it.\n$t"), 'x')
            return false
        }
    }

    override fun onBack(): Boolean {
        if (endTextInput()) return true   // first Back: stop typing, stay on the form
        if (!dirty || !hasInput) { a.showExplorer(); return true }  // nothing typed: discard silently
        ClassicDialog(ctx, "KeePass Explorer",
            W.text(ctx, L.t("入力内容がまだ作成されていません。\n作成しますか?", "What you entered has not been created yet.\nCreate it?"), 15f),
            listOf(
                ClassicDialog.Btn(L.t("作成する", "Create"), true) { create(); true },
                ClassicDialog.Btn(L.t("破棄する", "Discard")) { a.showExplorer(); true },
                ClassicDialog.Btn(L.t("キャンセル", "Cancel")))).show()
        return true
    }
}
