package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Hand-made pixel-art icons in the Windows 9x palette, scaled with nearest-neighbour. */
object Px {
    private val PAL = mapOf(
        'K' to 0xFF000000.toInt(), 'W' to 0xFFFFFFFF.toInt(), 'g' to 0xFFC0C0C0.toInt(), 'd' to 0xFF808080.toInt(),
        'Y' to 0xFFFFFF80.toInt(), 'L' to 0xFFFFFFE0.toInt(), 'o' to 0xFFE0C040.toInt(), 'y' to 0xFF808000.toInt(),
        'B' to 0xFF000080.toInt(), 'b' to 0xFF0000FF.toInt(), 'C' to 0xFF00FFFF.toInt(), 'c' to 0xFF008080.toInt(),
        'R' to 0xFFFF0000.toInt(), 'r' to 0xFF800000.toInt(), 'G' to 0xFF00C000.toInt(), 'n' to 0xFF008000.toInt(),
        'O' to 0xFFB06820.toInt(), 'S' to 0xFFFFD700.toInt()
    )

    private val cache = HashMap<String, Bitmap>()

    private fun art(name: String, vararg rows: String): Bitmap = cache.getOrPut(name) {
        val h = rows.size; val w = rows[0].length
        require(rows.all { it.length == w }) { "bad art $name" }
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = PAL[rows[y][x]] ?: 0
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun drawable(ctx: Context, bmp: Bitmap, scale: Int): Drawable {
        val d = object : BitmapDrawable(ctx.resources, bmp) {
            override fun getIntrinsicWidth() = bmp.width * scale
            override fun getIntrinsicHeight() = bmp.height * scale
        }
        d.setFilterBitmap(false)
        d.isFilterBitmap = false
        d.setAntiAlias(false)
        return d
    }

    /** Explorer-style "selected" look: navy dither over the opaque pixels. */
    fun selected(src: Bitmap): Bitmap = cache.getOrPut("sel:" + System.identityHashCode(src)) {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            if ((c ushr 24) != 0 && (x + y) % 2 == 0) b.setPixel(x, y, 0xFF000080.toInt())
        }
        b
    }

