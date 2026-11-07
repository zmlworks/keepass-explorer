package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.view.KeyEvent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/** Windows 95/98/2000 "classic" look: palette, 3-D bevels, and small widget builders. */
object W {
    const val FACE = 0xFFC0C0C0.toInt()
    const val HILITE = 0xFFFFFFFF.toInt()
    const val LIGHT = 0xFFDFDFDF.toInt()
    const val SHADOW = 0xFF808080.toInt()
    const val DARK = 0xFF000000.toInt()
    const val NAVY = 0xFF000080.toInt()
    /** Softer muted blue for key-detail location band (not shocking #000080). */
    const val BAND_SOFT = 0xFF4A6FA5.toInt()
    /** Darker gray for plain-row field labels (stronger contrast than SHADOW). */
    const val LABEL_DARK = 0xFF3A3A3A.toInt()
    const val TITLE2 = 0xFF1084D0.toInt()
    /** Temporary read-only browse: sepia title / band (not shocking blue). */
    const val SEPIA1 = 0xFF5C4033.toInt()
    const val SEPIA2 = 0xFF8B6914.toInt()
    const val BAND_SEPIA = 0xFF7A5C45.toInt()
    /** Live title-bar colors: sepia while [Session.readOnlyTemp]. */
    fun accent1(): Int = if (io.github.zmlworks.kpexplorer.Session.readOnlyTemp) SEPIA1 else NAVY
    fun accent2(): Int = if (io.github.zmlworks.kpexplorer.Session.readOnlyTemp) SEPIA2 else TITLE2
    fun bandFill(): Int = if (io.github.zmlworks.kpexplorer.Session.readOnlyTemp) BAND_SEPIA else BAND_SOFT
    const val INACTIVE1 = 0xFF808080.toInt()
    const val INACTIVE2 = 0xFFB5B5B5.toInt()
    const val DESKTOP = 0xFF008080.toInt()
    const val WINDOW = 0xFFFFFFFF.toInt()
    const val TEXT = 0xFF000000.toInt()
    const val INFO = 0xFFFFFFE1.toInt()

    /** One classic screen pixel, in device px. */
    var px = 3
    var density = 3f

    fun init(ctx: Context) {
        density = ctx.resources.displayMetrics.density
        px = max(1, density.roundToInt())
    }

    fun dp(v: Float): Int = (v * density).roundToInt()
    fun dp(v: Int): Int = dp(v.toFloat())

    /** Integer zoom for pixel-art icons (≈1.33 dp per art pixel). */
    fun iconScale(): Int = max(1, (density * 1.33f).roundToInt())

    // ------------------------------------------------------------------ bevels

    enum class Style { RAISED, BUTTON, DEFAULT_BUTTON, SUNKEN, FIELD, THIN_RAISED, THIN_SUNKEN, WINDOW, FLAT_TOOL, HEADER, NONE }

    /** Stateful 3-D frame. BUTTON / FLAT_TOOL / HEADER react to pressed; FIELD draws a white well. */
    class Bevel(val style: Style, var fill: Int = FACE) : Drawable() {
        private val p = Paint()
        private var pressed = false
        private var checked = false
        var forcePressed = false
            set(v) { field = v; invalidateSelf() }

        override fun isStateful() = style == Style.BUTTON || style == Style.FLAT_TOOL || style == Style.HEADER ||
            style == Style.DEFAULT_BUTTON

        override fun onStateChange(state: IntArray): Boolean {
            val pr = state.contains(android.R.attr.state_pressed)
            val ch = state.contains(android.R.attr.state_checked) || state.contains(android.R.attr.state_selected)
            val changed = pr != pressed || ch != checked
            pressed = pr; checked = ch
            if (changed) invalidateSelf()
            return changed
        }

