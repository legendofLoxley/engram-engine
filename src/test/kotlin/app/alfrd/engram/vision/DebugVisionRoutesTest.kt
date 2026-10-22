package app.alfrd.engram.vision

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.configureDebugClaimRoutes
import app.alfrd.engram.api.configureDebugVisionRoutes
import app.alfrd.engram.api.connectedFromLoopback
import app.alfrd.engram.claims.FakeClaimModel
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.PdfFixtures
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PdfSourceService
import app.alfrd.engram.pdf.poster
import app.alfrd.engram.pdf.scenarios
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
import java.io.File
import java.nio.file.Files

class DebugVisionRoutesTest {
    private val token = "test-debug-token-vision"
    private val auth = "Bearer $token"
    private val label = "vision-route-1"
    private val email = DebugConverseService.resolveUserId(label)

    private val answer = """{"sections": [{"heading": "SCENARIO A", "subsections": [{"heading": "BOUNDARY CHANGES", "numbers": ["38"]}]}]}"""

    private fun withEnv(block: (DatabaseManager, File, String) -> Unit) {
        val dir = Files.createTempDirectory("engram-vision-routes-").toFile()
        val manager = DatabaseManager(File(dir, "db").path)
        try {
            val db = manager.getDatabase(); SchemaBootstrap.bootstrap(db); DebugConverseService.ensureSyntheticUser(db, email)
            val pdf = File(dir, "cards.pdf").apply { writeBytes(PdfFixtures.poster()) }
            val uid = PdfSourceService(SourceStore(db), PdfExtractor(), PdfPreparationCache(File(dir, "cache"))).commit(email, pdf.readBytes(), "file:cards.pdf").sourceUid
            block(manager, dir, uid)
        } finally { manager.close(); dir.deleteRecursively() }
    }

    private fun Application.module(m: DatabaseManager, dir: File, model: VisionModel, isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == token) UserIdPrincipal("debug") else null } } }
        configureDebugVisionRoutes(m.getDatabase(), model, null, PdfExtractor(), PdfPreparationCache(File(dir, "cache")), isLoopback)
        configureDebugClaimRoutes(m.getDatabase(), modelFactory = { FakeClaimModel { _, _ -> emptyList() } }, isLoopback = isLoopback)
    }

    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `the routes need the token, a loopback caller, a synthetic identity and the source's own pdf`() = withEnv { m, dir, uid ->
        val pdf = File(dir, "cards.pdf").absolutePath
        testApplication {
            application { module(m, dir, VisionModel { _, _ -> error("not called") }) }
            suspend fun post(path: String, body: String, withAuth: Boolean = true) = client.post("/debug/sources/$uid/vision/$path") {
                if (withAuth) header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(body)
            }
            assertEquals(HttpStatusCode.Unauthorized, post("plan", """{"syntheticUserId":"$label","path":"$pdf"}""", withAuth = false).status)
            assertEquals(HttpStatusCode.Forbidden, post("plan", """{"syntheticUserId":"owner-jacob","path":"$pdf"}""").status)
            assertEquals(HttpStatusCode.BadRequest, post("plan", """{"syntheticUserId":"$label","path":"/etc/hostname"}""").status)
            val other = File(dir, "other.pdf").apply { writeBytes(PdfFixtures.scenarios()) }
            val wrong = post("plan", """{"syntheticUserId":"$label","path":"${other.absolutePath}"}""")
            assertEquals(HttpStatusCode.BadRequest, wrong.status); assertTrue(wrong.bodyAsText().contains("SHA-256"))
            assertEquals(HttpStatusCode.BadRequest, post("page", """{"syntheticUserId":"$label","path":"$pdf"}""").status, "page is required")
        }
        testApplication {
            application { module(m, dir, VisionModel { _, _ -> error("not called") }, isLoopback = { false }) }
            assertEquals(HttpStatusCode.Forbidden, client.post("/debug/sources/$uid/vision/plan") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody("not json") }.status)
        }
    }

    @Test
    fun `a page is read into a chunk, read again without a model call, and claimed from with the structure reader`() = withEnv { m, dir, uid ->
        val pdf = File(dir, "cards.pdf").absolutePath
        var calls = 0
        testApplication {
            application { module(m, dir, VisionModel { _, _ -> calls++; VisionAnswer(answer, "Qwen3.8-27B", 5, "stop", 10) }) }
            suspend fun post(path: String, body: String) = client.post(path) { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(body) }
            val plan = json(post("/debug/sources/$uid/vision/plan", """{"syntheticUserId":"$label","path":"$pdf"}""").bodyAsText()).jsonArray
            assertEquals(1, plan.size)
            val page = json(post("/debug/sources/$uid/vision/page", """{"syntheticUserId":"$label","path":"$pdf","page":1}""").bodyAsText()).jsonObject
            assertEquals("added", page["outcome"]!!.jsonPrimitive.content)
            assertEquals("already there", json(post("/debug/sources/$uid/vision/page", """{"syntheticUserId":"$label","path":"$pdf","page":1}""").bodyAsText()).jsonObject["outcome"]!!.jsonPrimitive.content)
            assertEquals(1, calls)

            assertEquals(HttpStatusCode.Accepted, post("/debug/sources/$uid/claims/extract", """{"syntheticUserId":"$label","runId":"s1","reader":"structure"}""").status)
            assertEquals(HttpStatusCode.BadRequest, post("/debug/sources/$uid/claims/extract", """{"syntheticUserId":"$label","runId":"s2","reader":"magic"}""").status)
            repeat(200) { if (m.getDatabase().query("sql", "SELECT status FROM ExtractionRun").use { it.hasNext() && it.next().getProperty<String>("status") == "complete" }.not()) delay(50) }
            val claims = json(client.get("/debug/sources/$uid/claims?syntheticUserId=$label") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonArray
            assertEquals(listOf("SCENARIO A > BOUNDARY CHANGES: 38"), claims.map { it.jsonObject["quote"]!!.jsonPrimitive.content })
            assertEquals("SCENARIO A", claims.single().jsonObject["place"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a failing vision server answers 502 with the reason and stores nothing`() = withEnv { m, dir, uid ->
        val pdf = File(dir, "cards.pdf").absolutePath
        testApplication {
            application { module(m, dir, VisionModel { _, _ -> throw java.io.IOException("down") }) }
            val r = client.post("/debug/sources/$uid/vision/page") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody("""{"syntheticUserId":"$label","path":"$pdf","page":1}""") }
            assertEquals(HttpStatusCode.BadGateway, r.status)
            assertTrue(r.bodyAsText().contains("IOException"))
        }
        assertEquals(0L, m.getDatabase().query("sql", "SELECT count(*) AS n FROM Chunk WHERE kind = 'vision'").use { it.next().getProperty<Number>("n").toLong() })
    }
}
