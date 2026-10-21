package io.github.zmlworks.kpexplorer.sec

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The KeePass composite key (32 bytes, never the password itself), encrypted with an AES-256-GCM key in the
 * Android Keystore. The key requires strong biometric authentication for every use and is invalidated when
 * biometrics are re-enrolled. Only the ciphertext + IV are stored (SharedPreferences, app-private, no backup).
 */
class Vault(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("vault", Context.MODE_PRIVATE)

    companion object {
        const val ALIAS = "kpexplorer.master"
        /** Tests can force the UI state (Robolectric has no Keystore). */
        var forceAvailableForTest: Boolean? = null
    }

    fun hasStoredKey(): Boolean = prefs.contains("ct") && prefs.contains("iv")

    fun isAvailable(): Boolean {
        forceAvailableForTest?.let { return it }
        return try {
            app.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Throwable) { false }
    }

    fun clear() {
        prefs.edit().clear().apply()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Throwable) {}
    }

    private fun newKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(b.build())
        return kg.generateKey()
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    private fun prompt(activity: Activity, title: String, subtitle: String, negative: String): BiometricPrompt {
        val b = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton(negative, activity.mainExecutor) { _, _ -> }
        b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        b.setConfirmationRequired(false)
        return b.build()
    }

    interface Result {
        fun ok(composite: ByteArray?)
        fun fail(msg: String, invalidated: Boolean)
    }

    /** Ask for a fingerprint and store [composite] wrapped by a fresh Keystore key. [composite] is wiped. */
    fun enroll(activity: Activity, composite: ByteArray, r: Result) {
        val cipher: Cipher
        try {
            val key = newKey()
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (t: Throwable) {
            composite.fill(0)
            r.fail("キーストアを準備できませんでした: ${t.javaClass.simpleName}", false)
            return
        }
        prompt(activity, "指紋認証の登録", "マスターキーを Android キーストアで保護します", "キャンセル")
            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val c = result.cryptoObject.cipher!!
                            val ct = c.doFinal(composite)
                            prefs.edit()
                                .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                                .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                                .apply()
                            r.ok(null)
                        } catch (t: Throwable) {
                            r.fail("暗号化に失敗しました: ${t.javaClass.simpleName}", false)
                        } finally { composite.fill(0) }
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        composite.fill(0)
                        r.fail(msg.toString(), false)
                    }
                })
    }

    /** Fingerprint → decrypted composite key (caller wipes it). */
    fun unlock(activity: Activity, r: Result) {
        val ct: ByteArray; val iv: ByteArray
        try {
            ct = Base64.decode(prefs.getString("ct", null), Base64.NO_WRAP)
            iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        } catch (t: Throwable) { r.fail("保存された鍵がありません", true); return }
        val cipher: Cipher
        try {
            val key = existingKey() ?: run { clear(); r.fail("鍵が見つかりません。パスワードで解除してください。", true); return }
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear(); r.fail("指紋の登録が変更されたため、鍵は無効になりました。パスワードで解除してください。", true); return
        } catch (t: Throwable) {
            r.fail("キーストアエラー: ${t.javaClass.simpleName}", false); return
        }
        prompt(activity, "KeePass Explorer のロック解除", "指紋でデータベースを開きます", "パスワードを使う")
            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try { r.ok(result.cryptoObject.cipher!!.doFinal(ct)) }
                        catch (t: Throwable) { r.fail("復号に失敗しました: ${t.javaClass.simpleName}", false) }
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        r.fail(if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) "" else msg.toString(), false)
                    }
                })
    }
}
