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

/** http/https URL validation for the open-in-browser swipe (no other schemes / intents). */
object UrlCheck {
    private val WITH_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    /** "mailto:", "otpauth:", "javascript:" … (a scheme without //); "host:8080" is not one (port digits). */
    private val OPAQUE_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?![0-9]+(/|\\?|#|$))")

    /**
     * 0.4.14: what to open for a stored URL. A value with only the domain onwards ("example.com/login",
     * "www.example.com", "//example.com") gets "https://" in front; the stored value is not changed.
     * Values with a scheme are taken as they are, and only http / https are ever opened (others → null).
     */
    fun withScheme(raw: String): String {
        val s = raw.trim()
        if (WITH_SCHEME.containsMatchIn(s) || OPAQUE_SCHEME.containsMatchIn(s)) return s
        return "https://" + s.removePrefix("//")
    }

    fun safe(raw: String): String? {
        val s0 = raw.trim()
        if (s0.isEmpty() || s0.length > 2048 || s0.any { it.isWhitespace() || it.code < 0x20 }) return null
        val added = !WITH_SCHEME.containsMatchIn(s0) && !OPAQUE_SCHEME.containsMatchIn(s0)
        val s = withScheme(s0)
        val u = runCatching { java.net.URI(s) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.host.isNullOrEmpty()) return null
        // a bare word ("memo") is not taken for a domain; "localhost" and IP addresses are fine
        if (added && !u.host.contains('.') && !u.host.equals("localhost", true)) return null
        return s
    }
}

/** Shared field labels / formatting for the key screen and the explorer preview pane. */
object KeyFields {
    /** 0.4.20: follows the date order setting ([L.dateTime]). */
    val fmt: DateTimeFormatter get() = L.dateTime(true)
    fun label(k: String) = when (k) {
        Order.F_TITLE -> "Title:"; Order.F_USER -> "User:"; Order.F_PASS -> "Pass:"; Order.F_URL -> "URL:"
        Order.F_TAGS -> "Tag:"; Order.F_OTP -> "OTP:"; Order.F_NOTES -> "Memo:"; else -> "$k:"
    }
    /**
     * 0.4.24: field values shown in a monospace font (0.4.25: the bundled [io.github.zmlworks.kpexplorer.ui.Mono]) (user name, password, URL, custom fields) so that
     * look-alike characters (0/O, l/1/I) are easier to tell apart. Title, notes and tags keep the normal font.
     * One place to change: every value view goes through [valueFont].
     */
    fun monospace(k: String) = k != Order.F_TITLE && k != Order.F_NOTES && k != Order.F_TAGS
    fun valueFont(t: android.widget.TextView, k: String) { if (monospace(k)) t.typeface = io.github.zmlworks.kpexplorer.ui.Mono.typeface(t.context) }

    /** Small label shown above the value in a field well. */
    fun name(k: String) = label(k).removeSuffix(":")
    fun lastModified(e: Entry?): String = "Last Modified: " + (e?.times?.lastModificationTime?.let { fmt.format(it) } ?: "-")

