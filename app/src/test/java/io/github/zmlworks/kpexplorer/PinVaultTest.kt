package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.sec.PinVault
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinVaultTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("pin-test", 0)

    @Before fun setUp() { PinVault.testWrapper = TestWrapper(); prefs.edit().clear().commit() }
    @After fun tearDown() { PinVault.testWrapper = null }

    @Test fun setUnlockWrongAndWipe() {
        val v = PinVault(prefs)
        assertFalse(v.hasPin())
        assertTrue(v.unlock("1234") is PinVault.Unlock.NoPin)
        val key = ByteArray(32) { it.toByte() }
        v.setPin("2468", key)
        assertTrue(v.hasPin())
        assertFalse(prefs.all.values.any { it.toString().contains("2468") })
        val ok = v.unlock("2468") as PinVault.Unlock.Ok
        assertArrayEquals(key, ok.composite)
        assertEquals(PinVault.MAX_ATTEMPTS, v.attemptsLeft())
        repeat(PinVault.MAX_ATTEMPTS - 1) { i -> assertEquals(PinVault.MAX_ATTEMPTS - 1 - i, (v.unlock("0000") as PinVault.Unlock.Wrong).left) }
        // a correct PIN resets the counter
        assertTrue(v.unlock("2468") is PinVault.Unlock.Ok)
        assertEquals(PinVault.MAX_ATTEMPTS, v.attemptsLeft())
        repeat(PinVault.MAX_ATTEMPTS - 1) { v.unlock("1111") }
        assertTrue(v.unlock("1111") is PinVault.Unlock.Wiped)
        assertFalse(v.hasPin())
        assertTrue(v.unlock("2468") is PinVault.Unlock.NoPin)
    }

    @Test fun validation() {
        assertTrue(PinVault.valid("1234")); assertTrue(PinVault.valid("123456789012"))
        assertFalse(PinVault.valid("123")); assertFalse(PinVault.valid("12a4")); assertFalse(PinVault.valid("1234567890123"))
    }
}

/** Plain AES-GCM stand-in for the AndroidKeyStore key (Robolectric has no keystore). */
class TestWrapper : PinVault.Wrapper {
    private val k = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES")
    override fun wrap(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE, k)
        return c.iv to c.doFinal(plain)
    }
    override fun unwrap(iv: ByteArray, ct: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, k, javax.crypto.spec.GCMParameterSpec(128, iv)); return c.doFinal(ct)
    }
    override fun clear() {}
}
