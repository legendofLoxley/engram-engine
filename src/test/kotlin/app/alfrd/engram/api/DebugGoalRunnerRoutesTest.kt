package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.horizon.*
import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.director.goals.*
import io.ktor.client.request.get
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.UUID

private const val GR_TOKEN = "test-debug-token-goal-runner"

/** `/debug/goal-runner` and `/debug/goal-runner/run-once` are loopback and debug-token only, and the single-shot trigger works with the runner off without bypassing a goal's state. */
class DebugGoalRunnerRoutesTest {
    private fun Application.module(startup: GoalRunnerStartup, isLoopback: (ApplicationCall) -> Boolean = { true }) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == GR_TOKEN) UserIdPrincipal("debug") else null } } }
        configureDebugGoalRunnerRoutes(startup, isLoopback)
    }

    private fun <T> withRunner(block: (GoalRunner, DatabaseManager, String, ItemRecord) -> T): T {
        val base = Files.createTempDirectory("engram-goal-route-").toFile()
        val manager = DatabaseManager(File(base, "db").path)
        try {
            val db = manager.getDatabase()
            SchemaBootstrap.bootstrap(db)
            val structure = ArcadeHorizonStructureStore(db)
            val messages = ArcadeDirectorMessageStore(db)
            val writer = DirectorWriter(structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), directorMessages = messages), messages = messages)
            val user = "route-${UUID.randomUUID()}@example.test"
            db.transaction { db.newVertex("User").apply { set("uid", UUID.randomUUID().toString()); set("username", "r"); set("email", user); set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save() } }
            val project = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, "Williams")!! }
            val proposed = runBlocking { structure.createItem(user, 1, ItemKind.GOAL, "Only proposed", projectUid = project.uid)!! }
            val cfg = GoalRunnerConfig(enabled = false, killFile = File(base, "STOP").toPath())
            val runner = GoalRunner(
                email = user, structure = structure, messages = messages, writer = writer, cycles = DirectorCycles(structure, messages),
                planner = GoalPlanDirector(LocalLlmClient(baseUrl = cfg.planUrl), cfg), stepper = GoalStepExecutor(LocalLlmClient(baseUrl = cfg.stepUrl), cfg), qc = GoalQcDirector(LocalLlmClient(baseUrl = cfg.planUrl), cfg), verifier = GoalVerifier(LocalLlmClient(baseUrl = cfg.planUrl), cfg),
                config = cfg, resources = object : ResourceProbe { override fun freeRamMb() = 16_000L; override fun freeGpuMb() = 30_000L },
                slots = object : SlotProbe { override suspend fun slots(baseUrl: String) = Slots(2, 2) }, activity = ConversationActivity(),
                hermesBusy = { 0 }, cycleSeq = { 100L }, zone = ZoneOffset.UTC,
            )
            return block(runner, manager, user, proposed)
        } finally { manager.close(); base.deleteRecursively() }
    }

    @Test
    fun `both routes need the debug token`() = withRunner { runner, _, _, _ ->
        testApplication {
            application { module(GoalRunnerStartup.Ready(runner)) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/debug/goal-runner").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/debug/goal-runner/run-once").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/debug/goal-runner/run-once") { header(HttpHeaders.Authorization, "Bearer wrong") }.status)
        }
    }

    @Test
    fun `a caller that is not on loopback is refused on both routes even with the right token, and nothing runs`() = withRunner { runner, manager, _, _ ->
        val before = manager.getDatabase().countType("DirectorMessage")
        testApplication {
            application { module(GoalRunnerStartup.Ready(runner)) { false } }
            assertEquals(HttpStatusCode.Forbidden, client.get("/debug/goal-runner") { header(HttpHeaders.Authorization, "Bearer $GR_TOKEN") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/debug/goal-runner/run-once") { header(HttpHeaders.Authorization, "Bearer $GR_TOKEN") }.status)
        }
        assertEquals(null, runner.lastReport(), "no cycle was attempted")
        assertEquals(before, manager.getDatabase().countType("DirectorMessage"))
    }

    @Test
    fun `without a runner the status says so and the trigger answers 503`() {
        testApplication {
            application { module(GoalRunnerStartup.Disabled("DIRECTOR_TICK_IDENTITY is not set")) }
            val g = client.get("/debug/goal-runner") { header(HttpHeaders.Authorization, "Bearer $GR_TOKEN") }
            assertEquals(HttpStatusCode.OK, g.status)
            assertEquals("false", Json.parseToJsonElement(g.bodyAsText()).jsonObject["built"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/debug/goal-runner/run-once") { header(HttpHeaders.Authorization, "Bearer $GR_TOKEN") }.status)
        }
    }

    @Test
    fun `with the runner off the status says enabled false and the trigger still refuses a goal that is only proposed`() = withRunner { runner, _, _, proposed ->
        testApplication {
            application { module(GoalRunnerStartup.Ready(runner)) }
            val g = client.get("/debug/goal-runner") { header(HttpHeaders.Authorization, "Bearer $GR_TOKEN") }
            assertEquals("false", Json.parseToJsonElement(g.bodyAsText()).jsonObject["enabled"]!!.jsonPrimitive.content)
            val r = client.post("/debug/goal-runner/run-once") { header(HttpHeaders.Authorization, "Bearer $GR_TOKEN"); setBody("""{"goalUid":"${proposed.uid}"}""") }
            assertEquals(HttpStatusCode.OK, r.status)
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            assertEquals("NO_GOAL", body["outcome"]!!.jsonPrimitive.content)
            assertTrue(body["reason"]!!.jsonPrimitive.content.contains("proposed"), body.toString())
        }
    }

    private fun com.arcadedb.database.Database.countType(type: String): Long = query("sql", "SELECT count(*) AS n FROM $type").use { it.next().getProperty<Number>("n").toLong() }
}
