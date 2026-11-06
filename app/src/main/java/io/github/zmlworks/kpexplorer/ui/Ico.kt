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
    val notepad32: Bitmap get() = cache.getOrPut("notepad32-clear2") { notepad32draw() }
    val notepad16: Bitmap get() = cache.getOrPut("notepad16-clear2") { notepad16draw() }

    private fun notepad16draw(): Bitmap {
        // Hand-tuned 16×16: white pad, blue clear cover, ruled lines, no spiral.
        val p = P(16, 16)
        // shadow
        p.rect(4, 3, 14, 14, D)
        // white page
        p.rect(2, 2, 12, 13, W)
        p.h(2, 12, 2, K); p.h(2, 12, 13, K); p.v(2, 2, 13, K); p.v(12, 2, 13, K)
        // binding strip
        p.rect(3, 3, 11, 4, G); p.h(3, 11, 4, D)
        // rules + margin
        for (y in intArrayOf(7, 9, 11)) p.h(4, 10, y, LBLUE)
        p.v(4, 6, 12, 0xFFFF9090.toInt())
        // translucent blue clear cover (top ~2/3), drawn with alpha via blend into existing
        val cover = 0x992060E0.toInt(); val edge = 0xBB1040B0.toInt(); val shine = 0x66FFFFFF.toInt()
        fun put(x: Int, y: Int, c: Int) {
            if (x !in 0 until 16 || y !in 0 until 16) return
            val i = y * 16 + x; val dst = p.a[i]; val sa = (c ushr 24) and 0xFF
            if (dst == 0) { p.a[i] = c; return }
            val da = (dst ushr 24) and 0xFF
            val outA = sa + da * (255 - sa) / 255
            fun ch(s: Int, d: Int) = (s * sa + d * da * (255 - sa) / 255) / maxOf(1, outA)
            p.a[i] = (outA shl 24) or (ch((c shr 16) and 0xFF, (dst shr 16) and 0xFF) shl 16) or
                (ch((c shr 8) and 0xFF, (dst shr 8) and 0xFF) shl 8) or ch(c and 0xFF, dst and 0xFF)
        }
        for (y in 1..9) for (x in 1..12) {
            val e = x == 1 || x == 12 || y == 1 || y == 9
            put(x, y, if (e) edge else cover)
            if (!e && x + y in 5..7) put(x, y, shine)
        }
        return p.bmp()
    }

    private fun notepad32draw(): Bitmap {
        val n = 32
        val a = IntArray(n * n)
        fun blend(dst: Int, src: Int): Int {
            if (src == 0) return dst
            val sa = (src ushr 24) and 0xFF
            if (sa >= 250 || dst == 0) return src
            val da = (dst ushr 24) and 0xFF
            val outA = sa + da * (255 - sa) / 255
            fun ch(s: Int, d: Int) = (s * sa + d * da * (255 - sa) / 255) / maxOf(1, outA)
            return (outA shl 24) or (ch((src shr 16) and 0xFF, (dst shr 16) and 0xFF) shl 16) or
                (ch((src shr 8) and 0xFF, (dst shr 8) and 0xFF) shl 8) or ch(src and 0xFF, dst and 0xFF)
        }
        fun put(x: Int, y: Int, c: Int) { if (x in 0 until n && y in 0 until n) a[y * n + x] = blend(a[y * n + x], c) }
        // shadow
        for (y in 5..29) for (x in 7..27) {
            val u = x - 7f - (y - 5f) * 0.06f; val v = y - 5f
            if (u in 0f..18f && v in 0f..23f) put(x, y, 0x80808080.toInt())
        }
        // white ruled page (slight tilt)
        for (y in 3..28) for (x in 4..25) {
            val u = x - 5f - (y - 4f) * 0.08f; val v = y - 4f
            if (u !in 0f..19f || v !in 0f..23f) continue
            val edge = u < 0.7f || u > 18.3f || v < 0.7f || v > 22.3f
            put(x, y, if (edge) K else W)
            if (!edge && v > 5.5f) {
                if (((v - 6.5f) % 3.0f) < 0.5f) put(x, y, LBLUE)
                if (u in 3.2f..3.9f) put(x, y, 0xFFFF9090.toInt())
                if (v in 8.5f..9.3f && u in 5f..14f) put(x, y, K)
                if (v in 11.5f..12.3f && u in 5f..12f) put(x, y, K)
                if (v in 14.5f..15.3f && u in 5f..15f) put(x, y, K)
            }
        }
        // top binding
        for (y in 3..28) for (x in 4..25) {
            val u = x - 5f - (y - 4f) * 0.08f; val v = y - 4f
            if (u in 0.5f..18.5f && v in 0.5f..2.8f) put(x, y, G)
            if (u in 0.5f..18.5f && v in 2.3f..3.0f) put(x, y, D)
        }
        // translucent blue clear cover over most of pad
        val cover = 0x88286AE8.toInt(); val coverEdge = 0xBB1850C0.toInt(); val shine = 0x66FFFFFF.toInt()
        for (y in 2..22) for (x in 3..24) {
            val u = x - 4f - (y - 2.5f) * 0.1f; val v = y - 2.5f
            if (u !in 0f..18f || v !in 0f..16f) continue
            val edge = u < 0.6f || u > 17.4f || v < 0.6f || v > 15.4f
            put(x, y, if (edge) coverEdge else cover)
            if (!edge && u + v * 0.35f in 3.5f..6.5f) put(x, y, shine)
        }
        return Bitmap.createBitmap(a, n, n, Bitmap.Config.ARGB_8888)
    }

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

    val newMemo16: Bitmap get() = cache.getOrPut("newMemo16-clear2") {
        val p = P(16, 16); val k = notepad16
        for (y in 0 until 16) for (x in 0 until 16) { val c = k.getPixel(x, y); if (c != 0) p.set(x, y, c) }
        star(p, 13, 2); p.bmp()
    }

    val menu16: Bitmap get() = cache.getOrPut("menu16") {
        val p = P(16, 16); for (i in 0..2) p.rect(3, 3 + i * 4, 12, 4 + i * 4, K); p.bmp()
    }

    @Suppress("unused") private val unused = intArrayOf(CYAN, RED)
}
