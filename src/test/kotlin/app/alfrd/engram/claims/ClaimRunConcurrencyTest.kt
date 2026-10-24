package app.alfrd.engram.claims

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.db.ChunkDraft
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceRecord
import app.alfrd.engram.db.SourceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** The two passes of a chunk run together, a failing pass ends its chunk and cancels the other, and a run can be asked to stop between batches. */
class ClaimRunConcurrencyTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var store: SourceStore
    private lateinit var claims: ClaimStore
    private lateinit var service: (Int) -> ClaimExtractionService
    private val ana = "debug+concurrency-a@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-concurrency-test-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        val db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana)
        store = SourceStore(db); claims = ClaimStore(db)
        service = { batch ->
            val structure = ArcadeHorizonStructureStore(db)
            val commit = SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db))
            ClaimExtractionService(store, claims, commit, ClaimantResolver(), batch)
        }
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private fun ingest(vararg texts: String): SourceRecord = store.ingest(
        ana, SourceInput("document", "gdrive:c", "Doc", texts.joinToString("\n"), chunks = texts.mapIndexed { i, t -> ChunkDraft("page ${i + 1}", t, page = i + 1) }),
    ).source

    private val name = "Qwen3.6-35B-A3B-UD-Q4_K_M.gguf"

    @Test
    fun `both passes of a chunk are in flight at the same time`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.")
        val inFlight = AtomicInteger(0); val peak = AtomicInteger(0)
        val bothStarted = CompletableDeferred<Unit>()
        val model = object : ClaimPassModel {
            override val endpoint: String? = null
            override val promptVersion = "test"
            override suspend fun extract(source: SourceRecord, chunk: ChunkRecord, pass: Int): PassResult {
                peak.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
                if (inFlight.get() == 2) bothStarted.complete(Unit)
                // Neither pass can finish until the other has started: a sequential loop would never get here.
                withTimeout(5_000) { bothStarted.await() }
                inFlight.decrementAndGet()
                return PassResult(listOf(PassClaim("Scenario A closes 14 schools.", null, "A", "NUMBER")), name)
            }
        }
        val report = service(10).run(ana, s.uid, "run-par", model)
        assertEquals(2, peak.get())
        assertEquals(1, report.claimsEstablished); assertEquals(0, report.disagreements)
    }

    @Test
    fun `a pass that fails ends its chunk, cancels the other pass, and leaves the next chunk to run`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.", "Scenario B closes 11 schools.")
        val cancelled = AtomicInteger(0)
        val model = object : ClaimPassModel {
            override val endpoint: String? = null
            override val promptVersion = "test"
            override suspend fun extract(source: SourceRecord, chunk: ChunkRecord, pass: Int): PassResult {
                if (chunk.ordinal == 0) {
                    if (pass == 1) throw IllegalStateException("model stalled")
                    try { kotlinx.coroutines.awaitCancellation() } catch (e: kotlinx.coroutines.CancellationException) { cancelled.incrementAndGet(); throw e }
                }
                return PassResult(listOf(PassClaim("Scenario B closes 11 schools.", null, "B", "NUMBER")), name)
            }
        }
        val report = service(10).run(ana, s.uid, "run-fail", model)
        assertEquals(1, cancelled.get(), "the sibling pass was cancelled, not waited for")
        assertEquals(1, report.chunksFailed); assertEquals(listOf("chunk 0: pass 1 failed (IllegalStateException)"), report.failures)
        assertEquals(1, report.claimsEstablished, "the next chunk was still read")
    }

    @Test
    fun `a run asked to stop finishes its batch, is recorded as stopped, and continues from there with the same id`() = runBlocking<Unit> {
        val texts = (0 until 6).map { "Statement $it: school $it closes in 20${10 + it}." }
        val s = ingest(*texts.toTypedArray())
        val seen = ArrayList<Int>()
        val model = object : ClaimPassModel {
            override val endpoint: String? = null
            override val promptVersion = "test"
            override suspend fun extract(source: SourceRecord, chunk: ChunkRecord, pass: Int): PassResult {
                synchronized(seen) { seen += chunk.ordinal }
                return PassResult(listOf(PassClaim(texts[chunk.ordinal].substringAfter(": "), null, "s", "NUMBER")), name)
            }
        }
        var stop = true
        val first = service(2).run(ana, s.uid, "run-stop", model, shouldStop = { stop })
        assertEquals("stopped", first.status)
        assertEquals(2, first.chunksDone, "the batch in hand was finished and kept")
        assertEquals(2, claims.claims(ana, s.uid).size)
        assertEquals("stopped", claims.run(ana, "run-stop")?.status)
        stop = false
        val second = service(2).run(ana, s.uid, "run-stop", model)
        assertEquals("complete", second.status); assertEquals(6, second.chunksDone); assertEquals(6, claims.claims(ana, s.uid).size)
        assertTrue(seen.filter { it < 2 }.size == 4, "chunks 0 and 1 were not read again: $seen")
    }
}
