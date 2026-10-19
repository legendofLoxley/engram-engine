package app.alfrd.engram.pdf

import app.alfrd.engram.api.configureDebugPdfSourceRoutes
import app.alfrd.engram.api.configureDebugSourceRoutes
import app.alfrd.engram.api.connectedFromLoopback
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Base64

class DebugPdfSourceRoutesTest {
    private val token = "test-debug-token-pdf"
    private val auth = "Bearer $token"

    private fun withEnv(block: (DatabaseManager, java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("engram-pdf-routes-").toFile()
        val manager = DatabaseManager(java.io.File(dir, "db").path)
        try { SchemaBootstrap.bootstrap(manager.getDatabase()); block(manager, dir) } finally { manager.close(); dir.deleteRecursively() }
    }

    private fun Application.module(manager: DatabaseManager, dir: java.io.File, isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == token) UserIdPrincipal("debug") else null } } }
        configureDebugSourceRoutes(manager.getDatabase(), isLoopback)
        configureDebugPdfSourceRoutes(manager.getDatabase(), PdfExtractor(null), PdfPreparationCache(java.io.File(dir, "cache")), isLoopback)
    }

    private fun n(m: DatabaseManager, type: String) =
        m.getDatabase().query("sql", "SELECT count(*) AS n FROM `$type`").use { it.next().getProperty<Number>("n").toLong() }

    private fun body(user: String = "pdf-route-1", bytes: ByteArray = PdfFixtures.board(), locator: String = "file:board.pdf") =
        """{"syntheticUserId":"$user","locator":"$locator","contentBase64":"${Base64.getEncoder().encodeToString(bytes)}"}"""

    @Test
    fun `the route needs the token, a loopback caller and a synthetic identity that is not owner-jacob`() = withEnv { m, dir ->
        testApplication {
            application { module(m, dir) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/debug/sources/pdf") { setBody(body()) }.status)
            val owner = client.post("/debug/sources/pdf") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(body("owner-jacob")) }
            assertEquals(HttpStatusCode.Forbidden, owner.status)
            assertEquals(0L, n(m, "Source"))
        }
        testApplication {
            application { module(m, dir, isLoopback = { false }) }
            for (b in listOf("not json", "{}", body())) {
                assertEquals(HttpStatusCode.Forbidden, client.post("/debug/sources/pdf") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(b) }.status)
            }
            assertEquals(HttpStatusCode.Forbidden, client.get("/debug/sources/x/provenance?syntheticUserId=pdf-route-1") { header(HttpHeaders.Authorization, auth) }.status)
            assertEquals(0L, n(m, "Source"))
        }
    }

    @Test
    fun `a pdf is committed, shown with source and page for every chunk, and committed again with nothing added`() = withEnv { m, dir ->
        testApplication {
            application { module(m, dir) }
            suspend fun post(b: String): JsonObject = Json.parseToJsonElement(client.post("/debug/sources/pdf") {
                header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(b)
            }.bodyAsText()).jsonObject

            val first = post(body())
            assertEquals("true", first["created"]!!.jsonPrimitive.content)
            assertEquals("3", first["pageCount"]!!.jsonPrimitive.content)
            assertEquals("full", first["readMode"]!!.jsonPrimitive.content)
            val uid = first["sourceUid"]!!.jsonPrimitive.content

            val prov = Json.parseToJsonElement(client.get("/debug/sources/$uid/provenance?syntheticUserId=pdf-route-1") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonObject
            assertEquals("file:board.pdf", prov["source"]!!.jsonObject["locator"]!!.jsonPrimitive.content)
            assertEquals("full", prov["source"]!!.jsonObject["readMode"]!!.jsonPrimitive.content)
            assertEquals(64, prov["source"]!!.jsonObject["fileSha256"]!!.jsonPrimitive.content.length)
            val chunks = prov["chunks"]!!.jsonArray.map { it.jsonObject }
            assertTrue(chunks.isNotEmpty())
            for (c in chunks) {
                assertTrue(c["page"]!!.jsonPrimitive.content.toInt() in 1..3, c.toString())
                assertTrue(c["location"]!!.jsonPrimitive.content.isNotBlank())
            }
            assertEquals(setOf("2"), chunks.filter { it["page"]!!.jsonPrimitive.content == "3" }.map { it["printedPage"]!!.jsonPrimitive.content }.toSet())
            assertTrue(chunks.any { it["kind"]!!.jsonPrimitive.content == "table" })

            // The general sources listing carries the same page facts.
            val listed = Json.parseToJsonElement(client.get("/debug/sources?syntheticUserId=pdf-route-1") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonArray
            assertEquals("3", listed.single().jsonObject["chunks"]!!.jsonArray.map { it.jsonObject }.last()["page"]!!.jsonPrimitive.content)

            val before = listOf("Source", "Chunk", "CONTAINS", "TRUSTS").map { n(m, it) }
            val second = post(body())
            assertEquals("false", second["created"]!!.jsonPrimitive.content)
            assertEquals("true", second["fromCache"]!!.jsonPrimitive.content)
            assertEquals(before, listOf("Source", "Chunk", "CONTAINS", "TRUSTS").map { n(m, it) })
        }
    }

    @Test
    fun `a local file can be named by path, but only a pdf`() = withEnv { m, dir ->
        val pdf = java.io.File(dir, "b.pdf").also { it.writeBytes(PdfFixtures.board()) }
        val txt = java.io.File(dir, "secrets.txt").also { it.writeText("hello") }
        val fake = java.io.File(dir, "fake.pdf").also { it.writeText("hello") }
        testApplication {
            application { module(m, dir) }
            suspend fun status(path: String) = client.post("/debug/sources/pdf") {
                header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json")
                setBody("""{"syntheticUserId":"pdf-route-2","path":${Json.encodeToString(kotlinx.serialization.serializer<String>(), path)}}""")
            }.status
            assertEquals(HttpStatusCode.OK, status(pdf.path))
            assertEquals(HttpStatusCode.BadRequest, status(txt.path))
            assertEquals(HttpStatusCode.BadRequest, status(fake.path))
            assertEquals(HttpStatusCode.BadRequest, status(java.io.File(dir, "missing.pdf").path))
            assertEquals(1L, n(m, "Source"))
        }
    }

    @Test
    fun `bad input is a 400 that writes nothing`() = withEnv { m, dir ->
        testApplication {
            application { module(m, dir) }
            for (b in listOf(
                """{"syntheticUserId":"pdf-route-3"}""",
                """{"syntheticUserId":"pdf-route-3","contentBase64":"%%%"}""",
                body("pdf-route-3", "not a pdf".toByteArray()),
                body("pdf-route-3", PdfFixtures.build(listOf({ _ -> }))),
            )) {
                val r = client.post("/debug/sources/pdf") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(b) }
                assertEquals(HttpStatusCode.BadRequest, r.status, b.take(60))
                assertFalse(r.bodyAsText().contains("hello"))
            }
            assertEquals(0L, n(m, "Source")); assertEquals(0L, n(m, "Chunk"))
        }
    }
}
