package app.alfrd.engram.db

import com.arcadedb.database.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.UUID
import java.util.zip.ZipFile
import org.slf4j.LoggerFactory

/** What one backup run reports. Metadata only: a file name, sizes, counts and a duration. Never a path outside the backup folder, never graph content. */
@Serializable
data class LocalBackupResult(
    val fileName: String,
    val sizeBytes: Long,
    val entries: Int,
    val durationMs: Long,
    /** Backups in the folder after pruning. */
    val kept: Int,
    val pruned: Int,
    /** Null when no second destination is configured; otherwise whether the copy there succeeded. */
    val copiedToSecond: Boolean?,
)

/**
 * A daily backup of the embedded graph to a local folder, made by the engine's own `BACKUP DATABASE` while it runs
 * (no stop, no network, no encryption, no Spaces). Used on the box; the hosted path is [GraphBackupCoordinator].
 *
 * Layout under [root]: `auto/graph-<UTC yyyyMMddTHHmmssZ>.zip`, mode 600, in a folder of mode 700. Cold copies the
 * runbook describes live beside `auto/` and are never touched here. Each run writes into a staging folder, **verifies
 * the zip (every entry read through its CRC)**, then moves it into `auto/` and only then prunes ([BackupRetention]).
 * A backup that fails verification is deleted and nothing is pruned, so the only good backup is never lost to a bad one.
 *
 * [secondDestination] is an optional second folder (for example a mounted drive); unset by default. The copy is a plain
 * local file copy, and a failure there is reported, never thrown. Logs carry counts, sizes, durations and file names only.
 */
class LocalGraphBackup(
    private val db: Database,
    private val root: File,
    private val secondDestination: File? = null,
    private val dbPath: File = File(db.databasePath),
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(LocalGraphBackup::class.java)
    private val inFlight = Mutex()

    init {
        val r = root.canonicalFile
        val d = dbPath.canonicalFile
        require(!r.path.startsWith(d.path + File.separator) && r != d) { "the backup folder must be outside the graph's data directory" }
        require(!d.path.startsWith(r.path + File.separator)) { "the backup folder must not contain the graph's data directory" }
        secondDestination?.canonicalFile?.let { s ->
            require(!s.path.startsWith(d.path + File.separator) && s != d && s != r) { "the second destination must be a different folder outside the graph's data directory" }
        }
    }

    suspend fun run(): LocalBackupResult = inFlight.withLock { withContext(Dispatchers.IO) { runLocked() } }

    private fun runLocked(): LocalBackupResult {
        val started = System.nanoTime()
        val now = clock()
        privateDir(root)
        val auto = File(root, AUTO).also { privateDir(it) }
        val staging = File(root, ".staging-${UUID.randomUUID()}").also { privateDir(it) }
        try {
            val raw = ArcadeSnapshot.backupInto(db, staging, "graph.zip")
            val entries = verify(raw)
            val name = "graph-${STAMP.format(now.atOffset(ZoneOffset.UTC))}.zip"
            val target = File(auto, name)
            check(!target.exists()) { "a backup with this timestamp already exists" }
            Files.move(raw.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))

            val dropped = BackupRetention.toPrune(auto.listFiles().orEmpty().map { it.name }).also { names -> names.forEach { File(auto, it).delete() } }
            val kept = auto.listFiles().orEmpty().count { BackupRetention.parse(it.name) != null }
            val copied = secondDestination?.let { copyTo(it, target) }
            val result = LocalBackupResult(name, target.length(), entries, (System.nanoTime() - started) / 1_000_000, kept, dropped.size, copied)
            logger.info("graph-backup: wrote {} ({} bytes, {} entries) in {} ms; kept {}, pruned {}, second destination {}", result.fileName, result.sizeBytes, entries, result.durationMs, kept, dropped.size, copied ?: "not set")
            return result
        } catch (e: Exception) {
            logger.warn("graph-backup: failed after {} ms: {}", (System.nanoTime() - started) / 1_000_000, e::class.java.simpleName)
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Reads every entry to its end, which checks each CRC. Returns the entry count; throws on an empty or damaged zip. */
    private fun verify(zip: File): Int {
        require(zip.length() > 0) { "the backup is empty" }
        var count = 0
        ZipFile(zip).use { z ->
            val buf = ByteArray(64 * 1024)
            for (entry in z.entries()) {
                z.getInputStream(entry).use { input -> while (input.read(buf) >= 0) { /* reading checks the CRC */ } }
                count++
            }
        }
        require(count > 0) { "the backup has no entries" }
        return count
    }

    private fun copyTo(dir: File, source: File): Boolean = try {
        privateDir(dir)
        val tmp = File(dir, ".${source.name}.part")
        Files.copy(source.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp.toPath(), File(dir, source.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: Exception) {
        logger.warn("graph-backup: copy to the second destination failed: {}", e::class.java.simpleName)
        false
    }

    private fun privateDir(dir: File) {
        if (!dir.exists()) Files.createDirectories(dir.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
    }

    companion object {
        const val AUTO = "auto"
        const val ENV_DIR = "GRAPH_BACKUP_DIR"
        const val ENV_SECOND = "GRAPH_BACKUP_COPY_TO"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}

/**
 * 14 daily and 8 weekly, by UTC date. Daily: the newest backup of each of the 14 most recent days that have one.
 * Weekly: the newest backup of each of the 8 most recent ISO weeks that have one. A backup is pruned only when it
 * is in neither set, and the newest backup is never pruned. Files that do not match the backup name are ignored.
 */
object BackupRetention {
    const val DAILY = 14
    const val WEEKLY = 8
    private val NAME = Regex("""graph-(\d{8})T(\d{6})Z\.zip""")

    /** The time in the name, or null when [name] is not a backup file name. */
    fun parse(name: String): Instant? {
        val m = NAME.matchEntire(name) ?: return null
        return runCatching { Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).parse(m.groupValues[1] + "T" + m.groupValues[2] + "Z")) }.getOrNull()
    }

    fun toPrune(names: List<String>): List<String> {
        val dated = names.mapNotNull { n -> parse(n)?.let { n to it } }.sortedByDescending { it.second }
        if (dated.size <= 1) return emptyList()
        fun day(t: Instant) = t.atOffset(ZoneOffset.UTC).toLocalDate()
        fun week(t: Instant): Pair<Int, Int> = day(t).let { it.get(IsoFields.WEEK_BASED_YEAR) to it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
        val keep = HashSet<String>()
        keep += dated.first().first
        keep += dated.distinctBy { day(it.second) }.take(DAILY).map { it.first }
        keep += dated.distinctBy { week(it.second) }.take(WEEKLY).map { it.first }
        return dated.map { it.first }.filter { it !in keep }
    }
}
