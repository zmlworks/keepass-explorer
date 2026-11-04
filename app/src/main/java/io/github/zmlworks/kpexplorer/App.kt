package io.github.zmlworks.kpexplorer

import android.content.Context
import io.github.zmlworks.kpexplorer.db.Store
import java.io.File

object App {
    fun store(ctx: Context): Store {
        Session.store?.let { return it }
        // dated copies stay inside the app's private storage (export outside only with a password)
        val s = Store(ctx.filesDir, File(ctx.filesDir, "backups"))
        Session.store = s
        return s
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bumped when a prototype update drops the previous internal data (no migration while in prototype stage). */
    const val DATA_VERSION = 5

    /**
     * Prototype policy: if the internal data was written by an older version, delete it (database, settings,
     * backups, Keystore blobs) and start with setup again. 0.3.1 switched from a master password to a random
     * Keystore key, so older files (and backups) could not be opened anyway.
     */
    fun wipeOldData(ctx: Context) {
        val marker = File(ctx.filesDir, "data-version")
        val have = runCatching { marker.readText().trim().toInt() }.getOrDefault(0)
        if (have == DATA_VERSION) return
        Session.lock()
        File(ctx.filesDir, "db").deleteRecursively()
        File(ctx.filesDir, "backups").deleteRecursively()
        File(ctx.filesDir, "wrapped.bin").delete()
        runCatching { ctx.getExternalFilesDir("backups")?.deleteRecursively() }
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
