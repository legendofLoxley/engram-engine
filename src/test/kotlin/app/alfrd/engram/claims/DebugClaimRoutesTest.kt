package app.alfrd.engram.claims

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.configureDebugClaimRoutes
import app.alfrd.engram.api.connectedFromLoopback
import app.alfrd.engram.db.ChunkDraft
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceStore
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class DebugClaimRoutesTest {
    private val token = "test-debug-token-claims"
    private val auth = "Bearer $token"
    private val label = "claims-route-1"
    private val email = DebugConverseService.resolveUserId(label)

    private fun withEnv(block: (DatabaseManager) -> Unit) {
        val dir = Files.createTempDirectory("engram-claim-routes-").toFile()
        val manager = DatabaseManager(java.io.File(dir, "db").path)
        try { SchemaBootstrap.bootstrap(manager.getDatabase()); block(manager) } finally { manager.close(); dir.deleteRecursively() }
    }

    private val promptsSeen = ArrayList<PromptSet>()

    private fun Application.module(manager: DatabaseManager, model: FakeClaimModel, isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == token) UserIdPrincipal("debug") else null } } }
        configureDebugClaimRoutes(manager.getDatabase(), modelFactory = { p -> promptsSeen += p; model.promptVersion = p.version; model }, isLoopback = isLoopback)
    }

    private fun ingest(m: DatabaseManager): String {
        val db = m.getDatabase(); DebugConverseService.ensureSyntheticUser(db, email)
        return SourceStore(db).ingest(
            email, SourceInput("document", "gdrive:routes", "Routes", "x", chunks = listOf(ChunkDraft("page 1", "Scenario A closes 14 schools."), ChunkDraft("page 2", "Scenario B closes 11 schools."))),
        ).source.uid
    }

    /**
     * Polls the run through its route until it is complete (up to 20 s). Through the route, not the graph: a read on the test's own thread can
     * keep seeing the graph as it was, and the poll then never ends.
     */
    private suspend fun ApplicationTestBuilder.awaitRun(runId: String) {
        repeat(400) {
            val status = Json.parseToJsonElement(client.get("/debug/claim-runs/$runId?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.bodyAsText())
                .let { (it as? kotlinx.serialization.json.JsonObject)?.get("status")?.jsonPrimitive?.content }
            if (status == "complete") return
            delay(50)
        }
    }

    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `the routes need the token, a loopback caller and a synthetic identity that is not owner-jacob`() = withEnv { m ->
        val uid = ingest(m)
        val model = FakeClaimModel { _, _ -> emptyList() }
        testApplication {
            application { module(m, model) }
            val path = "/debug/sources/$uid/claims/extract"
            assertEquals(HttpStatusCode.Unauthorized, client.post(path) { setBody("""{"syntheticUserId":"$label"}""") }.status)
            val owner = client.post(path) { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody("""{"syntheticUserId":"owner-jacob"}""") }
            assertEquals(HttpStatusCode.Forbidden, owner.status)
        }
        testApplication {
            application { module(m, model, isLoopback = { false }) }
            for (b in listOf("not json", "{}", """{"syntheticUserId":"$label"}""")) {
                assertEquals(HttpStatusCode.Forbidden, client.post("/debug/sources/$uid/claims/extract") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(b) }.status)
            }
            assertEquals(HttpStatusCode.Forbidden, client.get("/debug/sources/$uid/claims?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/debug/claim-runs/r?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.status)
        }
        assertEquals(0, model.calls.size)
    }

    @Test
    fun `a run is started, reports its progress and claims, and starting the finished run again calls no model`() = withEnv { m ->
        val uid = ingest(m)
        val model = FakeClaimModel { ch, _ -> listOf(PassClaim(if (ch.ordinal == 0) "Scenario A closes 14 schools." else "Scenario B closes 11 schools.", "PPS", "closures", "number")) }
        testApplication {
            application { module(m, model) }
            suspend fun post(body: String) = client.post("/debug/sources/$uid/claims/extract") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(body) }
            val started = post("""{"syntheticUserId":"$label","runId":"route-run-1"}""")
            assertEquals(HttpStatusCode.Accepted, started.status)
            assertEquals("started", json(started.bodyAsText()).jsonObject["state"]!!.jsonPrimitive.content)

            var status = ""
            repeat(200) {
                if (status != "complete") {
                    status = json(client.get("/debug/claim-runs/route-run-1?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content.orEmpty()
                    if (status != "complete") delay(50)
                }
            }
            assertEquals("complete", status)
            val run = json(client.get("/debug/claim-runs/route-run-1?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonObject
            assertEquals("Qwen3.6-35B-A3B-UD-Q4_K_M.gguf", run["modelName"]!!.jsonPrimitive.content)
            assertEquals("2", run["chunksDone"]!!.jsonPrimitive.content)

            val list = json(client.get("/debug/sources/$uid/claims?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonArray
            assertEquals(2, list.size)
            assertEquals(setOf("Settled"), list.map { it.jsonObject["checkState"]!!.jsonPrimitive.content }.toSet())
            assertEquals(listOf("page 1"), list.first { it.jsonObject["quote"]!!.jsonPrimitive.content.startsWith("Scenario A") }.jsonObject["locations"]!!.jsonArray.map { it.jsonPrimitive.content })

            val calls = model.calls.size
            val again = post("""{"syntheticUserId":"$label","runId":"route-run-1"}""")
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals("complete", json(again.bodyAsText()).jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals(calls, model.calls.size)
            assertEquals(HttpStatusCode.NotFound, post("""{"syntheticUserId":"$label"}""").let { client.post("/debug/sources/nope/claims/extract") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody("""{"syntheticUserId":"$label"}""") }.status })
        }
    }

    @Test
    fun `retained disagreements are shown with both versions`() = withEnv { m ->
        val uid = ingest(m)
        val model = FakeClaimModel { ch, pass -> if (ch.ordinal == 0) listOf(PassClaim("Scenario A closes 14 schools.", if (pass == 1) "PPS" else "The Board", "x", "number")) else emptyList() }
        testApplication {
            application { module(m, model) }
            client.post("/debug/sources/$uid/claims/extract") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody("""{"syntheticUserId":"$label","runId":"route-run-2"}""") }
            awaitRun("route-run-2")
            val list = json(client.get("/debug/sources/$uid/claim-disagreements?syntheticUserId=$label&runId=route-run-2") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonArray
            assertEquals(1, list.size)
            val d = list.single().jsonObject
            assertEquals("PPS", d["versionA"]!!.jsonObject["claimantAsWritten"]!!.jsonPrimitive.content)
            assertEquals("The Board", d["versionB"]!!.jsonObject["claimantAsWritten"]!!.jsonPrimitive.content)
            assertTrue(json(client.get("/debug/sources/$uid/claims?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonArray.isEmpty())
        }
    }

    @Test
    fun `a request can bring its own prompts, the run records only their hash name, and bad prompts are refused before any run starts`() = withEnv { m ->
        val uid = ingest(m)
        val model = FakeClaimModel { ch, _ -> listOf(PassClaim(if (ch.ordinal == 0) "Scenario A closes 14 schools." else "Scenario B closes 11 schools.", "PPS", "x", "number")) }
        testApplication {
            application { module(m, model) }
            suspend fun post(body: String) = client.post("/debug/sources/$uid/claims/extract") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, post("""{"syntheticUserId":"$label","prompts":{"pass1":"","pass2":"x"}}""").status)
            assertEquals(HttpStatusCode.BadRequest, post("""{"syntheticUserId":"$label","prompts":{"pass1":"${"y".repeat(PromptSet.MAX_CHARS + 1)}","pass2":"x"}}""").status)
            assertEquals(0L, m.getDatabase().query("sql", "SELECT count(*) AS n FROM ExtractionRun").use { it.next().getProperty<Number>("n").toLong() })

            val secret = "WORDING-THAT-MUST-NOT-BE-STORED"
            assertEquals(HttpStatusCode.Accepted, post("""{"syntheticUserId":"$label","runId":"route-run-p","prompts":{"pass1":"one $secret","pass2":"two"}}""").status)
            awaitRun("route-run-p")
            val run = json(client.get("/debug/claim-runs/route-run-p?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonObject
            val version = run["promptVersion"]!!.jsonPrimitive.content
            assertTrue(Regex("custom-[0-9a-f]{16}").matches(version), version)
            assertEquals(PromptSet.custom("one $secret", "two").version, version)
            assertTrue(!run.toString().contains(secret), "the record carries the version, never the text")
        }
        assertEquals("one WORDING-THAT-MUST-NOT-BE-STORED", promptsSeen.last().pass1)
    }

    @Test
    fun `without prompts the built-in wording is used and named`() = withEnv { m ->
        val uid = ingest(m)
        val model = FakeClaimModel { _, _ -> emptyList() }
        testApplication {
            application { module(m, model) }
            client.post("/debug/sources/$uid/claims/extract") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody("""{"syntheticUserId":"$label","runId":"route-run-d"}""") }
            awaitRun("route-run-d")
            val run = json(client.get("/debug/claim-runs/route-run-d?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonObject
            assertEquals("claims-v5", run["promptVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `claims show their passage, things and period, and things are looked up by name with their candidates`() = withEnv { m ->
        val uid = ingest(m)
        val model = FakeClaimModel { ch, _ ->
            if (ch.ordinal == 0) listOf(PassClaim("Scenario A closes 14 schools.", "PPS", "closures", "number", passage = "Scenario A closes 14 schools.", about = listOf(ThingRef("Scenario A", "scenario"))))
            else emptyList()
        }
        testApplication {
            application { module(m, model) }
            client.post("/debug/sources/$uid/claims/extract") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody("""{"syntheticUserId":"$label","runId":"route-run-c"}""") }
            awaitRun("route-run-c")
            val claim = json(client.get("/debug/sources/$uid/claims?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonArray.single().jsonObject
            assertEquals("Scenario A closes 14 schools.", claim["passage"]!!.jsonPrimitive.content)
            assertEquals("Scenario A", claim["about"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)

            val found = json(client.get("/debug/concepts?syntheticUserId=$label&name=scenario%20a") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonArray.single().jsonObject
            assertEquals("scenario", found["kind"]!!.jsonPrimitive.content)
            assertEquals("1", found["claims"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.BadRequest, client.get("/debug/concepts?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/debug/concepts?syntheticUserId=owner-jacob&name=x") { header(HttpHeaders.Authorization, auth) }.status)
        }
    }
}
