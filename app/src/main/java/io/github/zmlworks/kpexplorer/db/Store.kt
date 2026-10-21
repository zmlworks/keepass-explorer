package io.github.zmlworks.kpexplorer.db

import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

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

    fun backups(): List<File> =
        (backupDir.listFiles { f -> f.isFile && f.name.endsWith(".kdbx") } ?: emptyArray())
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

    fun backupFile(name: String): File? {
        if (name.contains('/') || name.contains("..")) return null
        return File(backupDir, name).takeIf { it.isFile }
    }

    companion object {
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
