package app.alfrd.engram.cognitive.pipeline

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
        assertTrue(elapsed < 50L, "Expected INIT latency < 50ms, got ${elapsed}ms")
    }
}
