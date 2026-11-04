package io.github.zmlworks.kpexplorer.sec

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * PIN unlock. The KeePass composite key is encrypted twice:
 *   inner = AES-256-GCM(PBKDF2-HMAC-SHA256(PIN, salt, 120k), composite)
 *   outer = AES-256-GCM(Android Keystore key, inner)
 * The Keystore layer keeps the blob useless off the device (no offline brute force of the short PIN);
 * on the device the attempt counter wipes the blob after [MAX_ATTEMPTS] wrong PINs (master password then needed).
 */
class PinVault(private val prefs: SharedPreferences, private val outer: Wrapper = KeystoreWrapper()) {
    constructor(ctx: Context) : this(ctx.applicationContext.getSharedPreferences("pin", Context.MODE_PRIVATE))

    interface Wrapper {
        fun wrap(plain: ByteArray): Pair<ByteArray, ByteArray>   // (iv, ct)
        fun unwrap(iv: ByteArray, ct: ByteArray): ByteArray
        fun clear()
    }

    sealed class Unlock {
        class Ok(val composite: ByteArray) : Unlock()
        class Wrong(val left: Int) : Unlock()
        object Wiped : Unlock()
        object NoPin : Unlock()
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val ITERATIONS = 120_000
        /** Robolectric has no AndroidKeyStore: tests swap in a plain wrapper. */
        var testWrapper: Wrapper? = null
        fun valid(pin: String) = pin.length in 4..12 && pin.all { it in '0'..'9' }

        fun derive(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
    }

    private val w get() = testWrapper ?: outer
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(k: String) = Base64.decode(prefs.getString(k, null) ?: throw IllegalStateException("no $k"), Base64.NO_WRAP)

    fun hasPin() = prefs.contains("ct")
    fun attemptsLeft() = MAX_ATTEMPTS - prefs.getInt("fail", 0)

    /** Slow (PBKDF2): call off the main thread. */
    fun setPin(pin: String, composite: ByteArray) {
        require(valid(pin)) { "PIN は 4〜12 桁の数字です" }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val k = derive(pin, salt)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        val inner = c.doFinal(composite)
        k.fill(0)
        val (oiv, oct) = w.wrap(inner)
        check(prefs.edit().putString("salt", b64(salt)).putString("iv", b64(iv)).putString("oiv", b64(oiv))
            .putString("ct", b64(oct)).putInt("fail", 0).commit()) { "prefs" }
    }

    /** Slow (PBKDF2): call off the main thread. */
    fun unlock(pin: String): Unlock {
        if (!hasPin()) return Unlock.NoPin
        val inner = try { w.unwrap(unb64("oiv"), unb64("ct")) } catch (t: Throwable) { clear(); return Unlock.Wiped }
        val k = derive(pin, unb64("salt"))
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, unb64("iv")))
            val out = c.doFinal(inner)
            prefs.edit().putInt("fail", 0).commit()
            Unlock.Ok(out)
        } catch (t: javax.crypto.AEADBadTagException) {
            val fail = prefs.getInt("fail", 0) + 1
            if (fail >= MAX_ATTEMPTS) { clear(); Unlock.Wiped }
            else { prefs.edit().putInt("fail", fail).commit(); Unlock.Wrong(MAX_ATTEMPTS - fail) }
        } finally { k.fill(0); inner.fill(0) }
    }

    fun clear() {
        prefs.edit().clear().commit()
        runCatching { w.clear() }
    }

    class KeystoreWrapper : Wrapper {
        private val alias = "kpexplorer.pin"
        private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun key(): SecretKey {
            (ks().getKey(alias, null) as? SecretKey)?.let { return it }
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            return g.generateKey()
        }
        override fun wrap(plain: ByteArray): Pair<ByteArray, ByteArray> {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            return c.iv to c.doFinal(plain)
        }
        override fun unwrap(iv: ByteArray, ct: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return c.doFinal(ct)
        }
        override fun clear() { runCatching { ks().deleteEntry(alias) } }
    }
}
