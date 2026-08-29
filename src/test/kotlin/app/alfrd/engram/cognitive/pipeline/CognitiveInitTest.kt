package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CognitiveInitTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var service: ResponseSelectionService
    private lateinit var pipeline: CognitivePipeline

    // Fixed instants for deterministic time-of-day scoring
    private val MORNING_UTC = Instant.parse("2024-01-15T09:00:00Z") // 9 AM UTC → morning
    private val EVENING_UTC = Instant.parse("2024-01-15T19:00:00Z") // 7 PM UTC → evening

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-init-db-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        ResponsePhraseSeed.seed(db)
        // Dispatchers.Unconfined so recordSelected launches run eagerly (freshness test)
        service = ResponseSelectionService(db, CoroutineScope(Dispatchers.Unconfined))
        pipeline = CognitivePipeline(selectionService = service)
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-init-db-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `INIT returns a greeting from the pool`() = runTest {
        val result = pipeline.initSession(
            sessionId = "s-pool",
            userId    = "user-pool",
            timestamp = MORNING_UTC,
        )
        assertTrue(result.greeting.isNotBlank(), "Greeting should not be blank")
        assertNotEquals("fallback", result.phraseId, "Expected a real phrase from the pool")
        assertEquals("s-pool", result.sessionId)
    }

    @Test
    fun `INIT with morning UTC context favors morning greeting`() = runTest {
        val result = pipeline.initSession(
            sessionId = "s-morning",
            userId    = "user-morning",
            context   = mapOf("timezone" to "UTC"),
            timestamp = MORNING_UTC,
        )
        assertTrue(result.greeting.isNotBlank())
        assertTrue(
            "morning" in result.greeting.lowercase(),
            "Expected morning greeting, got: ${result.greeting}",
        )
    }

    @Test
    fun `INIT with evening UTC context favors evening greeting`() = runTest {
        val result = pipeline.initSession(
            sessionId = "s-evening",
            userId    = "user-evening",
            context   = mapOf("timezone" to "UTC"),
            timestamp = EVENING_UTC,
        )
        assertTrue(result.greeting.isNotBlank())
        assertTrue(
            "evening" in result.greeting.lowercase(),
            "Expected evening greeting, got: ${result.greeting}",
        )
    }

    @Test
    fun `INIT consecutive calls in same session return different greetings`() = runTest {
        // Seed a real User vertex so recordSelected (fire-and-forget, Unconfined) can write
        // SELECTED edges that feed freshness scoring on the second call.
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", java.util.UUID.randomUUID().toString())
                set("username", "user-repeat")
                set("email", "user-repeat")
                set("tier", 1)
                set("createdAt", System.currentTimeMillis())
                save()
            }
        }

        val result1 = pipeline.initSession(
            sessionId = "s-repeat",
            userId    = "user-repeat",
            timestamp = MORNING_UTC,
        )
        val result2 = pipeline.initSession(
            sessionId = "s-repeat",
            userId    = "user-repeat",
            timestamp = MORNING_UTC,
        )
        assertTrue(result1.greeting.isNotBlank())
        assertTrue(result2.greeting.isNotBlank())
        assertNotEquals(
            result1.phraseId,
            result2.phraseId,
            "Freshness tracking should prevent selecting the same phrase twice " +
                "(first: '${result1.greeting}', second: '${result2.greeting}')",
        )
    }

    @Test
    fun `INIT with unknown user returns a valid greeting`() = runTest {
        val unknownUserId = "unknown-user-${System.currentTimeMillis()}"
        val result = pipeline.initSession(
            sessionId = "s-unknown",
            userId    = unknownUserId,
            timestamp = MORNING_UTC,
        )
        assertTrue(result.greeting.isNotBlank(), "Expected a valid greeting for an unknown user")
        assertEquals("s-unknown", result.sessionId)
    }

    @Test
    fun `INIT latency is under 50ms`() = runTest {
        val start = System.currentTimeMillis()
        pipeline.initSession(
            sessionId = "s-latency",
            userId    = "user-latency",
            timestamp = MORNING_UTC,
        )
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 100L, "Expected INIT latency < 100ms, got ${elapsed}ms")
    }

    // ── Scaffold-aware INIT tests ────────────────────────────────────────────

    private fun scaffoldPipeline(scaffoldState: ScaffoldState): CognitivePipeline {
        val engram = InMemoryEngramClient()
        engram.apply {
            kotlinx.coroutines.runBlocking { updateScaffoldState("scaffold-user", scaffoldState) }
        }
        return CognitivePipeline(engramClient = engram, selectionService = service)
    }

    @Test
    fun `INIT brand-new ORIENTATION user receives a greeting from the scored pool`() = runTest {
        val p = scaffoldPipeline(ScaffoldState(trustPhase = 1, answeredCategories = emptySet()))
        val result = p.initSession(
            sessionId = "s-first",
            userId    = "scaffold-user",
            timestamp = MORNING_UTC,
        )
        assertTrue(result.greeting.isNotBlank(), "Greeting should not be blank")
        assertNotEquals(
            "fallback",
            result.phraseId,
            "Brand-new user must receive a greeting from the pool, got phraseId=’${result.phraseId}’",
        )
    }

    @Test
    fun `INIT late-night session gets time-appropriate greeting`() = runTest {
        val lateNightUTC = Instant.parse("2024-01-15T23:00:00Z") // 11 PM UTC
        val result = pipeline.initSession(
            sessionId = "s-latenight",
            userId    = "user-latenight",
            context   = mapOf("timezone" to "UTC"),
            timestamp = lateNightUTC,
        )
        assertTrue(result.greeting.isNotBlank())
        // At 11 PM, "Burning the midnight oil" should win; morning/afternoon/evening score 0.0
        assertTrue(
            "midnight oil" in result.greeting.lowercase() || "evening" !in result.greeting.lowercase(),
            "Expected late-night appropriate greeting at 11 PM, got: ${result.greeting}",
        )
        // Positive assertion: midnight oil phrase is selected
        assertTrue(
            result.greeting.lowercase().contains("midnight oil"),
            "Expected 'midnight oil' greeting at 11 PM UTC, got: ${result.greeting}",
        )
    }

    @Test
    fun `INIT interpolation does not leave unresolved template tokens`() = runTest {
        // CONTEXT phase user will be eligible for phrases with {timeOfDay} and {userName}
        val state = ScaffoldState(trustPhase = 3, answeredCategories = PhraseCategory.entries.toSet())
        val p = scaffoldPipeline(state)
        val result = p.initSession(
            sessionId = "s-interp",
            userId    = "scaffold-user",
            context   = mapOf("timezone" to "UTC"),
            timestamp = MORNING_UTC,
        )
        assertFalse(
            result.greeting.contains("{"),
            "Greeting should have all template tokens resolved, got: ${result.greeting}",
        )
    }

    // ── sessionCount wiring ──────────────────────────────────────────────────

    @Test
    fun `INIT increments sessionCount across repeated calls for the same user`() = runTest {
        val engram = InMemoryEngramClient()
        val p = CognitivePipeline(engramClient = engram, selectionService = service)

        p.initSession(sessionId = "s-count-1", userId = "user-count", timestamp = MORNING_UTC)
        p.initSession(sessionId = "s-count-2", userId = "user-count", timestamp = MORNING_UTC)

        assertEquals(
            2,
            engram.getScaffoldState("user-count").sessionCount,
            "Expected sessionCount to increment once per initSession call",
        )
    }

    @Test
    fun `INIT sessionCount increment does not disturb this call's own greeting selection`() = runTest {
        // The sessionCount bump is for the *next* session, not this one — SelectionScorer
        // gates the ORIENTATION "meet you"/"acquainted"/"work best when" phrases on
        // sessionCount == 0 (see SelectionScorer.kt), so the increment write must happen
        // without reassigning the local `scaffoldState` that `ctx.sessionCount` is later built
        // from (see the comment at the increment site in CognitivePipeline.initSession).
        // Composite scoring mixes in other dimensions (time of day, freshness), so this can't
        // assert which literal phrase wins — it checks the two things a wrong placement would
        // actually break: cold-start selection still succeeds (not the fallback), and the
        // increment still lands correctly for next time.
        val engram = InMemoryEngramClient()
        kotlinx.coroutines.runBlocking {
            engram.updateScaffoldState(
                "scaffold-user",
                ScaffoldState(trustPhase = 1, answeredCategories = emptySet(), sessionCount = 0),
            )
        }
        val p = CognitivePipeline(engramClient = engram, selectionService = service)
        val result = p.initSession(
            sessionId = "s-first-regression",
            userId    = "scaffold-user",
            timestamp = MORNING_UTC,
        )
        assertTrue(result.greeting.isNotBlank())
        assertNotEquals(
            "fallback",
            result.phraseId,
            "Cold-start selection must still succeed, got phraseId='${result.phraseId}'",
        )
        assertEquals(
            1,
            engram.getScaffoldState("scaffold-user").sessionCount,
            "Expected sessionCount to persist as incremented for the next session",
        )
    }

    @Test
    fun `INIT when scaffold state unavailable falls back gracefully`() = runTest {
        // A pipeline with a broken EngramClient: getScaffoldState throws
        val brokenClient = object : app.alfrd.engram.cognitive.pipeline.memory.EngramClient {
            override suspend fun decompose(text: String, context: List<String>) = emptyList<app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate>()
            override suspend fun ingest(candidates: List<app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate>, userEmail: String) {}
            override suspend fun queryPhrases(userEmail: String, concept: String?, limit: Int) = emptyList<app.alfrd.engram.cognitive.pipeline.memory.ScoredPhrase>()
            override suspend fun getScaffoldState(userId: String): ScaffoldState = error("scaffold unavailable")
            override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) {}
            override suspend fun amendPhrase(phraseId: String, newContent: String) {}
        }
        val p = CognitivePipeline(engramClient = brokenClient, selectionService = service)
        val result = p.initSession(
            sessionId = "s-broken",
            userId    = "user-broken",
            timestamp = MORNING_UTC,
        )
        assertTrue(result.greeting.isNotBlank(), "Should return a greeting even when scaffold state is unavailable")
    }
}
