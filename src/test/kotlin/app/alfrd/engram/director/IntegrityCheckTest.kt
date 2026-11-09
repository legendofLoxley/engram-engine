package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.db.AuditReport
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaAudit
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.database.Database
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The daily integrity check run from the Director tick: at most once a day, its own file only, and the tick's output unchanged. */
class IntegrityCheckTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private val email = "debug+integrity@test.alfrd.internal"
    private var now = Instant.parse("2026-11-08T10:00:00Z")

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-integrity-test-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, email)
        // one violation to find: a Task with no state
        db.transaction { db.newVertex("Task").set("uid", "legacy").set("text", "old").set("status", "open").set("ownerEmail", email).set("createdAt", 1L).save() }
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private class QuietProbe : SelfStateProbe {
        override fun servers() = SystemProbe.DEFAULT_SERVERS.map { (n, p) -> ServerStatus(n, p, true, 3L) }
        override fun memory() = MemoryUse(10L * 1_073_741_824, 96L * 1_073_741_824, 20L * 1_073_741_824, 31L * 1_073_741_824)
    }

    private fun tick(dir: Path, integrity: IntegrityCheck?) = DirectorTick(
        email = email, label = "integrity", interval = Duration.ofSeconds(300), outDir = dir,
        markers = ChangeMarkerReader(db), assembler = ArcadeHorizonAssembler(db), probe = QuietProbe(),
        notion = { NotionState(false, "off") }, hermes = { emptyMap() }, budgets = { BudgetDefaults(10, 25, 600, 12_000, false) },
        clock = { now }, zone = ZoneOffset.UTC, integrity = integrity,
    )

    @Test
    fun `runs on the first tick, then at most once in 24 hours, and again after`() = runBlocking<Unit> {
        var runs = 0
        val dir = tempDir.toPath().resolve("out")
        val check = IntegrityCheck(db, dir.resolve("integrity.md"), audit = { runs++; SchemaAudit(db).run() })
        val t = tick(dir, check)
        t.tickOnce(); assertEquals(1, runs)
        now = now.plusSeconds(3600); t.tickOnce(); assertEquals(1, runs, "an hour later it is not due")
        now = now.plus(Duration.ofHours(22)).plus(Duration.ofMinutes(59)); t.tickOnce(); assertEquals(1, runs, "23h59 after the last run it is still not due")
        now = now.plus(Duration.ofMinutes(2)); t.tickOnce(); assertEquals(2, runs, "past 24 hours it runs again")
    }

    @Test
    fun `the last run survives a restart because it is read from the file`() = runBlocking<Unit> {
        var runs = 0
        val dir = tempDir.toPath().resolve("out")
        val file = dir.resolve("integrity.md")
        tick(dir, IntegrityCheck(db, file, audit = { runs++; SchemaAudit(db).run() })).tickOnce()
        val restarted = IntegrityCheck(db, file, audit = { runs++; SchemaAudit(db).run() })
        assertEquals(now, restarted.lastRan())
        tick(dir, restarted).tickOnce()
        assertEquals(1, runs)
    }

    @Test
    fun `the report names the finding with a count and its first uids`() = runBlocking<Unit> {
        val dir = tempDir.toPath().resolve("out")
        tick(dir, IntegrityCheck(db, dir.resolve("integrity.md"))).tickOnce()
        val text = Files.readString(dir.resolve("integrity.md"))
        assertTrue(text.contains("ran: 2026-11-08T10:00:00Z"), text)
        assertTrue(Regex("A6 .*: 1 \\[VIOLATION] e\\.g\\. legacy").containsMatchIn(text), text)
        assertTrue(text.contains("A6=1"), text)
        assertFalse(text.lines().any { it.contains("text") && it.contains("old") && !it.contains("A13") }, "no memory text in the report")
    }

    @Test
    fun `the tick's own output is byte-identical with and without the check`() = runBlocking<Unit> {
        val plain = tempDir.toPath().resolve("plain")
        val checked = tempDir.toPath().resolve("checked")
        tick(plain, null).tickOnce()
        tick(checked, IntegrityCheck(db, checked.resolve("integrity.md"))).tickOnce()
        assertEquals(Files.readString(plain.resolve("latest.md")), Files.readString(checked.resolve("latest.md")))
        assertEquals(
            Files.list(plain).use { s -> s.map { it.fileName.toString() }.filter { !it.startsWith("tick-") }.toList() },
            Files.list(checked).use { s -> s.map { it.fileName.toString() }.filter { !it.startsWith("tick-") && it != "integrity.md" }.toList() },
            "the only extra file is integrity.md",
        )
    }

    @Test
    fun `it writes nothing to the graph and a failing audit does not break the tick`() = runBlocking<Unit> {
        fun count() = db.schema.types.sumOf { db.countType(it.name, false) }
        val before = count()
        val dir = tempDir.toPath().resolve("out")
        tick(dir, IntegrityCheck(db, dir.resolve("integrity.md"))).tickOnce()
        assertEquals(before, count())
        val boom = IntegrityCheck(db, dir.resolve("boom.md"), audit = { throw IllegalStateException("graph said: secret memory text") })
        val report = tick(dir.resolveSibling("out2"), boom).tickOnce()
        assertTrue(report.rendered, "the tick still rendered")
        assertFalse(Files.exists(dir.resolve("boom.md")))
    }
}
