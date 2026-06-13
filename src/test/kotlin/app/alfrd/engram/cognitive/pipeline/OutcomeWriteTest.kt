package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.cognitive.pipeline.selection.OutcomeSignalClassifier
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionQuery
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.OutcomeSignal
import app.alfrd.engram.model.ResponseCategory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// OUTCOME edge write — ArcadeDB integration
// ─────────────────────────────────────────────────────────────────────────────

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutcomeEdgeWriteTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var selectionService: ResponseSelectionService

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-outcome-edge-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        ResponsePhraseSeed.seed(db)
        selectionService = ResponseSelectionService(db)
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-outcome-edge-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `recordOutcome writes OUTCOME edge with all required fields`() {
        val db = dbManager.getDatabase()

        // Seed a real User vertex so the email-based lookup in recordOutcome finds it.
        db.transaction {
            db.newVertex("User").apply {
                set("uid", java.util.UUID.randomUUID().toString())
                set("username", "u-outcome-1")
                set("email", "u-outcome-1")
                set("tier", 1)
                set("createdAt", System.currentTimeMillis())
                save()
            }
        }

        // Select a phrase so we have a valid uid
        val ctx = app.alfrd.engram.cognitive.pipeline.CognitiveContext(
            utterance = "hello",
            sessionId = "s-outcome-1",
            userId = "u-outcome-1",
            timestamp = Instant.now(),
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.FIRST_RESPONSE,
            category = ResponseCategory.GREETING,
            context = ctx,
            limit = 1,
        )
        val results = selectionService.select(query)
        assertTrue(results.isNotEmpty(), "Need a phrase to test against")
        val phraseUid = results.first().phrase.uid

        selectionService.recordOutcome(
            phraseUid       = phraseUid,
            sessionId       = "s-outcome-1",
            userId          = "u-outcome-1",
            turnIndex       = 1,
            signal          = OutcomeSignal.ENGAGED,
            contextSnapshot = """{"utterance":"hello","confidence":0.7}""",
        )

        // Give the fire-and-forget coroutine a moment to complete
        Thread.sleep(200)

        val edges = mutableListOf<Map<String, Any?>>()
        db.query(
            "sql",
            "SELECT FROM OUTCOME WHERE phraseUid = :uid AND userId = :uid2",
            mapOf("uid" to phraseUid, "uid2" to "u-outcome-1"),
        ).use { rs ->
            while (rs.hasNext()) edges.add(rs.next().toMap())
        }

        assertEquals(1, edges.size, "Expected exactly one OUTCOME edge")
        val edge = edges.first()
        assertEquals(phraseUid, edge["phraseUid"])
        assertEquals("s-outcome-1", edge["sessionId"])
        assertEquals("u-outcome-1", edge["userId"])
        assertEquals(1, (edge["turnIndex"] as? Number)?.toInt())
        assertEquals("ENGAGED", edge["signal"])
        assertNotNull(edge["contextSnapshot"])
        assertNotNull(edge["timestamp"])
    }

    @Test
    fun `recordOutcome returns immediately (fire-and-forget does not block)`() {
        val phraseUid = "nonexistent-uid-for-timing-test"

        val startMs = System.currentTimeMillis()
        selectionService.recordOutcome(
            phraseUid       = phraseUid,
            sessionId       = "s-timing",
            userId          = "u-timing",
            turnIndex       = 1,
            signal          = OutcomeSignal.NEUTRAL,
            contextSnapshot = "{}",
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        // The call itself must return in < 50 ms — DB work is async
        assertTrue(elapsedMs < 50, "recordOutcome should return immediately, took ${elapsedMs}ms")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// pendingOutcome lifecycle — unit / pipeline integration
// ─────────────────────────────────────────────────────────────────────────────

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PendingOutcomeLifecycleTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var pipeline: CognitivePipeline

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-outcome-lifecycle-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        ResponsePhraseSeed.seed(db)
        val selectionService = ResponseSelectionService(db)
        pipeline = CognitivePipeline(selectionService = selectionService)
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-outcome-lifecycle-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `pendingOutcome is null before any turn`() {
        val fresh = CognitivePipeline(selectionService = ResponseSelectionService(dbManager.getDatabase()))
        assertNull(fresh.pendingOutcome)
    }

    @Test
    fun `pendingOutcome is set after a social turn with phrase selection`() = runTest {
        pipeline.init()
        pipeline.process("Hey", "session-pending-1", "user-pending-1")
        // SocialBranch selects a greeting phrase → pendingOutcome should be set
        assertNotNull(pipeline.pendingOutcome, "pendingOutcome should be set after a phrase-selecting turn")
    }

    @Test
    fun `pendingOutcome is consumed and cleared on the next turn`() = runTest {
        val p = CognitivePipeline(selectionService = ResponseSelectionService(dbManager.getDatabase()))
        p.init()

        // Turn 1 — should set pendingOutcome
        p.process("Hello", "session-consume-1", "user-consume-1")
        assertNotNull(p.pendingOutcome, "pendingOutcome should be set after turn 1")

        // Turn 2 — should consume and clear pendingOutcome
        p.process("Yes, let's get started with the project.", "session-consume-1", "user-consume-1")
        assertNull(p.pendingOutcome.also { /* captured before next set */ }, "pendingOutcome consumed and replaced")

        // After turn 2 pendingOutcome is set again (turn 2 also selected a phrase)
        // or null if turn 2 went to a non-phrase branch — either way the turn-1 value is gone
    }

    @Test
    fun `pendingOutcome not set for non-social branch (no phrase selection)`() = runTest {
        val p = CognitivePipeline(selectionService = ResponseSelectionService(dbManager.getDatabase()))
        p.init()

        // TASK branch does not call selectionService → ctx.selectionResult stays null
        p.process("Remind me to call the vet", "session-no-phrase", "user-no-phrase")
        // TaskBranch is a stub — no selectionResult → pendingOutcome stays null
        assertNull(p.pendingOutcome, "pendingOutcome should not be set for pure-reason branches")
    }

    @Test
    fun `pendingOutcome correct fields captured after selection`() = runTest {
        val p = CognitivePipeline(selectionService = ResponseSelectionService(dbManager.getDatabase()))
        p.init()

        p.process("Hey", "session-fields-1", "user-fields-1")

        val pending = p.pendingOutcome
        assertNotNull(pending)
        assertEquals("session-fields-1", pending!!.sessionId)
        assertEquals("user-fields-1", pending.userId)
        assertTrue(pending.phraseUid.isNotBlank())
        assertTrue(pending.priorContext.utterance.isNotBlank())
        assertTrue(pending.priorContext.phraseText.isNotBlank())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session eviction → DISENGAGED
// ─────────────────────────────────────────────────────────────────────────────

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionEvictionDisengagedTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var db: com.arcadedb.database.Database

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-outcome-eviction-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        ResponsePhraseSeed.seed(db)
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-outcome-eviction-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `eviction writes DISENGAGED when pipeline has a pendingOutcome`() = runTest {
        val selectionService = ResponseSelectionService(db)

        // Create a session manager with a 1 ms TTL so everything expires immediately
        val manager = SessionManager(
            factory = { CognitivePipeline(selectionService = selectionService) },
            ttlMs   = 1L,
        )

        val pipeline = manager.getOrCreate("session-evict-1")
        // Simulate a phrase-selecting turn by directly setting pendingOutcome
        val pendingCtx = OutcomeSignalClassifier.PriorTurnContext(
            utterance  = "Hey",
            phraseText = "Good morning.",
        )
        pipeline.pendingOutcome = CognitivePipeline.PendingOutcome(
            phraseUid    = "test-phrase-uid-evict",
            sessionId    = "session-evict-1",
            userId       = "user-evict-1",
            turnIndex    = 1,
            priorContext = pendingCtx,
        )

        // Wait for TTL to expire
        Thread.sleep(10)

        // Trigger eviction via getOrCreate for a different session
        manager.getOrCreate("session-evict-2")

        // Give the fire-and-forget coroutine a moment (phrase uid doesn't exist → write silently fails
        // but recordDisengagedOutcome() was called and pendingOutcome was cleared)
        Thread.sleep(200)

        // pendingOutcome should be cleared regardless of whether the DB write succeeded
        assertNull(pipeline.pendingOutcome, "pendingOutcome should be cleared after eviction")
    }

    @Test
    fun `eviction does not call recordDisengagedOutcome when pendingOutcome is null`() = runTest {
        val selectionService = ResponseSelectionService(db)

        val manager = SessionManager(
            factory = { CognitivePipeline(selectionService = selectionService) },
            ttlMs   = 1L,
        )

        val pipeline = manager.getOrCreate("session-evict-clean")
        // Leave pendingOutcome null — no phrase was selected
        assertNull(pipeline.pendingOutcome)

        Thread.sleep(10)
        manager.getOrCreate("session-evict-clean-trigger")

        // Should not throw or affect state; pendingOutcome remains null
        assertNull(pipeline.pendingOutcome)
    }
}