        private fun rect(c: Canvas, l: Int, t: Int, r: Int, b: Int, color: Int) {
            p.color = color
            c.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), p)
        }

        /** frame at inset i (in classic pixels): top/left colour tl, bottom/right colour br */
        private fun frame(c: Canvas, b: Rect, i: Int, tl: Int, br: Int) {
            val u = px
            val l = b.left + i * u; val t = b.top + i * u; val r = b.right - i * u; val bo = b.bottom - i * u
            rect(c, l, t, r - u, t + u, tl)
            rect(c, l, t, l + u, bo - u, tl)
            rect(c, l, bo - u, r, bo, br)
            rect(c, r - u, t, r, bo, br)
        }

        override fun draw(c: Canvas) {
            val b = bounds
            val down = pressed || checked || forcePressed
            when (style) {
                Style.NONE -> rect(c, b.left, b.top, b.right, b.bottom, fill)
                Style.RAISED, Style.WINDOW -> {
                    rect(c, b.left, b.top, b.right, b.bottom, fill)
                    if (style == Style.WINDOW) { frame(c, b, 0, LIGHT, DARK); frame(c, b, 1, HILITE, SHADOW) }
                    else { frame(c, b, 0, HILITE, DARK); frame(c, b, 1, LIGHT, SHADOW) }
                }
                Style.BUTTON, Style.HEADER -> {
                    rect(c, b.left, b.top, b.right, b.bottom, fill)
                    if (down) { frame(c, b, 0, DARK, HILITE); frame(c, b, 1, SHADOW, LIGHT) }
                    else { frame(c, b, 0, HILITE, DARK); frame(c, b, 1, LIGHT, SHADOW) }
                }
                Style.DEFAULT_BUTTON -> {
                    rect(c, b.left, b.top, b.right, b.bottom, fill)
                    frame(c, b, 0, DARK, DARK)
                    if (down) { frame(c, b, 1, SHADOW, SHADOW) }
                    else { frame(c, b, 1, HILITE, DARK); frame(c, b, 2, LIGHT, SHADOW) }
                }
                Style.SUNKEN, Style.FIELD -> {
                    rect(c, b.left, b.top, b.right, b.bottom, if (style == Style.FIELD && fill == FACE) WINDOW else fill)
                    frame(c, b, 0, SHADOW, HILITE); frame(c, b, 1, DARK, LIGHT)
                }
                Style.THIN_RAISED -> { rect(c, b.left, b.top, b.right, b.bottom, fill); frame(c, b, 0, HILITE, SHADOW) }
                Style.THIN_SUNKEN -> { rect(c, b.left, b.top, b.right, b.bottom, fill); frame(c, b, 0, SHADOW, HILITE) }
                Style.FLAT_TOOL -> {
                    rect(c, b.left, b.top, b.right, b.bottom, fill)
                    if (down) frame(c, b, 0, SHADOW, HILITE)
                }
            }
        }

        override fun getPadding(padding: Rect): Boolean {
            val n = when (style) {
                Style.NONE, Style.FLAT_TOOL -> 0
                Style.THIN_RAISED, Style.THIN_SUNKEN -> 1
                Style.DEFAULT_BUTTON -> 3
                else -> 2
            } * px
            padding.set(n, n, n, n)
            return true
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** Etched line (group boxes, menu separators): shadow + highlight. */
    class Etched(val horizontalOnly: Boolean = true) : Drawable() {
        private val p = Paint()
        override fun draw(c: Canvas) {
            val b = bounds; val u = px.toFloat()
            if (horizontalOnly) {
                val y = b.centerY().toFloat() - u
                p.color = SHADOW; c.drawRect(b.left.toFloat(), y, b.right.toFloat(), y + u, p)
                p.color = HILITE; c.drawRect(b.left.toFloat(), y + u, b.right.toFloat(), y + 2 * u, p)
            } else {
                val l = b.left.toFloat(); val t = b.top.toFloat(); val r = b.right.toFloat(); val bo = b.bottom.toFloat()
                p.color = SHADOW
                c.drawRect(l, t, r - u, t + u, p); c.drawRect(l, t, l + u, bo - u, p)
                c.drawRect(l, bo - 2 * u, r - u, bo - u, p); c.drawRect(r - 2 * u, t, r - u, bo - u, p)
                p.color = HILITE
                c.drawRect(l + u, t + u, r - 2 * u, t + 2 * u, p); c.drawRect(l + u, t + u, l + 2 * u, bo - 2 * u, p)
                c.drawRect(l, bo - u, r, bo, p); c.drawRect(r - u, t, r, bo, p)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Classic check box / radio button glyph (13×13 / 12×12 classic px). */
    class CheckGlyph(val radio: Boolean) : Drawable() {
        private val p = Paint()
        private var checked = false
        private var pressed = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val c = state.contains(android.R.attr.state_checked); val pr = state.contains(android.R.attr.state_pressed)
            val ch = c != checked || pr != pressed
            checked = c; pressed = pr
            if (ch) invalidateSelf()
            return ch
        }
        private val u get() = iconScale()
        override fun getIntrinsicWidth() = 13 * u + 5 * u
        override fun getIntrinsicHeight() = 13 * u
        private fun dot(c: Canvas, x: Int, y: Int, color: Int) {
            p.color = color
            val l = bounds.left + x * u; val t = bounds.top + (bounds.height() - 13 * u) / 2 + y * u
            c.drawRect(l.toFloat(), t.toFloat(), (l + u).toFloat(), (t + u).toFloat(), p)
        }
        override fun draw(c: Canvas) {
            val well = if (pressed) FACE else WINDOW
            if (!radio) {
                for (i in 0 until 13) for (j in 0 until 13) {
                    val col = when {
                        i == 0 || j == 0 -> if (i == 12 || j == 12) HILITE else SHADOW
                        i == 12 || j == 12 -> HILITE
                        i == 1 || j == 1 -> if (i == 11 || j == 11) LIGHT else DARK
                        i == 11 || j == 11 -> LIGHT
                        else -> well
                    }
                    dot(c, i, j, col)
                }
                if (checked) {
                    // the classic 7×7 tick
                    val ys = intArrayOf(4, 5, 6, 5, 4, 3, 2)
                    for (k in 0 until 7) for (d in 0 until 3) dot(c, 3 + k, ys[k] + d, DARK)
                }
            } else {
                val rows = arrayOf(
                    "....SSSS....", "..SSDDDDSS..", ".SDD....DDH.", ".SD......LH.", "SD........LH", "SD........LH",
                    "SD........LH", "SD........LH", ".SD......LH.", ".SLL....LLH.", "..HHLLLLHH..", "....HHHH...."
                )
                for (j in rows.indices) for (i in 0 until 12) {
                    val col = when (rows[j][i]) {
                        'S' -> SHADOW; 'D' -> DARK; 'H' -> HILITE; 'L' -> LIGHT; else -> -1
                    }
                    if (col != -1) dot(c, i, j, col)
                    else if (j in 1..10 && rows[j].indexOfFirst { it != '.' } < i && i < rows[j].indexOfLast { it != '.' }) dot(c, i, j, well)
                }
                if (checked) for (j in 4..7) for (i in 4..7) if (!((i == 4 || i == 7) && (j == 4 || j == 7))) dot(c, i, j, DARK)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Glyphs for caption buttons and combo arrows, drawn in classic pixels. */
    class Glyph(val kind: Char, val color: Int = DARK) : Drawable() {
        private val p = Paint()
        override fun getIntrinsicWidth() = 9 * px
        override fun getIntrinsicHeight() = 9 * px
        override fun draw(c: Canvas) {
            val rows = when (kind) {
                'x' -> arrayOf("XX....XX", ".XX..XX.", "..XXXX..", "...XX...", "..XXXX..", ".XX..XX.", "XX....XX")
                '_' -> arrayOf("........", "........", "........", "........", "........", "XXXXXX..", "XXXXXX..")
                'o' -> arrayOf("XXXXXXXXX", "XXXXXXXXX", "X.......X", "X.......X", "X.......X", "X.......X", "X.......X", "XXXXXXXXX")
                'v' -> arrayOf("XXXXXXX", ".XXXXX.", "..XXX..", "...X...")
                '^' -> arrayOf("...X...", "..XXX..", ".XXXXX.", "XXXXXXX")
                '>' -> arrayOf("X...", "XX..", "XXX.", "XXXX", "XXX.", "XX..", "X...")
                '<' -> arrayOf("...X", "..XX", ".XXX", "XXXX", ".XXX", "..XX", "...X")
                else -> arrayOf()
            }
            if (rows.isEmpty()) return
            val w = rows.maxOf { it.length }; val h = rows.size
            val ox = bounds.centerX() - w * px / 2; val oy = bounds.centerY() - h * px / 2
            p.color = color
            for (j in rows.indices) for (i in rows[j].indices) if (rows[j][i] == 'X')
                c.drawRect((ox + i * px).toFloat(), (oy + j * px).toFloat(), (ox + (i + 1) * px).toFloat(), (oy + (j + 1) * px).toFloat(), p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Stippled scrollbar track (the 50 % white/gray checkerboard). */
    class Stipple : Drawable() {
        private val p = Paint()
        override fun draw(c: Canvas) {
            val b = bounds
            p.color = FACE; c.drawRect(b, p)
            p.color = HILITE
            var y = b.top; var row = 0
            while (y < b.bottom) {
                var x = b.left + (row % 2) * px
                while (x < b.right) { c.drawRect(x.toFloat(), y.toFloat(), (x + px).toFloat(), (y + px).toFloat(), p); x += 2 * px }
                y += px; row++
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    // ------------------------------------------------------------------ widgets

    const val TEXT_SP = 14f

    fun text(ctx: Context, s: CharSequence, sp: Float = TEXT_SP, bold: Boolean = false, color: Int = TEXT): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

    /** Gray embossed text for disabled controls. */
    fun setEnabledLook(v: TextView, enabled: Boolean) {
        v.isEnabled = enabled
        if (enabled) { v.setTextColor(TEXT); v.setShadowLayer(0f, 0f, 0f, 0) }
        else { v.setTextColor(SHADOW); v.setShadowLayer(0.01f, px.toFloat(), px.toFloat(), HILITE) }
        v.compoundDrawables.forEach { it?.alpha = if (enabled) 255 else 90 }
    }

    fun button(ctx: Context, s: String, default: Boolean = false, onClick: (View) -> Unit = {}): TextView =
        text(ctx, s).apply {
            background = Bevel(if (default) Style.DEFAULT_BUTTON else Style.BUTTON)
            gravity = Gravity.CENTER
            minWidth = dp(84); minHeight = dp(38)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true; isFocusable = true
            setOnClickListener(onClick)
        }

    /** EditText that sees Back before the IME (0.4.6): lets an inline edit end on ONE Back. */
    class BackEditText(ctx: Context) : EditText(ctx) {
        var onPreImeBack: ((KeyEvent) -> Boolean)? = null
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK) onPreImeBack?.let { if (it(event)) return true }
            return super.onKeyPreIme(keyCode, event)
        }
    }

    fun field(ctx: Context, hint: String = "", multi: Boolean = false, password: Boolean = false): EditText =
        BackEditText(ctx).apply {
            background = Bevel(Style.FIELD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(TEXT)
            setHintTextColor(SHADOW)
            this.hint = hint
            setPadding(dp(6), dp(6), dp(6), dp(6))
            highlightColor = 0xFF8080C0.toInt()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            if (Build.VERSION.SDK_INT >= 29) {
                textCursorDrawable = GradientDrawable().apply { setColor(DARK); setSize(px, dp(18)) }
            }
            // setSingleLine() replaces the transformation method, so it must come before the password input type
            if (multi) { isSingleLine = false; gravity = Gravity.TOP or Gravity.START } else isSingleLine = true
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (password) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }

    fun check(ctx: Context, s: String, checked: Boolean = false): CheckBox = CheckBox(ctx).apply {
        text = s
        buttonDrawable = CheckGlyph(false)
        buttonTintList = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        setTextColor(TEXT)
        isChecked = checked
        background = null
        minHeight = dp(36)
    }

    fun radio(ctx: Context, s: String, checked: Boolean = false): RadioButton = RadioButton(ctx).apply {
        text = s
        buttonDrawable = CheckGlyph(true)
        buttonTintList = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        setTextColor(TEXT)
        isChecked = checked
        background = null
        minHeight = dp(40)
    }

    fun icon(ctx: Context, bmp: android.graphics.Bitmap, scale: Int = iconScale()): ImageView = ImageView(ctx).apply {
        setImageDrawable(Px.drawable(ctx, bmp, scale))
        scaleType = ImageView.ScaleType.CENTER
    }

    fun hsep(ctx: Context): View = View(ctx).apply {
        background = Etched(true)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4 * px)
    }

    /** Toolbar separator: a thin etched vertical groove. */
    fun vsep(ctx: Context): View = object : View(ctx) {
        val p = Paint()
        override fun onDraw(c: Canvas) {
            val x = width / 2f - px
            p.color = SHADOW; c.drawRect(x, dp(4).toFloat(), x + px, height - dp(4).toFloat(), p)
            p.color = HILITE; c.drawRect(x + px, dp(4).toFloat(), x + 2 * px, height - dp(4).toFloat(), p)
        }
    }.apply { layoutParams = LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.MATCH_PARENT) }

    fun vbox(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    fun hbox(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ------------------------------------------------------------------ title bar / window

    class Caption(val bar: LinearLayout, val title: TextView, val close: TextView?)

    /** Navy-gradient title bar with icon, title and the three caption buttons. */
    fun titleBar(ctx: Context, title: String, iconBmp: android.graphics.Bitmap?, active: Boolean = true,
                 onMin: (() -> Unit)? = null, onMax: (() -> Unit)? = null, onClose: (() -> Unit)? = null): Caption {
        val bar = hbox(ctx)
        bar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            if (active) intArrayOf(accent1(), accent2()) else intArrayOf(INACTIVE1, INACTIVE2))
        bar.setPadding(dp(4), dp(3), dp(3), dp(3))
        bar.minimumHeight = dp(32)
        if (iconBmp != null) bar.addView(icon(ctx, iconBmp, max(1, iconScale() - 1)), lp(WRAP, WRAP, r = 4))
        val t = text(ctx, title, 15f, true, if (active) HILITE else LIGHT).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(t, lp(0, WRAP, 1f))
        fun cap(kind: Char, f: (() -> Unit)?, gap: Int): TextView = TextView(ctx).apply {
            background = Bevel(Style.BUTTON)
            setCompoundDrawablesRelative(null, null, null, null)
            val g = Glyph(kind, if (f != null) DARK else SHADOW)
            foreground = g.also { it.setBounds(0, 0, 0, 0) }
            foregroundGravity = Gravity.CENTER
            isClickable = f != null
            if (f != null) setOnClickListener { f() }
            layoutParams = lp(dp(26), dp(22), l = gap)
        }
        if (onMin != null || onMax != null) {
            bar.addView(cap('_', onMin, 0))
            bar.addView(cap('o', onMax, 0))
        }
        var close: TextView? = null
        if (onClose != null) close = cap('x', onClose, 3).also { bar.addView(it) }
        return Caption(bar, t, close)
    }

    /** A raised window frame: title bar on top, [body] below. */
    fun window(ctx: Context, caption: Caption, body: View, bodyWeight: Boolean = false): LinearLayout {
        val w = vbox(ctx)
        w.background = Bevel(Style.WINDOW)
        w.setPadding(3 * px + 1, 3 * px + 1, 3 * px + 1, 3 * px + 1)
        w.addView(caption.bar, lp(MATCH, WRAP))
        w.addView(body, if (bodyWeight) lp(MATCH, 0, 1f) else lp(MATCH, WRAP))
        return w
    }

    /** Group box: etched frame with the caption sitting on the top line. */
    fun groupBox(ctx: Context, caption: String, content: View): View {
        val f = FrameLayout(ctx)
        val frame = View(ctx).apply { background = Etched(false) }
        f.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = dp(9) })
        val inner = FrameLayout(ctx).apply { setPadding(dp(10), dp(20), dp(10), dp(10)) }
        inner.addView(content)
        f.addView(inner, FrameLayout.LayoutParams(MATCH, WRAP))
        f.addView(text(ctx, " $caption ").apply { setBackgroundColor(FACE) },
            FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        return f
    }

    /** Classic scrollbar look (API 29+). */
    fun classicScrollbars(v: View) {
        v.isVerticalScrollBarEnabled = true
        v.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        if (Build.VERSION.SDK_INT >= 29) {
            v.verticalScrollbarThumbDrawable = Bevel(Style.RAISED)
            v.verticalScrollbarTrackDrawable = Stipple()
            v.scrollBarSize = dp(12)
            v.isScrollbarFadingEnabled = false
        }
    }

    /** Much thicker classic scrollbar (field-arrange editor). */
    fun thickScrollbars(v: View, sizeDp: Int = 28) {
        classicScrollbars(v)
        if (Build.VERSION.SDK_INT >= 29) {
            v.scrollBarSize = dp(sizeDp)
            v.verticalScrollbarThumbDrawable = Bevel(Style.RAISED)
            v.verticalScrollbarTrackDrawable = Stipple()
            v.isScrollbarFadingEnabled = false
        }
    }

    fun colors(vararg pairs: Pair<Int, Int>): ColorStateList =
        ColorStateList(pairs.map { if (it.first == 0) intArrayOf() else intArrayOf(it.first) }.toTypedArray(),
            pairs.map { it.second }.toIntArray())
}