    /** "Cut" look: half transparent. */
    fun ghost(src: Bitmap): Bitmap = cache.getOrPut("ghost:" + System.identityHashCode(src)) {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            if ((c ushr 24) != 0 && (x + y) % 2 == 1) b.setPixel(x, y, 0)
        }
        b
    }

    // ------------------------------------------------------------------ 16×16

    val folder16 get() = art("folder16",
        "................",
        "................",
        "..yyyyy.........",
        ".yLLLLLy........",
        "yLYYYYYLyyyyyyy.",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yoooooooooooooyK",
        "yyyyyyyyyyyyyyyK",
        ".KKKKKKKKKKKKKKK",
        "................")

    val file16 get() = art("file16",
        "..KKKKKKKKK.....",
        "..KWWWWWWWKK....",
        "..KWWWWWWWKWK...",
        "..KWddddWWKKKK..",
        "..KWWWWWWWWWWK..",
        "..KWdddddddWWK..",
        "..KWWWWWWWWWWK..",
        "..KWdddddddWWK..",
        "..KWWWWWWWWWWK..",
        "..KWdddddddWWK..",
        "..KWWWWWWWWWWK..",
        "..KWddddddWWWK..",
        "..KWWWWWWWWWWK..",
        "..KWWWWWWWWWWK..",
        "..KKKKKKKKKKKK..",
        "................")

    val keyfile16 get() = art("keyfile16",
        "..KKKKKKKKK.....",
        "..KWWWWWWWKK....",
        "..KWWWWWWWKWK...",
        "..KWddddWWKKKK..",
        "..KWWWWWWWWWWK..",
        "..KWdddddddWWK..",
        "..KWWWWWWWWWWK..",
        "..KWddddKKKWWK..",
        "..KWWWWKSSSKWK..",
        "..KWddKSKWKSKK..",
        "..KWWWWKSSSKWK..",
        "..KWddddKSKWWK..",
        "..KWWWWWKSSKWK..",
        "..KWWWWWKSKWWK..",
        "..KKKKKKKKKKKK..",
        "................")

    val bin16 get() = art("bin16",
        "................",
        "....KKKKKKK.....",
        "..KKgWWWWWgKK...",
        ".KgggggggggggK..",
        ".KdddddddddddK..",
        "..KgWgWgWgWgK...",
        "..KgWgWgWgWgK...",
        "..KgWgWgWgWgK...",
        "..KgWgWgWgWgK...",
        "...KgWgWgWgK....",
        "...KgWgWgWgK....",
        "...KgWgWgWgK....",
        "...KgWgWgWgK....",
        "....KdddddK.....",
        ".....KKKKK......",
        "................")

    val back16 get() = art("back16",
        "................",
        "................",
        "......K.........",
        ".....KGK........",
        "....KGGK........",
        "...KGGGKKKKKKK..",
        "..KGGGGGGGGGGGK.",
        ".KGGGGGGGGGGGGK.",
        "..KGGnnnnnnnnnK.",
        "...KGnnKKKKKKK..",
        "....KnnK........",
        ".....KnK........",
        "......K.........",
        "................",
        "................",
        "................")

    val up16 get() = art("up16",
        "................",
        "..yyyyy.........",
        ".yLLLLLy........",
        "yLYYYYYLyyyyyyy.",
        "yLYYYYYKYYYYYYyK",
        "yLYYYYKKKYYYYYyK",
        "yLYYYKKKKKYYYYyK",
        "yLYYYYYKYYYYYYyK",
        "yLYYYYYKYYYYYYyK",
        "yLYYYYYKYYYYYYyK",
        "yLYYYYYKKKKKYYyK",
        "yLYYYYYYYYYYYYyK",
        "yoooooooooooooyK",
        "yyyyyyyyyyyyyyyK",
        ".KKKKKKKKKKKKKKK",
        "................")

    val cut16 get() = art("cut16",
        "................",
        "....d.....d.....",
        "....d.....d.....",
        "....dd...dd.....",
        ".....d...d......",
        ".....dd.dd......",
        "......ddd.......",
        ".......d........",
        "......K.K.......",
        "...KKK...KKK....",
        "..K..K...K..K...",
        ".K...K...K...K..",
        ".K...K...K...K..",
        "..K.K.....K.K...",
        "...K.......K....",
        "................")

    val paste16 get() = art("paste16",
        "................",
        "......KKK.......",
        "..OOOKgggKOOO...",
        ".KOOKWWWWWKOOK..",
        ".KOOOKKKKKOOOK..",
        ".KOOOOOOOOOOOK..",
        ".KOOOOKKKKKKKKK.",
        ".KOOOOKWWWWWWWK.",
        ".KOOOOKWddddWWK.",
        ".KOOOOKWWWWWWWK.",
        ".KOOOOKWddddWWK.",
        ".KOOOOKWWWWWWWK.",
        ".KOOOOKWdddddWK.",
        ".KKKKKKWWWWWWWK.",
        "......KKKKKKKKK.",
        "................")

    val delete16 get() = art("delete16",
        "................",
        "................",
        "..RR.......RR...",
        "..RRR.....RRR...",
        "...RRR...RRR....",
        "....RRR.RRR.....",
        ".....RRRRR......",
        "......RRR.......",
        ".....RRRRR......",
        "....RRR.RRR.....",
        "...RRR...RRR....",
        "..RRR.....RRR...",
        "..RR.......RR...",
        "................",
        "................",
        "................")

    val newFolder16 get() = art("newFolder16",
        "............S...",
        "..........S.S.S.",
        "...........SWS..",
        "..yyyyy...SSWSS.",
        ".yLLLLLy...SWS..",
        "yLYYYYYLyyS.S.S.",
        "yLYYYYYYYYYYSYyK",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yLYYYYYYYYYYYYyK",
        "yoooooooooooooyK",
        "yyyyyyyyyyyyyyyK",
        ".KKKKKKKKKKKKKKK",
        "................")

    val newFile16 get() = art("newFile16",
        "............S...",
        "KKKKKKKKK.S.S.S.",
        "KWWWWWWWKK.SWS..",
        "KWWWWWWWKWSSWSS.",
        "KWddddWWKKKSWS..",
        "KWWWWWWWWWS.S.S.",
        "KWdddddddWWKS...",
        "KWWWWWWWWWWK....",
        "KWdddddddWWK....",
        "KWWWWWWWWWWK....",
        "KWdddddddWWK....",
        "KWWWWWWWWWWK....",
        "KWddddddWWWK....",
        "KWWWWWWWWWWK....",
        "KKKKKKKKKKKK....",
        "................")

    val floppy16 get() = art("floppy16",
        "................",
        ".KKKKKKKKKKKKKK.",
        ".KBBggggggggBBK.",
        ".KBBggKKggggBBK.",
        ".KBBggKKggggBBK.",
        ".KBBggggggggBBK.",
        ".KBBBBBBBBBBBBK.",
        ".KBBBBBBBBBBBBK.",
        ".KBWWWWWWWWWWBK.",
        ".KBWddddddddWBK.",
        ".KBWWWWWWWWWWBK.",
        ".KBWddddddddWBK.",
        ".KBWWWWWWWWWWBK.",
        ".KBWWWWWWWWWWBK.",
        ".KKKKKKKKKKKKKK.",
        "................")

    val find16 get() = art("find16",
        "................",
        "....KKKK........",
        "..KKCCCCKK......",
        "..KCWCCCCK......",
        ".KCWCCCCCCK.....",
        ".KCCCCCCCCK.....",
        ".KCCCCCCCCK.....",
        ".KCCCCCCCCK.....",
        "..KCCCCCCK......",
        "..KKCCCCKK......",
        "....KKKKdK......",
        ".........KdK....",
        "..........KdK...",
        "...........KdK..",
        "............KK..",
        "................")

    val lock16 get() = art("lock16",
        "................",
        ".....KKKKK......",
        "....KdddddK.....",
        "...KdK...KdK....",
        "...Kd.....dK....",
        "...Kd.....dK....",
        "..KKKKKKKKKKK...",
        "..KSSSSSSSSSK...",
        "..KSoooooooSK...",
        "..KSoooKoooSK...",
        "..KSooKKKooSK...",
        "..KSoooKoooSK...",
        "..KSoooooooSK...",
        "..KKKKKKKKKKK...",
        "................",
        "................")

    val key16 get() = art("key16",
        "................",
        "................",
        "...KKKK.........",
        "..KSSSSK........",
        ".KSSKKSSK.......",
        ".KSK..KSK.......",
        ".KSK..KSKKKKKKK.",
        ".KSSKKSSSSSSSSSK",
        "..KSSSSKKKKSKSK.",
        "...KKKK....K.K..",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................")

    val computer16 get() = art("computer16",
        "................",
        ".KKKKKKKKKKKKK..",
        ".KgggggggggggK..",
        ".KgKKKKKKKKKgK..",
        ".KgKccccccdKgK..",
        ".KgKccccccdKgK..",
        ".KgKccccccdKgK..",
        ".KgKddddddWKgK..",
        ".KgggggggggggK..",
        ".KKKKKKKKKKKKK..",
        "....KgggggK.....",
        "..KKKKKKKKKKK...",
        "..KgggggggGgK...",
        "..KdddddddddK...",
        "..KKKKKKKKKKK...",
        "................")

    // ------------------------------------------------------------------ 32×32 (drawn with primitives)

    private class P(val w: Int, val h: Int) {
        val a = IntArray(w * h)
        fun set(x: Int, y: Int, c: Char) { if (x in 0 until w && y in 0 until h) a[y * w + x] = PAL[c] ?: 0 }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Char) { for (y in y0..y1) for (x in x0..x1) set(x, y, c) }
        fun h(x0: Int, x1: Int, y: Int, c: Char) = rect(x0, y, x1, y, c)
        fun v(x: Int, y0: Int, y1: Int, c: Char) = rect(x, y0, x, y1, c)
        fun box(x0: Int, y0: Int, x1: Int, y1: Int, fill: Char, line: Char) { rect(x0, y0, x1, y1, line); rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill) }
        fun bmp(): Bitmap = Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    val folder32: Bitmap get() = cache.getOrPut("folder32") {
        val p = P(32, 32)
        // back panel with tab
        p.box(1, 5, 12, 10, 'Y', 'y')
        p.box(1, 8, 28, 27, 'o', 'y')
        p.h(2, 11, 6, 'L'); p.v(2, 6, 26, 'L')
        // front flap (slanted)
        for (y in 12..28) {
            val off = (28 - y) / 4
            val x0 = 3 + off; val x1 = 30 + off.coerceAtMost(1) - 0
            p.h(x0, x1.coerceAtMost(30), y, 'Y')
            p.set(x0, y, 'y'); p.set(x1.coerceAtMost(30), y, 'y')
        }
        p.h(7, 30, 12, 'y'); p.h(8, 29, 13, 'L')
        p.h(3, 30, 28, 'y')
        p.v(31, 13, 29, 'K'); p.h(4, 31, 29, 'K')
        p.bmp()
    }

    val file32: Bitmap get() = cache.getOrPut("file32") {
        val p = P(32, 32)
        p.box(5, 1, 26, 30, 'W', 'K')
        // dog-ear
        for (i in 0..6) { for (x in 26 - 6 + i..26) p.set(x, 1 + i, '.'); }
        for (i in 0..6) p.set(20 + i, 1 + i, 'K')
        p.rect(20, 2, 20, 7, 'K'); p.h(20, 26, 7, 'K')
        for (i in 1..5) for (x in 21..(20 + i)) p.set(x, 1 + i, 'g')
        p.v(26, 7, 30, 'K')
        // text lines
        val lens = intArrayOf(10, 15, 13, 16, 9, 14, 12)
        for ((k, n) in lens.withIndex()) p.h(9, 9 + n - 1, 10 + k * 3, 'd')
        p.h(9, 15, 4, 'd')
        // drop shadow
        p.v(27, 3, 31, 'd'); p.h(7, 27, 31, 'd')
        p.bmp()
    }

    /** text file with a small gold key (entry that holds a password) */
    val keyfile32: Bitmap get() = cache.getOrPut("keyfile32") {
        val src = file32
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        val k = arrayOf(
            "..KKKK.......",
            ".KSSSSK......",
            "KSSKKSSKKKKKK",
            "KSK..KSSSSSSSK",
            "KSSKKSSKKSKSK.",
            ".KSSSSK.K.K..",
            "..KKKK.......")
        for ((j, row) in k.withIndex()) for ((i, ch) in row.withIndex()) PAL[ch]?.let { b.setPixel(9 + i, 21 + j, it) }
        b
    }

    val bin32: Bitmap get() = cache.getOrPut("bin32") {
        val p = P(32, 32)
        p.rect(7, 4, 24, 5, 'K'); p.h(6, 25, 6, 'K'); p.h(7, 24, 7, 'd'); p.rect(6, 5, 6, 6, 'K'); p.rect(25, 5, 25, 6, 'K')
        p.h(8, 23, 5, 'W')
        for (y in 8..28) {
            val off = (y - 8) / 7
            val x0 = 6 + off; val x1 = 25 - off
            p.h(x0, x1, y, 'g'); p.set(x0, y, 'K'); p.set(x1, y, 'K')
            var x = x0 + 2
            while (x < x1 - 1) { p.set(x, y, if (y % 6 == 0) 'd' else 'W'); x += 3 }
        }
        p.h(8, 23, 29, 'K'); p.h(8, 23, 28, 'd')
        p.v(26, 9, 29, 'd')
        p.bmp()
    }

    val computer32: Bitmap get() = cache.getOrPut("computer32") {
        val p = P(32, 32)
        p.box(3, 2, 28, 20, 'g', 'K')
        p.box(6, 4, 25, 17, 'c', 'K')
        p.h(7, 24, 16, 'd'); p.v(24, 5, 16, 'd'); p.h(7, 12, 6, 'W')
        p.rect(12, 21, 19, 23, 'g'); p.v(12, 21, 23, 'K'); p.v(19, 21, 23, 'K')
        p.box(4, 24, 27, 29, 'g', 'K'); p.h(5, 26, 28, 'd'); p.rect(20, 26, 24, 26, 'G')
        p.bmp()
    }

    val floppy32: Bitmap get() = cache.getOrPut("floppy32") {
        val b = floppy16
        Bitmap.createScaledBitmap(b, 32, 32, false)
    }

    /** Classic message-box icons (32×32): 'i' info, '?' question, '!' warning, 'x' error. */
    fun msgIcon(kind: Char): Bitmap = cache.getOrPut("msg$kind") {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val p = P(32, 32)
        when (kind) {
            '!' -> {
                for (y in 2..28) {
                    val half = (y - 2) / 2
                    p.h(15 - half, 16 + half, y, 'K')
                    if (y in 4..27) p.h(15 - half + 1, 16 + half - 1, y, 'S')
                }
                p.rect(15, 9, 16, 20, 'K'); p.rect(15, 23, 16, 25, 'K')
            }
            else -> {
                val fill = if (kind == 'x') 'R' else 'W'
                for (y in 0 until 32) for (x in 0 until 32) {
                    val dx = x - 15.5; val dy = y - 14.5
                    val d = dx * dx + dy * dy
                    if (d < 14.0 * 14.0) p.set(x, y, 'K')
                    if (d < 13.0 * 13.0) p.set(x, y, fill)
                }
                if (kind != 'x') { p.rect(9, 26, 14, 28, 'K'); p.rect(11, 25, 13, 26, 'W') }
                when (kind) {
                    'i' -> { p.rect(14, 6, 17, 9, 'b'); p.rect(14, 12, 17, 22, 'b'); p.rect(12, 12, 13, 12, 'b'); p.rect(12, 22, 19, 22, 'b') }
                    '?' -> {
                        p.rect(12, 6, 19, 7, 'b'); p.rect(10, 8, 12, 11, 'b'); p.rect(19, 8, 21, 12, 'b')
                        p.rect(16, 13, 19, 14, 'b'); p.rect(14, 15, 16, 17, 'b'); p.rect(14, 20, 16, 22, 'b')
                    }
                    'x' -> for (i in 0..10) { p.rect(10 + i, 9 + i, 11 + i, 10 + i, 'W'); p.rect(20 - i, 9 + i, 21 - i, 10 + i, 'W') }
                }
            }
        }
        Canvas(bmp).drawBitmap(p.bmp(), 0f, 0f, Paint())
        bmp
    }
}
