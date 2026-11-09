package app.alfrd.engram.director

import app.alfrd.engram.db.AuditReport
import app.alfrd.engram.db.SchemaAudit
import com.arcadedb.database.Database
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant

/**
 * The schema audit, run from the Director tick at most once per [every] and written to `integrity.md` beside the tick files.
 *
 * It is read-only (see [SchemaAudit]) and it touches nothing the tick owns: not the prompt, not `latest.md`, not the change marker, not the
 * tick's counters. It only writes its own file. The time of the last run is the `ran:` line of that file, so "at most once per day" survives a
 * restart, and a missing or unreadable file simply means it is due.
 *
 * The file holds counts and the first few uids per finding, so it carries identifiers but no memory text; it is mode 600 like the tick files.
 */
class IntegrityCheck(
    private val db: Database,
    val file: Path,
    private val every: Duration = Duration.ofHours(24),
    private val firstFew: Int = 3,
    private val audit: () -> AuditReport = { SchemaAudit(db, firstFew).run() },
) {
    private val logger = LoggerFactory.getLogger(IntegrityCheck::class.java)

    /** When the last report was written, read from the file; null if there is none. */
    fun lastRan(): Instant? = try {
        if (!Files.exists(file)) null
        else Files.newBufferedReader(file).useLines { lines -> lines.firstOrNull { it.startsWith(RAN) }?.removePrefix(RAN)?.trim()?.let(Instant::parse) }
    } catch (_: Exception) { null }

    fun due(now: Instant): Boolean = lastRan()?.let { Duration.between(it, now) >= every } ?: true

    /** Runs the audit and writes the report if one is due. Returns true if it ran. Never throws. */
    fun runIfDue(now: Instant): Boolean {
        if (!due(now)) return false
        return try {
            val t0 = System.nanoTime()
            val report = audit()
            val ms = (System.nanoTime() - t0) / 1_000_000
            write(render(report, now, ms))
            logger.info("integrity-check: ran in {} ms; {} finding(s) with violations: {}", ms, report.violations.size, report.violations.joinToString(",") { "${it.id}=${it.count}" })
            true
        } catch (e: Exception) {
            // Class name only: a graph exception message could carry memory text.
            logger.warn("integrity-check: failed: {}", e::class.java.simpleName)
            false
        }
    }

    internal fun render(report: AuditReport, now: Instant, ms: Long): String = buildString {
        appendLine("# Graph integrity")
        appendLine("$RAN $now")
        appendLine("took: $ms ms")
        val bad = report.violations
        appendLine("violations: ${if (bad.isEmpty()) "none" else bad.joinToString(", ") { "${it.id}=${it.count}" }}")
        appendLine()
        for (f in report.findings.filter { it.count > 0 }) {
            val tag = if (f.informational) "info" else "VIOLATION"
            appendLine("- ${f.id} ${f.title}: ${f.count} [$tag]" + if (f.examples.isNotEmpty()) " e.g. " + f.examples.take(firstFew).joinToString(", ") else "")
        }
        val clean = report.findings.filter { it.count == 0L }.map { it.id }
        if (clean.isNotEmpty()) appendLine("- clean: ${clean.joinToString(", ")}")
    }

    private fun write(text: String) {
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".integrity-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(tmp, text)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        const val FILE_NAME = "integrity.md"
        const val ENV_DISABLE = "DIRECTOR_INTEGRITY_CHECK"
        private const val RAN = "ran:"
    }
}
