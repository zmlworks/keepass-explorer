package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.Meta
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant

/** KDBX open / create / encode on top of the vendored kotpass. Master password only (no key file). */
object Kdbx {
    const val GENERATOR = "KeePass Explorer"
    val CIPHERS: List<CipherProvider> = BaseCiphers.entries + TwofishCipher

    /** Argon2id for new databases. Pure-Kotlin Argon2 runs on every auto-save, so keep it phone-friendly. */
    const val ARGON2_MEMORY = 32L * 1024 * 1024
    const val ARGON2_ITERATIONS = 3L
    const val ARGON2_PARALLELISM = 2

    /**
     * Remembers the Argon2 output for (composite key, KDF parameters) in memory, so that re-saving with the same
     * salt (see [encode] keepSalt) and verifying the written bytes don't run Argon2 again. Cleared on lock.
     */
    object KdfCache : app.keemobile.kotpass.cryptography.format.KdfProvider {
        private val map = LinkedHashMap<String, ByteArray>()
        @Volatile var misses = 0
            private set
        private fun id(p: KdfParameters, composite: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(composite)
            md.update(p.toString().toByteArray())   // data class: variant, salt, memory, iterations, parallelism, …
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        override fun transformKey(kdfParameters: KdfParameters, compositeKey: ByteArray): ByteArray {
            val k = id(kdfParameters, compositeKey)
            synchronized(this) { map[k]?.let { return it.copyOf() } }
            val out = app.keemobile.kotpass.cryptography.format.BaseKdfProvider.transformKey(kdfParameters, compositeKey)
            synchronized(this) {
                misses++
                if (map.size >= 4) map.remove(map.keys.first())?.fill(0)
                map[k] = out.copyOf()
            }
            return out
        }
        fun clear() = synchronized(this) { map.values.forEach { it.fill(0) }; map.clear() }
    }

    class WrongPasswordException(cause: Throwable?) : Exception("wrong password or damaged file", cause)

    /** KeePass composite key for a password-only database: SHA-256(SHA-256(UTF-8(NFC(password)))). */
    fun compositeKey(password: CharSequence): ByteArray {
        val nfc = Normalizer.normalize(password, Normalizer.Form.NFC)
        val pw = nfc.toByteArray(StandardCharsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val h = md.digest(pw)
        pw.fill(0)
        val out = MessageDigest.getInstance("SHA-256").digest(h)
        h.fill(0)
        return out
    }

    fun credentials(composite: ByteArray): Credentials = Credentials.fromComposite(composite)

    fun open(bytes: ByteArray, composite: ByteArray): KeePassDatabase = try {
        KeePassDatabase.decode(ByteArrayInputStream(bytes), credentials(composite), cipherProviders = CIPHERS, kdfProvider = KdfCache)
    } catch (e: app.keemobile.kotpass.errors.FormatError.InvalidContent) {
        throw WrongPasswordException(e)
    } catch (e: app.keemobile.kotpass.errors.CryptoError) {
        throw WrongPasswordException(e)
    }

    fun isTwofish(db: KeePassDatabase) = db.header.cipherId == TwofishCipher.uuid

    fun describe(db: KeePassDatabase): String {
        val cipher = when (db.header.cipherId) {
            BaseCiphers.Aes.uuid -> "AES-256"
            BaseCiphers.ChaCha20.uuid -> "ChaCha20"
            TwofishCipher.uuid -> "Twofish"
            else -> db.header.cipherId.toString()
        }
        val kdf = when (val h = db.header) {
            is DatabaseHeader.Ver3x -> "AES-KDF ${h.transformRounds}"
            is DatabaseHeader.Ver4x -> when (val k = h.kdfParameters) {
                is KdfParameters.Aes -> "AES-KDF ${k.rounds}"
                is KdfParameters.Argon2 -> "${k.variant.name} ${k.memory / 1024u / 1024u}MiB×${k.iterations}"
            }
        }
        val ver = if (db is KeePassDatabase.Ver4x) "KDBX 4" else "KDBX 3.1"
        return "$ver / $cipher / $kdf"
    }

    /** New KDBX 4 database: AES-256, Argon2id, recycle bin enabled (group created lazily on first delete). */
    fun create(name: String, composite: ByteArray, rootName: String = name, light: Boolean = false): KeePassDatabase {
        val random = SecureRandom()
        val now = Instant.now()
        var db: KeePassDatabase = KeePassDatabase.Ver4x.create(
            rootName = rootName,
            meta = Meta(generator = GENERATOR, name = name, recycleBinEnabled = true, settingsChanged = now, historyMaxItems = Tree.HISTORY_MAX),
            credentials = credentials(composite),
            random = random
        )
        return withHeader(db, light, random)
    }

    /**
     * Fresh AES-256 / Argon2id header (new salt + IV). [light] = 1 MiB × 1 for the app-internal file, whose key is
     * 32 random bytes (nothing to brute-force, so key stretching only costs battery on every save); the full
     * [ARGON2_MEMORY] × [ARGON2_ITERATIONS] for password-protected exports.
     */
    fun withHeader(db: KeePassDatabase, light: Boolean, random: SecureRandom = SecureRandom()): KeePassDatabase {
        val salt = ByteArray(32).also { random.nextBytes(it) }.toByteString()
        val kdf = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2id, salt = salt,
            parallelism = (if (light) 1 else ARGON2_PARALLELISM).toUInt(),
            memory = (if (light) 1024L * 1024 else ARGON2_MEMORY).toULong(),
            iterations = (if (light) 1L else ARGON2_ITERATIONS).toULong(), version = 0x13u, secretKey = null, associatedData = null
        )
        val v4 = db as? KeePassDatabase.Ver4x ?: throw IllegalArgumentException("KDBX 4 only")
        return v4.copy(header = v4.header.copy(
            cipherId = BaseCiphers.Aes.uuid,
            encryptionIV = ByteArray(BaseCiphers.Aes.ivLength.toInt()).also { random.nextBytes(it) }.toByteString(),
            kdfParameters = kdf
        ))
    }

