package io.github.zmlworks.kpexplorer.sec

import io.github.zmlworks.kpexplorer.L
import io.github.zmlworks.kpexplorer.db.FileInfo
import kotlin.math.log10

/**
 * Master password strength (0.4.38: the app's own estimate; 0.4.37 used zxcvbn4j and its dictionaries). Only the
 * verdict is ever kept, never the password. No dictionary: the user's rule is
 *
 *     guesses = (sum of the sizes of the character classes used) ^ effective length
 *
 * Classes: lowercase a–z 26, uppercase A–Z 26, digits 10, other ASCII (symbols, space, and the rare control
 * character) 33, and anything outside ASCII [OTHER_CLASS] = 100. A brute-force attacker cannot enumerate every
 * Unicode character, but Japanese passwords are mostly kana and common kanji, so 100 is a deliberately modest stand-in
 * (about the size of the kana sets) rather than the thousands a kanji alphabet would suggest.
 *
 * Effective length: obvious patterns count as a single character — a run of the same character (aaaa), an
 * ascending or descending sequence by code point (1234, abcd, 4321, ｱｲｳ), and neighbours along a keyboard row
 * (qwerty, asdf, zxcvb, 7890, and backwards; case ignored). A pattern needs at least [MIN_PATTERN] characters;
 * shorter stretches count one per character.
 *
 * "Strong" = at least 10^[STRONG_LOG10] guesses; "very strong" 10^[VERY_STRONG_LOG10]. This app's KDBX key
 * derivation is Argon2id 32 MiB × 3 passes; assuming about 10^4 such guesses per second per GPU, 10^12 keeps one GPU
 * busy for about 3 years on average and a 100-GPU rig for about 12 days; 10^14 is 100 times that.
 */
object PasswordStrength {
    const val STRONG_LOG10 = 12.0
    const val VERY_STRONG_LOG10 = 14.0
    const val LOWER_CLASS = 26
    const val UPPER_CLASS = 26
    const val DIGIT_CLASS = 10
    /** Printable ASCII that is not a letter or digit: 32 symbols + space (95 − 62). */
    const val SYMBOL_CLASS = 33
    /** Any character outside ASCII (see above). */
    const val OTHER_CLASS = 100
    const val MIN_PATTERN = 3
    /** Keyboard rows (US / JIS letters and digits are the same), lowercase. */
    private val ROWS = listOf("1234567890-", "qwertyuiop", "asdfghjkl", "zxcvbnm")

    class Result(val log10: Double, val pool: Int, val effectiveLength: Int) {
        val verdict: FileInfo.Strength get() = if (log10 >= STRONG_LOG10) FileInfo.Strength.STRONG else FileInfo.Strength.WEAK
        val strong: Boolean get() = verdict == FileInfo.Strength.STRONG
        val veryStrong: Boolean get() = log10 >= VERY_STRONG_LOG10
        /** Whole powers of ten (what is recorded per file). */
        val bucket: Int get() = log10.toInt().coerceIn(0, 99)
        /** 0..4 for the meter: very weak / weak / fair / almost / strong (or very strong). */
        val level: Int get() = when { strong -> 4; log10 >= 10 -> 3; log10 >= 8 -> 2; log10 >= 5 -> 1; else -> 0 }
        val label: String get() = when {
            veryStrong -> L.t("とても強い", "Very strong")
            level == 4 -> L.t("強い", "Strong")
            level == 3 -> L.t("もう少し", "Almost")
            level == 2 -> L.t("やや弱い", "Fair")
            level == 1 -> L.t("弱い", "Weak")
            else -> L.t("とても弱い", "Very weak")
        }
    }

    /** Sum of the sizes of the classes [cps] uses (each class counted once). */
    fun pool(cps: IntArray): Int {
        var lower = false; var upper = false; var digit = false; var symbol = false; var other = false
        for (c in cps) when {
            c in 'a'.code..'z'.code -> lower = true
            c in 'A'.code..'Z'.code -> upper = true
            c in '0'.code..'9'.code -> digit = true
            c <= 0x7F -> symbol = true
            else -> other = true
        }
        return (if (lower) LOWER_CLASS else 0) + (if (upper) UPPER_CLASS else 0) + (if (digit) DIGIT_CLASS else 0) +
            (if (symbol) SYMBOL_CLASS else 0) + (if (other) OTHER_CLASS else 0)
    }

    private fun lower(c: Int) = if (c in 'A'.code..'Z'.code) c + 32 else c

    /** The two characters are next to each other on a keyboard row, in [dir] (+1 / −1). */
    private fun keyNext(a: Int, b: Int, dir: Int): Boolean {
        val x = lower(a).toChar(); val y = lower(b).toChar()
        return ROWS.any { r -> val i = r.indexOf(x); i >= 0 && i + dir in r.indices && r[i + dir] == y }
    }

    /** Length of the pattern run starting at [i] (1 = none): repeat, sequence ±1, or keyboard row ±1. */
    private fun runAt(cps: IntArray, i: Int): Int {
        if (i + 1 >= cps.size) return 1
        val steps: List<(Int, Int) -> Boolean> = listOf(
            { a, b -> a == b },
            { a, b -> b == a + 1 }, { a, b -> b == a - 1 },
            { a, b -> keyNext(a, b, 1) }, { a, b -> keyNext(a, b, -1) })
        var best = 1
        for (s in steps) {
            var j = i
            while (j + 1 < cps.size && s(cps[j], cps[j + 1])) j++
            best = maxOf(best, j - i + 1)
        }
        return best
    }

    /** Characters, with each pattern of [MIN_PATTERN]+ characters counted as one. */
    fun effectiveLength(cps: IntArray): Int {
        var n = 0; var i = 0
        while (i < cps.size) {
            val r = runAt(cps, i)
            n++
            i += if (r >= MIN_PATTERN) r else 1
        }
        return n
    }

    fun judge(password: CharSequence): Result {
        if (password.isEmpty()) return Result(0.0, 0, 0)
        val cps = password.codePoints().toArray()
        val pool = pool(cps)
        val len = effectiveLength(cps)
        val r = Result(len * log10(pool.toDouble()), pool, len)
        cps.fill(0)
        return r
    }

    fun verdict(password: CharSequence): FileInfo.Strength = judge(password).verdict

    /** The record for a database (or backup) keyed by [password]: master password, verdict and its power of ten. */
    fun info(password: CharSequence): FileInfo = judge(password).let { FileInfo(FileInfo.Protection.PASSWORD, it.verdict, it.bucket) }
}
