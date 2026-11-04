package io.github.zmlworks.kpexplorer.db

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** TOTP (RFC 6238 / RFC 4226) and the KeePassXC "otp" field (otpauth:// URI). */
object Totp {
    data class Params(val secret: ByteArray, val digits: Int = 6, val period: Int = 30, val algorithm: String = "SHA1",
                      val issuer: String = "", val account: String = "") {
        override fun equals(other: Any?) = other is Params && secret.contentEquals(other.secret) && digits == other.digits &&
            period == other.period && algorithm == other.algorithm
        override fun hashCode() = secret.contentHashCode() * 31 + digits
    }

    fun base32Decode(s: String): ByteArray {
        val clean = s.uppercase().filter { !it.isWhitespace() && it != '-' && it != '=' }
        require(clean.isNotEmpty()) { "empty secret" }
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (c in clean) {
            val v = when (c) { in 'A'..'Z' -> c - 'A'; in '2'..'7' -> c - '2' + 26; else -> throw IllegalArgumentException("invalid base32: $c") }
            buf = (buf shl 5) or v; bits += 5
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }

    fun base32Encode(b: ByteArray): String {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (x in b) { buf = (buf shl 8) or (x.toInt() and 0xFF); bits += 8
            while (bits >= 5) { bits -= 5; sb.append(a[(buf shr bits) and 31]) } }
        if (bits > 0) sb.append(a[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Accepts an otpauth://totp/ URI or a bare Base32 secret. */
    fun parse(input: String): Params {
        val t = input.trim()
        if (!t.startsWith("otpauth://", true)) return Params(base32Decode(t))
        require(t.substring(10).startsWith("totp", true)) { "TOTP のみ対応しています" }
        val q = t.substringAfter('?', "")
        val label = URLDecoder.decode(t.substringAfter("://").substringAfter('/').substringBefore('?'), "UTF-8")
        val m = q.split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=').lowercase() to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val alg = (m["algorithm"] ?: "SHA1").uppercase().replace("-", "")
        require(alg in setOf("SHA1", "SHA256", "SHA512")) { "未対応のアルゴリズム: $alg" }
        val digits = m["digits"]?.toIntOrNull() ?: 6
        val period = m["period"]?.toIntOrNull() ?: 30
        require(digits in 6..10 && period in 1..3600) { "digits / period が不正です" }
        return Params(base32Decode(m["secret"] ?: throw IllegalArgumentException("secret がありません")), digits, period, alg,
            m["issuer"] ?: label.substringBefore(':', ""), label.substringAfter(':'))
    }

    fun toUri(p: Params): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val label = if (p.issuer.isNotEmpty()) "${e(p.issuer)}:${e(p.account)}" else e(p.account)
        return "otpauth://totp/$label?secret=${base32Encode(p.secret)}&period=${p.period}&digits=${p.digits}" +
            (if (p.issuer.isNotEmpty()) "&issuer=${e(p.issuer)}" else "") +
            (if (p.algorithm != "SHA1") "&algorithm=${p.algorithm}" else "")
    }

    fun hotp(secret: ByteArray, counter: Long, digits: Int, algorithm: String = "SHA1"): String {
        val mac = Mac.getInstance("Hmac$algorithm")
        mac.init(SecretKeySpec(secret, "RAW"))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or
            ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var mod = 1L; repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    fun code(p: Params, epochSeconds: Long): String = hotp(p.secret, epochSeconds / p.period, p.digits, p.algorithm)

    fun remaining(p: Params, epochSeconds: Long): Int = (p.period - (epochSeconds % p.period)).toInt()
}
