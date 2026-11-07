package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind
import app.alfrd.engram.cognitive.pipeline.horizon.FocusMode
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.db.ChunkDraft
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceStore
import com.arcadedb.database.Database
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The Director tick: a change marker, a Horizon with no message that writes nothing, a self-state block, and the schedule and files around them. */
class DirectorTickTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private val email = "debug+tick-a@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-tick-test-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, email)
        store = SourceStore(db)
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private fun ingest(locator: String, text: String = "Page one text.") =
        store.ingest(email, SourceInput("document", locator, "Doc $locator", text, chunks = listOf(ChunkDraft("page 1", text, page = 1))))

    /** Everything the graph holds, by type, plus the user's cycle counter. */
    private fun fingerprint(): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        for (type in db.schema.types.map { it.name }.sorted()) {
            out[type] = db.query("sql", "SELECT count(*) AS n FROM `$type`").use { it.next().getProperty<Number>("n").toLong() }
        }
        out["lastCycleSeq"] = db.query("sql", "SELECT lastCycleSeq FROM User WHERE email = :e", mapOf("e" to email)).use { (it.next().getProperty<Number?>("lastCycleSeq") ?: 0).toLong() }
        return out
    }

    private class FakeProbe(val up: Set<Int>) : SelfStateProbe {
        override fun servers() = SystemProbe.DEFAULT_SERVERS.map { (n, p) -> ServerStatus(n, p, p in up, if (p in up) 3L else null) }
        override fun memory() = MemoryUse(10L * 1_073_741_824, 96L * 1_073_741_824, 20L * 1_073_741_824, 31L * 1_073_741_824)
    }

    private fun tick(
        dir: Path, clock: () -> Instant, probe: SelfStateProbe = FakeProbe(setOf(8081, 8083, 8084)), keep: Int = 50,
    ) = DirectorTick(
        email = email, label = "tick-a", interval = Duration.ofSeconds(300), outDir = dir,
        markers = ChangeMarkerReader(db), assembler = ArcadeHorizonAssembler(db), probe = probe,
        notion = { NotionState(false, "NOTION_WORKER_CONFIG is not set") }, hermes = { emptyMap() },
        budgets = { BudgetDefaults(10, 25, 600, 12_000, false) }, clock = clock, zone = ZoneOffset.UTC, keep = keep,
    )

    // ── 1. The marker ─────────────────────────────────────────────────────────

    @Test
    fun `committing a Source to the identity changes the marker and names the parts that moved`() {
        val reader = ChangeMarkerReader(db)
        val before = reader.read(email)!!
        assertEquals(0L, before.counts["Source"])
        assertTrue(before.changesSince(before).isEmpty(), "a marker does not differ from itself")

        ingest("gdrive:one")
        val after = reader.read(email)!!
        val moved = after.changesSince(before).map { it.part }
        assertTrue("Source" in moved && "Chunk" in moved && "newestSourceReadAt" in moved, moved.toString())
        assertEquals(1L, after.counts["Source"])
        assertEquals(1L, after.counts["Chunk"])
        assertFalse("Project" in moved || "Domain" in moved, "parts that did not move are not reported: $moved")
        assertNotEquals(before, after)
    }

    @Test
    fun `an item and a new cycle move their own parts, and another identity's data moves nothing`() = runBlocking<Unit> {
        val reader = ChangeMarkerReader(db)
        val before = reader.read(email)!!
        val other = "debug+tick-b@test.alfrd.internal"
        DebugConverseService.ensureSyntheticUser(db, other)
        store.ingest(other, SourceInput("document", "gdrive:other", "Other", "Other text.", chunks = listOf(ChunkDraft("page 1", "Other text.", page = 1))))
        assertEquals(before, reader.read(email), "someone else's source is not this identity's change")

        val seq = ArcadeCycleSequencer(db).allocateCycle(email)!!
        ArcadeHorizonStructureStore(db).createItem(email, seq, ItemKind.TASK, "Send the budget memo")
        val moved = reader.read(email)!!.changesSince(before).map { it.part }
        assertTrue("lastCycleSeq" in moved && "Task" in moved && "Phrase" in moved && "Asserted" in moved, moved.toString())
    }

    @Test
    fun `an unknown identity has no marker`() { assertEquals(null, ChangeMarkerReader(db).read("debug+nobody@test.alfrd.internal")) }

    // ── 2. A Horizon with no message writes nothing ───────────────────────────

    @Test
    fun `assembling with no message, and a whole tick, leave every type's count and the cycle counter unchanged`() = runBlocking<Unit> {
        val structure = ArcadeHorizonStructureStore(db)
        val seq = ArcadeCycleSequencer(db).allocateCycle(email)!!
        val project = structure.createContainer(email, seq, ContainerKind.PROJECT, "Garden")!!
        structure.createItem(email, seq, ItemKind.TASK, "Order the seeds", projectUid = project.uid)
        structure.createItem(email, seq, ItemKind.FACT, "The shed key is under the blue pot")
        structure.setFocus(email, seq, project.uid, FocusMode.PINNED)
        structure.setFocus(email, seq, project.uid, FocusMode.NUDGE, weight = 0.5)
        ingest("gdrive:two")
        val before = fingerprint()
        assertTrue(before["FOCUSES_ON"]!! >= 1 && before["ProcessedRequest"] == 0L || before.containsKey("ProcessedRequest"))

        val outcome = ArcadeHorizonAssembler(db).assemble(email, before["lastCycleSeq"]!! + 1)
        assertTrue(outcome is AssembleOutcome.Assembled, outcome.toString())
        assertEquals(before, fingerprint(), "assemble wrote something")

        val dir = tempDir.toPath().resolve("out")
        val report = tick(dir, { Instant.parse("2026-11-07T12:00:00Z") }).tickOnce()
        assertTrue(report.rendered, report.toString())
        assertEquals(before, fingerprint(), "a tick wrote something to the graph")
        assertTrue(report.itemsInView > 0, "the focused project's item should be in view")
    }

    // ── 3. Self-state ─────────────────────────────────────────────────────────

    @Test
    fun `the situation says which model server is down and carries the rest of the self-state`() {
        val marker = ChangeMarkerReader(db).read(email)!!
        val state = SelfState(
            tickEnabled = true, intervalSeconds = 300, tickAt = "2026-11-07T12:00:00Z", renderReason = "marker changed",
            servers = FakeProbe(setOf(8081, 8084)).servers(), memory = FakeProbe(emptySet()).memory(),
            notion = NotionState(true, null, 180, "2026-11-07T11:59:00Z", 0, 1), hermesInFlight = mapOf("document_summary" to 2),
            budgets = BudgetDefaults(10, 25, 600, 12_000, true), marker = marker,
            changes = listOf(ChangeMarker.Change("Source", "2", "3")),
        )
        val text = DirectorPromptRenderer.situation(state, ZoneOffset.UTC)
        assertTrue("text reader :8081 up" in text, text)
        assertTrue("vision reader :8083 DOWN" in text, text)
        assertTrue("OCR :8084 up" in text, text)
        assertTrue("GPU 10.0 GB of 96.0 GB" in text && "system RAM 20.0 GB of 31.0 GB" in text, text)
        assertTrue("running, polls every 180 s, last poll 2026-11-07T11:59:00Z, 0 failures in a row, 1 cycles held under lease" in text, text)
        assertTrue("2 document_summary" in text, text)
        assertTrue("up to 10 tasks per cycle (hard ceiling 25); plan call timeout 600 s, at most 12000 tokens (as configured)" in text, text)
        assertTrue("Changed since the last tick: Source 2 -> 3." in text, text)
        assertTrue("Tick: enabled, every 300 s; this one at 2026-11-07 12:00:00 Z (marker changed)" in text, text)
    }

    @Test
    fun `the first tick says there is nothing to compare with instead of listing every part as changed`() {
        val marker = ChangeMarkerReader(db).read(email)!!
        val state = SelfState(true, 300, "2026-11-07T12:00:00Z", "first tick", FakeProbe(setOf(8081)).servers(), MemoryUse(), NotionState(false), emptyMap(),
            BudgetDefaults(10, 25, 600, 12_000, false), marker, marker.changesSince(null))
        val text = DirectorPromptRenderer.situation(state, ZoneOffset.UTC)
        assertTrue("Changed since the last tick: nothing to compare with: this is the first tick since the engine started." in text, text)
    }

    @Test
    fun `the real probe reports a listening server up and a closed port down, within its timeout`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex -> ex.sendResponseHeaders(200, -1); ex.close() }
        server.start()
        val closed = ServerSocket(0).use { it.localPort }
        try {
            val probe = SystemProbe(listOf("up one" to server.address.port, "gone" to closed), Duration.ofMillis(800), tempDir.toPath(), tempDir.toPath().resolve("no-meminfo"))
            val started = System.nanoTime()
            val status = probe.servers()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
            assertEquals(listOf(true, false), status.map { it.up })
            assertEquals(MemoryUse(), probe.memory(), "nothing readable: every field null, no exception")
        } finally { server.stop(0) }
    }

    @Test
    fun `memory is read from sysfs and meminfo, taking the largest GPU`() {
        val drm = tempDir.toPath().resolve("drm")
        fun card(name: String, used: Long, total: Long) {
            val dev = Files.createDirectories(drm.resolve("$name/device"))
            Files.writeString(dev.resolve("mem_info_vram_used"), "$used\n"); Files.writeString(dev.resolve("mem_info_vram_total"), "$total\n")
        }
        card("card0", 1, 1000); card("card1", 5_000, 90_000)
        val meminfo = tempDir.toPath().resolve("meminfo")
        Files.writeString(meminfo, "MemTotal:       1000 kB\nMemFree:         100 kB\nMemAvailable:    400 kB\n")
        val m = SystemProbe(emptyList(), Duration.ofMillis(100), drm, meminfo).memory()
        assertEquals(MemoryUse(5_000, 90_000, 600 * 1024, 1000 * 1024), m)
    }

    // ── 4. Schedule and output ────────────────────────────────────────────────

    @Test
    fun `it renders on the first tick, on a change, and hourly, and otherwise only reads the marker`() = runBlocking<Unit> {
        var now = Instant.parse("2026-11-07T12:00:00Z")
        val t = tick(tempDir.toPath().resolve("out"), { now })

        assertEquals("first tick", t.tickOnce().reason)
        now = now.plusSeconds(300)
        val quiet = t.tickOnce()
        assertFalse(quiet.rendered, quiet.toString()); assertEquals("no change", quiet.reason)
        ingest("gdrive:new")
        now = now.plusSeconds(300)
        val changed = t.tickOnce()
        assertTrue(changed.rendered); assertEquals("marker changed", changed.reason)
        assertTrue(changed.changes.any { it.part == "Source" })
        now = now.plusSeconds(3_600)
        assertEquals("hourly refresh", t.tickOnce().reason)

        val snap = t.snapshot()
        assertEquals(4, snap.ticks)
        assertEquals("hourly refresh", snap.lastRenderReason)
        assertTrue(snap.render!!.contains("Your situation:") && snap.render!!.contains("Contextual awareness"))
    }

    @Test
    fun `latest and a timestamped copy are written, only the newest keep are kept, and the files are private`() = runBlocking<Unit> {
        var now = Instant.parse("2026-11-07T12:00:00Z")
        val dir = tempDir.toPath().resolve("out")
        val t = tick(dir, { now }, keep = 3)
        repeat(5) { i -> ingest("gdrive:n$i"); now = now.plusSeconds(60); t.tickOnce() }

        val copies = Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.startsWith("tick-") }.sorted().toList() }
        assertEquals(3, copies.size, copies.toString())
        assertEquals("tick-20261107T120500Z.md", copies.last())
        assertEquals(Files.readString(dir.resolve("latest.md")), Files.readString(dir.resolve(copies.last())))
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("latest.md"))))
        assertEquals(0, Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() }.toInt(), "no temp file is left behind")
    }

    @Test
    fun `an identity with no User vertex is reported, not created`() = runBlocking<Unit> {
        val t = DirectorTick("debug+ghost@test.alfrd.internal", "ghost", Duration.ofSeconds(60), tempDir.toPath().resolve("o"), ChangeMarkerReader(db), ArcadeHorizonAssembler(db),
            FakeProbe(emptySet()), { NotionState(false) }, { emptyMap() }, { BudgetDefaults(10, 25, 600, 12_000, false) })
        val report = t.tickOnce()
        assertEquals("identity has no User vertex", report.error)
        assertEquals(0L, db.query("sql", "SELECT count(*) AS n FROM User WHERE email = 'debug+ghost@test.alfrd.internal'").use { it.next().getProperty<Number>("n").toLong() })
    }

    // ── 4b. The environment gate ──────────────────────────────────────────────

    private fun boot(env: Map<String, String>): DirectorTickStartup {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return bootstrapDirectorTick(scope, db, NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), env, FakeProbe(emptySet()), start = false)
        } finally { scope.cancel() }
    }

    @Test
    fun `it is off unless DIRECTOR_TICK_IDENTITY is set, and refuses owner-jacob and a bad interval`() {
        assertTrue((boot(emptyMap()) as DirectorTickStartup.Disabled).let { !it.misconfigured })
        assertTrue((boot(mapOf("DIRECTOR_TICK_IDENTITY" to "owner-jacob")) as DirectorTickStartup.Disabled).misconfigured)
        assertTrue((boot(mapOf("DIRECTOR_TICK_IDENTITY" to "tick-a", "DIRECTOR_TICK_INTERVAL_S" to "5")) as DirectorTickStartup.Disabled).misconfigured)
        assertTrue((boot(mapOf("DIRECTOR_TICK_IDENTITY" to "tick-a", "DIRECTOR_TICK_INTERVAL_S" to "soon")) as DirectorTickStartup.Disabled).misconfigured)
        val running = boot(mapOf("DIRECTOR_TICK_IDENTITY" to "tick-a", "DIRECTOR_TICK_DIR" to tempDir.resolve("o").toString())) as DirectorTickStartup.Running
        assertEquals(300L, running.tick.interval.seconds, "the default interval is 300 s")
        assertEquals(60L, (boot(mapOf("DIRECTOR_TICK_IDENTITY" to "tick-a", "DIRECTOR_TICK_INTERVAL_S" to "60")) as DirectorTickStartup.Running).tick.interval.seconds)
    }
}
