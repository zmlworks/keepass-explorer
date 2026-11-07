package io.github.zmlworks.kpexplorer.ui

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/** 0.3.0 icons: Notepad-style memo, big gold key, toolbar glyphs (drawn procedurally, Win9x palette). */
object Ico {
    private const val K = 0xFF000000.toInt(); private const val W = 0xFFFFFFFF.toInt(); private const val G = 0xFFC0C0C0.toInt()
    private const val D = 0xFF808080.toInt(); private const val NAVY = 0xFF000080.toInt(); private const val BLUE = 0xFF0000FF.toInt()
    private const val CYAN = 0xFF00FFFF.toInt(); private const val TEAL = 0xFF008080.toInt(); private const val GOLD = 0xFFFFD700.toInt()
    private const val OCHRE = 0xFFC09000.toInt(); private const val BROWN = 0xFF805000.toInt(); private const val LY = 0xFFFFFF80.toInt()
    private const val RED = 0xFFFF0000.toInt(); private const val LBLUE = 0xFFA0C0FF.toInt()

    private val cache = HashMap<String, Bitmap>()

    private class P(val w: Int, val h: Int) {
        val a = IntArray(w * h)
        fun set(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) a[y * w + x] = c }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0..y1) for (x in x0..x1) set(x, y, c) }
        fun h(x0: Int, x1: Int, y: Int, c: Int) = rect(x0, y, x1, y, c)
        fun v(x: Int, y0: Int, y1: Int, c: Int) = rect(x, y0, x, y1, c)
        fun box(x0: Int, y0: Int, x1: Int, y1: Int, fill: Int, line: Int) { rect(x0, y0, x1, y1, line); rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill) }
        fun bmp(): Bitmap = Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Independent-memo icon (not the selectable entry "note"): classic angled notepad —
     * white ruled page with a translucent blue clear plastic cover. Original drawing
     * (not a Microsoft asset copy). Used for Order.Kind.MEMO list / toolbar / preview.
     */
    val notepad32: Bitmap get() = cache.getOrPut("memo32-049") { MemoArt.memo32() }
    val notepad16: Bitmap get() = cache.getOrPut("memo16-049") { MemoArt.memo16() }

    val bigKey32: Bitmap get() = cache.getOrPut("bigKey32") { key(32) }
    val key16: Bitmap get() = cache.getOrPut("key16") { key(16) }

    private fun key(n: Int): Bitmap {
        val p = P(n, n)
        val s = n / 32f
        val cx = 9.5f * s; val cy = 9.5f * s; val ro = 8f * s; val ri = 3.2f * s
        // shaft along the diagonal from ring to bottom-right
        fun shaft(x: Float, y: Float): Float { val t = ((x - cx) + (y - cy)) / 2f; val px = cx + t; val py = cy + t
            return if (t < 0 || t > 20f * s) 99f else sqrt((x - px) * (x - px) + (y - py) * (y - py)) }
        val col = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val fx = x + .5f; val fy = y + .5f
            val d = sqrt((fx - cx) * (fx - cx) + (fy - cy) * (fy - cy))
            var c = 0
            if (d <= ro && d >= ri) c = if (fx + fy < cx + cy - 2 * s) LY else if (d > ro - 1.2f * s || d < ri + 1f) OCHRE else GOLD
            else if (shaft(fx, fy) <= 2.0f * s) c = if (fx - fy > 1f * s) OCHRE else GOLD
            // teeth: perpendicular stubs toward bottom-left
            val t = ((fx - cx) + (fy - cy)) / 2f
            val off = ((fy - cy) - (fx - cx)) / 2f
            if (c == 0 && off > 0 && off < 5f * s && (abs(t - 15f * s) < 1.6f * s || abs(t - 19f * s) < 1.6f * s)) c = OCHRE
            col[y * n + x] = c
        }
        // 1px dark outline
        for (y in 0 until n) for (x in 0 until n) {
            if (col[y * n + x] != 0) { p.set(x, y, col[y * n + x]); continue }
            val nb = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { (a, b) -> a in 0 until n && b in 0 until n && col[b * n + a] != 0 }
            if (nb) p.set(x, y, BROWN)
        }
        return p.bmp()
    }

    val viewIcons16: Bitmap get() = cache.getOrPut("viewIcons16") {
        val p = P(16, 16)
        for (r in 0..1) for (c in 0..1) p.box(1 + c * 8, 1 + r * 8, 6 + c * 8, 6 + r * 8, TEAL, K)
        p.bmp()
    }

    val viewDetails16: Bitmap get() = cache.getOrPut("viewDetails16") {
        val p = P(16, 16)
        for (i in 0..3) { p.box(1, 1 + i * 4, 3, 3 + i * 4, TEAL, K); p.h(5, 14, 2 + i * 4, K) }
        p.bmp()
    }

    private fun star(p: P, x: Int, y: Int) {
        p.h(x - 2, x + 2, y, GOLD); p.v(x, y - 2, y + 2, GOLD); p.set(x - 1, y - 1, GOLD); p.set(x + 1, y + 1, GOLD)
        p.set(x + 1, y - 1, GOLD); p.set(x - 1, y + 1, GOLD); p.set(x, y, W)
    }

    val newKey16: Bitmap get() = cache.getOrPut("newKey16") {
        val p = P(16, 16); val k = key16
        for (y in 0 until 16) for (x in 0 until 16) { val c = k.getPixel(x, y); if (c != 0) p.set(x, y, c) }
        star(p, 13, 2); p.bmp()
    }

    val newMemo16: Bitmap get() = cache.getOrPut("newMemo16-049") {
        val p = P(16, 16); val k = notepad16
        for (y in 0 until 16) for (x in 0 until 16) { val c = k.getPixel(x, y); if (c != 0) p.set(x, y, c) }
        star(p, 13, 2); p.bmp()
    }

    val menu16: Bitmap get() = cache.getOrPut("menu16") {
        val p = P(16, 16); for (i in 0..2) p.rect(3, 3 + i * 4, 12, 4 + i * 4, K); p.bmp()
    }

    @Suppress("unused") private val unused = intArrayOf(CYAN, RED)
}
