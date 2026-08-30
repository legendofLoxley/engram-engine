package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.DatabaseEngramClient
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Acceptance tests for the durable episodic conversation log — structurally separate from the
 * Phrase/Concept fact graph and its confidence/trust scoring. Verifies `Utterance` vertices are
 * chained via the existing `FOLLOWS` edge (reused, not a parallel edge type — see plan notes)
 * and retrievable via [DatabaseEngramClient.getEpisodicLog].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseEngramClientEpisodicTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var engramClient: DatabaseEngramClient

    @BeforeAll
    fun setup() {
        val dbPath = "./data/test-episodic-log-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(dbPath)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        engramClient = DatabaseEngramClient(db)
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-episodic-log-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `two consecutive turns produce a 4-vertex chain linked by FOLLOWS`() = runTest {
        val sessionId = "chain-session-${System.currentTimeMillis()}"
        val userId = "chain-user@example.com"

        engramClient.appendEpisodicTurn(sessionId, userId, 0, "first user turn", "first alfrd turn")
        engramClient.appendEpisodicTurn(sessionId, userId, 1, "second user turn", "second alfrd turn")

        val db = dbManager.getDatabase()
        val user1 = db.query(
            "sql",
            "SELECT FROM Utterance WHERE sessionId = :sessionId AND text = :text",
            mapOf("sessionId" to sessionId, "text" to "first user turn"),
        ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
        assertTrue(user1 != null, "Expected the first user Utterance vertex to exist")

        val alfrd1 = user1!!.getEdges(Vertex.DIRECTION.OUT, "FOLLOWS").firstOrNull()?.getVertex(Vertex.DIRECTION.IN)
        assertTrue(alfrd1 != null, "Expected a FOLLOWS edge from the first user turn")
        assertEquals("first alfrd turn", alfrd1!!.get("text"))

        val user2 = alfrd1.getEdges(Vertex.DIRECTION.OUT, "FOLLOWS").firstOrNull()?.getVertex(Vertex.DIRECTION.IN)
        assertTrue(user2 != null, "Expected a FOLLOWS edge from the first alfrd turn to the second user turn")
        assertEquals("second user turn", user2!!.get("text"))

        val alfrd2 = user2.getEdges(Vertex.DIRECTION.OUT, "FOLLOWS").firstOrNull()?.getVertex(Vertex.DIRECTION.IN)
        assertTrue(alfrd2 != null, "Expected a FOLLOWS edge from the second user turn to the second alfrd turn")
        assertEquals("second alfrd turn", alfrd2!!.get("text"))
    }

    @Test
    fun `getEpisodicLog returns turns ordered correctly`() = runTest {
        val sessionId = "order-session-${System.currentTimeMillis()}"
        val userId = "order-user@example.com"

        engramClient.appendEpisodicTurn(sessionId, userId, 0, "turn zero", "response zero")
        Thread.sleep(5) // guarantee strictly later createdAt, avoiding a millis tie between turns
        engramClient.appendEpisodicTurn(sessionId, userId, 1, "turn one", "response one")

        val log = engramClient.getEpisodicLog(userId = userId)
        assertEquals(4, log.size)
        assertEquals(listOf("turn zero", "response zero", "turn one", "response one"), log.map { it.text })
    }

    @Test
    fun `getEpisodicLog applies sinceMillis and untilMillis filters`() = runTest {
        val sessionId = "date-session-${System.currentTimeMillis()}"
        val userId = "date-user@example.com"

        engramClient.appendEpisodicTurn(sessionId, userId, 0, "early turn", "early response")
        Thread.sleep(5)
        val midpoint = System.currentTimeMillis()
        Thread.sleep(5)
        engramClient.appendEpisodicTurn(sessionId, userId, 1, "late turn", "late response")

        val onlyLate = engramClient.getEpisodicLog(userId = userId, sinceMillis = midpoint)
        assertTrue(
            onlyLate.isNotEmpty() && onlyLate.all { it.text.startsWith("late") },
            "Expected only turns after midpoint, got: ${onlyLate.map { it.text }}",
        )

        val onlyEarly = engramClient.getEpisodicLog(userId = userId, untilMillis = midpoint)
        assertTrue(
            onlyEarly.isNotEmpty() && onlyEarly.all { it.text.startsWith("early") },
            "Expected only turns before midpoint, got: ${onlyEarly.map { it.text }}",
        )
    }

    @Test
    fun `getEpisodicLog applies keyword filter`() = runTest {
        val sessionId = "keyword-session-${System.currentTimeMillis()}"
        val userId = "keyword-user@example.com"

        engramClient.appendEpisodicTurn(sessionId, userId, 0, "remind me to call the vet", "noted")
        engramClient.appendEpisodicTurn(sessionId, userId, 1, "what's the weather", "unknown")

        val log = engramClient.getEpisodicLog(userId = userId, keyword = "vet")
        assertTrue(log.isNotEmpty(), "Expected at least one match for keyword 'vet'")
        assertTrue(log.all { it.text.contains("vet", ignoreCase = true) })
    }

    @Test
    fun `chains for different sessions never cross`() = runTest {
        val userId = "cross-session-user@example.com"
        val sessionA = "session-a-${System.currentTimeMillis()}"
        val sessionB = "session-b-${System.currentTimeMillis()}"

        engramClient.appendEpisodicTurn(sessionA, userId, 0, "session A turn", "session A response")
        engramClient.appendEpisodicTurn(sessionB, userId, 0, "session B turn", "session B response")

        val db = dbManager.getDatabase()
        val sessionBUser = db.query(
            "sql",
            "SELECT FROM Utterance WHERE sessionId = :sessionId AND role = 'user'",
            mapOf("sessionId" to sessionB),
        ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
        assertTrue(sessionBUser != null, "Expected session B's user Utterance vertex to exist")

        val incomingToSessionBUser = sessionBUser!!.getEdges(Vertex.DIRECTION.IN, "FOLLOWS").toList()
        assertTrue(
            incomingToSessionBUser.isEmpty(),
            "Session B's first turn must not be chained from session A's tail — chains must stay session-scoped",
        )
    }
}
