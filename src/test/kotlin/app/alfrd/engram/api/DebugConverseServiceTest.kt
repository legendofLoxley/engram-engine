package app.alfrd.engram.api

import app.alfrd.engram.cognitive.CognitivePipelineFactory
import app.alfrd.engram.cognitive.pipeline.CognitivePipeline
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
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
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// resolveUserId sanitization — unit tests, no DB
// ─────────────────────────────────────────────────────────────────────────────

class ResolveUserIdSanitizationTest {

    @Test
    fun `null input generates a uuid-based email ending in synthetic domain`() {
        val result = DebugConverseService.resolveUserId(null)
        assertTrue(result.endsWith(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN))
        assertTrue(result.startsWith("debug+"))
        val inner = result.removePrefix("debug+").removeSuffix(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN)
        assertTrue(inner.isNotBlank())
    }

    @Test
    fun `blank input generates a uuid-based email`() {
        val result = DebugConverseService.resolveUserId("   ")
        assertTrue(result.endsWith(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN))
        assertTrue(result.startsWith("debug+"))
    }

    @Test
    fun `at-sign in input is stripped so domain appears only once`() {
        val result = DebugConverseService.resolveUserId("alice@example.com")
        assertTrue(result.endsWith(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN))
        val withoutSuffix = result.removeSuffix(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN)
        assertFalse(withoutSuffix.contains(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN),
            "Injected domain must not appear mid-string")
        assertFalse(withoutSuffix.contains("@"), "@ must be replaced")
    }

    @Test
    fun `forward slash and other special chars are replaced`() {
        val result = DebugConverseService.resolveUserId("foo/bar?baz=1")
        assertTrue(result.endsWith(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN))
        val inner = result.removePrefix("debug+").removeSuffix(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN)
        assertFalse(inner.contains("/"))
        assertFalse(inner.contains("?"))
        assertFalse(inner.contains("="))
    }

    @Test
    fun `long input is truncated to 64 chars in the base`() {
        val result = DebugConverseService.resolveUserId("a".repeat(200))
        assertTrue(result.endsWith(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN))
        val inner = result.removePrefix("debug+").removeSuffix(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN)
        assertTrue(inner.length <= 64, "Base must be at most 64 chars, was ${inner.length}")
    }

    @Test
    fun `two null calls produce different emails`() {
        val r1 = DebugConverseService.resolveUserId(null)
        val r2 = DebugConverseService.resolveUserId(null)
        assertNotEquals(r1, r2)
    }

