package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.ResponseCategory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Unit tests for the retrieval-coverage readout Script.run() attaches to
 * [CognitiveContext.retrievalCoverage] — telemetry only, one readout per [RetrievalIntent] path.
 */
class ScriptTest {

    private lateinit var engramClient: InMemoryEngramClient
    private lateinit var script: Script

    @BeforeEach
    fun setup() {
        engramClient = InMemoryEngramClient()
        script = Script(engramClient)
    }

    private fun ctx(utterance: String = "hi") =
        CognitiveContext(utterance = utterance, sessionId = "s", userId = "u", userEmail = "u@test.internal")

    // ── None ─────────────────────────────────────────────────────────────────

    @Test
    fun `None intent yields full trivial coverage with no gaps`() = runTest {
        val c = ctx()
        script.run(c, RetrievalIntent.None)

        val coverage = c.retrievalCoverage
        assertNotNull(coverage)
        assertEquals(1.0, coverage!!.coverage)
        assertFalse(coverage.playFired)
        assertEquals(1.0, coverage.conceptResolutionRatio)
        assertTrue(coverage.gaps.isEmpty())
    }

    // ── PhrasePool: no selection service wired ──────────────────────────────

    @Test
    fun `PhrasePool intent with no selection service reports zero coverage and a gap`() = runTest {
        val c = ctx()
        script.run(c, RetrievalIntent.PhrasePool(branch = BranchType.SOCIAL))

        val coverage = c.retrievalCoverage
        assertNotNull(coverage)
        assertFalse(coverage!!.playFired)
        assertEquals(0.0, coverage.activationMass)
        assertEquals(0.0, coverage.coverage)
        assertTrue(coverage.gaps.any { it.contains("selection service unavailable") })
    }

    // ── PhrasePool: real selection service ──────────────────────────────────

    @Test
    fun `PhrasePool intent with a real match reports playFired and positive activation mass`() = runTest {
        val testDbPath = "./data/test-script-phrasepool-db-${System.currentTimeMillis()}"
        val dbManager = DatabaseManager(testDbPath)
        try {
            val db = dbManager.getDatabase()
            SchemaBootstrap.bootstrap(db)
            ResponsePhraseSeed.seed(db)
            val selectionService = ResponseSelectionService(db)
            val scriptWithSelection = Script(engramClient, selectionService)

            val c = ctx()
            val result = scriptWithSelection.run(
                c,
                RetrievalIntent.PhrasePool(
                    branch = BranchType.SOCIAL,
                    category = ResponseCategory.GREETING,
                    expressionPhase = ExpressionPhase.FIRST_RESPONSE,
                ),
            )

            assertTrue(result.lines.isNotEmpty(), "a greeting phrase should have been retrieved")

            val coverage = c.retrievalCoverage
            assertNotNull(coverage)
            assertTrue(coverage!!.playFired, "a scored phrase was selected — playFired should be true")
            assertTrue(coverage.activationMass > 0.0, "activation mass should reflect the composite score")
            assertEquals(c.selectionResult?.compositeScore, coverage.activationMass)
            assertTrue(coverage.coverage in 0.0..1.0)
        } finally {
            dbManager.close()
            File("./data").listFiles()
                ?.filter { it.name.startsWith("test-script-phrasepool-db-") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    // ── MemoryQuery: matches found ───────────────────────────────────────────

    @Test
    fun `MemoryQuery intent with matches reports playFired true and ratio of returned over requested`() = runTest {
        engramClient.ingest(
            listOf(
                PhraseCandidate("I love hiking on weekends", "user", PhraseCategory.PREFERENCE),
                PhraseCandidate("I go hiking every Saturday", "user", PhraseCategory.ROUTINE),
            ),
            "u@test.internal",
        )

        val c = ctx()
        val result = script.run(c, RetrievalIntent.MemoryQuery(hint = "hiking", limit = 5))

        assertEquals(2, result.lines.size)
        val coverage = c.retrievalCoverage
        assertNotNull(coverage)
        assertTrue(coverage!!.playFired)
        assertEquals(0.5, coverage.activationMass, 0.0001) // InMemoryEngramClient fixes trust=0.5
        assertEquals(2.0 / 5.0, coverage.conceptResolutionRatio, 0.0001)
        assertTrue(coverage.gaps.any { it.contains("resolved 2/5") })
    }

    // ── MemoryQuery: no matches ──────────────────────────────────────────────

    @Test
    fun `MemoryQuery intent with no matches reports playFired false and a gap`() = runTest {
        val c = ctx()
        val result = script.run(c, RetrievalIntent.MemoryQuery(hint = "nonexistent topic", limit = 5))

        assertTrue(result.lines.isEmpty())
        val coverage = c.retrievalCoverage
        assertNotNull(coverage)
        assertFalse(coverage!!.playFired)
        assertEquals(0.0, coverage.activationMass)
        assertEquals(0.0, coverage.coverage)
        assertTrue(coverage.gaps.any { it.contains("returned no phrases") })
    }

    // ── Correction: existing phrase amended ──────────────────────────────────

    @Test
    fun `Correction intent that amends an existing phrase reports full coverage`() = runTest {
        engramClient.ingest(
            listOf(PhraseCandidate("My favorite color is blue", "user", PhraseCategory.PREFERENCE)),
            "u@test.internal",
        )

        val c = ctx()
        script.run(
            c,
            RetrievalIntent.Correction(supersededValue = "favorite color is blue", newFact = "My favorite color is green"),
        )

        val coverage = c.retrievalCoverage
        assertNotNull(coverage)
        assertEquals(1.0, coverage!!.coverage)
        assertTrue(coverage.playFired)
        assertEquals(1.0, coverage.conceptResolutionRatio)
        assertTrue(coverage.gaps.isEmpty())
    }

    // ── Correction: no existing phrase, fresh ingestion ──────────────────────

    @Test
    fun `Correction intent with no existing match reports partial coverage and a gap`() = runTest {
        val c = ctx()
        script.run(
            c,
            RetrievalIntent.Correction(supersededValue = "something never said before", newFact = "My favorite color is green"),
        )

        val coverage = c.retrievalCoverage
        assertNotNull(coverage)
        assertTrue(coverage!!.playFired)
        assertEquals(0.0, coverage.conceptResolutionRatio)
        assertTrue(coverage.gaps.isNotEmpty())
    }
}
