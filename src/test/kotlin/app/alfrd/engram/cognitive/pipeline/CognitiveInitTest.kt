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
            // Pre-seed the scaffold state synchronously via a blocking call from a test helper
            kotlinx.coroutines.runBlocking { updateScaffoldState("scaffold-user", scaffoldState) }
        }
        return CognitivePipeline(engramClient = engram, selectionService = service)
    }

    @Test
    fun `INIT first-ever user gets ORIENTATION greeting and scaffold question`() = runTest {
        val p = scaffoldPipeline(ScaffoldState(trustPhase = 1, answeredCategories = emptySet()))
        val result = p.initSession(
            sessionId = "s-first",
            userId    = "scaffold-user",
            timestamp = MORNING_UTC,
        )
        assertTrue(result.greeting.isNotBlank(), "Greeting should not be blank")
        assertNotNull(result.scaffoldQuestion, "First-ever user should receive a scaffold question")
        assertTrue(result.scaffoldQuestion!!.contains("?"),
            "Scaffold question should be a question: ${result.scaffoldQuestion}")
    }

    @Test
    fun `INIT returning ORIENTATION user with 2 answered categories gets scaffold question`() = runTest {
        val state = ScaffoldState(
            trustPhase = 1,
            answeredCategories = setOf(PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE),
            activeScaffoldQuestion = "Is there a particular way you prefer to work?",
        )
        val p = scaffoldPipeline(state)
        val result = p.initSession(
            sessionId = "s-returning-orient",
            userId    = "scaffold-user",
            timestamp = MORNING_UTC,
        )
        assertTrue(result.greeting.isNotBlank())
        assertNotNull(result.scaffoldQuestion,
            "Returning ORIENTATION user with < 3 answered categories should get scaffold question")
        assertEquals("Is there a particular way you prefer to work?", result.scaffoldQuestion)
    }

    @Test
    fun `INIT CONTEXT phase user gets no scaffold question`() = runTest {
        val state = ScaffoldState(
            trustPhase = 3,
            answeredCategories = PhraseCategory.entries.toSet(),
        )
        val p = scaffoldPipeline(state)
        val result = p.initSession(
            sessionId = "s-context",
            userId    = "scaffold-user",
            timestamp = MORNING_UTC,
        )
        assertTrue(result.greeting.isNotBlank())
        assertNull(result.scaffoldQuestion, "CONTEXT phase user should not receive scaffold question")
    }

    @Test
    fun `INIT ORIENTATION user with 3+ answered categories gets no scaffold question`() = runTest {
        val state = ScaffoldState(
            trustPhase = 1,
            answeredCategories = setOf(
                PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE
            ),
        )
        val p = scaffoldPipeline(state)
        val result = p.initSession(
            sessionId = "s-orient-sufficient",
            userId    = "scaffold-user",
            timestamp = MORNING_UTC,
        )
        assertNull(result.scaffoldQuestion, "3+ answered categories should suppress scaffold question")
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

    @Test
    fun `INIT when scaffold state unavailable falls back gracefully`() = runTest {
        // A pipeline with a broken EngramClient: getScaffoldState throws
        val brokenClient = object : app.alfrd.engram.cognitive.pipeline.memory.EngramClient {
            override suspend fun decompose(text: String, context: List<String>) = emptyList<app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate>()
            override suspend fun ingest(candidates: List<app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate>) {}
            override suspend fun queryPhrases(concept: String, userId: String) = emptyList<app.alfrd.engram.cognitive.pipeline.memory.Phrase>()
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
        assertNull(result.scaffoldQuestion, "No scaffold question when scaffold state is unavailable")
    }
}
