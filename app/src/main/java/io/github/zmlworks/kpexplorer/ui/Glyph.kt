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
    enum class Kind { TITLE, USER, PASS, URL, TAG, OTP, MEMO, CUSTOM, EYE, EYE_OFF, OPEN, MORE, PLUS, KEY, FLOPPY,
        MAIL, GLOBE, WIFI, FOOD, ACCOUNT, CARD, BANK, GAME, PHONE, LOCK, CART, HOME, STAR, NOTE }

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
            Kind.MAIL -> {
                fill.color = W.HILITE; c.drawRect(2f, 5f, 22f, 19f, fill)
                line.strokeWidth = 1.6f; c.drawRect(2f, 5f, 22f, 19f, line)
                line.color = accent; line.strokeWidth = 2f
                c.drawPath(Path().apply { moveTo(3f, 6f); lineTo(12f, 13f); lineTo(21f, 6f) }, line)
            }
            Kind.GLOBE -> {
                fill.color = 0xFF3A7BD5.toInt(); c.drawCircle(12f, 12f, 9.5f, fill)
                fill.color = 0xFF2E8B3E.toInt()
                c.drawPath(Path().apply { moveTo(6f, 7f); lineTo(11f, 6f); lineTo(12f, 10f); lineTo(9f, 13f); lineTo(7f, 17f); lineTo(5f, 12f); close() }, fill)
                c.drawPath(Path().apply { moveTo(14f, 12f); lineTo(19f, 11f); lineTo(18f, 16f); lineTo(14f, 18f); close() }, fill)
                line.strokeWidth = 1.4f; line.color = accent; c.drawCircle(12f, 12f, 9.5f, line)
            }
            Kind.WIFI -> {
                line.color = accent; line.strokeWidth = 2.4f; line.strokeCap = Paint.Cap.ROUND
                for ((r, _) in listOf(10f to 0, 6.5f to 0, 3f to 0)) c.drawArc(RectF(12f - r, 18f - r, 12f + r, 18f + r), 225f, 90f, false, line)
                fill.color = accent; c.drawCircle(12f, 18.5f, 1.8f, fill); line.strokeCap = Paint.Cap.SQUARE
            }
            Kind.FOOD -> { // rice bowl with chopsticks
                line.strokeWidth = 1.8f; c.drawLine(13f, 2f, 20f, 10f, line); c.drawLine(16f, 2f, 21f, 9f, line)
                fill.color = W.HILITE; c.drawArc(RectF(4f, 6f, 20f, 14f), 180f, 180f, true, fill)
                fill.color = 0xFFC0392B.toInt(); c.drawArc(RectF(3f, 4f, 21f, 22f), 0f, 180f, true, fill)
                fill.color = color; c.drawRect(8f, 20.5f, 16f, 22f, fill)
            }
            Kind.ACCOUNT -> {
                fill.color = accent; c.drawCircle(12f, 12f, 10f, fill)
                fill.color = W.HILITE; c.drawCircle(12f, 9f, 3.6f, fill)
                c.drawPath(Path().apply { moveTo(5.5f, 18.5f); quadTo(12f, 10.5f, 18.5f, 18.5f); quadTo(12f, 23f, 5.5f, 18.5f); close() }, fill)
            }
            Kind.CARD -> {
                fill.color = 0xFF2E8B3E.toInt(); c.drawRoundRect(RectF(2f, 5f, 22f, 19f), 2f, 2f, fill)
                fill.color = color; c.drawRect(2f, 8f, 22f, 11f, fill)
                fill.color = 0xFFE0C040.toInt(); c.drawRect(5f, 13f, 9f, 16f, fill)
                fill.color = W.HILITE; c.drawRect(12f, 14f, 19f, 15.5f, fill)
            }
            Kind.BANK -> {
                fill.color = accent
                c.drawPath(Path().apply { moveTo(2f, 9f); lineTo(12f, 3f); lineTo(22f, 9f); close() }, fill)
                for (x in listOf(4f, 9f, 14f, 19f)) c.drawRect(x, 10f, x + 2f, 18f, fill)
                c.drawRect(2f, 19f, 22f, 21.5f, fill)
            }
            Kind.GAME -> {
                fill.color = W.SHADOW; c.drawRoundRect(RectF(2f, 7f, 22f, 18f), 5f, 5f, fill)
                fill.color = color; c.drawRect(5f, 11.5f, 10f, 13.5f, fill); c.drawRect(6.5f, 10f, 8.5f, 15f, fill)
                fill.color = 0xFFC0392B.toInt(); c.drawCircle(16f, 11f, 1.5f, fill)
                fill.color = accent; c.drawCircle(18.5f, 13.5f, 1.5f, fill)
            }
            Kind.PHONE -> {
                fill.color = color; c.drawRoundRect(RectF(6f, 2f, 18f, 22f), 2f, 2f, fill)
                fill.color = 0xFF9ED0FF.toInt(); c.drawRect(7.5f, 4.5f, 16.5f, 18f, fill)
                fill.color = W.HILITE; c.drawCircle(12f, 20f, 1f, fill)
            }
            Kind.LOCK -> {
                line.color = W.SHADOW; line.strokeWidth = 2.6f; c.drawArc(RectF(7f, 2.5f, 17f, 13f), 180f, 180f, false, line)
                c.drawLine(7f, 8f, 7f, 11f, line); c.drawLine(17f, 8f, 17f, 11f, line)
                fill.color = 0xFFE0B020.toInt(); c.drawRect(4.5f, 10.5f, 19.5f, 21.5f, fill)
                fill.color = color; c.drawCircle(12f, 15f, 1.8f, fill); c.drawRect(11.2f, 15f, 12.8f, 19f, fill)
            }
            Kind.CART -> {
                line.strokeWidth = 2f; c.drawPath(Path().apply { moveTo(2f, 4f); lineTo(5f, 4f); lineTo(8f, 16f); lineTo(19f, 16f) }, line)
                fill.color = accent; c.drawPath(Path().apply { moveTo(6f, 7f); lineTo(21f, 7f); lineTo(19f, 13f); lineTo(7.5f, 13f); close() }, fill)
                fill.color = color; c.drawCircle(9f, 19.5f, 1.8f, fill); c.drawCircle(18f, 19.5f, 1.8f, fill)
            }
            Kind.HOME -> {
                fill.color = 0xFFC0392B.toInt(); c.drawPath(Path().apply { moveTo(2f, 12f); lineTo(12f, 3f); lineTo(22f, 12f); close() }, fill)
                fill.color = 0xFFF0E0B0.toInt(); c.drawRect(5f, 12f, 19f, 21f, fill)
                fill.color = accent; c.drawRect(10f, 15f, 14f, 21f, fill)
            }
            Kind.STAR -> {
                fill.color = 0xFFE0B020.toInt()
                val p = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 10f else 4.2f
                    val ang = Math.toRadians((-90 + i * 36).toDouble())
                    val x = 12f + r * Math.cos(ang).toFloat(); val y = 12.5f + r * Math.sin(ang).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); c.drawPath(p, fill); line.strokeWidth = 1f; c.drawPath(p, line)
            }
            Kind.NOTE -> {
                fill.color = 0xFFFFF59D.toInt(); c.drawRect(4f, 3f, 20f, 21f, fill)
                line.strokeWidth = 1.4f; c.drawRect(4f, 3f, 20f, 21f, line)
                line.color = W.SHADOW; for (y in listOf(8f, 12f, 16f)) c.drawLine(7f, y, 17f, y, line)
            }
        }
        c.restore()
    }

    companion object {
        /** Renders a glyph into a small bitmap (explorer list / grid use bitmaps scaled by integer factors). */
        private val cache = HashMap<String, android.graphics.Bitmap>()
        fun bitmap(kind: Kind, size: Int): android.graphics.Bitmap = cache.getOrPut("$kind/$size") {
            val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val g = Glyph(kind); g.setBounds(0, 0, size, size); g.draw(Canvas(b)); b
        }
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; line.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; line.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
