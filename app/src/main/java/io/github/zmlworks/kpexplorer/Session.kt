package io.github.zmlworks.kpexplorer

import android.os.Handler
import android.os.Looper
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Store
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The unlocked database (immutable kotpass model) and the auto-saver. Every change goes through [update];
 * a debounced save then encodes, verifies (decodes again) and atomically replaces the file.
 */
object Session {
    @Volatile var db: KeePassDatabase? = null
        private set
    var store: Store? = null

    enum class SaveState { SAVED, PENDING, SAVING, ERROR }
    @Volatile var saveState = SaveState.SAVED
        private set
    var lastError: String? = null
    var listener: (() -> Unit)? = null

    /** Background thread for KDF-heavy work (open, save). Single thread = saves are serialized. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "kpx-io").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var version = 0L
    /** Bumped on open/lock so late save callbacks of an earlier session are ignored. */
    private var generation = 0L
    private var savedVersion = 0L
    private val saveRunnable = Runnable { flush() }
    var debounceMs = 600L

    val isOpen get() = db != null

    fun open(d: KeePassDatabase) {
        generation++
        db = d; version = 0; savedVersion = 0; saveState = SaveState.SAVED
    }

    /** Apply a change and schedule an auto-save. */
    fun update(f: (KeePassDatabase) -> KeePassDatabase): KeePassDatabase? {
        val cur = db ?: return null
        val next = f(cur)
        if (next === cur) return cur
        db = next
        version++
        saveState = SaveState.PENDING
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, debounceMs)
        notifyListener()
        return next
    }

    /** Save now (asynchronously) if there are unsaved changes. */
    fun flush() {
        main.removeCallbacks(saveRunnable)
        val snap = db ?: return
        val st = store ?: return
        if (version == savedVersion) return
        val v = version
        val gen = generation
        saveState = SaveState.SAVING
        notifyListener()
        io.execute {
            try {
                val bytes = Kdbx.encodeVerified(snap)
                st.write(bytes)
                main.post {
                    if (gen != generation) return@post
                    if (v > savedVersion) savedVersion = v
                    saveState = if (savedVersion == version) SaveState.SAVED else SaveState.PENDING
                    lastError = null
                    notifyListener()
                    if (saveState == SaveState.PENDING) main.postDelayed(saveRunnable, debounceMs)
                }
            } catch (t: Throwable) {
                main.post { if (gen == generation) { saveState = SaveState.ERROR; lastError = t.toString(); notifyListener() } }
            }
        }
    }

    /** Save synchronously from the caller's thread (used before backups / lock). */
    fun flushAndWait(timeoutMs: Long = 30_000) {
        flush()
        val f = io.submit { }
        try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
    }

    val hasUnsaved get() = db != null && version != savedVersion

    /** Lock: pending changes are saved from the snapshot, then every reference to the database is dropped. */
    fun lock() {
        if (db != null && version != savedVersion) flush()
        main.removeCallbacks(saveRunnable)
        generation++
        db = null
        version = 0; savedVersion = 0
        saveState = SaveState.SAVED
        Clip.clearNowIfOurs()
        notifyListener()
    }

    private fun notifyListener() { listener?.invoke() }

    /** Composite key of the open database (caller must wipe). */
    fun compositeCopy(): ByteArray? = db?.credentials?.passphrase?.getBinary()?.copyOf()
}
