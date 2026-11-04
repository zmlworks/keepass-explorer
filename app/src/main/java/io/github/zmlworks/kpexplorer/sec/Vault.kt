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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Everyday unlock without typing the master password. db.kdbx is a normal KDBX 4 protected by the master
 * password; its 32-byte composite key (SHA-256 of SHA-256(password), never the password itself) is stored in
 * filesDir/wrapped.bin, encrypted with an AES-256-GCM key in the Android Keystore that needs the OS screen lock
 * (strong biometric OR device PIN / pattern / password) for every use. If that Keystore key disappears
 * (screen lock removed, etc.) the data is still there: the master password opens it and [wrap] is done again.
 */
class Vault(ctx: Context) {
    private val app = ctx.applicationContext
    private val file = java.io.File(app.filesDir, "wrapped.bin")

    companion object {
        const val ALIAS = "kpexplorer.db"
        const val AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        /** Tests force the UI state (Robolectric has no Keystore / BiometricPrompt). */
        var forceAvailableForTest: Boolean? = null
    }

    fun hasStoredKey(): Boolean = file.length() > 12

    /** A secure OS screen lock is set, so a wrapped key can be used. */
    fun isAvailable(): Boolean {
        forceAvailableForTest?.let { return it }
        return try {
            app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true &&
                app.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Throwable) { false }
    }

    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun clear() {
        file.delete()
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
        /** [msg] empty = the user cancelled. */
        fun fail(msg: String)
        /** The wrapped key can't be used any more (Keystore key gone / invalidated): master password needed. */
        fun lost(msg: String) = fail(msg)
    }

    private fun cancelled(code: Int) = code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
        code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED || code == 13 /* ERROR_NEGATIVE_BUTTON */

    /** Store [composite] (copied) wrapped by a new Keystore key, after one OS authentication. done(error or null; "" = cancelled). */
    fun wrap(activity: Activity, composite: ByteArray, done: (String?) -> Unit) {
        val key = composite.copyOf()
        val cipher: Cipher
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, newKey()) }
        } catch (t: Throwable) {
            key.fill(0); clear(); done("キーストアを準備できませんでした: ${t.javaClass.simpleName}"); return
        }
        prompt(activity, "指紋・画面ロックで開けるようにする", "次回からマスターパスワードの入力を省きます")
            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val c = result.cryptoObject.cipher!!
                            val ct = c.doFinal(key)
                            val tmp = java.io.File(file.path + ".tmp")
                            tmp.writeBytes(c.iv + ct)
                            check(tmp.renameTo(file)) { "rename" }
                            done(null)
                        } catch (t: Throwable) {
                            clear(); done("暗号化に失敗しました: ${t.javaClass.simpleName}")
                        } finally { key.fill(0) }
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        key.fill(0); clear(); done(if (cancelled(code)) "" else msg.toString())
                    }
                })
    }

    /** OS authentication → decrypted composite key. */
    fun unlock(activity: Activity, r: Result) {
        val all = try { file.readBytes() } catch (_: Throwable) { null }
        if (all == null || all.size <= 12) { r.lost("指紋・画面ロック用の鍵がありません。"); return }
        val iv = all.copyOfRange(0, 12); val ct = all.copyOfRange(12, all.size)
        val cipher: Cipher
        try {
            val key = ks().getKey(ALIAS, null) as? SecretKey ?: run { clear(); r.lost("キーストアの鍵が見つかりません (画面ロックの解除など)。"); return }
            cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear(); r.lost("画面ロックが解除・変更されたため、指紋・画面ロック用の鍵は無効になりました。"); return
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
