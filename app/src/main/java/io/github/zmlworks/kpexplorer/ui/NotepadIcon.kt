package io.github.zmlworks.kpexplorer.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Original Win9x-style notepad glyph (not a Microsoft asset copy): white page, navy header bar,
 * spiral binding rings, ruled lines, red margin. Drawn on a 24×24 design grid; primarily navy/blue.
 */
class NotepadIcon(
    private val page: Int = 0xFFFFFFF8.toInt(),
    private val ink: Int = W.NAVY,
    private val rule: Int = 0xFF9EB8E0.toInt(),
    private val margin: Int = 0xFFE89090.toInt(),
    private val ring: Int = W.NAVY,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        c.scale(s, s)
        // page
        fill.color = page
        c.drawRoundRect(RectF(4f, 3f, 20f, 22f), 1.2f, 1.2f, fill)
        stroke.color = ink; stroke.strokeWidth = 1.2f
        c.drawRoundRect(RectF(4f, 3f, 20f, 22f), 1.2f, 1.2f, stroke)
        // navy header
        fill.color = ink
        c.drawRect(4f, 3f, 20f, 7.5f, fill)
        // spiral rings
        stroke.strokeWidth = 1.4f; stroke.color = ring
        for (x in listOf(7f, 10.5f, 14f, 17.5f)) {
            c.drawLine(x, 1.5f, x, 7f, stroke)
            fill.color = 0xFFE8E8F0.toInt()
            c.drawCircle(x, 2.2f, 1.1f, fill)
            stroke.color = ink; c.drawCircle(x, 2.2f, 1.1f, stroke)
            stroke.color = ring
        }
        // ruled lines
        stroke.color = rule; stroke.strokeWidth = 1f
        for (y in listOf(10f, 13f, 16f, 19f)) c.drawLine(6.5f, y, 18f, y, stroke)
        // margin
        stroke.color = margin; stroke.strokeWidth = 1.2f
        c.drawLine(8f, 8.5f, 8f, 21f, stroke)
        // scribble
        stroke.color = ink; stroke.strokeWidth = 1.1f
        c.drawLine(10f, 10f, 16.5f, 10f, stroke)
        c.drawLine(10f, 13f, 15f, 13f, stroke)
        c.drawLine(10f, 16f, 17f, 16f, stroke)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; stroke.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; stroke.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
