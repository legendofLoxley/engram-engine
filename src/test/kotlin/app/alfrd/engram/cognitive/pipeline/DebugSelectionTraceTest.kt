package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Integration tests that verify [PipelineTrace.responseSelection] is populated correctly
 * when the pipeline is wired with a real [ResponseSelectionService] backed by an
 * in-process ArcadeDB test database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugSelectionTraceTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var engramClient: InMemoryEngramClient
    private lateinit var pipeline: CognitivePipeline

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-debug-trace-db-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        ResponsePhraseSeed.seed(db)
        val selectionService = ResponseSelectionService(db)
        engramClient = InMemoryEngramClient()
        pipeline = CognitivePipeline(
            engramClient = engramClient,
            selectionService = selectionService,
        )
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-debug-trace-db-") }
            ?.forEach { it.deleteRecursively() }
    }

    // ── SOCIAL greeting → responseSelection populated ─────────────────────────

    @Test
    fun `responseSelection populated for SOCIAL greeting interaction`() = runTest {
        val result = pipeline.processForDebug("Hey", "session-g1", "user-g1")

        assertEquals(IntentType.SOCIAL, result.chat.intent)
        val sel = result.trace.responseSelection
        assertNotNull(sel, "responseSelection should be populated for SOCIAL greeting")
        sel!!

        assertTrue(sel.phraseId.isNotBlank(), "phraseId should not be blank")
        assertTrue(sel.phraseText.isNotBlank(), "phraseText should not be blank")
        assertTrue(sel.interpolatedText.isNotBlank(), "interpolatedText should not be blank")
        assertEquals(ResponseStrategy.SOCIAL, sel.strategy)
        assertTrue(sel.compositeScore > 0.0, "compositeScore should be positive")

        // All 5 score dimensions must be present with camelCase keys
        assertTrue(sel.scores.containsKey("freshness"), "scores must contain freshness")
        assertTrue(sel.scores.containsKey("contextualFit"), "scores must contain contextualFit")
        assertTrue(sel.scores.containsKey("communicationFit"), "scores must contain communicationFit")
        assertTrue(sel.scores.containsKey("phaseAppropriateness"), "scores must contain phaseAppropriateness")
        assertTrue(sel.scores.containsKey("effectiveness"), "scores must contain effectiveness")

        assertTrue(sel.candidatesConsidered >= 1, "candidatesConsidered should be at least 1")
        assertTrue(sel.selectionLatencyMs >= 0, "selectionLatencyMs should be non-negative")
    }

    // ── SOCIAL thanks → responseSelection with RECEIPT phrase ──────────────────

    @Test
    fun `responseSelection populated for SOCIAL thanks interaction`() = runTest {
        val result = pipeline.processForDebug("Thanks", "session-t1", "user-t1")

        assertEquals(IntentType.SOCIAL, result.chat.intent)
        val sel = result.trace.responseSelection
        assertNotNull(sel, "responseSelection should be populated for SOCIAL thanks")
        sel!!

        assertTrue(sel.phraseId.isNotBlank())
        assertTrue(sel.phraseText.isNotBlank())
        assertEquals(ResponseStrategy.SOCIAL, sel.strategy)
        assertTrue(sel.compositeScore > 0.0)
        assertTrue(sel.candidatesConsidered >= 1)

        // Score keys must use camelCase
        assertTrue(sel.scores.containsKey("contextualFit"))
        assertTrue(sel.scores.containsKey("communicationFit"))
        assertTrue(sel.scores.containsKey("phaseAppropriateness"))
    }

    // ── candidatesConsidered reflects filter count not total pool size ─────────

    @Test
    fun `candidatesConsidered reflects actual filter count not total pool`() = runTest {
        // Run two different utterances and capture their candidate counts
        val greeting = pipeline.processForDebug("Good morning", "session-c1", "user-c1")
        val goodbye  = pipeline.processForDebug("Goodbye", "session-c2", "user-c2")

        val greetingSel = greeting.trace.responseSelection
        val goodbyeSel  = goodbye.trace.responseSelection

        assertNotNull(greetingSel)
        assertNotNull(goodbyeSel)

        // Greeting and goodbye select from different categories so their
        // candidatesConsidered should differ (different filter sizes)
        assertNotEquals(
            greetingSel!!.candidatesConsidered,
            goodbyeSel!!.candidatesConsidered,
            "Greeting and goodbye should filter different candidate counts",
        )
    }

    // ── selectionLatencyMs is within a realistic wall-clock range ─────────────

    @Test
    fun `selectionLatencyMs is within realistic wall-clock range`() = runTest {
        val result = pipeline.processForDebug("Hello", "session-l1", "user-l1")

        val sel = result.trace.responseSelection
        assertNotNull(sel)
        sel!!

        // Should complete in under 5 seconds on any CI machine
        assertTrue(sel.selectionLatencyMs in 0..5_000,
            "selectionLatencyMs ${sel.selectionLatencyMs} should be between 0 and 5000 ms")
    }

    // ── retrievalCoverage populated alongside responseSelection ───────────────

    @Test
    fun `retrievalCoverage reflects the same phrase-pool pick as responseSelection`() = runTest {
        val result = pipeline.processForDebug("Hey", "session-cov1", "user-cov1")

        val sel = result.trace.responseSelection
        assertNotNull(sel)
        val coverage = result.trace.retrievalCoverage
        assertNotNull(coverage, "retrievalCoverage should be populated for every turn")
        coverage!!

        assertTrue(coverage.playFired, "a phrase was selected — playFired should be true")
        assertEquals(sel!!.compositeScore, coverage.activationMass)
        assertTrue(coverage.coverage in 0.0..1.0)
    }

    // ── Trust phase, now loaded on the main turn path, actually gates candidates ──

    @Test
    fun `trust phase loaded on the main path changes the GREETING candidate pool`() = runTest {
        // A fresh ORIENTATION user (default InMemoryEngramClient ScaffoldState) vs. a
        // long-standing UNDERSTANDING (4) user: ResponseSelectionService's phaseAffinity gate
        // (filterCandidates) only activates when ctx.trustPhase is non-null — before this task,
        // the main turn path never loaded it, so every user saw the same unfiltered pool. Now
        // it's loaded, the two phases should see different candidate counts for the same query.
        engramClient.updateScaffoldState("user-trust-high", ScaffoldState(trustPhase = 4))

        val orientation = pipeline.processForDebug("Hey", "session-trust-low", "user-trust-low")
        val understanding = pipeline.processForDebug("Hey", "session-trust-high", "user-trust-high")

        val low = orientation.trace.responseSelection
        val high = understanding.trace.responseSelection
        assertNotNull(low)
        assertNotNull(high)

        assertNotEquals(
            low!!.candidatesConsidered,
            high!!.candidatesConsidered,
            "ORIENTATION and UNDERSTANDING should see different GREETING candidate pools now that trust phase is loaded",
        )
    }
}
