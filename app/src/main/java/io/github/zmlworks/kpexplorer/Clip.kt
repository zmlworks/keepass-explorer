package io.github.zmlworks.kpexplorer

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.security.MessageDigest

/** Clipboard copies of secrets: marked sensitive and cleared after 45 s (only if the clip is still ours). */
object Clip {
    const val CLEAR_MS = 45_000L
    private val h = Handler(Looper.getMainLooper())
    private var lastHash: ByteArray? = null
    private var app: Context? = null
    private val clear = Runnable { clearNowIfOurs() }

    fun copy(ctx: Context, label: String, text: String, sensitive: Boolean) {
        val a = ctx.applicationContext
        app = a
        val cm = a.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        h.removeCallbacks(clear)
        lastHash = hash(text)
        h.postDelayed(clear, CLEAR_MS)
    }

    fun clearNowIfOurs() {
        h.removeCallbacks(clear)
        val a = app ?: return
        val want = lastHash ?: return
        lastHash = null
        val cm = a.getSystemService(ClipboardManager::class.java) ?: return
        val now = try { cm.primaryClip } catch (_: SecurityException) { null }
        val t = now?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (t != null && !hash(t).contentEquals(want)) return
        try { cm.clearPrimaryClip() } catch (_: Exception) {}
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
}
