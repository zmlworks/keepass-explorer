package io.github.zmlworks.kpexplorer

import android.os.Handler
import android.os.Looper
import app.keemobile.kotpass.database.KeePassDatabase
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Recent
import io.github.zmlworks.kpexplorer.db.Store
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.db.UsageLog
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
    /**
     * 0.4.40: changes made by [updateQuietly] (KeePass LastAccessTime / UsageCount from viewing an entry) and how many of
     * them the file has. They schedule no save of their own: any later save writes them (the database is one model),
     * and so do [lock] and [flushAll] (the app going to the background).
     */
    private var quietVersion = 0L
    private var savedQuietVersion = 0L
    /**
     * 0.4.42: entries used ([recordUse]) since the file last had their use, with the [quietVersion] of the use. Locking
     * or leaving the app writes their values to the side file ([UsageLog], [writeUsageFile]) instead of the database;
     * a save of the database writes them and removes that file.
     */
    private val pendingUse = HashMap<java.util.UUID, Long>()
    private val saveRunnable = Runnable { flush() }
    /** Rapid moves / reorders are coalesced into one save this long after the last change. */
    var debounceMs = 1500L

    val isOpen get() = db != null
    /** Number of completed file writes (tests: debounce coalescing). */
    @Volatile var saveCount = 0

    /** Temporary read-only browse of an external file or dated backup (sepia chrome). */
    @Volatile var readOnlyTemp = false
        private set
    @Volatile var tempSourceLabel: String = ""
        private set
    private var stashedPrimary: KeePassDatabase? = null
    private var stashedVersion = 0L
    private var stashedSavedVersion = 0L
    private var stashedQuiet = 0L to 0L
    private var stashedGeneration = 0L
    /**
     * 0.4.35: the read-only view was opened from the unlock screen, with the app's own database locked (nothing
     * stashed). Ending it locks again: see [exitTempView].
     */
    @Volatile var tempOverLocked = false
        private set

    /** Result of the last save task on [io] (null = ok). Written and read on the io thread. */
    @Volatile private var lastIoError: Throwable? = null
    /** A save started by [lock] failed after the session was gone; shown once by [takeLockSaveError]. */
    @Volatile private var lockSaveError: String? = null

    class SaveFailedException(cause: Throwable) : Exception(L.t("保存できませんでした: $cause", "Could not save: $cause"), cause)

    fun open(d: KeePassDatabase) {
        generation++
        // 0.4.42: uses not in a file yet follow a database that replaces this one (restore, import) where their
        // entries still exist; an unlock takes them back from the side file (unreadable with another key = none)
        val carried = db?.takeIf { !readOnlyTemp }?.let { UsageLog.of(it, pendingUse.keys) }.orEmpty()
        val fromFile = store?.let { st -> d.credentials.passphrase?.getBinary()?.let { k -> try { UsageLog.read(st.usageFile, k, d) } finally { k.fill(0) } } }.orEmpty()
        val merged = UsageLog.merge(d, carried + fromFile)
        db = merged; version = 0; savedVersion = 0; quietVersion = 0; savedQuietVersion = 0; saveState = SaveState.SAVED
        pendingUse.clear()
        if (merged !== d) { quietVersion = 1; (carried + fromFile).filter { Tree.findEntry(merged, it.id) != null }.forEach { pendingUse[it.id] = 1 } }
        // open() always means primary (or fresh); clear any temp stash
        readOnlyTemp = false
        tempSourceLabel = ""
        stashedPrimary = null
        io.execute { lastIoError = null }
    }

    /** Apply a change and schedule an auto-save. */
    fun update(f: (KeePassDatabase) -> KeePassDatabase): KeePassDatabase? {
        if (readOnlyTemp) throw IllegalStateException("read-only temp view")
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

    /**
     * 0.4.40: a change that needs no save of its own ([update] without the auto-save): the use of an entry when
     * 「ファイルを開くたびにデータベースを更新する」 is off. Shown at once (it is in [db]); written by the next save.
     */
    fun updateQuietly(f: (KeePassDatabase) -> KeePassDatabase): KeePassDatabase? {
        if (readOnlyTemp) throw IllegalStateException("read-only temp view")
        val cur = db ?: return null
        val next = f(cur)
        if (next === cur) return cur
        db = next
        quietVersion++
        return next
    }

    /** 0.4.42: a use of entry [id] (KeePass LastAccessTime / UsageCount), kept in memory: see [pendingUse]. */
    fun recordUse(id: java.util.UUID) {
        val before = db
        if (updateQuietly { Recent.markUsed(it, id) } !== before) pendingUse[id] = quietVersion
    }

    /** Entries whose use is only in memory or in the side file (tests). */
    val pendingUses: Set<java.util.UUID> get() = pendingUse.keys

    /**
     * 0.4.42: leaving the app or locking. Unsaved edits are saved (with the uses); only uses: they go to the side
     * file, the database is not rewritten.
     */
    fun leave() {
        if (readOnlyTemp || db == null) return
        if (version != savedVersion) flushAll()
        else if (quietVersion != savedQuietVersion && pendingUse.isNotEmpty()) writeUsageFile()
    }

    /** The pending uses to the side file (on [io], after any save in progress). */
    private fun writeUsageFile() {
        val snap = db ?: return
        val st = store ?: return
        val uses = UsageLog.of(snap, pendingUse.keys)
        val k = compositeCopy() ?: return
        io.execute {
            try { Store.writeAtomic(st.usageFile, UsageLog.encode(uses, k, snap)) } catch (_: Throwable) {} finally { k.fill(0) }
        }
    }

    /** Changes of [updateQuietly] not in the file yet. */
    val hasQuietUnsaved get() = db != null && quietVersion != savedQuietVersion

    /** Save now if there is anything unsaved, [updateQuietly]'s changes included (lock, leaving the app). */
    fun flushAll() = flush(quiet = true)

    /** Save now (asynchronously) if there are unsaved changes ([quiet]: also only [updateQuietly]'s). */
    fun flush(quiet: Boolean = false) {
        if (readOnlyTemp) return  // never write while browsing a temp file
        main.removeCallbacks(saveRunnable)
        val snap = db ?: return
        val st = store ?: return
        if (version == savedVersion && !(quiet && quietVersion != savedQuietVersion)) return
        val v = version
        val qv = quietVersion
        val gen = generation
        saveState = SaveState.SAVING
        notifyListener()
        io.execute {
            try {
                val bytes = Kdbx.encodeVerified(snap, keepSalt = true)
                st.write(bytes)
                st.usageFile.delete()   // 0.4.42: every use so far is in this save
                saveCount++
                lastIoError = null
                main.post {
                    if (gen != generation) return@post
                    if (v > savedVersion) savedVersion = v
                    if (qv > savedQuietVersion) savedQuietVersion = qv
                    pendingUse.values.removeAll { it <= qv }
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

    /**
     * Park the primary DB in memory and open [temp] read-only.
     * Does not write the primary file. [update]/exitTempView] restores primary intact.
     * Cancels any pending autosave so the primary file is never touched while browsing temp.
     * 0.4.35: also while locked (from the unlock screen): nothing is parked, and the primary stays locked.
     */
    fun beginTempView(temp: KeePassDatabase, sourceLabel: String) {
        if (readOnlyTemp) throw IllegalStateException("already in temp view")
        val cur = db
        tempOverLocked = cur == null
        main.removeCallbacks(saveRunnable)
        stashedPrimary = cur
        stashedVersion = version
        stashedSavedVersion = savedVersion
        stashedQuiet = quietVersion to savedQuietVersion
        stashedGeneration = generation
        // Bump generation so in-flight primary saves are ignored while in temp view.
        generation++
        db = temp
        version = 0L
        savedVersion = 0L
        quietVersion = 0L; savedQuietVersion = 0L
        saveState = SaveState.SAVED
        readOnlyTemp = true
        tempSourceLabel = sourceLabel
        notifyListener()
    }

    fun exitTempView() {
        if (!readOnlyTemp) return
        if (tempOverLocked) { lock(); return }   // 0.4.35: back to locked, the external file's data dropped
        val p = stashedPrimary ?: throw IllegalStateException("no stashed primary")
        main.removeCallbacks(saveRunnable)
        stashedPrimary = null
        generation++ // drop any temp-era callbacks
        db = p
        version = stashedVersion
        savedVersion = stashedSavedVersion
        quietVersion = stashedQuiet.first; savedQuietVersion = stashedQuiet.second
        // Restore saveState from version vs savedVersion
        saveState = if (version == savedVersion) SaveState.SAVED else SaveState.PENDING
        readOnlyTemp = false
        tempSourceLabel = ""
        notifyListener()
    }

    fun lock() {
        // Leave temp view first so flush (if any) targets the primary file, never the temp bytes.
        tempOverLocked = false
        if (readOnlyTemp) {
            val p = stashedPrimary
            stashedPrimary = null
            readOnlyTemp = false
            tempSourceLabel = ""
            if (p != null) {
                db = p
                version = stashedVersion
                savedVersion = stashedSavedVersion
                quietVersion = stashedQuiet.first; savedQuietVersion = stashedQuiet.second
            }
        }
        // 0.4.42: edits saved, uses only to the side file (see [leave])
        leave()
        pendingUse.clear()
        main.removeCallbacks(saveRunnable)
        generation++
        db = null
        version = 0; savedVersion = 0; quietVersion = 0; savedQuietVersion = 0
        saveState = SaveState.SAVED
        readOnlyTemp = false
        tempSourceLabel = ""
        stashedPrimary = null
        Clip.clearNowIfOurs()
        io.execute { Kdbx.KdfCache.clear() }   // after the final save above
        notifyListener()
    }

    private fun notifyListener() { listener?.invoke() }

    /** Composite key of the open database (caller must wipe). */
    fun compositeCopy(): ByteArray? = db?.credentials?.passphrase?.getBinary()?.copyOf()
}
