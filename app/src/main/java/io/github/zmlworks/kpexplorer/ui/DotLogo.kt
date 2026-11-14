package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import io.github.zmlworks.kpexplorer.R
import kotlin.math.max
import kotlin.math.min

/**
 * 0.4.32: the unlock screen's backdrop — teal with a faint grain, and "KeePass" / "Explorer" written large and
 * slanted up to the right ([ANGLE]° to [MAX_ANGLE]°, whichever lets them be largest), the letters made of
 * a grid of small light dots (like a perforated stencil). The text is drawn once into an offscreen mask; a dot goes wherever the mask is set at a grid point.
 * Built for each size in [onSizeChanged] and then only copied to the screen.
 */
class DotLogo(ctx: Context) : View(ctx) {
    private var art: Bitmap? = null
    /** Slant used for the current size, degrees counter-clockwise. */
    var angle = ANGLE; private set
    /** Grid of the dots in px (tests): pitch, dot diameter. */
    val pitch: Int get() = max(3, W.dp(1.4f))
    val dot: Int get() = max(2, pitch / 2)
    /** Number of dots of the last build (tests). */
    var dots = 0
        private set
    /** Where the two lines' text ended up (screen px, tests): bounds of all dots. */
    val dotBounds = Rect()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        art?.recycle(); art = null
        if (w > 0 && h > 0) art = build(w, h)
    }

    override fun onDraw(c: Canvas) {
        art?.let { c.drawBitmap(it, 0f, 0f, null) } ?: c.drawColor(TEAL)
    }

    private fun build(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(TEAL)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
        // the text, in a mask the size of the screen
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val m = Canvas(mask)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = typeface(context); textSize = 100f
            // a little heavier than the regular face, so each stroke is a few dots wide
            style = Paint.Style.FILL_AND_STROKE; strokeWidth = 2.5f }
        val l1 = "KeePass"; val l2 = "Explorer"
        val indent = 0.45f * p.textSize           // "Explorer" starts a little to the right of "KeePass"
        val gap = 1.02f * p.textSize              // baseline to baseline
        // the two lines as outlines, slanted, to measure exactly how far the letters reach
        val text = Path(); val line = Path()
        p.getTextPath(l1, 0, l1.length, 0f, 0f, line); text.addPath(line)
        p.getTextPath(l2, 0, l2.length, indent, gap, line); text.addPath(line)
        // the slant (from [ANGLE]° up to [MAX_ANGLE]°) at which the letters can be largest: on a tall screen
        // they then run from corner to corner; scaled to fill it across (a little past the sides) and most of the height
        val half = p.strokeWidth / 2
        var s = 0f; val ext = RectF()
        for (deg in ANGLE.toInt()..MAX_ANGLE.toInt()) {
            val t = Path(text); t.transform(Matrix().apply { setRotate(-deg.toFloat()) })
            val e = RectF(); t.computeBounds(e, true); e.inset(-half, -half)
            val k = min(OVER * w / e.width(), 0.92f * h / e.height())
            if (k > s * 1.01f) { s = k; angle = deg.toFloat(); ext.set(e) }
        }
        m.save()
        m.translate(w / 2f, h / 2f)
        m.scale(s, s)
        m.translate(-ext.centerX(), -ext.centerY())
        m.rotate(-angle)
        m.drawText(l1, 0f, 0f, p)
        m.drawText(l2, indent, gap, p)
        m.restore()
        val alpha = ByteArray(mask.rowBytes * h)
        mask.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(alpha))
        mask.recycle()
        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOT }
        val step = pitch; val rad = dot / 2f
        var n = 0
        dotBounds.setEmpty()
        var y = step / 2
        while (y < h) {
            var x = step / 2
            val row = y * mask.rowBytes
            while (x < w) {
                if ((alpha[row + x].toInt() and 0xFF) >= 128) {
                    c.drawCircle(x + 0.5f, y + 0.5f, rad, dp); n++
                    if (dotBounds.isEmpty) dotBounds.set(x, y, x + 1, y + 1) else dotBounds.union(x, y)
                }
                x += step
            }
            y += step
        }
        dots = n
        return out
    }

    companion object {
        /** Backdrop teal (a touch deeper than the classic desktop teal). */
        const val TEAL = 0xFF00797A.toInt()
        /** Dots: white at about 75 %. */
        const val DOT = 0xC0FFFFFF.toInt()
        /** Least slant of the text, degrees counter-clockwise (the reference picture's). */
        const val ANGLE = 55f
        /** How far the letters' outer ends may run past the screen's sides (only the tips of K and r leave it). */
        const val OVER = 1.12f
        /** Most slant, for a very tall screen. */
        const val MAX_ANGLE = 70f

        /** A 96 px tile of very faint light / dark grain (fixed seed: the same every time). */
        private fun grain(): Bitmap {
            val n = 96
            val r = java.util.Random(0x4B5058L)
            val px = IntArray(n * n) {
                val v = r.nextInt(17) - 8
                if (v >= 0) (v * 2 shl 24) or 0xFFFFFF else (-v * 2 shl 24)
            }
            return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }

        private var face: Typeface? = null
        /** Didact Gothic (OFL, subset to these letters): round geometric letters like Century Gothic. */
        fun typeface(ctx: Context): Typeface = face
            ?: (runCatching { ctx.applicationContext.resources.getFont(R.font.kpx_logo) }.getOrNull() ?: Typeface.SANS_SERIF).also { face = it }
    }
}

/**
 * 0.4.32: the unlock screen's buttons — light grey, flat, with a faint classic bevel (white top-left, grey
 * bottom-right), pressed = sunk.
 */
class SoftButton : Drawable() {
    private val p = Paint()
    private var pressed = false
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val pr = state.contains(android.R.attr.state_pressed)
        if (pr == pressed) return false
        pressed = pr; invalidateSelf(); return true
    }
    override fun draw(c: Canvas) {
        val b = bounds; val u = W.px
        p.color = if (pressed) PRESSED else FILL
        c.drawRect(b, p)
        val tl = if (pressed) EDGE_DARK else EDGE_LIGHT
        val br = if (pressed) EDGE_LIGHT else EDGE_DARK
        p.color = tl
        c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), (b.top + u).toFloat(), p)
        c.drawRect(b.left.toFloat(), b.top.toFloat(), (b.left + u).toFloat(), b.bottom.toFloat(), p)
        p.color = br
        c.drawRect(b.left.toFloat(), (b.bottom - u).toFloat(), b.right.toFloat(), b.bottom.toFloat(), p)
        c.drawRect((b.right - u).toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
    companion object {
        const val FILL = 0xFFE0E0E0.toInt()
        const val PRESSED = 0xFFD0D0D0.toInt()
        const val EDGE_LIGHT = 0xFFFFFFFF.toInt()
        const val EDGE_DARK = 0xFF9A9A9A.toInt()
    }
}
