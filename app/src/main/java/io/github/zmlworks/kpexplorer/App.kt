package io.github.zmlworks.kpexplorer

import android.content.Context
import io.github.zmlworks.kpexplorer.db.Store
import java.io.File

object App {
    fun store(ctx: Context): Store {
        Session.store?.let { return it }
        val ext = try { ctx.getExternalFilesDir("backups") } catch (_: Throwable) { null }
        val s = Store(ctx.filesDir, ext ?: File(ctx.filesDir, "backups"))
        Session.store = s
        return s
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bumped when a prototype update drops the previous internal data (no migration while in prototype stage). */
    const val DATA_VERSION = 3

    /**
     * Prototype policy: if the internal data was written by an older version, delete it (database, settings,
     * fingerprint / PIN blobs) and start with setup again. Dated backups (app-specific external dir) are kept.
     */
    fun wipeOldData(ctx: Context) {
        val marker = File(ctx.filesDir, "data-version")
        val have = runCatching { marker.readText().trim().toInt() }.getOrDefault(0)
        if (have == DATA_VERSION) return
        Session.lock()
        File(ctx.filesDir, "db").deleteRecursively()
        Session.store = null
        for (n in listOf("settings", "vault", "pin")) ctx.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.aliases().toList().filter { it.startsWith("kpexplorer.") }.forEach { ks.deleteEntry(it) }
        }
        ctx.filesDir.mkdirs()
        marker.writeText(DATA_VERSION.toString())
    }
}
