package io.github.zmlworks.kpexplorer.db

/**
 * 0.4.37: what the app knows about one database file (the app database or an internal backup) — never a secret.
 * [protection]: how its key is made (master password / none / random key in the OS keystore); [strength]: the
 * verdict on its master password, judged when the password was set, changed or typed ([UNKNOWN] when it was never
 * typed in this version, or for files that have no password). [log10] (0.4.38): the estimate's whole power of ten
 * (null in 0.4.37 records: a "strong" one then counts as 10^12, the threshold of that version). Kept in
 * db/files.properties by [Store].
 */
data class FileInfo(val protection: Protection, val strength: Strength, val log10: Int? = null) {
    enum class Protection(val key: String) {
        /** Standard KDBX protected by the user's master password (and the optional fingerprint / PIN unlock). */
        PASSWORD("password"),
        /** Not encrypted in practice: KDBX whose key is the empty password (KeePass opens it with an empty password). */
        NONE("none"),
        /** KDBX keyed by 32 random bytes kept only in wrapped.bin, wrapped by a screen-lock Android Keystore key. */
        KEYSTORE("keystore");
        companion object { fun of(s: String?) = entries.firstOrNull { it.key == s } }
    }

    enum class Strength(val key: String) {
        STRONG("strong"), WEAK("weak"), UNKNOWN("unknown");
        companion object { fun of(s: String?) = entries.firstOrNull { it.key == s } ?: UNKNOWN }
    }

    /** Estimated guesses, as a power of ten (null = not known). */
    val guessesLog10: Int? get() = when {
        protection != Protection.PASSWORD || strength == Strength.UNKNOWN -> null
        log10 != null -> log10
        strength == Strength.STRONG -> 12
        else -> null
    }

    /**
     * Can be written out without unlocking when the setting asks for 10^[minLog10] guesses (0 = never): only master
     * passwords whose recorded estimate reaches it. 0.4.40: [ANY] (セキュリティの設定 「すべて」) = every file with a
     * master password, whatever its strength (also not judged yet); never one without a password or with the OS key.
     */
    fun exportableAt(minLog10: Int): Boolean = when {
        minLog10 == 0 -> false
        minLog10 == ANY -> protection == Protection.PASSWORD
        else -> (guessesLog10 ?: -1) >= minLog10
    }

    fun encode() = "${protection.key},${strength.key}" + (log10?.let { ",$it" } ?: "")

    companion object {
        /** [exportableAt]'s "any strength". */
        const val ANY = -1
        /** Files written before 0.4.37 (and anything without a record): master password, not judged yet. */
        val LEGACY = FileInfo(Protection.PASSWORD, Strength.UNKNOWN)
        fun decode(s: String?): FileInfo? {
            val p = s?.split(',') ?: return null
            val prot = Protection.of(p.getOrNull(0)) ?: return null
            return FileInfo(prot, Strength.of(p.getOrNull(1)), p.getOrNull(2)?.toIntOrNull())
        }
    }
}
