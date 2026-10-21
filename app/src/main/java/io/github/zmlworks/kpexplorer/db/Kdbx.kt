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
        KeePassDatabase.decode(ByteArrayInputStream(bytes), credentials(composite), cipherProviders = CIPHERS)
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

    /** New KDBX 4 database: AES-256, Argon2id, recycle bin on. */
    fun create(name: String, composite: ByteArray, rootName: String = name): KeePassDatabase {
        val random = SecureRandom()
        val now = Instant.now()
        var db: KeePassDatabase = KeePassDatabase.Ver4x.create(
            rootName = rootName,
            meta = Meta(generator = GENERATOR, name = name, recycleBinEnabled = true, settingsChanged = now),
            credentials = credentials(composite),
            random = random
        )
        val salt = ByteArray(32).also { random.nextBytes(it) }.toByteString()
        val kdf = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2id, salt = salt,
            parallelism = ARGON2_PARALLELISM.toUInt(), memory = ARGON2_MEMORY.toULong(),
            iterations = ARGON2_ITERATIONS.toULong(), version = 0x13u, secretKey = null, associatedData = null
        )
        db as KeePassDatabase.Ver4x
        db = db.copy(header = db.header.copy(
            cipherId = BaseCiphers.Aes.uuid,
            encryptionIV = ByteArray(BaseCiphers.Aes.ivLength.toInt()).also { random.nextBytes(it) }.toByteString(),
            kdfParameters = kdf
        ))
        return db
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

    fun encode(db: KeePassDatabase): ByteArray {
        val bos = ByteArrayOutputStream()
        db.encode(bos, cipherProviders = CIPHERS)
        return bos.toByteArray()
    }

    /** Encode and decode again with the same credentials before anything is written to disk. */
    fun encodeVerified(db: KeePassDatabase): ByteArray {
        val bytes = encode(db)
        KeePassDatabase.decode(ByteArrayInputStream(bytes), db.credentials, cipherProviders = CIPHERS)
        return bytes
    }
}
