package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.database.KeePassDatabase
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 0.4.42: uses of entries (KeePass LastAccessTime / UsageCount) not written to the database yet, kept in a small side
 * file (db/usage.bin) when the app locks or goes to the background, so that viewing an entry never rewrites the
 * database. The next save of the database writes them and removes the file; an unlock merges the file back.
 *
 * The file is AES-256-GCM. Its key is HMAC-SHA256 over the open database's composite key (already in memory, so no
 * key derivation runs) of a fixed label and [Kdbx.keyToken] (key-change time + KDF salt), so another key or another
 * database cannot read it. For 「暗号化せずに使う」 the composite key is the well-known empty password: the file is
 * then as readable as that database itself. Any failure (missing, damaged, other key) reads as nothing: losing it
 * only loses some use counts.
 */
object UsageLog {
    /** One entry's use: its LastAccessTime and UsageCount after the use. */
    data class Use(val id: UUID, val at: Instant, val count: Int)

    private val MAGIC = "KPXU1".toByteArray()

    private fun key(composite: ByteArray, db: KeePassDatabase): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(composite, "HmacSHA256")) }
        val k = mac.doFinal("KeePass Explorer usage v1:${Kdbx.keyToken(db)}".toByteArray())
        return SecretKeySpec(k, "AES").also { k.fill(0) }
    }

    fun encode(uses: Collection<Use>, composite: ByteArray, db: KeePassDatabase): ByteArray {
        val plain = uses.joinToString("\n") { "${it.id} ${it.at.toEpochMilli()} ${it.count}" }.toByteArray()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(composite, db), GCMParameterSpec(128, iv)); updateAAD(MAGIC) }
        return MAGIC + iv + c.doFinal(plain).also { plain.fill(0) }
    }

    /** The uses in [bytes], or empty when they cannot be read with this key (never throws). */
    fun decode(bytes: ByteArray, composite: ByteArray, db: KeePassDatabase): List<Use> = try {
        require(bytes.size > MAGIC.size + 12 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
        val iv = bytes.copyOfRange(MAGIC.size, MAGIC.size + 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(composite, db), GCMParameterSpec(128, iv)); updateAAD(MAGIC) }
        String(c.doFinal(bytes, MAGIC.size + 12, bytes.size - MAGIC.size - 12)).lines().filter { it.isNotBlank() }.map { l ->
            val p = l.split(' ')
            Use(UUID.fromString(p[0]), Instant.ofEpochMilli(p[1].toLong()), p[2].toInt())
        }
    } catch (_: Exception) { emptyList() }

    fun read(f: File, composite: ByteArray, db: KeePassDatabase): List<Use> =
        if (!f.isFile) emptyList() else decode(runCatching { f.readBytes() }.getOrDefault(ByteArray(0)), composite, db)

    /** [db]'s current values for [ids] (entries that are gone are left out). */
    fun of(db: KeePassDatabase, ids: Collection<UUID>): List<Use> = ids.mapNotNull { id ->
        val t = Tree.findEntry(db, id)?.second?.times ?: return@mapNotNull null
        Use(id, t.lastAccessTime ?: return@mapNotNull null, t.usageCount)
    }

    /** [uses] merged into [db]: the later access time and the higher count win (merging twice changes nothing). */
    fun merge(db: KeePassDatabase, uses: Collection<Use>): KeePassDatabase = uses.fold(db) { d, u ->
        val (g, e) = Tree.findEntry(d, u.id) ?: return@fold d
        val t = e.times ?: return@fold d
        val at = listOfNotNull(t.lastAccessTime, u.at).max()
        val n = maxOf(t.usageCount, u.count)
        if (at == t.lastAccessTime && n == t.usageCount) d
        else Tree.modifyGroup(d, g.uuid) { p -> p.copy(entries = p.entries.map { x -> if (x.uuid != u.id) x else x.copy(times = t.copy(lastAccessTime = at, usageCount = n)) }) }
    }
}
