package io.github.zmlworks.kpexplorer.sec

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The app database is encrypted (KDBX) with a random 32-byte composite key that nobody types. That key is stored
 * only wrapped by an AES-256-GCM key in the Android Keystore which requires the OS screen lock (strong biometric
 * OR device PIN / pattern / password) for every use. Unwrapping happens once per unlock, via BiometricPrompt.
 * The Keystore key is not invalidated by adding a fingerprint; removing the screen lock destroys it (and the data).
 */
class Vault(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("vault", Context.MODE_PRIVATE)

    companion object {
        const val ALIAS = "kpexplorer.db"
        const val AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        /** Tests force the UI state (Robolectric has no Keystore / BiometricPrompt). */
        var forceAvailableForTest: Boolean? = null
    }

    fun hasStoredKey(): Boolean = prefs.contains("ct") && prefs.contains("iv")

    /** A secure OS screen lock is set (required: the app has no password of its own). */
    fun isAvailable(): Boolean {
        forceAvailableForTest?.let { return it }
        return try {
            app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true &&
                app.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Throwable) { false }
    }

    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun clear() {
        prefs.edit().clear().commit()
        try { ks().deleteEntry(ALIAS) } catch (_: Throwable) {}
    }

    private fun newKey(): SecretKey {
        val ks = ks()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(b.build())
        return kg.generateKey()
    }

    private fun prompt(activity: Activity, title: String, subtitle: String): BiometricPrompt =
        BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTH)   // no negative button allowed together with DEVICE_CREDENTIAL
            .setConfirmationRequired(false)
            .build()

    interface Result {
        /** [composite]: the 32-byte database key (caller wipes it). */
        fun ok(composite: ByteArray)
        fun fail(msg: String)
    }

    private fun cancelled(code: Int) = code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
        code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED || code == 13 /* ERROR_NEGATIVE_BUTTON */

    /** First run: new random database key, wrapped after one OS authentication. */
    fun create(activity: Activity, r: Result) {
        val cipher: Cipher
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, newKey()) }
        } catch (t: Throwable) {
            clear(); r.fail("キーストアを準備できませんでした: ${t.javaClass.simpleName}"); return
        }
        val composite = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prompt(activity, "KeePass Explorer の準備", "画面ロック (指紋・PIN など) で保護します")
            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val c = result.cryptoObject.cipher!!
                            val ct = c.doFinal(composite)
                            check(prefs.edit().putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                                .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP)).commit()) { "prefs" }
                            r.ok(composite)
                        } catch (t: Throwable) {
                            composite.fill(0); clear(); r.fail("暗号化に失敗しました: ${t.javaClass.simpleName}")
                        }
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        composite.fill(0); clear(); r.fail(if (cancelled(code)) "" else msg.toString())
                    }
                })
    }

    /** OS authentication → decrypted database key. */
    fun unlock(activity: Activity, r: Result) {
        val ct: ByteArray; val iv: ByteArray
        try {
            ct = Base64.decode(prefs.getString("ct", null), Base64.NO_WRAP)
            iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        } catch (t: Throwable) { r.fail("保存された鍵がありません。"); return }
        val cipher: Cipher
        try {
            val key = ks().getKey(ALIAS, null) as? SecretKey ?: run { r.fail("キーストアの鍵が見つかりません。"); return }
            cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            r.fail("画面ロックが解除・変更されたため、キーストアの鍵は無効になりました。"); return
        } catch (t: Throwable) {
            r.fail("キーストアエラー: ${t.javaClass.simpleName}"); return
        }
        prompt(activity, "KeePass Explorer のロック解除", "指紋または画面ロックの PIN などで開きます")
            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try { r.ok(result.cryptoObject.cipher!!.doFinal(ct)) }
                        catch (t: Throwable) { r.fail("復号に失敗しました: ${t.javaClass.simpleName}") }
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) { r.fail(if (cancelled(code)) "" else msg.toString()) }
                })
    }
}
