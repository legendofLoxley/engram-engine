package app.alfrd.engram.api

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

private const val TEST_DEBUG_TOKEN = "test-debug-token-12345"

/**
 * First `testApplication`-based test file in this repo — chosen over calling
 * `configureDebugActorEventRoutes` functions directly because the behavior under test
 * (authentication, feature-gating via whether the route is registered at all, and HTTP status
 * codes) only exists at the Ktor routing layer, not in [ActorEventIngestionService] itself
 * (already covered by `ActorEventIngestionServiceTest`, with no HTTP concern).
 *
 * The test module reproduces only what `/debug/actor-event` actually needs at runtime — JSON
 * content negotiation and the `debug-token` bearer provider (mirroring [configureAuth]'s own
 * `bearer("debug-token")` block exactly) — never the full [configureAuth], which would require
 * `SUPABASE_ISSUER` to be set for its unrelated Supabase JWT provider.
 */
class DebugActorEventRoutesTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var testDbPath: String

    @BeforeEach
    fun setUp() {
        testDbPath = "./data/test-actor-event-routes-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(testDbPath).deleteRecursively()
    }

    /** [registerRoute] false reproduces the `DEBUG_CONVERSE_ENABLED != "true"` case in `Application.kt` — the route simply never exists. */
    private fun Application.testModule(registerRoute: Boolean = true) {
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
            configureDebugActorEventRoutes(dbManager.getDatabase())
        }
    }

    private fun requestJson(
        kind: String = "observation",
        text: String = "hello",
        eventId: String = "evt-${UUID.randomUUID()}",
        sourceName: String = "hermes",
        userEmail: String? = null,
        syntheticUserId: String? = null,
        basis: String? = null,
        toolName: String? = null,
        toolSucceeded: Boolean? = null,
    ): String = Json.encodeToString(
        DebugActorEventRequest(
            kind = kind, text = text, eventId = eventId, sourceName = sourceName,
            basis = basis, toolName = toolName, toolSucceeded = toolSucceeded,
            userEmail = userEmail, syntheticUserId = syntheticUserId,
        ),
    )

    // ── Authentication ──────────────────────────────────────────────────────

    @Test
    fun `a request with no Authorization header is rejected with 401`() = testApplication {
        application { testModule() }
        val response = client.post("/debug/actor-event") {
            contentType(ContentType.Application.Json)
            setBody(requestJson(userEmail = "debug+t@test.alfrd.internal"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a request with the wrong debug token is rejected with 401`() = testApplication {
        application { testModule() }
        val response = client.post("/debug/actor-event") {
            header(HttpHeaders.Authorization, "Bearer not-the-real-token")
            contentType(ContentType.Application.Json)
            setBody(requestJson(userEmail = "debug+t@test.alfrd.internal"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ── Feature gating ───────────────────────────────────────────────────────

    @Test
    fun `the route does not exist at all when not registered, mirroring DEBUG_CONVERSE_ENABLED gating`() = testApplication {
        application { testModule(registerRoute = false) }
        val response = client.post("/debug/actor-event") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(requestJson(userEmail = "debug+t@test.alfrd.internal"))
        }
        assertEquals(HttpStatusCode.NotFound, response.status, "an unregistered route must be genuinely absent, never merely unauthenticated")
    }

    // ── Invalid kinds ────────────────────────────────────────────────────────

    @Test
    fun `an unrecognized kind is rejected with 400, never a passthrough into graph-visible provenance`() = testApplication {
        application { testModule() }
        val response = client.post("/debug/actor-event") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(requestJson(kind = "not-a-real-kind", userEmail = "debug+t@test.alfrd.internal"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `an interpretation without a basis is rejected with 400`() = testApplication {
        application { testModule() }
        val response = client.post("/debug/actor-event") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(requestJson(kind = "interpretation", userEmail = "debug+t@test.alfrd.internal"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a tool_result missing toolName or toolSucceeded is rejected with 400`() = testApplication {
        application { testModule() }
        val response = client.post("/debug/actor-event") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(requestJson(kind = "tool_result", userEmail = "debug+t@test.alfrd.internal"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ── Disallowed identities — the synthetic-only scope ────────────────────

    @Test
    fun `a non-synthetic userEmail is rejected with 400, this route is the synthetic injection harness only`() = testApplication {
        application { testModule() }
        val response = client.post("/debug/actor-event") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(requestJson(userEmail = "real.person@gmail.com"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("synthetic"), "the rejection reason must name why, not just fail silently")
    }

    @Test
    fun `a userEmail on a lookalike domain is still rejected, suffix match only not a substring anywhere`() = testApplication {
        application { testModule() }
        val response = client.post("/debug/actor-event") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(requestJson(userEmail = "attacker@test.alfrd.internal.evil.com"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ── Accepted synthetic identities — the real service is exercised ──────

    @Test
    fun `an explicit synthetic userEmail is accepted and committed through the real service`() = testApplication {
        application { testModule() }
        val response = client.post("/debug/actor-event") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(requestJson(userEmail = "debug+routes-explicit@test.alfrd.internal", eventId = "evt-route-explicit"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"outcome\":\"Committed\""))
    }

    @Test
    fun `omitting userEmail resolves via syntheticUserId to a synthetic identity and is accepted`() = testApplication {
        application { testModule() }
        val response = client.post("/debug/actor-event") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(requestJson(syntheticUserId = "routes-label", eventId = "evt-route-label"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"outcome\":\"Committed\""))
        assertTrue(body.contains(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN), "the resolved identity itself must actually be synthetic")
    }
}