    /** Copy of [db] as a standard password-protected KDBX 4 (what KeePass / KeePassXC open with [password]). */
    fun exportCopy(db: KeePassDatabase, password: CharSequence): KeePassDatabase {
        val k = compositeKey(password)
        try { return withHeader(withCredentials(db, k), light = false) } finally { k.fill(0) }
    }

    /** Re-encrypt a Twofish database with AES-256 (only for the app-private imported copy). */
    fun convertToAes(db: KeePassDatabase): KeePassDatabase {
        if (!isTwofish(db)) return db
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }.toByteString()
        return when (db) {
            is KeePassDatabase.Ver4x -> db.copy(header = db.header.copy(cipherId = BaseCiphers.Aes.uuid, encryptionIV = iv))
            is KeePassDatabase.Ver3x -> db.copy(header = db.header.copy(cipherId = BaseCiphers.Aes.uuid, encryptionIV = iv))
        }
    }

    fun withCredentials(db: KeePassDatabase, composite: ByteArray): KeePassDatabase {
        val c = credentials(composite)
        val now = Instant.now()
        val d = when (db) {
            is KeePassDatabase.Ver4x -> db.copy(credentials = c)
            is KeePassDatabase.Ver3x -> db.copy(credentials = c)
        }
        return d.modifyContent { copy(meta = meta.copy(masterKeyChanged = now)) }
    }

    /**
     * [keepSalt] = keep the Argon2 salt / parameters of [db]'s header (fresh master seed and IVs every time),
     * so the cached Argon2 result is reused and a save costs no key derivation. Exports use a fresh salt.
     */
    fun encode(db: KeePassDatabase, keepSalt: Boolean = false): ByteArray {
        val bos = ByteArrayOutputStream()
        db.encode(bos, cipherProviders = CIPHERS, kdfProvider = KdfCache, keepKdfSalt = keepSalt)
        return bos.toByteArray()
    }

    /** Encode and decode again with the same credentials before anything is written to disk. */
    fun encodeVerified(db: KeePassDatabase, keepSalt: Boolean = false): ByteArray {
        val bytes = encode(db, keepSalt)
        KeePassDatabase.decode(ByteArrayInputStream(bytes), db.credentials, cipherProviders = CIPHERS, kdfProvider = KdfCache)
        return bytes
    }
}