    @Test
    fun `same non-null label produces same email on repeated calls`() {
        val r1 = DebugConverseService.resolveUserId("my-test-user")
        val r2 = DebugConverseService.resolveUserId("my-test-user")
        assertEquals(r1, r2)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// purge round-trip + debug response shape — DB-integrated
// ─────────────────────────────────────────────────────────────────────────────

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurgeAndShapeTest {

    private lateinit var dbManager: DatabaseManager

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-purge-shape-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        ResponsePhraseSeed.seed(dbManager.getDatabase())
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-purge-shape-") }
            ?.forEach { it.deleteRecursively() }
    }

    // ── Purge round-trip ─────────────────────────────────────────────────────

    @Test
    fun `purge deletes synthetic user and SELECTED and OUTCOME edges with no orphans`() {
        val db = dbManager.getDatabase()
        val email = DebugConverseService.resolveUserId("purge-round-trip")
        DebugConverseService.ensureSyntheticUser(db, email)

        val userVertex = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
        assertNotNull(userVertex, "Synthetic user vertex must exist before purge")

        val phraseVertex = db.query("sql", "SELECT FROM ResponsePhrase LIMIT 1", emptyMap<String, Any>())
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
        assertNotNull(phraseVertex, "Need at least one ResponsePhrase for edge anchoring")

        db.transaction {
            userVertex!!.newEdge("SELECTED", phraseVertex!!, false).apply {
                set("phraseUid", phraseVertex.get("uid") as? String ?: "test-phrase")
                set("sessionId", "test-session-purge")
                set("userId", email)
                set("turnIndex", 1)
                set("compositeScore", 0.5)
                set("scoreBreakdown", "{}")
                set("timestamp", System.currentTimeMillis())
                save()
            }
            userVertex.newEdge("OUTCOME", phraseVertex, false).apply {
                set("phraseUid", phraseVertex.get("uid") as? String ?: "test-phrase")
                set("sessionId", "test-session-purge")
                set("userId", email)
                set("turnIndex", 1)
                set("signal", "ENGAGED")
                set("contextSnapshot", "{}")
                set("timestamp", System.currentTimeMillis())
                save()
            }
        }

        fun countByUserId(type: String): Int =
            db.query("sql", "SELECT FROM $type WHERE userId = :e", mapOf("e" to email))
                .use { rs -> var c = 0; while (rs.hasNext()) { rs.next(); c++ }; c }

        assertEquals(1, countByUserId("SELECTED"), "Expected 1 SELECTED edge before purge")
        assertEquals(1, countByUserId("OUTCOME"), "Expected 1 OUTCOME edge before purge")

        val deleted = DebugConverseService.purgeAllSyntheticUsers(db)
        assertEquals(1, deleted, "Expected exactly 1 user purged")

        val userExists = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email))
            .use { rs -> rs.hasNext() }
        assertFalse(userExists, "User vertex must not exist after purge")
        assertEquals(0, countByUserId("SELECTED"), "No SELECTED edges should remain")
        assertEquals(0, countByUserId("OUTCOME"), "No OUTCOME edges should remain")
    }

    @Test
    fun `purge returns 0 when no synthetic users exist`() {
        assertEquals(0, DebugConverseService.purgeAllSyntheticUsers(dbManager.getDatabase()))
    }

    @Test
    fun `purge does not delete real users`() {
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", "real-user")
                set("email", "real@example.com")
                set("tier", 1)
                set("createdAt", System.currentTimeMillis())
                set("updatedAt", System.currentTimeMillis())
                save()
            }
        }

        DebugConverseService.purgeAllSyntheticUsers(db)

        val realUserExists = db.query("sql", "SELECT FROM User WHERE email = :e",
            mapOf("e" to "real@example.com")).use { rs -> rs.hasNext() }
        assertTrue(realUserExists, "Real user must not be deleted by purge")

        db.transaction {
            db.query("sql", "SELECT FROM User WHERE email = :e",
                mapOf("e" to "real@example.com")).use { rs ->
                if (rs.hasNext()) rs.next().toElement().asVertex().delete()
            }
        }
    }

    // ── Debug response shape ─────────────────────────────────────────────────

    @Test
    fun `debug response has non-blank traceId, non-blank resolutionPath, and populated trace`() = runTest {
        val pipeline = CognitivePipelineFactory.create(dbManager.getDatabase())
        pipeline.init()

        val debugResult = pipeline.processForDebug("Hey", "session-shape-1", "user-shape-1@test.alfrd.internal")

        val traceId = UUID.randomUUID().toString()
        val resolution = classifyDebugResolution(debugResult.chat.synthesisSource)
        val response = DebugConverseResponse(
            traceId           = traceId,
            reply             = debugResult.chat.responseText,
            sessionId         = "session-shape-1",
            syntheticUserId   = "user-shape-1@test.alfrd.internal",
            resolutionPath    = resolution.resolutionPath,
            fallbackTriggered = resolution.fallbackTriggered,
            fallbackReason    = resolution.fallbackReason,
            totalLatencyMs    = 0L,
            trace             = debugResult.trace,
        )

        assertTrue(response.traceId.isNotBlank())
        assertDoesNotThrow { UUID.fromString(response.traceId) }
        assertTrue(response.resolutionPath.isNotBlank())
        assertTrue(response.reply.isNotBlank())
        assertTrue(response.trace.latencyBreakdown.totalPipelineMs >= 0)
        assertTrue(response.trace.session.turnCount >= 1)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LLM fallback flag — verifies item 3 (fallbackTriggered for "llm" source)
// ─────────────────────────────────────────────────────────────────────────────

class LlmFallbackFlagTest {

    @Test
    fun `llm synthesisSource maps to fallbackTriggered true and llm_branch_no_graph_phrase reason`() = runTest {
        // Tier 2 classification returns "QUESTION" → Router selects QuestionBranch →
        // QuestionBranch calls llmClient for the answer → synthesisSource = "llm".
        val llm = TestLlmClient { req ->
            if (req.prompt.contains("Classify"))
                LlmResponse(text = "QUESTION", latencyMs = 0L, retryCount = 0)
            else
                LlmResponse(text = "Here is the answer.", latencyMs = 0L, retryCount = 0)
        }
        val pipeline = CognitivePipeline(llmClient = llm)
        pipeline.init()

        // "Blah blorp zam" is ambiguous: Tier 1 yields AMBIGUOUS (0.30), so Tier 2 fires.
        val debugResult = pipeline.processForDebug("Blah blorp zam", "session-llm-1", "user-llm-1")

        assertEquals("llm", debugResult.chat.synthesisSource,
            "Expected QuestionBranch LLM path; got synthesisSource=${debugResult.chat.synthesisSource}")

        val resolution = classifyDebugResolution(debugResult.chat.synthesisSource)
        assertTrue(resolution.fallbackTriggered, "LLM path must set fallbackTriggered=true")
        assertEquals("llm_branch_no_graph_phrase", resolution.fallbackReason)
        assertEquals("LlmBranch", resolution.resolutionPath)
    }
}
