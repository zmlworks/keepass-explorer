package io.github.zmlworks.kpexplorer

import android.os.Handler
import android.os.Looper
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Store
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    /** Result of the last save task on [io] (null = ok). Written and read on the io thread. */
    @Volatile private var lastIoError: Throwable? = null
    /** A save started by [lock] failed after the session was gone; shown once by [takeLockSaveError]. */
    @Volatile private var lockSaveError: String? = null

    class SaveFailedException(cause: Throwable) : Exception("保存できませんでした: $cause", cause)

    fun open(d: KeePassDatabase) {
        generation++
        db = d; version = 0; savedVersion = 0; saveState = SaveState.SAVED
        io.execute { lastIoError = null }
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
                lastIoError = null
                main.post {
                    if (gen != generation) return@post
                    if (v > savedVersion) savedVersion = v
                    saveState = if (savedVersion == version) SaveState.SAVED else SaveState.PENDING
                    lastError = null
                    notifyListener()
                    if (saveState == SaveState.PENDING) main.postDelayed(saveRunnable, debounceMs)
                }
            } catch (t: Throwable) {
                lastIoError = t
                main.post {
                    if (gen == generation) { saveState = SaveState.ERROR; lastError = t.toString() }
                    else lockSaveError = t.toString()   // locked meanwhile: report on the next screen
                    notifyListener()
                }
            }
        }
    }

    /**
     * Save pending changes, then run [task] on the io thread after that save (saves are serialized
     * there, so [task] sees the saved file). [done] gets the result on the main thread; a failed
     * save is a [SaveFailedException] and [task] is not run. Never blocks the main thread.
     */
    fun <T> afterSave(task: () -> T, done: (Result<T>) -> Unit) {
        val open = db != null   // when locked there is nothing of ours to save
        flush()
        io.execute {
            val err = if (open) lastIoError else null
            val r: Result<T> = if (err != null) Result.failure(SaveFailedException(err)) else runCatching(task)
            main.post { done(r) }
        }
    }

    /** Error of a save that was still running when the database was locked (once). */
    fun takeLockSaveError(): String? = lockSaveError.also { lockSaveError = null }

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
