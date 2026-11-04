package io.github.zmlworks.kpexplorer

import io.github.zmlworks.kpexplorer.db.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** RFC 6238 Appendix B vectors + KeePassXC style otpauth URIs. */
class TotpTest {
    private val s1 = "12345678901234567890".toByteArray()
    private val s256 = "12345678901234567890123456789012".toByteArray()
    private val s512 = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    private fun c(secret: ByteArray, alg: String, t: Long) = Totp.code(Totp.Params(secret, 8, 30, alg), t)

    @Test fun rfc6238Vectors() {
        val times = listOf(59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L)
        assertEquals(listOf("94287082", "07081804", "14050471", "89005924", "69279037", "65353130"), times.map { c(s1, "SHA1", it) })
        assertEquals(listOf("46119246", "68084774", "67062674", "91819424", "90698825", "77737706"), times.map { c(s256, "SHA256", it) })
        assertEquals(listOf("90693936", "25091201", "99943326", "93441116", "38618901", "47863826"), times.map { c(s512, "SHA512", it) })
    }

    @Test fun base32RoundTrip() {
        val b32 = Totp.base32Encode(s1)
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", b32)
        assertArrayEquals(s1, Totp.base32Decode(b32.lowercase().chunked(4).joinToString(" ") + "=="))
    }

    @Test fun parseUriAndBareSecret() {
        val p = Totp.parse("otpauth://totp/Example:alice@example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&digits=8&period=30&algorithm=SHA1")
        assertEquals(8, p.digits); assertEquals("94287082", Totp.code(p, 59))
        val bare = Totp.parse("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ")
        assertEquals(6, bare.digits); assertEquals(30, bare.period)
        assertEquals("287082", Totp.code(bare, 59))
        val again = Totp.parse(Totp.toUri(p))
        assertEquals(p, again)
        assertEquals(1, Totp.remaining(p, 59)); assertEquals(30, Totp.remaining(p, 60))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsGarbage() { Totp.parse("not base32 !!") }
}
