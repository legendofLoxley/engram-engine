package app.alfrd.engram.claims

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.configureDebugClaimRoutes
import app.alfrd.engram.api.connectedFromLoopback
import app.alfrd.engram.db.AliasRegistry
import app.alfrd.engram.db.ChunkDraft
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.ConceptStore
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceRecord
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID

/** The routes behind entity identity: issuer, name checks, the alias list, entities, and stopping a claim run. */
class DebugIdentityRoutesTest {
    private val token = "test-debug-token-identity"
    private val auth = "Bearer $token"
    private val label = "identity-route-1"
    private val email = DebugConverseService.resolveUserId(label)

    private fun withEnv(block: (DatabaseManager) -> Unit) {
        val dir = Files.createTempDirectory("engram-identity-routes-").toFile()
        val manager = DatabaseManager(java.io.File(dir, "db").path)
        try { SchemaBootstrap.bootstrap(manager.getDatabase()); DebugConverseService.ensureSyntheticUser(manager.getDatabase(), email); block(manager) } finally { manager.close(); dir.deleteRecursively() }
    }

    private fun Application.module(manager: DatabaseManager, model: ClaimPassModel = FakeClaimModel { _, _ -> emptyList() }, isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == token) UserIdPrincipal("debug") else null } } }
        configureDebugClaimRoutes(manager.getDatabase(), modelFactory = { model }, isLoopback = isLoopback)
    }

    private fun ingest(m: DatabaseManager, chunks: Int = 2): String = SourceStore(m.getDatabase()).ingest(
        email, SourceInput("document", "gdrive:ident", "Ident", "x", chunks = (1..chunks).map { ChunkDraft("page $it", "Scenario $it closes ${10 + it} schools.", page = it) }),
    ).source.uid

    private fun json(s: String) = Json.parseToJsonElement(s)

    private suspend fun ApplicationTestBuilder.post(path: String, body: String) =
        client.post(path) { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(body) }

    private suspend fun ApplicationTestBuilder.get(path: String) = client.get(path) { header(HttpHeaders.Authorization, auth) }

    @Test
    fun `the new routes need the token, a loopback caller and a synthetic identity that is not owner-jacob`() = withEnv { m ->
        val uid = ingest(m)
        testApplication {
            application { module(m) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/debug/name-checks?syntheticUserId=$label").status)
            assertEquals(HttpStatusCode.Forbidden, get("/debug/name-checks?syntheticUserId=owner-jacob").status)
            assertEquals(HttpStatusCode.Forbidden, post("/debug/sources/$uid/issuer", """{"syntheticUserId":"owner-jacob","issuer":"PPS"}""").status)
            assertEquals(HttpStatusCode.Forbidden, post("/debug/claim-runs/r/stop", """{"syntheticUserId":"owner-jacob"}""").status)
        }
        testApplication {
            application { module(m, isLoopback = { false }) }
            assertEquals(HttpStatusCode.Forbidden, get("/debug/entities?syntheticUserId=$label").status)
            assertEquals(HttpStatusCode.Forbidden, post("/debug/name-checks/decide", "not json").status)
            assertEquals(HttpStatusCode.Forbidden, post("/debug/claim-runs/r/stop", "{}").status)
        }
    }

    @Test
    fun `a Source's issuer is set by a person and refused for a Source that is not theirs`() = withEnv { m ->
        val uid = ingest(m)
        testApplication {
            application { module(m) }
            assertEquals(HttpStatusCode.OK, post("/debug/sources/$uid/issuer", """{"syntheticUserId":"$label","issuer":"  Portland   Public Schools "}""").status)
            assertEquals("Portland Public Schools", SourceStore(m.getDatabase()).sourceByUid(email, uid)!!.issuer)
            assertEquals(HttpStatusCode.NotFound, post("/debug/sources/${UUID.randomUUID()}/issuer", """{"syntheticUserId":"$label","issuer":"PPS"}""").status)
        }
    }

    @Test
    fun `name checks are listed, answered once, and the answer shows in the alias list and the entities`() = withEnv { m ->
        val db = m.getDatabase(); val registry = AliasRegistry(db)
        val concepts = ConceptStore(db, registry.resolverFor(email))
        // Each is linked from a claim as soon as it is resolved, as extraction does.
        fun thing(name: String): app.alfrd.engram.db.ConceptRef {
            val c = concepts.resolve(email, name, "school", null, "PPS")
            val phrase = UUID.randomUUID().toString()
            db.transaction { db.newVertex("Phrase").apply { set("uid", phrase); set("text", "c"); set("ownerEmail", email); set("claimSourceUid", "s"); save() } }
            concepts.linkAbout(email, phrase, c.uid, "r")
            return c
        }
        val clark = thing("Clark"); val elem = thing("Clark Elementary")
        testApplication {
            application { module(m) }
            val checks = json(get("/debug/name-checks?syntheticUserId=$label").bodyAsText()).jsonArray
            assertEquals(1, checks.size)
            assertTrue(checks[0].jsonObject["reason"]!!.jsonPrimitive.content.startsWith("same name apart from a designator"))
            assertEquals(HttpStatusCode.BadRequest, post("/debug/name-checks/decide", """{"syntheticUserId":"$label","a":"${clark.uid}","b":"${elem.uid}","decision":"maybe"}""").status)
            assertEquals(HttpStatusCode.OK, post("/debug/name-checks/decide", """{"syntheticUserId":"$label","a":"${clark.uid}","b":"${elem.uid}","decision":"same","canonical":"${clark.uid}"}""").status)
            assertEquals(0, json(get("/debug/name-checks?syntheticUserId=$label").bodyAsText()).jsonArray.size)
            assertEquals(HttpStatusCode.NotFound, post("/debug/name-checks/decide", """{"syntheticUserId":"$label","a":"${clark.uid}","b":"${elem.uid}","decision":"same"}""").status, "an answered pair is not waiting")
            val aliases = json(get("/debug/entity-aliases?syntheticUserId=$label").bodyAsText()).jsonArray
            assertEquals("confirmed", aliases.single().jsonObject["origin"]!!.jsonPrimitive.content)
            val entities = json(get("/debug/entities?syntheticUserId=$label").bodyAsText()).jsonArray
            assertEquals(1, entities.size); assertEquals("Clark", entities[0].jsonObject["name"]!!.jsonPrimitive.content)
            val uid = aliases.single().jsonObject["uid"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.OK, post("/debug/entity-aliases/$uid/active", """{"syntheticUserId":"$label","active":false}""").status)
            assertEquals(2, json(get("/debug/entities?syntheticUserId=$label").bodyAsText()).jsonArray.size)
            // The concept lookup shows each candidate's parent, where the parent came from, and the alias entries.
            val candidates = json(get("/debug/concepts?syntheticUserId=$label&name=Clark").bodyAsText()).jsonArray
            assertTrue(candidates.all { it.jsonObject["parentOrigin"]!!.jsonPrimitive.content == "issuer" && it.jsonObject["parent"]!!.jsonPrimitive.content == "PPS" })
        }
    }

    @Test
    fun `a run in progress is stopped at the end of its batch and continues with the same id, and one that is not running is refused`() = withEnv { m ->
        val uid = ingest(m, chunks = 12)
        val gate = CompletableDeferred<Unit>()
        val model = object : ClaimPassModel {
            override val endpoint: String? = null
            override val promptVersion = "test"
            override suspend fun extract(source: SourceRecord, chunk: ChunkRecord, pass: Int): PassResult {
                if (chunk.ordinal == 0) gate.await()
                return PassResult(listOf(PassClaim("Scenario ${chunk.ordinal + 1} closes ${11 + chunk.ordinal} schools.", null, "s", "NUMBER")), "Qwen3.6-35B-A3B-UD-Q4_K_M.gguf")
            }
        }
        testApplication {
            application { module(m, model) }
            assertEquals(HttpStatusCode.NotFound, post("/debug/claim-runs/nope/stop", """{"syntheticUserId":"$label"}""").status)
            assertEquals(HttpStatusCode.Accepted, post("/debug/sources/$uid/claims/extract", """{"syntheticUserId":"$label","runId":"stop-run"}""").status)
            assertEquals(HttpStatusCode.Accepted, post("/debug/claim-runs/stop-run/stop", """{"syntheticUserId":"$label"}""").status)
            gate.complete(Unit)
            var status = ""
            repeat(400) { if (status != "stopped") { delay(50); status = json(get("/debug/claim-runs/stop-run?syntheticUserId=$label").bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content.orEmpty() } }
            assertEquals("stopped", status)
            assertEquals("10", json(get("/debug/claim-runs/stop-run?syntheticUserId=$label").bodyAsText()).jsonObject["chunksDone"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.Conflict, post("/debug/claim-runs/stop-run/stop", """{"syntheticUserId":"$label"}""").status, "it is no longer running")
            assertEquals("resumed", json(post("/debug/sources/$uid/claims/extract", """{"syntheticUserId":"$label","runId":"stop-run"}""").bodyAsText()).jsonObject["state"]!!.jsonPrimitive.content)
        }
    }
}
