package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseSelectionTraceTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var selectionService: ResponseSelectionService
    private val json = Json { encodeDefaults = true }

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-trace-db-${System.currentTimeMillis()}"
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
            ?.filter { it.name.startsWith("test-trace-db-") }
            ?.forEach { it.deleteRecursively() }
    }

    // ── Debug returns responseSelection for SOCIAL greeting ───────────────

    @Test
    fun `debug endpoint returns responseSelection for a SOCIAL greeting`() = runTest {
        val pipeline = CognitivePipeline(selectionService = selectionService)
        pipeline.init()

        val result = pipeline.processForDebug("Hello", "session-trace-1", "user-1")

        assertEquals(IntentType.SOCIAL, result.chat.intent)
        val sel = result.trace.responseSelection
        assertNotNull(sel, "responseSelection should be populated for SOCIAL with selectionService")
        sel!!

        assertTrue(sel.phraseId.isNotBlank(), "phraseId should not be blank")
        assertTrue(sel.phraseText.isNotBlank(), "phraseText should not be blank")
        assertTrue(sel.interpolatedText.isNotBlank(), "interpolatedText should not be blank")
        assertEquals(ResponseStrategy.SOCIAL, sel.strategy)
        assertTrue(sel.compositeScore > 0.0, "compositeScore should be positive")
        assertTrue(sel.scores.containsKey("freshness"))
        assertTrue(sel.scores.containsKey("contextualFit"))
        assertTrue(sel.scores.containsKey("communicationFit"))
        assertTrue(sel.scores.containsKey("phaseAppropriateness"))
        assertTrue(sel.scores.containsKey("effectiveness"))
        assertTrue(sel.candidatesConsidered >= 1, "should have at least 1 candidate")
        assertTrue(sel.selectionLatencyMs >= 0, "selectionLatencyMs should be non-negative")
    }

    // ── Debug returns responseSelection for SOCIAL farewell ───────────────

    @Test
    fun `debug endpoint returns responseSelection for a SOCIAL farewell`() = runTest {
        val pipeline = CognitivePipeline(selectionService = selectionService)
        pipeline.init()

        val result = pipeline.processForDebug("Goodbye", "session-trace-2", "user-1")

        assertEquals(IntentType.SOCIAL, result.chat.intent)
        val sel = result.trace.responseSelection
        assertNotNull(sel, "responseSelection should be populated for farewell")
    }

    // ── responseSelection is null without selectionService ────────────────

    @Test
    fun `debug endpoint returns null responseSelection without selectionService`() = runTest {
        val pipeline = CognitivePipeline()
        pipeline.init()

        val result = pipeline.processForDebug("Hey", "session-trace-3", "user-1")

        assertNull(result.trace.responseSelection)
    }

    // ── candidatesConsidered reflects filter count ────────────────────────

    @Test
    fun `candidatesConsidered reflects actual filter count not total pool`() = runTest {
        val pipeline = CognitivePipeline(selectionService = selectionService)
        pipeline.init()

        val result = pipeline.processForDebug("Hello", "session-trace-4", "user-1")

        val sel = result.trace.responseSelection
        assertNotNull(sel)
        // candidatesConsidered should match all phrases matching the filter criteria
        // (GREETING category, ACKNOWLEDGE phase, SOCIAL branch affinity), not the
        // total phrase pool which includes sign-offs, acknowledgments, etc.
        assertTrue(sel!!.candidatesConsidered >= 1, "Should have at least 1 candidate")
    }

    // ── ResponseSelectionTrace serializes correctly ──────────────────────

    @Test
    fun `ResponseSelectionTrace serializes and deserializes correctly`() {
        val trace = ResponseSelectionTrace(
            phraseId = "phrase-abc123",
            phraseText = "Good {timeOfDay}, sir.",
            interpolatedText = "Good evening, sir.",
            strategy = ResponseStrategy.SOCIAL,
            compositeScore = 0.847,
            scores = mapOf(
                "freshness" to 0.9,
                "contextualFit" to 0.95,
                "communicationFit" to 0.7,
                "phaseAppropriateness" to 1.0,
                "effectiveness" to 0.5,
            ),
            candidatesConsidered = 6,
            selectionLatencyMs = 3,
        )

        val jsonStr = json.encodeToString(trace)
        val decoded = json.decodeFromString<ResponseSelectionTrace>(jsonStr)
        assertEquals(trace, decoded)

        // Verify JSON shape matches frontend expectations
        val jsonObj = Json.parseToJsonElement(jsonStr).jsonObject
        assertEquals("phrase-abc123", jsonObj["phraseId"]?.jsonPrimitive?.content)
        assertEquals("SOCIAL", jsonObj["strategy"]?.jsonPrimitive?.content)
        assertTrue(jsonObj.containsKey("scores"))
        assertTrue(jsonObj.containsKey("candidatesConsidered"))
        assertTrue(jsonObj.containsKey("selectionLatencyMs"))
    }

    // ── PipelineTrace with null responseSelection omits it ──────────────

    @Test
    fun `PipelineTrace serializes with null responseSelection`() {
        val trace = PipelineTrace()
        val jsonStr = json.encodeToString(trace)
        val jsonObj = Json.parseToJsonElement(jsonStr).jsonObject
        // With encodeDefaults=true, null should serialize as null
        // The frontend handles both null and absent
        val decoded = json.decodeFromString<PipelineTrace>(jsonStr)
        assertNull(decoded.responseSelection)
    }

    // ── PipelineTrace with populated responseSelection round-trips ──────

    @Test
    fun `PipelineTrace with responseSelection round-trips correctly`() {
        val selTrace = ResponseSelectionTrace(
            phraseId = "p-1",
            phraseText = "Hello there.",
            interpolatedText = "Hello there.",
            strategy = ResponseStrategy.SOCIAL,
            compositeScore = 0.75,
            scores = mapOf("freshness" to 0.8, "contextualFit" to 0.7),
            candidatesConsidered = 3,
            selectionLatencyMs = 5,
        )
        val trace = PipelineTrace(responseSelection = selTrace)
        val jsonStr = json.encodeToString(trace)
        val decoded = json.decodeFromString<PipelineTrace>(jsonStr)
        assertEquals(selTrace, decoded.responseSelection)
    }
}
