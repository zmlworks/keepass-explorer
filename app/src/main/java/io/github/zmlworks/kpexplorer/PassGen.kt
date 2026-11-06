package io.github.zmlworks.kpexplorer

import android.view.Gravity
import android.widget.Toast
import io.github.zmlworks.kpexplorer.ui.ClassicDialog
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.W
import java.security.SecureRandom

/**
 * Standalone menu password generator: class checkboxes → long SecureRandom password with copy.
 * Guarantees every selected class appears at least once; unbiased Fisher–Yates shuffle.
 */
object PassGen {
    const val DEFAULT_LEN = 32
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!#%+-=?@_~^*()[]{}"

    data class Classes(val lower: Boolean, val upper: Boolean, val digits: Boolean, val symbols: Boolean) {
        fun any() = lower || upper || digits || symbols
        fun pools(): List<String> = buildList {
            if (lower) add(LOWER)
            if (upper) add(UPPER)
            if (digits) add(DIGITS)
            if (symbols) add(SYMBOLS)
        }
    }

    /** Crypto-secure generator: include ≥1 of each selected class, fill rest from union, shuffle. */
    fun generate(classes: Classes, length: Int = DEFAULT_LEN, random: SecureRandom = SecureRandom()): String {
        require(classes.any()) { "at least one class" }
        val len = length.coerceAtLeast(classes.pools().size)
        val pools = classes.pools()
        val union = pools.joinToString("")
        val chars = CharArray(len)
        // Guarantee each class
        for (i in pools.indices) {
            val p = pools[i]
            chars[i] = p[unbiasedIndex(random, p.length)]
        }
        for (i in pools.size until len) {
            chars[i] = union[unbiasedIndex(random, union.length)]
        }
        // Fisher–Yates
        for (i in len - 1 downTo 1) {
            val j = unbiasedIndex(random, i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return String(chars)
    }

    /** Rejection sampling so nextInt(bound) is unbiased when bound is not a power of 2. */
    fun unbiasedIndex(random: SecureRandom, bound: Int): Int {
        require(bound > 0)
        if (bound == 1) return 0
        val mask = bound.highestPowerOfTwoMask()
        while (true) {
            val v = random.nextInt() and mask
            if (v < bound) return v
        }
    }

    private fun Int.highestPowerOfTwoMask(): Int {
        var n = this - 1
        n = n or (n ushr 1)
        n = n or (n ushr 2)
        n = n or (n ushr 4)
        n = n or (n ushr 8)
        n = n or (n ushr 16)
        return n
    }

    fun show(a: MainActivity) {
        val ctx = a
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, ctx.getString(R.string.passgen_classes), 14f, true), W.lp(W.MATCH, W.WRAP, b = 6))
        val cLower = W.check(ctx, ctx.getString(R.string.passgen_lower), true)
        val cUpper = W.check(ctx, ctx.getString(R.string.passgen_upper), true)
        val cDigits = W.check(ctx, ctx.getString(R.string.passgen_digits), true)
        val cSymbols = W.check(ctx, ctx.getString(R.string.passgen_symbols), false)
        listOf(cLower, cUpper, cDigits, cSymbols).forEach { col.addView(it, W.lp(W.MATCH, W.WRAP, t = 2)) }
        ClassicDialog(ctx, ctx.getString(R.string.passgen_title), col, listOf(
            ClassicDialog.Btn(ctx.getString(R.string.passgen_next), true) {
                val c = Classes(cLower.isChecked, cUpper.isChecked, cDigits.isChecked, cSymbols.isChecked)
                if (!c.any()) {
                    Dlg.message(ctx, ctx.getString(R.string.passgen_title), ctx.getString(R.string.passgen_need_one), '!')
                    return@Btn false
                }
                showResult(a, c)
                true
            },
            ClassicDialog.Btn(ctx.getString(R.string.cancel))
        )).show()
    }

    private fun showResult(a: MainActivity, classes: Classes) {
        val ctx = a
        val col = W.vbox(ctx)
        col.addView(W.text(ctx, ctx.getString(R.string.passgen_result), 14f, true), W.lp(W.MATCH, W.WRAP, b = 6))
        val field = W.field(ctx).apply {
            isSingleLine = false
            minLines = 2
            setText(generate(classes))
            setTextIsSelectable(true)
        }
        col.addView(field, W.lp(W.MATCH, W.WRAP))
        val btns = W.hbox(ctx).apply { gravity = Gravity.END }
        btns.addView(W.button(ctx, ctx.getString(R.string.passgen_regenerate)) {
            field.setText(generate(classes))
        }, W.lp(W.WRAP, W.WRAP, r = 6))
        btns.addView(W.button(ctx, ctx.getString(R.string.passgen_copy)) {
            val s = field.text?.toString().orEmpty()
            if (s.isNotEmpty()) {
                Clip.copy(ctx, ctx.getString(R.string.passgen_result), s, true)
                Toast.makeText(ctx, ctx.getString(R.string.passgen_copied, (Clip.CLEAR_MS / 1000).toInt()), Toast.LENGTH_SHORT).show()
            }
        }, W.lp(W.WRAP, W.WRAP))
        col.addView(btns, W.lp(W.MATCH, W.WRAP, t = 10))
        ClassicDialog(ctx, ctx.getString(R.string.passgen_title), col, listOf(
            ClassicDialog.Btn(ctx.getString(R.string.close))
        )).show()
    }
}
