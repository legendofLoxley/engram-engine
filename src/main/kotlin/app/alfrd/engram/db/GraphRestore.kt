package app.alfrd.engram.db

import com.arcadedb.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Logger
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory

/**
 * Result of a boot-time restore attempt. [Failed] means the caller MUST abort startup rather than
 * proceed with a fresh empty database — per the durability decision record, a manifest that
 * *exists* but fails validation is a hard startup failure, never a silent empty-graph fallback.
 */
sealed interface RestoreOutcome {
    /** No manifest has ever been published — the expected first-boot case. Proceed with a fresh local database. */
    data object FreshStart : RestoreOutcome

    /** [dbPath] already contains files — never overwrite a live local database. No action taken. */
    data object AlreadyPresent : RestoreOutcome

    /** Downloaded, decrypted, checksum- and schema-validated, and extracted into [dbPath]. */
    data object Restored : RestoreOutcome

    /** A manifest existed but failed validation. The caller must not open [dbPath] — abort startup. */
    data class Failed(val reason: String) : RestoreOutcome
}

/**
 * Boot-time restore for the embedded ArcadeDB directory, per the Memory Custody & Portability
 * design's "restore occurs only when the hosted local database is absent and completes before
 * the database opens" requirement. Must run before [DatabaseManager] is constructed.
 */
object GraphRestore {

    private val logger = Logger.getLogger(GraphRestore::class.java.name)

    suspend fun restoreIfAvailable(
        dbPath: String,
        repository: SnapshotRepository,
        encryptor: SnapshotEncryptor,
    ): RestoreOutcome {
        val dbDir = File(dbPath)
        if (dbDir.exists() && dbDir.listFiles()?.isNotEmpty() == true) {
            logger.info("GraphRestore: local database already present at $dbPath, skipping restore")
            return RestoreOutcome.AlreadyPresent
        }

        val manifest = withContext(Dispatchers.IO) { repository.latestManifest() }
            ?: run {
                logger.info("GraphRestore: no snapshot manifest found — starting fresh (expected first boot)")
                return RestoreOutcome.FreshStart
            }

        val workDir = createTempDirectory("engram-restore-").toFile()
        try {
            return withContext(Dispatchers.IO) {
                val encryptedFile = File(workDir, "snapshot.enc")
                repository.download(manifest, encryptedFile)

                val actualChecksum = SnapshotEncryptor.sha256(encryptedFile)
                if (actualChecksum != manifest.checksumSha256) {
                    return@withContext RestoreOutcome.Failed(
                        "Checksum mismatch: expected ${manifest.checksumSha256}, got $actualChecksum"
                    )
                }

                if (manifest.schemaVersion != ENGRAM_SCHEMA_VERSION) {
                    return@withContext RestoreOutcome.Failed(
                        "Schema version mismatch: snapshot is v${manifest.schemaVersion}, this build expects v$ENGRAM_SCHEMA_VERSION"
                    )
                }

                val extractDir = File(workDir, "extracted")
                val plaintextZip = File(workDir, "snapshot.zip")
                try {
                    encryptor.decrypt(encryptedFile, plaintextZip)
                } catch (e: Exception) {
                    return@withContext RestoreOutcome.Failed("Decryption failed: ${e.message}")
                }

                try {
                    extractZip(plaintextZip, extractDir)
                } catch (e: Exception) {
                    return@withContext RestoreOutcome.Failed("Snapshot archive is not a valid zip: ${e.message}")
                }

                if (extractDir.listFiles().isNullOrEmpty()) {
                    return@withContext RestoreOutcome.Failed("Extracted snapshot directory is empty")
                }

                dbDir.mkdirs()
                extractDir.copyRecursively(dbDir, overwrite = true)

                logger.info(
                    "GraphRestore: restored snapshot from ${manifest.timestampMillis} " +
                        "(engine ${manifest.engineVersion}, this build ${Constants.getVersion()})"
                )
                RestoreOutcome.Restored
            }
        } catch (e: Exception) {
            return RestoreOutcome.Failed("Restore failed: ${e.message}")
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
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
