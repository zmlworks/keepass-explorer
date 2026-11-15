package io.github.zmlworks.kpexplorer.sec

import com.nulabinc.zxcvbn.Zxcvbn
import io.github.zmlworks.kpexplorer.L
import io.github.zmlworks.kpexplorer.db.FileInfo

/**
 * 0.4.37: master password strength, estimated with zxcvbn (Nulab's zxcvbn4j: dictionary words, names, common
 * passwords, keyboard patterns incl. JIS, repeats, sequences, dates, l33t — not just length). Only the verdict is
 * ever kept, never the password.
 *
 * "Strong" = zxcvbn estimates at least 10^[STRONG_LOG10] guesses. This app's KDBX key derivation is Argon2id
 * 32 MiB × 3 passes; assuming an attacker manages about 10^4 such guesses per second per GPU, 10^12 guesses
 * keep one GPU busy for about 3 years on average (half the space) and a 100-GPU rig for about 12 days, so this
 * is the floor, not a comfortable margin. zxcvbn counts an unrecognised random string as 10^length, so the floor
 * means e.g. 12 random characters or 3–4 uncommon words; a well-known pattern of any length stays weak.
 */
object PasswordStrength {
    const val STRONG_LOG10 = 12.0
    /** zxcvbn's matching is quadratic; a longer password is judged on its first [MAX_JUDGED] characters (never stronger than the whole). */
    const val MAX_JUDGED = 100

    class Result(val log10: Double, val score: Int) {
        val verdict: FileInfo.Strength get() = if (log10 >= STRONG_LOG10) FileInfo.Strength.STRONG else FileInfo.Strength.WEAK
        val strong: Boolean get() = verdict == FileInfo.Strength.STRONG
        /** 0..4 for the meter: very weak / weak / fair / almost / strong. */
        val level: Int get() = when { strong -> 4; log10 >= 10 -> 3; log10 >= 8 -> 2; log10 >= 5 -> 1; else -> 0 }
        val label: String get() = when (level) {
            4 -> L.t("強い", "Strong")
            3 -> L.t("もう少し", "Almost")
            2 -> L.t("やや弱い", "Fair")
            1 -> L.t("弱い", "Weak")
            else -> L.t("とても弱い", "Very weak")
        }
    }

    private val z by lazy { Zxcvbn() }
    /** Words a guesser would try first for this app's file. */
    private val APP_WORDS = listOf("keepass", "explorer", "kpexplorer", "kdbx", "master", "masterpassword")

    fun judge(password: CharSequence): Result {
        if (password.isEmpty()) return Result(0.0, 0)
        val s = z.measure(if (password.length > MAX_JUDGED) password.subSequence(0, MAX_JUDGED) else password, APP_WORDS)
        val r = Result(s.guessesLog10, s.score)
        s.wipe()
        return r
    }

    fun verdict(password: CharSequence): FileInfo.Strength = judge(password).verdict

    /** Load the dictionaries ahead of the first keystroke (a few hundred ms on a phone). */
    fun warmUp() { Thread { runCatching { z.measure("x") } }.apply { isDaemon = true }.start() }
}