    /** Live TOTP text: "123 456 (10s)", refreshed every second while attached. */
    class OtpView(ctx: Context, private val placeholder: String = "", private val uri: () -> String?) : TextView(ctx) {
        private val tick = object : Runnable { override fun run() { update(); postDelayed(this, 1000) } }
        init { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f); typeface = io.github.zmlworks.kpexplorer.ui.Mono.typeface(ctx); setTextColor(W.TEXT); update() }
        fun current(): String? = runCatching { uri()?.let { Totp.code(Totp.parse(it), Instant.now().epochSecond) } }.getOrNull()
        fun update() {
            val u = uri()
            text = if (u.isNullOrBlank()) android.text.SpannableString(placeholder).apply {
                setSpan(android.text.style.RelativeSizeSpan(0.65f), 0, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(W.SHADOW), 0, length, 0)
            } else runCatching<CharSequence> {
                val p = Totp.parse(u); val now = Instant.now().epochSecond
                val c = Totp.code(p, now)
                // 0.4.5: "123 456  28s" — countdown smaller, no parentheses; code stays full size/black
                val code = c.chunked(3).joinToString(" ")
                val sec = "${Totp.remaining(p, now)}s"
                android.text.SpannableStringBuilder(code).append("  ").append(sec).apply {
                    val st = length - sec.length
                    setSpan(android.text.style.RelativeSizeSpan(0.6f), st, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(W.SHADOW), st, length, 0)
                }
            }.getOrElse { L.t("⚠ 設定が不正です", "⚠ Invalid settings") }
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
    private val content = W.vbox(ctx).apply { setBackgroundColor(W.FACE) }
    /** Intercept outside taps while a [FieldBox] is editing → commit + save (consume so no accidental clicks). */
    private val root = object : android.widget.FrameLayout(ctx) {
        /** 0.4.8: an outside DOWN only ARMS the commit; it fires on UP. A system back gesture delivers
         *  DOWN then CANCEL to the app (pointer pilfered) — committing on DOWN ended the edit before the
         *  Back key arrived, so that Back navigated (real-device log: editEnd → imeHidden → act DOWN ed=false). */
        private var outsideArmed = false
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (outsideArmed) {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_UP -> {
                        outsideArmed = false
                        io.github.zmlworks.kpexplorer.ui.BackTrace.log("outside UP -> end")
                        commitActiveFieldEdit()
                        hideTagButtons()
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        outsideArmed = false
                        io.github.zmlworks.kpexplorer.ui.BackTrace.log("outside CANCEL (keep editing)")
                    }
                }
                return true
            }
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                val box = io.github.zmlworks.kpexplorer.ui.FieldEditSession.active
                if (box != null && box.editing) {
                    val loc = IntArray(2); box.getLocationOnScreen(loc)
                    val x = ev.rawX; val y = ev.rawY
                    val inside = x >= loc[0] && x < loc[0] + box.width && y >= loc[1] && y < loc[1] + box.height
                    // 0.4.5: tapping a used-tag chip while editing Tag must reach the chip (was swallowed)
                    val ch = chips
                    val onChips = ch != null && ch.visibility == View.VISIBLE && ch.width > 0 && run {
                        val c = IntArray(2); ch.getLocationOnScreen(c)
                        x >= c[0] && x < c[0] + ch.width && y >= c[1] && y < c[1] + ch.height
                    }
                    if (!inside && !onChips) {
                        // IME lives in another window — its taps never reach here. Consume the whole stream.
                        outsideArmed = true
                        io.github.zmlworks.kpexplorer.ui.BackTrace.log("outside DOWN (armed)")
                        return true
                    }
                } else if (tagsShown) {
                    val ch = chips
                    val onChips = ch != null && ch.visibility == View.VISIBLE && run {
                        val c = IntArray(2); ch.getLocationOnScreen(c)
                        ev.rawX >= c[0] && ev.rawX < c[0] + ch.width && ev.rawY >= c[1] && ev.rawY < c[1] + ch.height
                    }
                    if (!onChips) hideTagButtons()
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }.also { it.addView(content, android.widget.FrameLayout.LayoutParams(W.MATCH, W.MATCH)) }
    override val view: View get() = root
    /** 0.4.14: bottom buttons — one long [OK] while nothing has changed, 戻る / 保存 as soon as something has. */
    var footer: Parts.OkFooter? = null
        private set
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
        set(v) { if (!v) baseline = snapshot(); updateFooter() }
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
    internal var chips: android.widget.FrameLayout? = null
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
        if (Session.readOnlyTemp) {
            bar.title.text = ctx.getString(R.string.read_only_banner, Session.tempSourceLabel)
        }
        content.addView(bar.bar, W.lp())
        val sv = ScrollView(ctx).apply { isFillViewport = true; W.classicScrollbars(this) }
        scroll = sv
        sv.addView(body)
        content.addView(sv, W.lp(W.MATCH, 0, 1f))
        build()
        dirty = false
        // Outside-tap / IME Done only ends inline edit into the local draft (TextWatchers).
        // Database write happens solely via explicit footer 保存 (0.4.3).
        io.github.zmlworks.kpexplorer.ui.FieldEditSession.onCommitted = null
    }

    /** Outside-tap / IME Done: end edit into draft only — never Session.update / save. */
    fun commitActiveFieldEdit() {
        io.github.zmlworks.kpexplorer.ui.FieldEditSession.commitActive()
    }

