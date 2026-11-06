package io.github.zmlworks.kpexplorer

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.keemobile.kotpass.models.Entry
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Totp
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Glyph
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
    /** Small label shown above the value in a field well. */
    fun name(k: String) = label(k).removeSuffix(":")
    fun lastModified(e: Entry?): String = "Last Modified: " + (e?.times?.lastModificationTime?.let { fmt.format(it) } ?: "-")

    /** Live TOTP text: "123 456 (10s)", refreshed every second while attached. */
    class OtpView(ctx: Context, private val uri: () -> String?) : TextView(ctx) {
        private val tick = object : Runnable { override fun run() { update(); postDelayed(this, 1000) } }
        init { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f); typeface = android.graphics.Typeface.MONOSPACE; setTextColor(W.TEXT); update() }
        fun current(): String? = runCatching { uri()?.let { Totp.code(Totp.parse(it), Instant.now().epochSecond) } }.getOrNull()
        fun update() {
            val u = uri()
            text = if (u.isNullOrBlank()) "" else runCatching {
                val p = Totp.parse(u); val now = Instant.now().epochSecond
                val c = Totp.code(p, now)
                "${c.chunked(3).joinToString(" ")} (${Totp.remaining(p, now)}s)"
            }.getOrElse { "⚠ 設定が不正です" }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    }
}

/**
 * Key (password entry) window: ← + title, folder path tree, Last Modified, then the fields in the per-key
 * layout (Title / User / Pass / URL / Tag / OTP / Memo / custom), each "Label: [input]" on one line except Memo.
 * Navy header band + plain gray rows with thin separators (proposal #3). Pass ****** until tapped. 戻る / 保存 at bottom.
 * [entryId] null = new key in [parent], created on the first save.
 */
