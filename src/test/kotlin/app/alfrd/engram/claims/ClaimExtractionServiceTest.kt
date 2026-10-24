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
import com.arcadedb.database.Database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/** A scripted model: what each pass says about each chunk, and a record of every call. */
class FakeClaimModel(
    private val name: String = "Qwen3.6-35B-A3B-UD-Q4_K_M.gguf",
    private val script: (ChunkRecord, Int) -> List<PassClaim>,
) : ClaimPassModel {
    override val endpoint: String = "http://127.0.0.1:8081/v1"
    override var promptVersion: String = "test-v1"
    val calls = ArrayList<Pair<Int, Int>>()
    var failOn: ((ChunkRecord, Int) -> Throwable?)? = null
    override suspend fun extract(source: SourceRecord, chunk: ChunkRecord, pass: Int): PassResult {
        calls += chunk.ordinal to pass
        failOn?.invoke(chunk, pass)?.let { throw it }
        return PassResult(script(chunk, pass), name)
    }
}

/**
 * The extraction contract against a real embedded graph and a scripted model: independent passes, establishment only on agreement,
 * retained disagreements, provenance, run and model stamps, canonical claimants, idempotent reruns, later-pass Re-checking.
 * The model is a script, so these prove the contract around the model, not what Qwen does with a passage.
 */
class ClaimExtractionServiceTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private lateinit var claims: ClaimStore
    private val ana = "debug+claims-a@test.alfrd.internal"
    private val resolver = ClaimantResolver(mapOf("Portland Public Schools" to listOf("PPS", "the District")))

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-claims-test-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana)
        store = SourceStore(db)
        claims = ClaimStore(db)
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private fun service(batch: Int = 10): ClaimExtractionService {
        val structure = ArcadeHorizonStructureStore(db)
        val commit = SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db))
        return ClaimExtractionService(store, claims, commit, resolver, batch)
    }

    private fun n(type: String): Long = db.query("sql", "SELECT count(*) AS n FROM `$type`").use { (it.next().getProperty<Number>("n")).toLong() }
    private fun everything() = listOf("Source", "Chunk", "Phrase", "ASSERTS", "QUOTES", "CONTAINS", "ExtractionRun", "ClaimDisagreement", "User").associateWith { n(it) }

    private fun ingest(vararg texts: String, locator: String = "gdrive:doc1"): SourceRecord = store.ingest(
        ana,
        SourceInput("document", locator, "District document", texts.joinToString("\n"), chunks = texts.mapIndexed { i, t -> ChunkDraft("page ${i + 1}", t, page = i + 1) }),
    ).source

    private fun c(quote: String, claimant: String? = "PPS", place: String? = null, kind: String = "number", subject: String = "") =
        PassClaim(quote, claimant, subject, kind, place)

    private val both: (List<PassClaim>) -> (ChunkRecord, Int) -> List<PassClaim> = { cs -> { _, _ -> cs } }

    // ── Establishment on agreement, provenance, stamps, canonical claimants ──

    @Test
    fun `a claim both passes report is established as a Settled fact tied to its chunk, page and run, with the canonical claimant`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools. The Board voted 5 to 2 on Tuesday.")
        val model = FakeClaimModel { _, pass ->
            if (pass == 1) listOf(c("Scenario A closes 14 schools.", "PPS", subject = "Scenario A")) else listOf(c("Scenario A closes 14 schools.", "the District", subject = "scenario A closures"))
        }
        val report = service().run(ana, s.uid, "run-1", model)

        assertEquals("complete", report.status)
        assertEquals(1, report.claimsEstablished)
        assertEquals(0, report.disagreements)
        val claim = claims.claims(ana, s.uid).single()
        assertEquals("Scenario A closes 14 schools.", claim.quote)
        assertEquals("Portland Public Schools", claim.claimant, "the alias is resolved to the canonical claimant")
        assertEquals("Settled", claim.checkState)
        assertEquals("run-1", claim.runId)
        assertEquals("Scenario A", claim.subject)
        // Provenance: the item quotes its chunk, whose page and source are known, and the link carries the claimant and the run.
        val trace = store.provenanceOf(ana, claim.uid).single()
        assertEquals("page 1", trace.location); assertEquals(s.uid, trace.sourceUid); assertEquals("gdrive:doc1", trace.sourceLocator)
        assertEquals("Portland Public Schools", trace.claimant); assertEquals(ClaimExtractionService.WRITER, trace.writer)
        db.query("sql", "SELECT runId, draftQuote FROM QUOTES").use { rs ->
            val row = rs.next(); assertEquals("run-1", row.getProperty<String>("runId")); assertEquals("Scenario A closes 14 schools.", row.getProperty<String>("draftQuote"))
        }
    }

    @Test
    fun `a run records the model the server reported, the endpoint, the prompt version and its counts`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.")
        service().run(ana, s.uid, "run-stamp", FakeClaimModel(name = "Qwen3.6-35B-A3B-Q8_0.gguf", script = both(listOf(c("Scenario A closes 14 schools.")))))
        val run = claims.run(ana, "run-stamp")!!
        assertEquals("complete", run.status)
        assertEquals("Qwen3.6-35B-A3B-Q8_0.gguf", run.modelName)
        assertEquals("http://127.0.0.1:8081/v1", run.modelEndpoint); assertEquals("test-v1", run.promptVersion); assertEquals(2, run.passes)
        assertEquals(1, run.chunksDone); assertEquals(1, run.report!!.claimsEstablished)
        assertEquals("Qwen3.6-35B-A3B", run.report!!.modelExpectation)
    }

    @Test
    fun `the two passes of every chunk are separate calls, in order`() = runBlocking<Unit> {
        val s = ingest("One. Two.", "Three. Four.")
        val model = FakeClaimModel(script = both(emptyList()))
        service().run(ana, s.uid, "run-calls", model)
        assertEquals(listOf(0 to 1, 0 to 2, 1 to 1, 1 to 2), model.calls)
    }

    // ── Disagreement retention ───────────────────────────────────────────────

    @Test
    fun `a claim the passes disagree on is retained with both versions and is never established`() = runBlocking<Unit> {
        val s = ingest("The Board voted 5 to 2 on Tuesday. Scenario A closes 14 schools.")
        val model = FakeClaimModel { _, pass ->
            if (pass == 1) listOf(c("The Board voted 5 to 2 on Tuesday.", "The Board"), c("Scenario A closes 14 schools."))
            else listOf(c("The Board voted 5 to 2 on Tuesday.", "Superintendent Smith"), c("Scenario A closes 14 schools."))
        }
        val report = service().run(ana, s.uid, "run-d", model)
        assertEquals(1, report.claimsEstablished); assertEquals(1, report.disagreements)
        assertEquals(listOf("Scenario A closes 14 schools."), claims.claims(ana, s.uid).map { it.quote }, "only the agreed claim exists as an item")
        val d = claims.disagreements(ana, s.uid, "run-d").single()
        assertEquals("passes differ on the claimant", d.reason)
        assertEquals("The Board", d.versionA!!.claimant); assertEquals("Superintendent Smith", d.versionB!!.claimant)
        assertEquals(1L, n("Phrase"), "the disputed claim was not written as an item")
    }

    @Test
    fun `a quote that is not in the chunk is retained as a rejection, even when both passes make it up`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.")
        val made = c("Scenario A closes 20 schools.")
        val report = service().run(ana, s.uid, "run-q", FakeClaimModel(script = both(listOf(made))))
        assertEquals(0, report.claimsEstablished); assertEquals(2, report.quotesRejected)
        assertEquals(0L, n("Phrase"))
        assertEquals(2, claims.disagreements(ana, s.uid).size)
    }

    @Test
    fun `a pass that fails leaves its chunk with no claims and the run goes on to the next chunk`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.", "Scenario B closes 11 schools.")
        val model = FakeClaimModel { ch, _ -> listOf(c(if (ch.ordinal == 0) "Scenario A closes 14 schools." else "Scenario B closes 11 schools.")) }
        model.failOn = { ch, pass -> if (ch.ordinal == 0 && pass == 2) IllegalStateException("model said nonsense about Scenario A") else null }
        val report = service().run(ana, s.uid, "run-f", model)
        assertEquals(1, report.chunksFailed); assertEquals(1, report.claimsEstablished)
        assertEquals(listOf("Scenario B closes 11 schools."), claims.claims(ana, s.uid).map { it.quote })
        assertTrue(report.failures.single().contains("IllegalStateException"))
        assertFalse(report.failures.single().contains("Scenario A"), "a failure names the class, never the passage")
    }

    // ── Model guard ──────────────────────────────────────────────────────────

    @Test
    fun `a server that reports another model stops the run before anything is written`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.")
        val report = service().run(ana, s.uid, "run-m", FakeClaimModel(name = "gpt-oss-20b", script = both(listOf(c("Scenario A closes 14 schools.")))))
        assertEquals("stopped", report.status)
        assertTrue(report.failures.single().contains("gpt-oss-20b"))
        assertEquals(0L, n("Phrase")); assertEquals(0L, n("ClaimDisagreement"))
        assertEquals("stopped", claims.run(ana, "run-m")!!.status)
    }

    @Test
    fun `a run made without the model check says so in its record`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.")
        val report = service().run(ana, s.uid, "run-any", FakeClaimModel(name = "local", script = both(listOf(c("Scenario A closes 14 schools.")))), expectModel = null)
        assertEquals("complete", report.status); assertNull(report.modelExpectation); assertEquals("local", report.modelName)
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    fun `rerunning a finished run id calls no model and writes nothing`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools. The Board voted 5 to 2 on Tuesday.", "Scenario B closes 11 schools.")
        val model = FakeClaimModel { ch, pass -> if (ch.ordinal == 0) listOf(c("Scenario A closes 14 schools."), c("The Board voted 5 to 2 on Tuesday.", if (pass == 1) "A" else "B")) else listOf(c("Scenario B closes 11 schools.")) }
        val first = service().run(ana, s.uid, "run-same", model)
        val before = everything(); val calls = model.calls.size
        val again = service().run(ana, s.uid, "run-same", model)
        assertEquals(first, again)
        assertEquals(calls, model.calls.size, "no model call")
        assertEquals(before, everything(), "no row of any kind")
    }

    @Test
    fun `a stopped run continues from the chunk it reached and does not redo the earlier ones`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.", "Scenario B closes 11 schools.", "Status quo closes none.")
        val answer = { ch: ChunkRecord, _: Int -> listOf(c(listOf("Scenario A closes 14 schools.", "Scenario B closes 11 schools.", "Status quo closes none.")[ch.ordinal])) }
        val interrupted = FakeClaimModel(script = answer)
        interrupted.failOn = { ch, _ -> if (ch.ordinal == 2) CancellationException("shutdown") else null }
        assertThrows<CancellationException> { service(batch = 1).run(ana, s.uid, "run-resume", interrupted) }
        assertEquals("running", claims.run(ana, "run-resume")!!.status)
        assertEquals(2, claims.run(ana, "run-resume")!!.chunksDone)

        val resumed = FakeClaimModel(script = answer)
        val report = service(batch = 1).run(ana, s.uid, "run-resume", resumed)
        assertEquals(listOf(2 to 1, 2 to 2), resumed.calls, "only the unfinished chunk is read again")
        assertEquals("complete", report.status); assertEquals(3, report.claimsEstablished)
        assertEquals(3L, n("Phrase"))
    }

    @Test
    fun `a second run over the same source with the same answers adds nothing and changes nothing`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.")
        val model = FakeClaimModel(script = both(listOf(c("Scenario A closes 14 schools."))))
        service().run(ana, s.uid, "run-1", model)
        val phrases = n("Phrase"); val quotes = n("QUOTES")
        val second = service().run(ana, s.uid, "run-2", model)
        assertEquals(0, second.claimsEstablished); assertEquals(1, second.claimsAlreadyThere); assertEquals(0, second.claimsRechecking)
        assertEquals(phrases, n("Phrase")); assertEquals(quotes, n("QUOTES"))
        assertEquals("run-1", claims.claims(ana, s.uid).single().runId, "the claim keeps the run that established it")
        assertEquals("Settled", claims.claims(ana, s.uid).single().checkState)
    }

    @Test
    fun `a run id cannot be reused for another source`() = runBlocking<Unit> {
        val a = ingest("Scenario A closes 14 schools.", locator = "gdrive:a")
        val b = ingest("Scenario B closes 11 schools.", locator = "gdrive:b")
        service().run(ana, a.uid, "run-x", FakeClaimModel(script = both(emptyList())))
        assertThrows<IllegalArgumentException> { runBlocking { service().run(ana, b.uid, "run-x", FakeClaimModel(script = both(emptyList()))) } }
    }

    // ── Later-pass Re-checking ───────────────────────────────────────────────

    @Test
    fun `a later run whose passes do not both reproduce an established claim moves it to Re-checking and keeps it`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools. Scenario B closes 11 schools.")
        val q1 = "Scenario A closes 14 schools."; val q2 = "Scenario B closes 11 schools."
        service().run(ana, s.uid, "run-1", FakeClaimModel(script = both(listOf(c(q1), c(q2)))))
        assertEquals(setOf("Settled"), claims.claims(ana, s.uid).map { it.checkState }.toSet())

        // Run 2: the second pass no longer reports the first claim.
        val report = service().run(ana, s.uid, "run-2", FakeClaimModel { _, pass -> if (pass == 1) listOf(c(q1), c(q2)) else listOf(c(q2)) })
        assertEquals(1, report.claimsRechecking)
        val byQuote = claims.claims(ana, s.uid).associateBy { it.quote }
        assertEquals(2, byQuote.size, "nothing was removed or duplicated")
        assertEquals("Re-checking", byQuote.getValue(q1).checkState); assertEquals("run-2", byQuote.getValue(q1).recheckRunId)
        assertEquals("run-1", byQuote.getValue(q1).runId, "its establishing run is unchanged")
        assertEquals("Settled", byQuote.getValue(q2).checkState)
        assertEquals(1, store.provenanceOf(ana, byQuote.getValue(q1).uid).size, "its location is intact")
        val retained = claims.disagreements(ana, s.uid, "run-2")
        assertEquals(setOf("only pass 1 reported it", "a later run's two passes did not both reproduce an established claim"), retained.map { it.reason }.toSet())
    }

    @Test
    fun `Re-checking is not undone by a later run that agrees again`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.")
        val q = "Scenario A closes 14 schools."
        service().run(ana, s.uid, "run-1", FakeClaimModel(script = both(listOf(c(q)))))
        service().run(ana, s.uid, "run-2", FakeClaimModel(script = both(emptyList())))
        assertEquals("Re-checking", claims.claims(ana, s.uid).single().checkState)
        service().run(ana, s.uid, "run-3", FakeClaimModel(script = both(listOf(c(q)))))
        assertEquals("Re-checking", claims.claims(ana, s.uid).single().checkState, "only a person settles it again")
        assertEquals(1L, n("Phrase"))
    }

    @Test
    fun `a chunk a pass failed on does not question the claims an earlier run established there`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools.")
        service().run(ana, s.uid, "run-1", FakeClaimModel(script = both(listOf(c("Scenario A closes 14 schools.")))))
        val flaky = FakeClaimModel(script = both(emptyList())); flaky.failOn = { _, pass -> if (pass == 2) IllegalStateException("x") else null }
        service().run(ana, s.uid, "run-2", flaky)
        assertEquals("Settled", claims.claims(ana, s.uid).single().checkState)
    }

    @Test
    fun `a later run that attributes an established passage to another claimant questions it and does not add a second claim`() = runBlocking<Unit> {
        val s = ingest("The Board voted 5 to 2 on Tuesday.")
        val q = "The Board voted 5 to 2 on Tuesday."
        service().run(ana, s.uid, "run-1", FakeClaimModel(script = both(listOf(c(q, "The Board")))))
        val report = service().run(ana, s.uid, "run-2", FakeClaimModel(script = both(listOf(c(q, "Superintendent Smith")))))
        assertEquals(1L, n("Phrase")); assertEquals(1L, n("QUOTES"))
        assertEquals(0, report.claimsEstablished); assertEquals(1, report.claimsRechecking)
        val claim = claims.claims(ana, s.uid).single()
        assertEquals("Re-checking", claim.checkState); assertEquals("The Board", claim.claimant, "the established claimant is untouched")
        val d = claims.disagreements(ana, s.uid, "run-2").single()
        assertEquals("The Board", d.versionA!!.claimant); assertEquals("Superintendent Smith", d.versionB!!.claimant)
    }

    // ── Repeated assertions ──────────────────────────────────────────────────

    @Test
    fun `an assertion repeated in another chunk is one claim with two locations, in one batch or across batches`() = runBlocking<Unit> {
        val q = "Scenario A closes 14 schools."
        for (batch in listOf(10, 1)) {
            val s = ingest("$q Page one.", "Page two. $q", "Page three has nothing.", locator = "gdrive:rep$batch")
            val report = service(batch).run(ana, s.uid, "run-rep-$batch", FakeClaimModel { ch, _ -> if (ch.ordinal < 2) listOf(c(q)) else emptyList() })
            val claim = claims.claims(ana, s.uid).single()
            assertEquals(1, report.claimsEstablished, "batch $batch"); assertEquals(1, report.locationsAdded, "batch $batch")
            assertEquals(listOf("page 1", "page 2"), store.provenanceOf(ana, claim.uid).map { it.location }.sorted(), "batch $batch")
        }
    }

    @Test
    fun `the same passage from a second source is a second claim in its own source`() = runBlocking<Unit> {
        val q = "Scenario A closes 14 schools."
        val a = ingest(q, locator = "gdrive:one"); val b = ingest(q, locator = "gdrive:two")
        service().run(ana, a.uid, "run-a", FakeClaimModel(script = both(listOf(c(q)))))
        service().run(ana, b.uid, "run-b", FakeClaimModel(script = both(listOf(c(q)))))
        assertEquals(2L, n("Phrase"))
        assertEquals(1, claims.claims(ana, a.uid).size); assertEquals(1, claims.claims(ana, b.uid).size)
    }

    // ── Long documents ───────────────────────────────────────────────────────

    @Test
    fun `a long document is read chunk by chunk in order, in batches, and every chunk is done`() = runBlocking<Unit> {
        val texts = (0 until 60).map { "Statement $it: school $it closes in 20${10 + it % 20}." }
        val s = ingest(*texts.toTypedArray(), locator = "gdrive:long")
        val model = FakeClaimModel { ch, _ -> listOf(c(texts[ch.ordinal].substringAfter(": "), "PPS")) }
        val progress = ArrayList<Int>()
        val report = service(batch = 7).run(ana, s.uid, "run-long", model) { progress += it.chunksDone }
        assertEquals(60, report.chunksDone); assertEquals(60, report.chunksTotal); assertEquals(60, report.claimsEstablished)
        assertEquals(120, model.calls.size)
        // The two passes of a chunk run together, so only the chunks' order is fixed, not the order within a chunk.
        assertEquals((0 until 60).flatMap { listOf(it to 1, it to 2) }, model.calls.sortedWith(compareBy({ it.first }, { it.second })))
        assertEquals((0 until 60).toList(), model.calls.map { it.first }.distinct())
        assertEquals(listOf(7, 14, 21, 28, 35, 42, 49, 56, 60), progress)
        assertEquals(60, claims.claims(ana, s.uid).size)
        assertEquals(1.0, report.agreementRate)
    }

    @Test
    fun `the agreement rate is a diagnostic over what the passes reported, and absent when nothing was reported`() = runBlocking<Unit> {
        val s = ingest("Scenario A closes 14 schools. Scenario B closes 11 schools.")
        val report = service().run(ana, s.uid, "run-rate", FakeClaimModel { _, pass ->
            if (pass == 1) listOf(c("Scenario A closes 14 schools."), c("Scenario B closes 11 schools.")) else listOf(c("Scenario A closes 14 schools."))
        })
        assertEquals(1, report.claimsAgreed); assertEquals(2, report.claimsReported); assertEquals(0.5, report.agreementRate)
        val none = ingest("Nothing to claim here.", locator = "gdrive:none")
        assertNull(service().run(ana, none.uid, "run-none", FakeClaimModel(script = both(emptyList()))).agreementRate)
    }
}
