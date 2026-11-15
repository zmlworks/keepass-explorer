package io.github.zmlworks.kpexplorer.db

import io.github.zmlworks.kpexplorer.L
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The single app database (app-private) and its dated backups.
 * @param dataDir app-private dir (Context.filesDir)
 * @param backupDir app-specific backup dir (getExternalFilesDir("backups"), falls back to filesDir/backups)
 */
class Store(dataDir: File, val backupDir: File) {
    val dbFile = File(File(dataDir, "db"), "database.kdbx")

    fun exists() = dbFile.isFile && dbFile.length() > 0

    fun read(): ByteArray = dbFile.readBytes()

    // ---------------------------------------------------------------- 0.4.37: per-file protection / strength

    /** db/files.properties: "database" and "backup:<name>" → [FileInfo.encode]. No secrets, only verdicts. */
    private val infoFile = File(dbFile.parentFile, "files.properties")

    private fun loadInfo(): Properties = Properties().also { p -> if (infoFile.isFile) runCatching { infoFile.inputStream().use { p.load(it) } } }

    private fun saveInfo(p: Properties) {
        // drop records of backups that are gone (deleted in the list, etc.)
        val names = backups().map { it.name }.toSet()
        p.stringPropertyNames().filter { it.startsWith(BACKUP_KEY) && it.removePrefix(BACKUP_KEY) !in names }.forEach { p.remove(it) }
        val out = java.io.ByteArrayOutputStream()
        p.store(out, "KeePass Explorer: protection and password strength per file (no secrets)")
        writeAtomic(infoFile, out.toByteArray())
    }

    /** The app database's record ([FileInfo.LEGACY] when none: a database made before 0.4.37). */
    @Synchronized
    fun info(): FileInfo = FileInfo.decode(loadInfo().getProperty(MAIN_KEY)) ?: FileInfo.LEGACY

    /** An internal backup's record ([FileInfo.LEGACY] = master password, unknown strength, when none). */
    @Synchronized
    fun info(backup: File): FileInfo = FileInfo.decode(loadInfo().getProperty(BACKUP_KEY + backup.name)) ?: FileInfo.LEGACY

    @Synchronized
    fun setInfo(i: FileInfo) { val p = loadInfo(); p.setProperty(MAIN_KEY, i.encode()); saveInfo(p) }

    @Synchronized
    fun setInfo(backup: File, i: FileInfo) { val p = loadInfo(); p.setProperty(BACKUP_KEY + backup.name, i.encode()); saveInfo(p) }

    /** A new backup is a copy of the database: it inherits the database's record. */
    private fun inherit(backup: File) = setInfo(backup, info())

    /** Remove an internal backup (and its record). */
    @Synchronized
    fun deleteBackup(f: File): Boolean {
        if (f.parentFile?.canonicalFile != backupDir.canonicalFile) return false
        val ok = f.delete()
        saveInfo(loadInfo())
        return ok
    }

    /** Write to a temp file, fsync, then rename over the database (never leaves a half-written file). */
    @Synchronized
    fun write(bytes: ByteArray) = writeAtomic(dbFile, bytes)

    @Synchronized
    fun delete() {
        dbFile.delete()
        infoFile.delete()   // 0.4.37: the records go with the database
    }

    /**
     * 0.4.33: the name the next backup of [today] gets: yyyy-MM-dd.kdbx, then yyyy-MM-dd-2.kdbx, -3 and so on
     * (shown in the Save box before it is made). Up to 0.4.32 the same-day suffix was _2, _3; those names stay.
     */
    @Synchronized
    fun nextBackupName(today: LocalDate = LocalDate.now()): String {
        val base = today.toString()
        val taken = (backupDir.listFiles() ?: emptyArray()).map { it.name.lowercase() }.toSet()
        if ("$base.kdbx" !in taken) return "$base.kdbx"
        var i = 2
        while ("$base-$i.kdbx" in taken) i++
        return "$base-$i.kdbx"
    }

    /** Copy the saved database to backupDir/[nextBackupName]. */
    @Synchronized
    fun backup(today: LocalDate = LocalDate.now()): File {
        check(exists()) { "no database" }
        backupDir.mkdirs()
        val f = File(backupDir, nextBackupName(today))
        writeAtomic(f, read())
        inherit(f)
        return f
    }

    /** What [rekeyBackups] did: rewritten, left as they were (do not open with the old key), could not be written. */
    data class Rekey(val rewritten: List<String>, val skipped: List<String>, val failed: List<String>)

    /**
     * 0.4.33 (master password change): every internal backup that opens with [old] is encrypted again for [new]
     * (fresh salt, verified by decoding before anything is written, then written to a temporary file and renamed
     * over it; its modification time is kept, so the list order stays). Backups that do not open with [old] (another
     * password, a key file, damaged) are not touched. Neither key is changed or wiped here.
     * 0.4.37: the rewritten ones get [info] (the new password's verdict); the others keep their record.
     */
    @Synchronized
    fun rekeyBackups(old: ByteArray, new: ByteArray, info: FileInfo? = null): Rekey {
        val done = ArrayList<String>(); val skipped = ArrayList<String>(); val failed = ArrayList<String>()
        for (f in backups()) {
            val db = try { Kdbx.open(f.readBytes(), old) } catch (_: Throwable) { skipped.add(f.name); continue }
            try {
                val bytes = Kdbx.encodeVerified(Kdbx.withHeader(Kdbx.withCredentials(db, new), light = false))
                val mtime = f.lastModified()
                writeAtomic(f, bytes)
                if (mtime > 0) f.setLastModified(mtime)
                done.add(f.name)
                if (info != null) setInfo(f, info)
            } catch (_: Throwable) { failed.add(f.name) }
        }
        return Rekey(done, skipped, failed)
    }

