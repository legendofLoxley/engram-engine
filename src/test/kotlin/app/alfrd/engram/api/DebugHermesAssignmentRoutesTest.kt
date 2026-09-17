package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore
import app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentOutcome
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val TEST_DEBUG_TOKEN = "test-debug-token-12345"

/**
 * Same `testApplication` pattern as `DebugActorEventRoutesTest` — this route needs no
 * [com.arcadedb.database.Database] at all (it only reads [HermesAssignmentCompletionStore],
 * an in-memory map), so the test module is even smaller: just content negotiation and the
 * `debug-token` bearer provider.
 */
class DebugHermesAssignmentRoutesTest {

    private val completed = HermesAssignmentOutcome.Completed(
        findingsText = "DH-FIXTURE-abc123", toolName = "read", toolTargetPath = "director-hermes-fixture.txt", toolSucceeded = true,
    )

    private fun Application.testModule(store: HermesAssignmentCompletionStore, registerRoute: Boolean = true) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(Authentication) {
            bearer("debug-token") {
                authenticate { tokenCredential ->
                    if (tokenCredential.token == TEST_DEBUG_TOKEN) UserIdPrincipal("debug") else null
                }
            }
        }
        if (registerRoute) {
            configureDebugHermesAssignmentRoutes(store)
        }
    }

    // ── Authentication ──────────────────────────────────────────────────────

    @Test
    fun `a request with no Authorization header is rejected with 401`() = testApplication {
        val store = HermesAssignmentCompletionStore()
        application { testModule(store) }
        val response = client.get("/debug/hermes-assignment/a1?syntheticUserId=t")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a request with the wrong debug token is rejected with 401`() = testApplication {
        val store = HermesAssignmentCompletionStore()
        application { testModule(store) }
        val response = client.get("/debug/hermes-assignment/a1?syntheticUserId=t") {
            header(HttpHeaders.Authorization, "Bearer not-the-real-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ── Feature gating ───────────────────────────────────────────────────────

    @Test
    fun `the route does not exist at all when not registered, mirroring DEBUG_CONVERSE_ENABLED gating`() = testApplication {
        val store = HermesAssignmentCompletionStore()
        application { testModule(store, registerRoute = false) }
        val response = client.get("/debug/hermes-assignment/a1?syntheticUserId=t") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── Disallowed identities ────────────────────────────────────────────────

    @Test
    fun `a non-synthetic userEmail is rejected with 400`() = testApplication {
        val store = HermesAssignmentCompletionStore()
        store.record("a1", "real.person@gmail.com", completed, "Committed")
        application { testModule(store) }
        val response = client.get("/debug/hermes-assignment/a1?userEmail=real.person@gmail.com") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("synthetic"))
    }

    // ── Not found: unknown id, and known id with the wrong identity ─────────

    @Test
    fun `an unknown assignmentId returns 404`() = testApplication {
        val store = HermesAssignmentCompletionStore()
        application { testModule(store) }
        val response = client.get("/debug/hermes-assignment/never-recorded?syntheticUserId=t") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `a known assignmentId queried under a different synthetic identity also returns 404, not the other identity's completion`() = testApplication {
        val store = HermesAssignmentCompletionStore()
        store.record("a1", "debug+owner@test.alfrd.internal", completed, "Committed")
        application { testModule(store) }
        val response = client.get("/debug/hermes-assignment/a1?syntheticUserId=someone-else") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── Found: Completed and Failed outcomes ────────────────────────────────

    @Test
    fun `a completed assignment is returned with its findings, tool attribution and graph outcome`() = testApplication {
        val store = HermesAssignmentCompletionStore()
        store.record("a1", "debug+owner@test.alfrd.internal", completed, "Committed")
        application { testModule(store) }
        val response = client.get("/debug/hermes-assignment/a1?syntheticUserId=owner") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"outcome\":\"Completed\""))
        assertTrue(body.contains("DH-FIXTURE-abc123"))
        assertTrue(body.contains("\"toolSucceeded\":true"))
        assertTrue(body.contains("\"graphIngestOutcome\":\"Committed\""))
    }

    @Test
    fun `a failed assignment is returned with its reason, not silently reported as success`() = testApplication {
        val store = HermesAssignmentCompletionStore()
        val failed = HermesAssignmentOutcome.Failed("no tool call observed (stopReason=refusal)")
        store.record("a2", "debug+owner@test.alfrd.internal", failed, "Committed")
        application { testModule(store) }
        val response = client.get("/debug/hermes-assignment/a2?syntheticUserId=owner") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"outcome\":\"Failed\""))
        assertTrue(body.contains("no tool call observed"))
    }
}