    private fun titleText() = values["Title"].orEmpty().ifEmpty { if (entryId == null) L.t("新しい鍵", "New key") else L.t("(無題)", "(Untitled)") }
    fun barTitle(): String = bar.title.text.toString()

    /**
     * Layout after the KeePassDX entry screen, drawn in Windows 98 style: a flat key icon with folder path
     * (└ tree with counts) and Last Modified beside it, then one sunken well per field (glyph, small label,
     * value, trailing action) with a ⋮ menu button, 「カスタム項目の追加」, and 戻る / 保存 at the bottom.
     */
    private fun build() {
        body.removeAllViews(); editors.clear(); boxes.clear(); otpView = null; tableHost = null
        val d = Session.db ?: return
        val path = Tree.path(Tree.root(d), parent)
        // Proposal #3: navy header band with icon tile + light path / Last Modified
        val band = W.vbox(ctx).apply {
            setBackgroundColor(W.BAND_SOFT)
            setPadding(W.dp(12), W.dp(12), W.dp(12), W.dp(14))
        }
        val tile = android.widget.FrameLayout(ctx).apply {
            // Flat icon on band — no 3D bevel/raised frame (0.4.3)
            background = null
            contentDescription = L.t("アイコン (ダブルタップまたは長押しで変更)", "Icon (double-tap or long-press to change)")
            isClickable = true
            val gd = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent) = true
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean { pickIcon(); return true }
                override fun onLongPress(e: android.view.MotionEvent) { pickIcon() }
            })
            setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
        }
        tile.addView(ImageView(ctx).apply { setImageDrawable(io.github.zmlworks.kpexplorer.ui.FugueIcons.drawable(ctx, iconId)) },
            android.widget.FrameLayout.LayoutParams(W.dp(52), W.dp(52), Gravity.CENTER))
        iconTile = tile
        // 0.4.16: icon + path on top, then Last Modified under the icon with 「- 履歴」 to its right
        val top = W.hbox(ctx).apply { gravity = Gravity.TOP }
        top.addView(tile, W.lp(W.dp(84), W.dp(84), r = 12))
        val info = W.vbox(ctx)
        path.forEachIndexed { i, g ->
            val name = if (i == 0) "/" else L.t("└ ${g.name.ifEmpty { "(名前なし)" }}", "└ ${g.name.ifEmpty { "(no name)" }}")
            info.addView(W.text(ctx, name, 15f, bold = i == path.size - 1, color = W.HILITE).apply {
                isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(W.dp(12) * i, 0, 0, W.dp(2))
            }, W.lp(W.MATCH, W.WRAP))
        }
        top.addView(info, W.lp(0, W.WRAP, 1f))
        band.addView(top, W.lp(W.MATCH, W.WRAP))
        val modRow = W.hbox(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        modRow.addView(W.text(ctx, "Last Modified: " + (entry()?.times?.lastModificationTime?.let { KeyFields.fmt.format(it) } ?: "-"),
            13f, color = 0xFFD6E4F7.toInt()).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }.also { lastMod = it }, W.lp(0, W.WRAP, 1f))   // 0.4.20: 「- 履歴」 at the right edge
        if (entryId != null) {
            val n = entry()?.history?.size ?: 0
            val label = android.text.SpannableString(L.t("- 履歴", "- History")).apply {
                setSpan(android.text.style.UnderlineSpan(), 2, length, 0)
            }
            modRow.addView(W.text(ctx, "", 14f, true, W.HILITE).apply {
                text = label; contentDescription = L.t("履歴 ($n 件)", "History ($n)"); isClickable = true
                setPadding(W.dp(10), W.dp(6), W.dp(10), W.dp(6))
                setOnClickListener { openHistory() }
            }.also { historyLink = it }, W.lp(W.WRAP, W.WRAP, l = 2))
        }
        band.addView(modRow, W.lp(W.MATCH, W.WRAP, t = 6))
        body.addView(band, W.lp(W.MATCH, W.WRAP, b = 4))
        // Plain rows (no cream/white boxes)
        for ((k, vis) in layout) {
            if (!vis) continue
            when (k) {
                // 0.4.11: always drawn when visible; empty = "未設定 (長押しで設定)" so OTP can be set up again
                Order.F_OTP -> otpRow()
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
        val edit = toolChip(L.t("項目の編集", "Edit fields"), L.t("項目の編集", "Edit fields"), plus = false) { editLayout() }
        val add = toolChip(L.t("カスタム項目の追加", "Add custom field"), L.t("カスタム項目の追加", "Add custom field"), plus = true) { addCustom() }
        editButton = edit; addButton = add
        rowBtns.addView(edit, W.lp(W.WRAP, W.WRAP, r = 8))
        rowBtns.addView(add, W.lp(W.WRAP, W.WRAP))
        body.addView(rowBtns, W.lp(W.MATCH, W.WRAP, t = 10))
        body.addView(View(ctx), W.lp(W.MATCH, 0, 1f))
        // 0.3.15: removed bottom OTP設定／この鍵を削除 (OTP = OTP欄長押し; 削除 = 一覧のメニュー)
        body.addView(W.hsep(ctx), W.lp(W.MATCH, 4 * W.px, t = 10))
        val ft = Parts.okFooter(ctx, onBack = { onBack() }, onSave = { save() }, onOk = { onBack() })
        footer = ft
        body.addView(ft.view, W.lp(W.MATCH, W.WRAP))
        updateFooter()
    }

    fun updateFooter() { footer?.setChanged(dirty) }

    private var tableHost: android.widget.LinearLayout? = null
    private var rowIndex = 0

    var iconTile: View? = null
        private set

    /** Icon picker: large nearly-square tiles, glyphs only (names via contentDescription). */
    fun pickIcon(): ClassicDialog {
        lateinit var dlg: ClassicDialog
        val grid = io.github.zmlworks.kpexplorer.ui.FugueIcons.pickerGrid(ctx, iconId) { id -> setIcon(id); dlg.dismiss() }
        dlg = ClassicDialog(ctx, L.t("アイコンの変更", "Change icon"), grid, listOf(ClassicDialog.Btn(L.t("キャンセル", "Cancel"))), scroll = true)
        dlg.show()
        return dlg
    }

    fun setIcon(id: String) { iconId = if (id == "key") null else id; build() }

    private var lastMod: TextView? = null
    /** 「- 履歴」 beside Last Modified (0.4.16). */
    var historyLink: TextView? = null
        private set

    /** Opens the history viewer of this entry (deleting there rebuilds this page's header). */
    fun openHistory(): io.github.zmlworks.kpexplorer.ui.ClassicDialog? {
        val id = entryId ?: return null
        return io.github.zmlworks.kpexplorer.ui.HistoryViewer.show(ctx, id) { if (Session.db != null) build() }
    }
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


    /** Already-used tags as toggle buttons under the Tag row (tap = add / remove). */
    private fun tagPicker() {
        if (allTags.isEmpty()) return
        val holder = android.widget.FrameLayout(ctx).apply { visibility = if (tagsShown) View.VISIBLE else View.GONE }
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
            allTags.filter { it.startsWith(partial, true) } + allTags.filter { !it.startsWith(partial, true) && it.contains(partial, true) }
        h.addView(Parts.tagChips(ctx, list, have.toSet()) { t -> toggleTag(t) })
    }

    /** Long-press on the Tag field: show the existing-tag buttons (most used first). */
    fun hideTagButtons() { tagsShown = false; chips?.visibility = View.GONE }
    fun showTagButtons() { tagsShown = true; chips?.visibility = View.VISIBLE; renderChips() }

    fun toggleTag(t: String) {
        val text = io.github.zmlworks.kpexplorer.db.TagEdit.toggle(tags, allTags, t)
        editors[Order.F_TAGS]?.let { it.setText(text); if (it.isFocused) it.setSelection(text.length) } ?: run { tags = text; renderChips() }
    }

    private fun small(s: String, f: () -> Unit) = W.button(ctx, s) { f() }.apply {
        minWidth = W.dp(52); minHeight = W.dp(36); setPadding(W.dp(6), W.dp(4), W.dp(6), W.dp(4)); textSize = 13f
    }

    private fun labelView(k: String) = W.text(ctx, KeyFields.label(k), 14f, true).apply {
        isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
    }


    private fun copyName(k: String) = when (k) {
        Order.F_TITLE -> L.t("タイトル", "Title"); Order.F_USER -> L.t("ユーザー名", "User name"); Order.F_PASS -> L.t("パスワード", "Password"); Order.F_URL -> "URL"
        Order.F_TAGS -> L.t("タグ", "Tags"); else -> k
    }

    /**
     * Every field is a sunken well (glyph, small label, value): swipe sideways = copy, long press = edit in place,
     * tap on Pass = show / hide (masked as ****** by default). URL is plain text (no browser open).
     */
    private fun fieldRow(k: String, value: String, multi: Boolean, set: (String) -> Unit) {
        val f = W.field(ctx, multi = multi)
        f.setText(value)
        f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { set(s.toString()); updateFooter() }
        })
        editors[k] = f
        if (multi) { f.minLines = 3; f.gravity = Gravity.TOP or Gravity.START } else { f.isSingleLine = true; f.setHorizontallyScrolling(true) }
        f.textSize = 17f
        KeyFields.valueFont(f, k)
        val sensitive = k != Order.F_TITLE && k != Order.F_URL && k != Order.F_TAGS && k != Order.F_USER && k != Order.F_NOTES
        rowIndex++
        // URL open-in-browser removed (0.4.3); Pass gen removed (0.4.2).
        val trail: View? = null
        val box = io.github.zmlworks.kpexplorer.ui.FieldBox(ctx, KeyFields.name(k), f,
            onSwipe = if (k == Order.F_TAGS) null else ({ copy(copyName(k), f.text.toString(), sensitive) }), icon = glyphFor(k),
            trailing = trail, plain = true)
        if (k == Order.F_TAGS) {
            // 0.4.5: no swipe copy for tags; long-press edits and shows used tags
            box.onLongPress = { showTagButtons() }
            box.onEditEnd = { hideTagButtons() }
            // 0.4.7: single tap does nothing; only the ~320ms long press edits (restored)
        }
        if (k == Order.F_URL) {
            // 0.4.5: LEFT swipe = open in browser (always confirmed); RIGHT swipe = copy
            box.onSwipeLeft = { confirmOpenUrl(f.text.toString()) }
            box.setLeftSwipeGlyph(Glyph(Glyph.Kind.OPEN, W.HILITE))
        }
        if (k == Order.F_PASS) {
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
        val o = KeyFields.OtpView(ctx, L.t("未設定 (長押しで設定)", "Not set (long-press to set)")) { currentOtpUri() }
        otpView = o
        rowIndex++
        val box = io.github.zmlworks.kpexplorer.ui.FieldBox(ctx, KeyFields.name(Order.F_OTP), W.field(ctx), view = o,
            onSwipe = { o.current()?.let { copy(L.t("ワンタイムパスワード", "One-time password"), it, true) } }, onLongPress = { setupOtp() },
            icon = glyphFor(Order.F_OTP), plain = true)
        boxes[Order.F_OTP] = box
        body.addView(box, W.lp(W.MATCH, W.WRAP))
        body.addView(View(ctx).apply { setBackgroundColor(W.SHADOW) }, W.lp(W.MATCH, W.px, l = 8, r = 8))
        body.addView(View(ctx).apply { setBackgroundColor(W.HILITE) }, W.lp(W.MATCH, W.px, l = 8, r = 8))
    }

    /** Last URL actually launched (tests). */
    var lastOpened: String? = null
        private set

    /** Confirm dialog showing the target, then ACTION_VIEW for http/https only. */
    fun confirmOpenUrl(raw: String) {
        val u = UrlCheck.safe(raw)
        if (u == null) {
            Dlg.message(ctx, ctx.getString(R.string.open_url_title), ctx.getString(R.string.open_url_invalid), '!')
            return
        }
        Dlg.confirm(ctx, ctx.getString(R.string.open_url_title), ctx.getString(R.string.open_url_msg, u), '?',
            yes = ctx.getString(R.string.open_url_yes), no = ctx.getString(R.string.cancel)) {
            lastOpened = u
            try {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))
                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE))
            } catch (t: Throwable) {
                Toast.makeText(ctx, ctx.getString(R.string.open_url_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copy(label: String, s: String, sensitive: Boolean) {
        if (s.isEmpty()) return
        Clip.copy(ctx, label, s, sensitive)
        Toast.makeText(ctx, L.t("${label}をコピーしました", "${label} copied"), Toast.LENGTH_SHORT).show()
    }

    fun generate(len: Int = 20): String {
        val cs = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#%+-=?@_"
        val r = SecureRandom()
        return (1..len).map { cs[r.nextInt(cs.length)] }.joinToString("")
    }

    /**
     * Former titlebar ⋮ inventory (0.3.13: no dropdown). Kept for tests / callers that probe labels.
     * Relocations: 項目の編集→「項目の編集」chip; カスタム項目の追加→chip; URL open trailing;
     * icon→tile gestures; OTP／削除→bottom secondary row; missing std fields→項目の編集 hidden zone.
     * Pass generator removed from existing key (0.4.2).
     */
    fun menu(): List<Menus.Item> = listOf(
        Menus.Item(L.t("項目の編集", "Edit fields")) { editLayout() },
        Menus.Item(L.t("カスタム項目の追加", "Add custom field")) { addCustom() },
        Menus.Item(L.t("アイコンの変更", "Change icon")) { pickIcon() },
    )

    /** 「カスタム項目の追加」: name + 1 行 / 複数行 (memo-like, wraps and keeps newlines). */
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
                    name in Order.STANDARD || name == Order.OTP || values.containsKey(name) || name.startsWith("@") || name.startsWith("-") -> {
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
        wrap.addView(editor, W.lp(W.MATCH, W.dp(480)))
        val dlg = ClassicDialog(ctx, L.t("項目の編集", "Edit fields"), wrap, listOf(
            ClassicDialog.Btn("OK", true) {
                applyArrangement(editor.snapshot(), editor.removedCustoms.toList())
                true
            },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel"))), scroll = false)
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
            Order.F_OTP -> { values.remove(Order.OTP); dropOtpExtras() }
            in Order.STANDARD -> values[k] = ""
            else -> { values.remove(k); multi.remove(k) }
        }
        layout.removeAll { it.first in keys && it.first != Order.F_TITLE }
        dirty = true
    }

    /** OTP of the draft: "otp" field, else KeePass 2 TimeOtp-* / legacy KeePassXC fields ([Order.otpUri]). */
    fun currentOtpUri(): String? = Order.otpUri { values[it].orEmpty() }

    /** Field names to drop on the next save (other apps' OTP fields after OTP 削除 / replace). */
    val removedKeys = LinkedHashSet<String>()

    private fun dropOtpExtras() {
        values.keys.filter { Order.isOtpExtra(it) }.forEach { values.remove(it); removedKeys.add(it) }
    }

    fun setupOtp() {
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, L.t("otpauth:// で始まる URI (QR コードの中身) か、Base32 の秘密鍵を入力してください。\nKeePassXC と同じ「otp」欄に保存します。", "Enter a URI starting with otpauth:// (the content of the QR code) or a Base32 secret.\nIt is saved in the \"otp\" field, like KeePassXC."), 14f).apply { setLineSpacing(0f, 1.1f) })
        val f = W.field(ctx, "otpauth://totp/...?secret=...").apply { setText(currentOtpUri().orEmpty()) }
        col.addView(f, W.lp(W.MATCH, W.WRAP, t = 8))
        ClassicDialog(ctx, L.t("ワンタイムパスワード", "One-time password"), col, listOf(
            ClassicDialog.Btn("OK", true) {
                val s = f.text.toString().trim()
                if (s.isEmpty()) { setOtp(null); return@Btn true }
                val r = runCatching { setOtp(s) }
                r.exceptionOrNull()?.let { Dlg.message(ctx, L.t("ワンタイムパスワード", "One-time password"), L.t("読み取れませんでした: ${it.message}", "Could not read it: ${it.message}"), 'x'); false } ?: true
            },
            ClassicDialog.Btn(L.t("削除", "Delete")) { setOtp(null); true },
            ClassicDialog.Btn(L.t("キャンセル", "Cancel")))).show()
    }

    /** Validate and store; a bare secret becomes an otpauth URI (issuer = title, account = user). */
    fun setOtp(input: String?) {
        if (input == null) { values.remove(Order.OTP); dropOtpExtras() }
        else {
            if (input.trim() == currentOtpUri()) { build(); return }  // unchanged (e.g. KeePass 2 TimeOtp shown as URI)
            val p = Totp.parse(input)
            dropOtpExtras()  // one secret only: a new OTP replaces other apps' OTP fields
            values[Order.OTP] = if (input.startsWith("otpauth://", true)) input
                else Totp.toUri(p.copy(issuer = values["Title"].orEmpty(), account = values["UserName"].orEmpty()))
            if (layout.none { it.first == Order.F_OTP }) layout.add(layout.indexOfFirst { it.first == Order.F_NOTES }.coerceAtLeast(0), Order.F_OTP to true)
        }
        dirty = true
        build()
    }

    fun save(silent: Boolean = false): Boolean {
        val d = Session.db ?: return false
        // No-op save: keep Session/DB/history untouched (explicit Save with no draft changes).
        if (entryId != null && !dirty) {
            if (!silent) Toast.makeText(ctx, L.t("変更はありません", "No changes"), Toast.LENGTH_SHORT).show()
            return true
        }
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
                icon = ic?.std ?: app.keemobile.kotpass.constants.PredefinedIcon.Key, removed = removedKeys.toSet()) }
            removedKeys.clear()
            Session.flush()
        } catch (t: Throwable) {
            Dlg.message(ctx, L.t("保存", "Save"), L.t("保存できませんでした。\n$t", "Could not save.\n$t"), 'x'); return false
        }
        if (!silent && (d !== Session.db || dirty)) Toast.makeText(ctx, L.t("保存しました", "Saved"), Toast.LENGTH_SHORT).show()
        dirty = false
        a.explorer?.selection = entryId
        build()
        return true
    }

    private fun askDelete() {
        val id = entryId ?: return
        if (Session.readOnlyTemp) return
        val d = Session.db ?: return
        val soft = d.content.meta.recycleBinEnabled && !Tree.isInRecycleBin(d, id)
        fun doDelete() {
            val binName = ctx.getString(R.string.recycle_bin)
            Session.update {
                if (soft) Tree.moveToBin(it, id, binName) else Tree.deletePermanently(it, id)
            }
            dirty = false
            a.showExplorer()
        }
        if (soft) doDelete()
        else Dlg.confirm(ctx, ctx.getString(R.string.delete_confirm_title),
            ctx.getString(R.string.confirm_delete_permanent, ctx.getString(R.string.quoted_name, titleText())), '!') { doDelete() }
    }

    /** Keyboard closed by the system (IME consumed Back): end inline edit into the draft, stay here. */
    override fun onImeHidden() {
        val b = io.github.zmlworks.kpexplorer.ui.FieldEditSession.active
        if (b != null && b.editing) {
            io.github.zmlworks.kpexplorer.ui.BackTrace.log("end by imeHidden")
            io.github.zmlworks.kpexplorer.ui.FieldEditSession.implicitEndAt = android.os.SystemClock.uptimeMillis()
            io.github.zmlworks.kpexplorer.ui.FieldEditSession.commitActive()
        }
    }

    override fun onBack(): Boolean {
        // 0.4.4: first Back while a field is in inline edit only ends the edit (draft) and stays.
        val b = io.github.zmlworks.kpexplorer.ui.FieldEditSession.active
        if (b != null && b.editing) {
            io.github.zmlworks.kpexplorer.ui.FieldEditSession.commitActive()
            return true
        }
        io.github.zmlworks.kpexplorer.ui.FieldEditSession.onCommitted = null
        if (!dirty) { a.showExplorer(); return true }
        confirmUnsaved(ctx, { if (save()) a.showExplorer() }, { dirty = false; a.showExplorer() })
        return true
    }

    companion object {
        /** "変更を保存しますか?" with 保存 / 保存しない / キャンセル. */
        fun confirmUnsaved(ctx: Context, onSave: () -> Unit, onDiscard: () -> Unit): ClassicDialog =
            ClassicDialog(ctx, "KeePass Explorer", W.text(ctx, L.t("変更が保存されていません。\n保存しますか?", "The changes have not been saved.\nSave them?"), 15f), listOf(
                ClassicDialog.Btn(L.t("保存する", "Save"), true) { onSave(); true },
                ClassicDialog.Btn(L.t("保存しない", "Don’t save")) { onDiscard(); true },
                ClassicDialog.Btn(L.t("キャンセル", "Cancel")))).also { it.show(); Dlg.last = it }
    }
}