    /**
     * Forced pre-import backup: unique date+time name so same-day imports never overwrite.
     * Format: yyyy-MM-dd_HH-mm-ss.kdbx (…_2 if collision within the same second).
     * Validates by writing atomically; throws if source missing or write fails.
     */
    @Synchronized
    fun backupTimestamped(when_: LocalDateTime = LocalDateTime.now()): File {
        check(exists()) { "no database" }
        backupDir.mkdirs()
        val stamp = when_.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        var f = File(backupDir, "$stamp.kdbx")
        var i = 2
        while (f.exists()) f = File(backupDir, "${stamp}_${i++}.kdbx")
        val bytes = read()
        check(bytes.isNotEmpty()) { "empty database" }
        writeAtomic(f, bytes)
        check(f.isFile && f.length() == bytes.size.toLong()) { "backup not durable" }
        inherit(f)
        return f
    }

    /** Replace primary with [bytes] after caller has already created a durable timestamped backup. */
    @Synchronized
    fun replacePrimary(bytes: ByteArray) {
        check(bytes.isNotEmpty()) { "empty candidate" }
        writeAtomic(dbFile, bytes)
    }

    fun backups(): List<File> =
        (backupDir.listFiles { f -> f.isFile && f.name.endsWith(".kdbx") } ?: emptyArray())
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

    fun backupFile(name: String): File? {
        if (name.contains('/') || name.contains("..")) return null
        return File(backupDir, name).takeIf { it.isFile }
    }

    /** Why a backup rename was refused (shown to the user). */
    class RenameException(msg: String) : Exception(msg)

    /**
     * 0.4.10: rename an internal backup. [input] is the new name with or without ".kdbx" (always kept);
     * no path separators / control characters / reserved names, max 100 chars, no collision with another
     * backup (case-insensitive, the backup dir can be on a case-insensitive file system). The file's
     * modification time (list order) is kept. Returns the renamed file (same file when the name is unchanged).
     */
    @Synchronized
    fun renameBackup(f: File, input: String): File {
        val base = backupBaseName(input)
        val target = File(backupDir, "$base.kdbx")
        if (f.parentFile?.canonicalFile != backupDir.canonicalFile || !f.isFile) throw RenameException(L.t("バックアップが見つかりません。", "Backup not found."))
        if (target.name == f.name) return f
        val clash = (backupDir.listFiles() ?: emptyArray()).any { it.name.equals(target.name, ignoreCase = true) && it.name != f.name }
        if (clash) throw RenameException(L.t("「${target.name}」は既にあります。別の名前にしてください。", "\"${target.name}\" already exists. Choose another name."))
        val mtime = f.lastModified()
        if (target.name.equals(f.name, ignoreCase = true)) {
            // case-only change: go through a temporary name
            val tmp = File(backupDir, f.name + ".rename.tmp")
            if (!f.renameTo(tmp) || !tmp.renameTo(target)) throw RenameException(L.t("名前を変更できませんでした。", "Could not rename it."))
        } else if (!f.renameTo(target)) throw RenameException(L.t("名前を変更できませんでした。", "Could not rename it."))
        if (mtime > 0) target.setLastModified(mtime)
        val p = loadInfo()
        val rec = p.getProperty(BACKUP_KEY + f.name)
        p.remove(BACKUP_KEY + f.name)
        if (rec != null) p.setProperty(BACKUP_KEY + target.name, rec) else p.remove(BACKUP_KEY + target.name)
        saveInfo(p)
        return target
    }

    companion object {
        const val MAX_BACKUP_NAME = 100
        private const val MAIN_KEY = "database"
        private const val BACKUP_KEY = "backup:"

        /** Validated base name (without ".kdbx") for [renameBackup]; throws [RenameException]. */
        fun backupBaseName(input: String): String {
            var s = input.trim()
            if (s.endsWith(".kdbx", ignoreCase = true)) s = s.dropLast(5).trimEnd()
            if (s.isEmpty()) throw RenameException(L.t("名前を入力してください。", "Enter a name."))
            if (s.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F || it in "<>:\"|?*" })
                throw RenameException(L.t("名前に次の文字は使えません: / \\ : * ? \" < > |", "Names cannot contain: / \\ : * ? \" < > |"))
            if (s == "." || s == ".." || s.startsWith(".")) throw RenameException(L.t("「.」で始まる名前は使えません。", "Names cannot start with \".\"."))
            if (s.length > MAX_BACKUP_NAME) throw RenameException(L.t("名前が長すぎます (${MAX_BACKUP_NAME} 文字まで)。", "The name is too long (up to ${MAX_BACKUP_NAME} characters)."))
            return s
        }

        fun writeAtomic(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { o ->
                o.write(bytes)
                o.flush()
                o.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "rename failed" }
            }
        }
    }
}
