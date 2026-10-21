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
}
