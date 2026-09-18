package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService
import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventKind
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.SalientTokenPropagator
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
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
 * Same `testApplication` pattern as `DebugActorEventRoutesTest`/`DebugHermesAssignmentRoutesTest`.
 * Seeds real committed events straight through [ActorEventIngestionService] (exactly what
 * [app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatcher] itself calls) rather than
 * via `POST /debug/actor-event` — that route's own DTO has no `assignmentKind`/`targetFilename`/
 * `executionOutcome` fields (they are Hermes-assignment-specific, not general-purpose), so it cannot
 * produce an event this new route considers eligible.
 */
class DebugHermesActivityRoutesTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var service: ActorEventIngestionService
    private lateinit var testDbPath: String

    @BeforeEach
    fun setUp() {
        testDbPath = "./data/test-hermes-activity-routes-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        val db = dbManager.getDatabase()
        val store = ArcadeHorizonGraphStore(db)
        service = ActorEventIngestionService(ArcadeCycleSequencer(db), store, SalientTokenPropagator(store, ArcadeHorizonAssembler(db)))
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(testDbPath).deleteRecursively()
    }

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
            configureDebugHermesActivityRoutes(dbManager.getDatabase())
        }
    }

    private fun seedUser(email: String) {
        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", email.substringBefore("@"))
                set("email", email)
                set("tier", -1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
        }
    }

    private fun seedCompletedDocumentSummary(userEmail: String, assignmentId: String, targetFilename: String) = runBlocking {
        service.ingest(
            userEmail = userEmail,
            eventId = "hermes-assignment-$assignmentId",
            kind = ActorEventKind.ToolResult(
                text = "Goal: ...", toolName = "read", toolSucceeded = true,
                assignmentKind = "document_summary", targetFilename = targetFilename, executionOutcome = "completed",
            ),
            sourceName = "hermes",
            assignmentId = assignmentId,
        )
    }

    // ── Authentication / gating ─────────────────────────────────────────────

    @Test
    fun `a request with no Authorization header is rejected with 401`() = testApplication {
        application { testModule() }
        val response = client.get("/debug/hermes-activity?syntheticUserId=t")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `the route does not exist at all when not registered, mirroring DEBUG_CONVERSE_ENABLED gating`() = testApplication {
        application { testModule(registerRoute = false) }
        val response = client.get("/debug/hermes-activity?syntheticUserId=t") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `a non-synthetic userEmail is rejected with 400`() = testApplication {
        application { testModule() }
        val response = client.get("/debug/hermes-activity?userEmail=real.person@gmail.com") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("synthetic"))
    }

    // ── Happy path: real committed evidence, independent of any completion store ──

    @Test
    fun `a completed document-summary event committed directly through the ingestion service is returned, labeled and named`() = testApplication {
        seedUser("debug+owner@test.alfrd.internal")
        seedCompletedDocumentSummary("debug+owner@test.alfrd.internal", "r1", "director-hermes-project-brief.md")
        application { testModule() }
        val response = client.get("/debug/hermes-activity?syntheticUserId=owner") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"eventId\":\"hermes-assignment-r1\""))
        assertTrue(body.contains("\"state\":\"completed\""))
        assertTrue(body.contains("\"targetFilename\":\"director-hermes-project-brief.md\""))
        assertTrue(body.contains("Summarized director-hermes-project-brief.md"))
    }

    @Test
    fun `an unknown or empty identity returns 200 with an empty items list, never a 404 or 503`() = testApplication {
        application { testModule() }
        val response = client.get("/debug/hermes-activity?syntheticUserId=never-seen") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"items\":[]"))
    }

    @Test
    fun `identity scoping — a different synthetic identity never sees another identity's committed activity`() = testApplication {
        seedUser("debug+owner@test.alfrd.internal")
        seedCompletedDocumentSummary("debug+owner@test.alfrd.internal", "r2", "director-hermes-project-brief.md")
        application { testModule() }
        val response = client.get("/debug/hermes-activity?syntheticUserId=someone-else") {
            header(HttpHeaders.Authorization, "Bearer $TEST_DEBUG_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"items\":[]"))
    }
}
