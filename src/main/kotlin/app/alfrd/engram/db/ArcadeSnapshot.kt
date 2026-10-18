package app.alfrd.engram.db

import com.arcadedb.GlobalConfiguration
import com.arcadedb.database.Database
import com.arcadedb.database.DatabaseInternal
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.slf4j.LoggerFactory

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

    private val logger = LoggerFactory.getLogger(ArcadeSnapshot::class.java)

    /** One `BACKUP DATABASE` attempt: the zip it wrote, and the size of every live file just before it started (after the flush wait). */
    internal class Attempt(val zip: File, val liveSizes: Map<String, Long>)

    /**
     * Runs `BACKUP DATABASE` into [directory] and returns the zip it wrote, `<directory>/<dbName>/<fileName>`.
     * A zip that holds a data file of 0 bytes whose file was not empty when the backup started is treated as failed:
     * it is deleted and the backup is retried once, then this throws. Sizes are taken before the backup so that a write
     * that commits while it runs cannot make a file that was legitimately empty at that time look lost.
     */
    fun backupInto(db: Database, directory: File, fileName: String): File = backupVerified(db, directory, fileName, ::backupOnce)

    /** [backupInto] with the attempt injectable, so a test can hand back a zip the guard rejects. Production always uses [backupOnce]. */
    internal fun backupVerified(db: Database, directory: File, fileName: String, attempt: (Database, File, String) -> Attempt): File = synchronized(processWide) {
        require('/' !in fileName && '\\' !in fileName) { "the backup file name must be bare" }
        var made = attempt(db, directory, fileName)
        var emptied = emptiedDataFiles(made.zip, made.liveSizes)
        if (emptied.isNotEmpty()) {
            logger.warn("graph-backup: {} data file(s) came out empty although they held {} bytes before the backup; retrying once", emptied.size, emptied.sum())
            made.zip.delete()
            made = attempt(db, directory, fileName)
            emptied = emptiedDataFiles(made.zip, made.liveSizes)
            if (emptied.isNotEmpty()) {
                made.zip.delete()
                logger.error("graph-backup: {} data file(s) came out empty again although they held {} bytes before the backup; failing", emptied.size, emptied.sum())
                error("the backup holds ${emptied.size} empty data file(s) that were not empty before it started")
            }
        }
        made.zip
    }

    private fun backupOnce(db: Database, directory: File, fileName: String): Attempt {
        // `BACKUP DATABASE` zips the files on disk, and a commit returns before the page-flush thread has written its pages,
        // so without this a transaction that has already committed can be missing from the zip.
        (db as DatabaseInternal).pageManager.waitAllPagesOfDatabaseAreFlushed(db)
        val liveSizes = liveFileSizes(File(db.databasePath))
        GlobalConfiguration.SERVER_BACKUP_DIRECTORY.setValue(directory.absolutePath)
        db.command("sql", "BACKUP DATABASE file://$fileName")
        val file = File(File(directory, db.name), fileName)
        check(file.exists()) { "BACKUP DATABASE reported success but the backup file does not exist" }
        return Attempt(file, liveSizes)
    }

    /** Size in bytes of every file under [dbDir], keyed by its path relative to it (the name the zip uses). */
    internal fun liveFileSizes(dbDir: File): Map<String, Long> =
        dbDir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dbDir).invariantSeparatorsPath to it.length() }

    /** The earlier sizes, in bytes, of the files that are 0 bytes in [zip] but were not empty in [liveSizes]. Write-ahead logs are not backed up and are skipped. */
    internal fun emptiedDataFiles(zip: File, liveSizes: Map<String, Long>): List<Long> = ZipFile(zip).use { z ->
        z.entries().asSequence()
            .filter { !it.isDirectory && it.size == 0L && !it.name.endsWith(".wal") }
            .map { liveSizes[it.name] ?: 0L }
            .filter { it > 0 }
            .toList()
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
