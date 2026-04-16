package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.api.ScaffoldStateStore
import app.alfrd.engram.api.UpdateScaffoldStateRequest
import app.alfrd.engram.cognitive.pipeline.memory.HttpEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// ScaffoldStateStore — ArcadeDB persistence tests
// ─────────────────────────────────────────────────────────────────────────────

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScaffoldStatePersistenceTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var store: ScaffoldStateStore

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-scaffold-persist-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        ResponsePhraseSeed.seed(dbManager.getDatabase())
        store = ScaffoldStateStore(dbManager.getDatabase())
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-scaffold-persist-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `GET returns default ORIENTATION state for new user`() {
        val state = store.get("brand-new-user")
        assertEquals("brand-new-user", state.userId)
        assertEquals("ORIENTATION", state.trustPhase)
        assertTrue(state.answeredCategories.isEmpty(), "New user should have no answered categories")
        assertNull(state.activeScaffoldQuestion)
        assertEquals(0, state.sessionCount)
        assertNull(state.lastInteractionAt)
        assertTrue(state.phaseTransitions.isEmpty())
    }

    @Test
    fun `PUT persists answered category, retrievable on next GET`() {
        val userId = "persist-category-user"

        store.upsert(userId, store.get(userId).copy(
            answeredCategories = setOf("IDENTITY"),
            sessionCount       = 1,
        ))

        val retrieved = store.get(userId)
        assertTrue("IDENTITY" in retrieved.answeredCategories,
            "Expected IDENTITY in answered categories, got: ${retrieved.answeredCategories}")
        assertEquals(1, retrieved.sessionCount)
    }

    @Test
    fun `PUT persists activeScaffoldQuestion, null clears it`() {
        val userId = "active-question-user"

        // Set a question
        store.upsert(userId, store.get(userId).copy(
            activeScaffoldQuestion = "What is your role?",
        ))
        assertEquals("What is your role?", store.get(userId).activeScaffoldQuestion)

        // Clear it
        store.upsert(userId, store.get(userId).copy(activeScaffoldQuestion = null))
        assertNull(store.get(userId).activeScaffoldQuestion)
    }

    @Test
    fun `multiple answered categories are all persisted`() {
        val userId = "multi-category-user"

        store.upsert(userId, store.get(userId).copy(
            answeredCategories = setOf("IDENTITY", "EXPERTISE", "PREFERENCE"),
            trustPhase         = "WORKING_RHYTHM",
        ))

        val retrieved = store.get(userId)
        assertEquals(setOf("IDENTITY", "EXPERTISE", "PREFERENCE"), retrieved.answeredCategories)
        assertEquals("WORKING_RHYTHM", retrieved.trustPhase)
    }

    @Test
    fun `scaffold state survives simulated container restart`() {
        val restartDbPath = "./data/test-restart-${System.currentTimeMillis()}"

        // Write in first DB instance
        val db1 = DatabaseManager(restartDbPath)
        SchemaBootstrap.bootstrap(db1.getDatabase())
        val store1 = ScaffoldStateStore(db1.getDatabase())
        store1.upsert("restart-user", store1.get("restart-user").copy(
            trustPhase             = "WORKING_RHYTHM",
            answeredCategories     = setOf("IDENTITY", "EXPERTISE"),
            activeScaffoldQuestion = "What tools do you use?",
            sessionCount           = 3,
        ))
        db1.close()

        // Reopen same path ("restart")
        val db2 = DatabaseManager(restartDbPath)
        val store2 = ScaffoldStateStore(db2.getDatabase())
        val state = store2.get("restart-user")
        db2.close()

        File(restartDbPath).deleteRecursively()

        assertEquals("WORKING_RHYTHM", state.trustPhase)
        assertEquals(setOf("IDENTITY", "EXPERTISE"), state.answeredCategories)
        assertEquals("What tools do you use?", state.activeScaffoldQuestion)
        assertEquals(3, state.sessionCount)
    }

    @Test
    fun `UserScaffoldState vertex has no session-scoped fields`() {
        // Declarative schema contract: session-scoped fields must NOT be persisted
        val schema = dbManager.getDatabase().schema
        assertTrue(schema.existsType("UserScaffoldState"), "UserScaffoldState vertex type must exist")
        val propertyNames = schema.getType("UserScaffoldState").properties.map { it.name }

        // Session-scoped — must NOT appear
        assertFalse("turnIndex" in propertyNames, "turnIndex is session-scoped, must not be persisted")
        assertFalse("selectionResult" in propertyNames)
        assertFalse("responseText" in propertyNames)
        assertFalse("sessionId" in propertyNames)

        // User-scoped — must appear
        assertTrue("userId" in propertyNames)
        assertTrue("trustPhase" in propertyNames)
        assertTrue("answeredCategories" in propertyNames)
        assertTrue("sessionCount" in propertyNames)
        assertTrue("phaseTransitions" in propertyNames)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HttpEngramClient scaffold state — MockWebServer tests
// ─────────────────────────────────────────────────────────────────────────────

class HttpEngramClientScaffoldTest {

    private val mockServer = MockWebServer()

    @BeforeEach
    fun startServer() { mockServer.start() }

    @AfterEach
    fun stopServer() { mockServer.shutdown() }

    private fun client() = HttpEngramClient(mockServer.url("/").toString().trimEnd('/'))

    @Test
    fun `getScaffoldState calls real endpoint not in-memory fallback`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"userId":"u1","trustPhase":"WORKING_RHYTHM","answeredCategories":["IDENTITY","EXPERTISE"],"sessionCount":2,"phaseTransitions":[]}""")
                .setHeader("Content-Type", "application/json"),
        )

        val state = client().getScaffoldState("u1")

        val request = mockServer.takeRequest()
        assertEquals("/scaffold/state/u1", request.path)
        assertEquals("GET", request.method)

        // WORKING_RHYTHM → trustPhase int = 2
        assertEquals(2, state.trustPhase, "Expected WORKING_RHYTHM (2), got ${state.trustPhase}")
        assertTrue(PhraseCategory.IDENTITY in state.answeredCategories)
        assertTrue(PhraseCategory.EXPERTISE in state.answeredCategories)
    }

    @Test
    fun `getScaffoldState degrades gracefully when endpoint returns 500`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val state = client().getScaffoldState("u1")

        assertEquals(1, state.trustPhase, "Expected default ORIENTATION (1) on error")
        assertTrue(state.answeredCategories.isEmpty())
        assertNull(state.activeScaffoldQuestion)
    }

    @Test
    fun `getScaffoldState degrades gracefully when endpoint is unreachable`() = runTest {
        mockServer.shutdown()
        // Port is now closed but we still have its port number
        val unreachableClient = HttpEngramClient("http://localhost:${mockServer.port}")

        val state = unreachableClient.getScaffoldState("u1")

        assertEquals(1, state.trustPhase, "Expected default ORIENTATION (1) when unreachable")
        assertTrue(state.answeredCategories.isEmpty())
        assertNull(state.activeScaffoldQuestion)
    }

    @Test
    fun `updateScaffoldState sends PUT to scaffold endpoint`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"userId":"u1","trustPhase":"ORIENTATION","answeredCategories":["IDENTITY"],"sessionCount":1,"phaseTransitions":[]}""")
                .setHeader("Content-Type", "application/json"),
        )

        val state = app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState(
            trustPhase             = 1,
            answeredCategories     = setOf(PhraseCategory.IDENTITY),
            activeScaffoldQuestion = "What do you do for work?",
        )
        client().updateScaffoldState("u1", state)

        val request = mockServer.takeRequest()
        assertEquals("/scaffold/state/u1", request.path)
        assertEquals("PUT", request.method)
        assertTrue(request.body.readUtf8().contains("ORIENTATION"),
            "Expected trustPhase ORIENTATION in PUT body")
    }

    @Test
    fun `queryPhrases includes userId in request URL`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("[]")
                .setHeader("Content-Type", "application/json"),
        )

        client().queryPhrases("Kotlin developer", "user-abc")

        val request = mockServer.takeRequest()
        assertTrue(request.path?.contains("q=") == true, "Expected q= param in URL")
        assertTrue(request.path?.contains("userId=user-abc") == true,
            "Expected userId=user-abc in URL, got: ${request.path}")
    }

    @Test
    fun `queryPhrases degrades gracefully when endpoint unreachable`() = runTest {
        mockServer.shutdown()
        val unreachableClient = HttpEngramClient("http://localhost:${mockServer.port}")

        val phrases = unreachableClient.queryPhrases("kotlin", "user-1")

        assertTrue(phrases.isEmpty(), "Expected empty list when unreachable")
    }
}
