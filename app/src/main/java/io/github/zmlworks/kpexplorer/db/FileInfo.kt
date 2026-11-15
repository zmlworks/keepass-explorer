package io.github.zmlworks.kpexplorer.db

/**
 * 0.4.37: what the app knows about one database file (the app database or an internal backup) — never a secret.
 * [protection]: how its key is made (master password / none / random key in the OS keystore); [strength]: the
 * verdict on its master password, judged when the password was set, changed or typed ([UNKNOWN] when it was never
 * typed in this version, or for files that have no password). Kept in db/files.properties by [Store].
 */
data class FileInfo(val protection: Protection, val strength: Strength) {
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

    /** Can be written out from the launch screen (nothing unlocked): only strong master passwords. */
    val launchExportable: Boolean get() = protection == Protection.PASSWORD && strength == Strength.STRONG

    fun encode() = "${protection.key},${strength.key}"

    companion object {
        /** Files written before 0.4.37 (and anything without a record): master password, not judged yet. */
        val LEGACY = FileInfo(Protection.PASSWORD, Strength.UNKNOWN)
        fun decode(s: String?): FileInfo? {
            val p = s?.split(',') ?: return null
            val prot = Protection.of(p.getOrNull(0)) ?: return null
            return FileInfo(prot, Strength.of(p.getOrNull(1)))
        }
    }
}
