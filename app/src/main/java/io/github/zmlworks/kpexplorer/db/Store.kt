package io.github.zmlworks.kpexplorer.db

import java.io.File
import java.io.FileOutputStream
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

    /** Write to a temp file, fsync, then rename over the database (never leaves a half-written file). */
    @Synchronized
    fun write(bytes: ByteArray) = writeAtomic(dbFile, bytes)

    @Synchronized
    fun delete() {
        dbFile.delete()
    }

    /** Copy the saved database to backupDir/yyyy-MM-dd.kdbx (…_2, _3 if taken). */
    @Synchronized
    fun backup(today: LocalDate = LocalDate.now()): File {
        check(exists()) { "no database" }
        backupDir.mkdirs()
        val base = today.toString()
        var f = File(backupDir, "$base.kdbx")
        var i = 2
        while (f.exists()) f = File(backupDir, "${base}_${i++}.kdbx")
        writeAtomic(f, read())
        return f
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
        if (f.parentFile?.canonicalFile != backupDir.canonicalFile || !f.isFile) throw RenameException("バックアップが見つかりません。")
        if (target.name == f.name) return f
        val clash = (backupDir.listFiles() ?: emptyArray()).any { it.name.equals(target.name, ignoreCase = true) && it.name != f.name }
        if (clash) throw RenameException("「${target.name}」は既にあります。別の名前にしてください。")
        val mtime = f.lastModified()
        if (target.name.equals(f.name, ignoreCase = true)) {
            // case-only change: go through a temporary name
            val tmp = File(backupDir, f.name + ".rename.tmp")
            if (!f.renameTo(tmp) || !tmp.renameTo(target)) throw RenameException("名前を変更できませんでした。")
        } else if (!f.renameTo(target)) throw RenameException("名前を変更できませんでした。")
        if (mtime > 0) target.setLastModified(mtime)
        return target
    }

    companion object {
        const val MAX_BACKUP_NAME = 100

        /** Validated base name (without ".kdbx") for [renameBackup]; throws [RenameException]. */
        fun backupBaseName(input: String): String {
            var s = input.trim()
            if (s.endsWith(".kdbx", ignoreCase = true)) s = s.dropLast(5).trimEnd()
            if (s.isEmpty()) throw RenameException("名前を入力してください。")
            if (s.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F || it in "<>:\"|?*" })
                throw RenameException("名前に次の文字は使えません: / \\ : * ? \" < > |")
            if (s == "." || s == ".." || s.startsWith(".")) throw RenameException("「.」で始まる名前は使えません。")
            if (s.length > MAX_BACKUP_NAME) throw RenameException("名前が長すぎます (${MAX_BACKUP_NAME} 文字まで)。")
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