class KeyScreen(private val a: MainActivity, private var entryId: UUID?, private val parent: UUID) : MainActivity.Screen {
    private val ctx = a
    private val root = W.vbox(ctx).apply { setBackgroundColor(W.FACE) }
    override val view: View get() = root
    val values = LinkedHashMap<String, String>()
    var tags = ""
    var layout: MutableList<Pair<String, Boolean>> = Order.DEFAULT_LAYOUT.map { it to true }.toMutableList()
    /**
     * Unsaved changes = current state differs from the state last loaded / saved. Computed (not a flag), so
     * re-building the views after 保存 (which re-sets every field's text) can't make the screen look dirty again.
     * Assigning false takes a new baseline; assigning true is ignored (any real change already shows).
     */
    var dirty: Boolean
        get() = snapshot() != baseline
        set(v) { if (!v) baseline = snapshot() }
    private var baseline: Any? = null
    private fun snapshot(): Any = listOf(LinkedHashMap(values).toSortedMap(),
        Tree.parseTags(tags), layout.toList(), iconId, multi.toSortedSet())
    /** Chosen icon id ([EntryIcon]); null = default key. */
    var iconId: String? = null
    /** Custom fields shown multi-line (memo-like). */
    val multi = LinkedHashSet<String>()
    /** Existing-tag buttons are hidden until the Tag field is long-pressed. */
    var tagsShown = false
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
            iconId = io.github.zmlworks.kpexplorer.db.EntryIcon.of(e)?.id
            multi.addAll(Order.multiline(e))
        } else listOf("Title", "UserName", "Password", "URL", "Notes").forEach { values[it] = "" }
        bar = Parts.keyDetailBar(ctx, titleText(), onClose = { onBack() })
        root.addView(bar.bar, W.lp())
        val sv = ScrollView(ctx).apply { isFillViewport = true; W.classicScrollbars(this) }
        scroll = sv
        sv.addView(body)
        root.addView(sv, W.lp(W.MATCH, 0, 1f))
        build()
        dirty = false
    }

    private fun titleText() = values["Title"].orEmpty().ifEmpty { if (entryId == null) "新しい鍵" else "(無題)" }
    fun barTitle(): String = bar.title.text.toString()

    /**
     * Layout after the KeePassDX entry screen, drawn in Windows 98 style: a raised key tile with folder path
     * (└ tree with counts) and Last Modified beside it, then one sunken well per field (glyph, small label,
     * value, trailing action) with a ⋮ menu button, 「欄を追加」, and 戻る / 保存 at the bottom.
     */
    private fun build() {
        body.removeAllViews(); editors.clear(); boxes.clear(); otpView = null; tableHost = null
        val d = Session.db ?: return
        val path = Tree.path(Tree.root(d), parent)
        // Proposal #3: navy header band with icon tile + light path / Last Modified
        val band = W.hbox(ctx).apply {
            setBackgroundColor(W.BAND_SOFT); gravity = Gravity.TOP
            setPadding(W.dp(12), W.dp(12), W.dp(12), W.dp(14))
        }
        val tile = android.widget.FrameLayout(ctx).apply {
            background = W.Bevel(W.Style.RAISED)
            contentDescription = "アイコン (ダブルタップまたは長押しで変更)"
            isClickable = true
            val gd = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent) = true
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean { pickIcon(); return true }
                override fun onLongPress(e: android.view.MotionEvent) { pickIcon() }
            })
            setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
        }
        tile.addView(ImageView(ctx).apply { setImageDrawable(io.github.zmlworks.kpexplorer.ui.MaterialIcons.drawable(ctx, iconId)) },
            android.widget.FrameLayout.LayoutParams(W.dp(52), W.dp(52), Gravity.CENTER))
        iconTile = tile
        band.addView(tile, W.lp(W.dp(84), W.dp(84), r = 12))
        val info = W.vbox(ctx)
        path.forEachIndexed { i, g ->
            val name = if (i == 0) "/ (${g.groups.size},${g.entries.size})" else "└ ${g.name.ifEmpty { "(名前なし)" }} (${g.groups.size},${g.entries.size})"
            info.addView(W.text(ctx, name, 15f, bold = i == path.size - 1, color = W.HILITE).apply {
                isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(W.dp(12) * i, 0, 0, W.dp(2))
            }, W.lp(W.MATCH, W.WRAP))
        }
        info.addView(W.text(ctx, "Last Modified: " + (entry()?.times?.lastModificationTime?.let { KeyFields.fmt.format(it) } ?: "-"),
            13f, color = 0xFFD6E4F7.toInt()).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }.also { lastMod = it }, W.lp(W.MATCH, W.WRAP, t = 8))
        band.addView(info, W.lp(0, W.WRAP, 1f))
        body.addView(band, W.lp(W.MATCH, W.WRAP, b = 4))
        // Plain rows (no cream/white boxes)
        for ((k, vis) in layout) {
            if (!vis) continue
            when (k) {
                Order.F_OTP -> if (!values[Order.OTP].isNullOrBlank()) otpRow()
                Order.F_TAGS -> { fieldRow(k, tags, false) { tags = it; renderChips() }; tagPicker() }
                Order.F_NOTES -> fieldRow(k, values["Notes"].orEmpty(), true) { values["Notes"] = it }
                else -> fieldRow(k, values[k].orEmpty(), k in multi) { values[k] = it; if (k == "Title") bar.title.text = titleText() }
            }
        }
        val rowBtns = W.hbox(ctx).apply {
            gravity = Gravity.CENTER; setPadding(W.dp(4), W.dp(4), W.dp(4), W.dp(4))
        }
        fun toolChip(desc: String, label: String, plus: Boolean, click: () -> Unit): View {
            val chip = W.hbox(ctx).apply {
                gravity = Gravity.CENTER; background = W.Bevel(W.Style.FLAT_TOOL); isClickable = true
                contentDescription = desc; setPadding(W.dp(10), W.dp(6), W.dp(10), W.dp(6)); setOnClickListener { click() }
            }
            if (plus) chip.addView(ImageView(ctx).apply { setImageDrawable(Glyph(Glyph.Kind.PLUS)) }, W.lp(W.dp(20), W.dp(20), r = 4))
            chip.addView(W.text(ctx, label, 14f, true, W.NAVY).apply { isSingleLine = true }, W.lp(W.WRAP, W.WRAP))
            return chip
        }
        val edit = toolChip("欄を編集", "欄を編集", plus = false) { editLayout() }
        val add = toolChip("欄を追加", "欄を追加", plus = true) { addCustom() }
        editButton = edit; addButton = add
        rowBtns.addView(edit, W.lp(W.WRAP, W.WRAP, r = 8))
        rowBtns.addView(add, W.lp(W.WRAP, W.WRAP))
        body.addView(rowBtns, W.lp(W.MATCH, W.WRAP, t = 10))
        body.addView(View(ctx), W.lp(W.MATCH, 0, 1f))
        // Infrequent actions formerly in titlebar ⋮ (OTP setup / delete)
        val extras = W.hbox(ctx).apply { gravity = Gravity.CENTER; setPadding(W.dp(4), W.dp(2), W.dp(4), W.dp(2)) }
        extras.addView(W.text(ctx,
            if (values[Order.OTP].isNullOrBlank()) "OTPを設定" else "OTP設定を変更",
            13f, true, W.NAVY).apply {
            gravity = Gravity.CENTER; background = W.Bevel(W.Style.FLAT_TOOL); isClickable = true
            contentDescription = text.toString(); setPadding(W.dp(10), W.dp(6), W.dp(10), W.dp(6))
            setOnClickListener { setupOtp() }
        }, W.lp(W.WRAP, W.WRAP, r = 8))
        if (entryId != null) {
            extras.addView(W.text(ctx, "この鍵を削除", 13f, true, W.DARK).apply {
                gravity = Gravity.CENTER; background = W.Bevel(W.Style.FLAT_TOOL); isClickable = true
                contentDescription = "この鍵を削除"; setPadding(W.dp(10), W.dp(6), W.dp(10), W.dp(6))
                setOnClickListener { askDelete() }
            }, W.lp(W.WRAP, W.WRAP))
        }
        body.addView(extras, W.lp(W.MATCH, W.WRAP, t = 4, b = 2))
        body.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 6))
        body.addView(Parts.footer(ctx, onBack = { onBack() }, onSave = { save() }), W.lp(W.MATCH, W.WRAP))
    }

    private var tableHost: android.widget.LinearLayout? = null
    private var rowIndex = 0

    var iconTile: View? = null
        private set

    /** Icon picker: large nearly-square tiles, glyphs only (names via contentDescription). */
    fun pickIcon(): ClassicDialog {
        lateinit var dlg: ClassicDialog
        val grid = io.github.zmlworks.kpexplorer.ui.MaterialIcons.pickerGrid(ctx, iconId) { id -> setIcon(id); dlg.dismiss() }
        dlg = ClassicDialog(ctx, "アイコンの変更", grid, listOf(ClassicDialog.Btn("キャンセル")), scroll = true)
        dlg.show()
        return dlg
    }

    fun setIcon(id: String) { iconId = if (id == "key") null else id; build() }

    private var lastMod: TextView? = null
    var addButton: View? = null
        private set
    var editButton: View? = null
        private set

    private fun glyphFor(k: String): android.graphics.drawable.Drawable = Glyph(when (k) {
        Order.F_TITLE -> Glyph.Kind.TITLE; Order.F_USER -> Glyph.Kind.USER; Order.F_PASS -> Glyph.Kind.PASS
        Order.F_URL -> Glyph.Kind.URL; Order.F_TAGS -> Glyph.Kind.TAG; Order.F_OTP -> Glyph.Kind.OTP
        Order.F_NOTES -> Glyph.Kind.MEMO; else -> Glyph.Kind.CUSTOM
    })

    /** Moves field [k] one visible step up (-1) or down (+1) in the per-key layout. */
    fun moveField(k: String, dir: Int) {
        val i = layout.indexOfFirst { it.first == k }
        if (i < 0) return
        var j = i + dir
        while (j in layout.indices && !layout[j].second) j += dir
        if (j !in layout.indices) return
        val item = layout.removeAt(i); layout.add(j, item)
        build()
    }

    fun openUrl(u: String) {
        if (u.isBlank()) return
        val uri = android.net.Uri.parse(if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)) u else "https://$u")
        runCatching { a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(ctx, "開けませんでした", Toast.LENGTH_SHORT).show() }
    }

    /** Already-used tags as toggle buttons under the Tag row (tap = add / remove). */
    private fun tagPicker() {
        if (allTags.isEmpty()) return
        val holder = android.widget.FrameLayout(ctx).apply { visibility = if (tagsShown) View.VISIBLE else View.GONE }
        chips = holder
        body.addView(holder, W.lp(W.MATCH, W.WRAP, l = 4, b = 4))
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

    /** Long-press on the Tag field: show the existing-tag buttons (most used first). */
    fun showTagButtons() { tagsShown = true; chips?.visibility = View.VISIBLE; renderChips() }

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
     * Every field is a sunken well (glyph, small label, value): swipe sideways = copy, long press = edit in place,
     * tap on Pass (or its eye button) = show / hide (masked as ****** by default). URL has an open-link button.
     */
    private fun fieldRow(k: String, value: String, multi: Boolean, set: (String) -> Unit) {
        val f = W.field(ctx, multi = multi)
        f.setText(value)
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { set(s.toString()) }
        })
        editors[k] = f
        if (multi) { f.minLines = 3; f.gravity = Gravity.TOP or Gravity.START } else { f.isSingleLine = true; f.setHorizontallyScrolling(true) }
        f.textSize = 17f
        val sensitive = k != Order.F_TITLE && k != Order.F_URL && k != Order.F_TAGS && k != Order.F_USER && k != Order.F_NOTES
        rowIndex++
        val trail: View? = when (k) {
            Order.F_PASS -> W.text(ctx, "生成", 13f, true, W.NAVY).apply {
                gravity = Gravity.CENTER; background = W.Bevel(W.Style.FLAT_TOOL); isClickable = true
                contentDescription = "パスワードを生成"; setPadding(W.dp(6), W.dp(4), W.dp(6), W.dp(4))
                setOnClickListener { f.setText(generate()) }
            }
            Order.F_URL -> ImageView(ctx).apply {
                setImageDrawable(Glyph(Glyph.Kind.OPEN, W.NAVY))
                contentDescription = "URL をブラウザで開く"; scaleType = ImageView.ScaleType.CENTER
                isClickable = true; setOnClickListener { openUrl(f.text.toString()) }
            }
            else -> null
        }
        val box = io.github.zmlworks.kpexplorer.ui.FieldBox(ctx, KeyFields.name(k), f,
            onSwipe = { copy(copyName(k), f.text.toString(), sensitive) }, icon = glyphFor(k),
            trailing = trail, plain = true)
        if (k == Order.F_TAGS) box.onLongPress = { showTagButtons() }
        if (k == Order.F_PASS) {
            f.typeface = android.graphics.Typeface.MONOSPACE
            io.github.zmlworks.kpexplorer.ui.FieldBox.mask(f, !passShown)
            box.onTap = { togglePass() }
            box.onLongPress = { passShown = true; io.github.zmlworks.kpexplorer.ui.FieldBox.mask(f, false) }
        }
        boxes[k] = box
        body.addView(box, W.lp(W.MATCH, W.WRAP))
        // thin separator (proposal #3)
        body.addView(View(ctx).apply { setBackgroundColor(W.SHADOW) }, W.lp(W.MATCH, W.px, l = 8, r = 8))
        body.addView(View(ctx).apply { setBackgroundColor(W.HILITE) }, W.lp(W.MATCH, W.px, l = 8, r = 8))
    }

    fun togglePass() {
        passShown = !passShown
        editors[Order.F_PASS]?.let { io.github.zmlworks.kpexplorer.ui.FieldBox.mask(it, !passShown) }
    }

    private fun otpRow() {
        val o = KeyFields.OtpView(ctx) { values[Order.OTP] }
        otpView = o
        rowIndex++
        val box = io.github.zmlworks.kpexplorer.ui.FieldBox(ctx, KeyFields.name(Order.F_OTP), W.field(ctx), view = o,
            onSwipe = { o.current()?.let { copy("ワンタイムパスワード", it, true) } }, onLongPress = { setupOtp() },
            icon = glyphFor(Order.F_OTP), plain = true)
        boxes[Order.F_OTP] = box
        body.addView(box, W.lp(W.MATCH, W.WRAP))
        body.addView(View(ctx).apply { setBackgroundColor(W.SHADOW) }, W.lp(W.MATCH, W.px, l = 8, r = 8))
        body.addView(View(ctx).apply { setBackgroundColor(W.HILITE) }, W.lp(W.MATCH, W.px, l = 8, r = 8))
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

    /**
     * Former titlebar ⋮ inventory (0.3.13: no dropdown). Kept for tests / callers that probe labels.
     * Relocations: 欄の編集→「欄を編集」chip; 欄を追加→chip; Pass「生成」trailing; URL open trailing;
     * icon→tile gestures; OTP／削除→bottom secondary row; missing std fields→欄の編集 hidden zone.
     */
    fun menu(): List<Menus.Item> = listOf(
        Menus.Item("欄を編集") { editLayout() },
        Menus.Item("欄を追加") { addCustom() },
        Menus.Item("パスワードを生成") { editors[Order.F_PASS]?.setText(generate()) },
        Menus.Item("URL をブラウザで開く", values["URL"].orEmpty().isNotBlank()) { openUrl(values["URL"].orEmpty()) },
        Menus.Item("アイコンの変更") { pickIcon() },
        Menus.Item(if (values[Order.OTP].isNullOrBlank()) "OTPを設定" else "OTP設定を変更") { setupOtp() },
        Menus.Item("この鍵を削除", entryId != null) { askDelete() },
    )

    /** 「欄を追加」: name + 1 行 / 複数行 (memo-like, wraps and keeps newlines). */
    fun addCustom(): ClassicDialog {
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, "欄の名前 (例: 暗証番号、秘密の質問):", 14f))
        val f = W.field(ctx).apply { isSingleLine = true }
        col.addView(f, W.lp(W.MATCH, W.WRAP, t = 4))
        col.addView(W.text(ctx, "種類:", 14f), W.lp(W.MATCH, W.WRAP, t = 10))
        val rg = android.widget.RadioGroup(ctx)
        val r1 = W.radio(ctx, "1 行 (ID・番号など)").apply { id = View.generateViewId() }
        val r2 = W.radio(ctx, "複数行 (メモのように改行・折り返し)").apply { id = View.generateViewId() }
        rg.addView(r1); rg.addView(r2); rg.check(r1.id)
        col.addView(rg)
        val dlg = ClassicDialog(ctx, "欄を追加", col, listOf(
            ClassicDialog.Btn("追加", true) {
                val name = f.text.toString().trim()
                when {
                    name.isEmpty() -> false
                    name in Order.STANDARD || name == Order.OTP || values.containsKey(name) || name.startsWith("@") || name.startsWith("-") -> {
                        Dlg.message(ctx, "欄を追加", "その名前は使えません。", '!'); false
                    }
                    else -> { addCustomField(name, multiline = rg.checkedRadioButtonId == r2.id); true }
                }
            },
            ClassicDialog.Btn("キャンセル")))
        dlg.show()
        nameField = f; multiRadio = r2
        return dlg
    }
    var nameField: EditText? = null; var multiRadio: android.widget.RadioButton? = null

    /** Put a deleted standard field back (empty), before Memo. */
    fun restoreField(k: String) {
        val at = layout.indexOfFirst { it.first == Order.F_NOTES }.let { if (it < 0) layout.size else it }
        layout.add(at, k to true); dirty = true; build()
    }

    fun addCustomField(name: String, value: String = "", multiline: Boolean = false) {
        values[name] = value
        if (multiline) multi.add(name)
        layout.add(name to true)
        dirty = true
        build()
    }

    var arrangeEditor: io.github.zmlworks.kpexplorer.ui.FieldArrangeEditor? = null
        private set

    fun editLayout(): ClassicDialog {
        val editor = io.github.zmlworks.kpexplorer.ui.FieldArrangeEditor(ctx, layout)
        arrangeEditor = editor
        // Fixed height so drag has room inside the dialog scroll area
        val wrap = W.vbox(ctx)
        wrap.addView(editor, W.lp(W.MATCH, W.dp(360)))
        val dlg = ClassicDialog(ctx, "欄の編集", wrap, listOf(
            ClassicDialog.Btn("OK", true) {
                applyArrangement(editor.snapshot(), editor.removedCustoms.toList())
                true
            },
            ClassicDialog.Btn("キャンセル")), scroll = false)
        dlg.show()
        return dlg
    }

    /** Apply arrangement from the editor (or tests). Hidden basic/custom fields keep values. */
    fun applyArrangement(ordered: List<Pair<String, Boolean>>, removedCustoms: List<String> = emptyList()) {
        captureEditors()
        if (removedCustoms.isNotEmpty()) removeFields(removedCustoms)
        // Drop layout keys that were removed; keep Title visible
        val next = ordered.map { (k, v) -> if (k == Order.F_TITLE) k to true else k to v }
            .filter { it.first == Order.F_TITLE || it.first in values || it.first in Order.DEFAULT_LAYOUT || it.first == Order.F_TAGS || it.first == Order.F_OTP }
            .toMutableList()
        if (next.none { it.first == Order.F_TITLE }) next.add(0, Order.F_TITLE to true)
        layout = next
        dirty = true
        build()
    }

    private fun captureEditors() { /* values are kept live by the TextWatchers */ }

    /** Delete fields and their content: customs disappear, standard ones are emptied, Tag / OTP cleared. */
    fun removeFields(keys: List<String>) {
        for (k in keys) when (k) {
            Order.F_TITLE -> {}
            Order.F_TAGS -> tags = ""
            Order.F_OTP -> values.remove(Order.OTP)
            in Order.STANDARD -> values[k] = ""
            else -> { values.remove(k); multi.remove(k) }
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
            val ic = io.github.zmlworks.kpexplorer.db.EntryIcon.def(iconId)
            Session.update { Order.saveEntry(it, id, vals, tagList, layout.toList(),
                cdSet = mapOf(io.github.zmlworks.kpexplorer.db.EntryIcon.CD to iconId,
                    Order.CD_MULTI to multi.filter { it in vals }.joinToString("\n")),
                icon = ic?.std ?: app.keemobile.kotpass.constants.PredefinedIcon.Key) }
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
