package app.alfrd.engram.db

import com.arcadedb.Constants
import com.arcadedb.GlobalConfiguration
import com.arcadedb.database.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes

/** Schema version for the ArcadeDB vertex/edge shape this build expects. Bump alongside SchemaBootstrap changes. */
const val ENGRAM_SCHEMA_VERSION = 1

/** Outcome of one backup attempt, for the `/health` observability fields. [error] is non-null only on failure. */
data class BackupResult(
    val atMillis: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val error: String?,
)

/**
 * Periodic and shutdown snapshot coordinator for the embedded ArcadeDB instance.
 *
 * Implements the hosted-durability decision recorded in the custody design: BACKUP DATABASE ->
 * checksum -> encrypt -> upload -> advance manifest -> prune, on a fixed interval (default 5
 * minutes, the accepted RPO). Periodic and shutdown-triggered runs both go through
 * [runBackupOnce], guarded by a single [Mutex] — they are single-flight by construction and can
 * never overlap.
 *
 * [runBackupOnce] never throws — a failed backup is recorded via [lastResult] and logged, exactly
 * like [app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService]'s write-path failures.
 * A backup failure must never crash the turn, the periodic loop, or the shutdown sequence.
 */
class GraphBackupCoordinator(
    private val db: Database,
    private val repository: SnapshotRepository,
    private val encryptor: SnapshotEncryptor,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val logger = Logger.getLogger(GraphBackupCoordinator::class.java.name)
    private val inFlight = Mutex()
    private val lastResultRef = AtomicReference<BackupResult?>(null)

    /** Most recent backup outcome (age/duration/size/error), or `null` before the first attempt. */
    fun lastResult(): BackupResult? = lastResultRef.get()

    /** Launches the periodic backup loop. Returns the [Job] so callers can cancel it on shutdown. */
    fun start(): Job = scope.launch {
        while (isActive) {
            delay(intervalMs)
            runBackupOnce()
        }
    }

    /**
     * Runs one backup end to end. Safe to call concurrently with [start]'s loop or from a
     * shutdown hook — [inFlight] ensures a second call waits for an in-progress one rather than
     * racing it. Never throws; failures are recorded in the returned [BackupResult] and logged.
     */
    suspend fun runBackupOnce(): BackupResult = inFlight.withLock {
        val startMs = System.currentTimeMillis()
        val result = try {
            val workDir = createTempDirectory("engram-backup-").toFile()
            try {
                // ArcadeDB's BACKUP DATABASE requires a bare filename (no path separators) when a
                // backup directory is configured, and always nests the output under a
                // <backupDirectory>/<dbName>/ subdirectory it creates itself — confirmed empirically
                // against arcadedb-engine 25.1.1, not documented behavior. SERVER_BACKUP_DIRECTORY is
                // a JVM-wide static setting, safe here only because `inFlight` guarantees at most one
                // backup runs at a time process-wide.
                GlobalConfiguration.SERVER_BACKUP_DIRECTORY.setValue(workDir.absolutePath)
                withContext(Dispatchers.IO) {
                    db.command("sql", "BACKUP DATABASE file://snapshot.zip")
                }
                val rawBackupFile = File(File(workDir, db.name), "snapshot.zip")
                check(rawBackupFile.exists()) { "BACKUP DATABASE reported success but ${rawBackupFile.absolutePath} does not exist" }

                val encryptedFile = File(workDir, "snapshot.enc")
                val checksum = withContext(Dispatchers.IO) {
                    encryptor.encrypt(rawBackupFile, encryptedFile)
                }

                val manifest = SnapshotManifest(
                    key = "engram-db/backup-$startMs-${UUID.randomUUID()}.enc",
                    timestampMillis = startMs,
                    checksumSha256 = checksum,
                    sizeBytes = encryptedFile.length(),
                    engineVersion = Constants.getVersion(),
                    schemaVersion = ENGRAM_SCHEMA_VERSION,
                )

                repository.upload(encryptedFile, manifest)
                repository.prune()

                BackupResult(
                    atMillis = startMs,
                    durationMs = System.currentTimeMillis() - startMs,
                    sizeBytes = manifest.sizeBytes,
                    error = null,
                )
            } finally {
                workDir.deleteRecursively()
            }
        } catch (e: Exception) {
            logger.warning("Graph backup failed: ${e.message}")
            BackupResult(
                atMillis = startMs,
                durationMs = System.currentTimeMillis() - startMs,
                sizeBytes = 0,
                error = e.message ?: e.javaClass.simpleName,
            )
        }
        lastResultRef.set(result)
        result
    }

    companion object {
        val DEFAULT_INTERVAL_MS = 5.minutes.inWholeMilliseconds
    }
}
