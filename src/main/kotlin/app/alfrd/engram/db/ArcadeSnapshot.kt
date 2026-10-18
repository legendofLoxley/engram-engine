package app.alfrd.engram.db

import com.arcadedb.GlobalConfiguration
import com.arcadedb.database.Database
import com.arcadedb.database.DatabaseInternal
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The two ArcadeDB mechanics both backup paths share: the engine's own `BACKUP DATABASE`, and unpacking its zip.
 * [GraphBackupCoordinator] (hosted, to Spaces) and [LocalGraphBackup] (the box, to a local folder) both use it,
 * so there is one copy of the quirks below.
 */
object ArcadeSnapshot {

    /**
     * ArcadeDB's `BACKUP DATABASE` requires a bare filename (no path separators) when a backup directory is
     * configured, and nests the output under `<backupDirectory>/<dbName>/` itself (confirmed against
     * arcadedb-engine 25.1.1). `SERVER_BACKUP_DIRECTORY` is a JVM-wide static, so one process-wide lock guards
     * every caller: two backups, from whichever path, can never interleave their directory setting.
     */
    private val processWide = Any()

    /** Runs `BACKUP DATABASE` into [directory] and returns the zip it wrote, `<directory>/<dbName>/<fileName>`. */
    fun backupInto(db: Database, directory: File, fileName: String): File = synchronized(processWide) {
        require('/' !in fileName && '\\' !in fileName) { "the backup file name must be bare" }
        // `BACKUP DATABASE` zips the files on disk, and a commit returns before the page-flush thread has written its pages,
        // so without this a transaction that has already committed can be missing from the zip.
        (db as DatabaseInternal).pageManager.waitAllPagesOfDatabaseAreFlushed(db)
        GlobalConfiguration.SERVER_BACKUP_DIRECTORY.setValue(directory.absolutePath)
        db.command("sql", "BACKUP DATABASE file://$fileName")
        val file = File(File(directory, db.name), fileName)
        check(file.exists()) { "BACKUP DATABASE reported success but the backup file does not exist" }
        file
    }

    /** Unpacks a `BACKUP DATABASE` zip into [destDir]. Refuses an entry that would land outside it. */
    fun extractZip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                require(outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator) || outFile.canonicalFile == destDir.canonicalFile) {
                    "Zip entry escapes destination directory: ${entry.name}"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
