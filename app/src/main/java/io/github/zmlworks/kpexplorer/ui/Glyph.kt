package io.github.zmlworks.kpexplorer.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Small hand-drawn vector glyphs for the key screen (own drawings, classic palette: black / navy / gray).
 * Drawn on a 24 × 24 design grid and scaled to the bounds.
 */
class Glyph(val kind: Kind, private val color: Int = W.TEXT, private val accent: Int = W.NAVY) : Drawable() {
    enum class Kind { TITLE, USER, PASS, URL, TAG, OTP, MEMO, CUSTOM, EYE, EYE_OFF, OPEN, MORE, PLUS, KEY, FLOPPY }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        c.scale(s, s)
        fill.color = color; line.color = color; line.strokeWidth = 2f
        when (kind) {
            Kind.TITLE -> { // label tag
                val p = Path().apply { moveTo(3f, 6f); lineTo(15f, 6f); lineTo(21f, 12f); lineTo(15f, 18f); lineTo(3f, 18f); close() }
                c.drawPath(p, fill); fill.color = W.FACE; c.drawCircle(7f, 12f, 1.6f, fill)
            }
            Kind.USER -> {
                c.drawCircle(12f, 8f, 4f, fill)
                c.drawPath(Path().apply { moveTo(4f, 21f); quadTo(4f, 13f, 12f, 13f); quadTo(20f, 13f, 20f, 21f); close() }, fill)
            }
            Kind.PASS -> {
                line.strokeWidth = 2.4f; c.drawArc(RectF(7.5f, 3f, 16.5f, 13f), 180f, 180f, false, line)
                c.drawLine(7.5f, 8f, 7.5f, 11f, line); c.drawLine(16.5f, 8f, 16.5f, 11f, line)
                c.drawRect(5f, 11f, 19f, 21f, fill); fill.color = W.FACE; c.drawRect(11f, 14f, 13f, 18f, fill)
            }
            Kind.URL -> { // two chain links
                line.strokeWidth = 2.2f
                c.drawRoundRect(RectF(2.5f, 8.5f, 13f, 15.5f), 3.5f, 3.5f, line)
                c.drawRoundRect(RectF(11f, 8.5f, 21.5f, 15.5f), 3.5f, 3.5f, line)
                line.color = accent; c.drawLine(8f, 12f, 16f, 12f, line)
            }
            Kind.TAG -> {
                fill.color = accent
                val p = Path().apply { moveTo(3f, 3f); lineTo(12f, 3f); lineTo(21f, 12f); lineTo(12f, 21f); lineTo(3f, 12f); close() }
                c.drawPath(p, fill); fill.color = W.HILITE; c.drawCircle(7.5f, 7.5f, 1.8f, fill)
            }
            Kind.OTP -> {
                line.strokeWidth = 2f; c.drawCircle(12f, 13f, 8f, line); c.drawLine(12f, 13f, 12f, 8f, line); c.drawLine(12f, 13f, 15.5f, 15f, line)
                c.drawRect(10f, 2f, 14f, 4f, fill)
            }
            Kind.MEMO -> {
                fill.color = W.HILITE; c.drawRect(5f, 3f, 19f, 21f, fill); line.strokeWidth = 1.5f; c.drawRect(5f, 3f, 19f, 21f, line)
                line.color = accent; for (y in listOf(8f, 12f, 16f)) c.drawLine(8f, y, 16f, y, line)
            }
            Kind.CUSTOM -> { // pencil
                c.save(); c.rotate(45f, 12f, 12f)
                c.drawRect(10f, 3f, 14f, 17f, fill); fill.color = accent; c.drawRect(10f, 3f, 14f, 6f, fill)
                fill.color = color; c.drawPath(Path().apply { moveTo(10f, 18f); lineTo(14f, 18f); lineTo(12f, 22f); close() }, fill)
                c.restore()
            }
            Kind.EYE, Kind.EYE_OFF -> {
                line.strokeWidth = 2f
                c.drawPath(Path().apply { moveTo(2f, 12f); quadTo(12f, 2f, 22f, 12f); quadTo(12f, 22f, 2f, 12f); close() }, line)
                fill.color = accent; c.drawCircle(12f, 12f, 3.5f, fill)
                if (kind == Kind.EYE_OFF) { line.strokeWidth = 2.4f; c.drawLine(4f, 20f, 20f, 4f, line) }
            }
            Kind.OPEN -> {
                line.strokeWidth = 2f
                c.drawPath(Path().apply { moveTo(11f, 4f); lineTo(4f, 4f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 13f) }, line)
                line.color = accent; c.drawLine(11f, 13f, 20f, 4f, line); c.drawLine(14f, 4f, 20f, 4f, line); c.drawLine(20f, 4f, 20f, 10f, line)
            }
            Kind.MORE -> for (y in listOf(5f, 12f, 19f)) c.drawRect(10f, y - 2f, 14f, y + 2f, fill)
            Kind.PLUS -> {
                line.strokeWidth = 2f; c.drawCircle(12f, 12f, 9f, line); line.color = accent; line.strokeWidth = 2.4f
                c.drawLine(12f, 7f, 12f, 17f, line); c.drawLine(7f, 12f, 17f, 12f, line)
            }
            Kind.KEY -> { // big key: round bow + shaft + teeth
                fill.color = accent
                c.drawCircle(15f, 9f, 6f, fill)
                fill.color = W.FACE; c.drawCircle(16.5f, 7.5f, 1.8f, fill)
                fill.color = accent
                c.save(); c.rotate(45f, 12f, 12f)
                c.drawRect(10.5f, 12f, 13.5f, 23f, fill)
                c.drawRect(13.5f, 17f, 16f, 19f, fill); c.drawRect(13.5f, 20.5f, 16f, 23f, fill)
                c.restore()
            }
            Kind.FLOPPY -> {
                fill.color = accent; c.drawRect(3f, 3f, 21f, 21f, fill)
                fill.color = W.HILITE; c.drawRect(6f, 3f, 18f, 10f, fill)
                fill.color = W.FACE; c.drawRect(7f, 14f, 17f, 21f, fill)
                fill.color = accent; c.drawRect(14f, 4f, 16f, 9f, fill)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; line.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; line.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
