package app.alfrd.engram.drive

import app.alfrd.engram.api.configureDebugDriveSourceRoutes
import app.alfrd.engram.api.configureDebugPdfSourceRoutes
import app.alfrd.engram.api.connectedFromLoopback
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.PdfFixtures
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PdfSourceService
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class DebugDriveSourceRoutesTest {
    private val token = "test-debug-token-drive"
    private val auth = "Bearer $token"
    private val folderId = "EVIDENCE-FOLDER"

    private fun Application.module(manager: DatabaseManager, dir: File, shelf: () -> DriveShelf?, isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == token) UserIdPrincipal("debug") else null } } }
        configureDebugPdfSourceRoutes(manager.getDatabase(), PdfExtractor(null), PdfPreparationCache(File(dir, "cache")), isLoopback)
        configureDebugDriveSourceRoutes(manager.getDatabase(), shelf, isLoopback)
    }

    private fun n(m: DatabaseManager, type: String) = m.getDatabase().query("sql", "SELECT count(*) AS n FROM `$type`").use { it.next().getProperty<Number>("n").toLong() }

    private fun withEnv(block: (DatabaseManager, File, FakeGoogle, DriveShelfService) -> Unit) {
        val dir = Files.createTempDirectory("engram-drive-routes-").toFile()
        val manager = DatabaseManager(File(dir, "db").path)
        val g = FakeGoogle(); val server = MockWebServer(); server.dispatcher = g; server.start()
        try {
            val db = manager.getDatabase(); SchemaBootstrap.bootstrap(db)
            val http = DriveHttp.client(setOf(server.hostName))
            val creds = GoogleCredentials.parse("""{"client_id":"$FAKE_CLIENT_ID","client_secret":"$FAKE_CLIENT_SECRET","refresh_token":"$FAKE_REFRESH"}""")
            val drive = DriveClient(GoogleAccessTokens(creds, http, server.url("/token").toString()), http, server.url("/").toString().trimEnd('/'), sleep = {})
            g.folder(folderId)
            val store = SourceStore(db)
            block(manager, dir, g, DriveShelfService(drive, PdfSourceService(store, PdfExtractor(null), PdfPreparationCache(File(dir, "cache"))), store, folderId))
        } finally { server.shutdown(); manager.close(); dir.deleteRecursively() }
    }

    private fun post(body: String = """{"syntheticUserId":"drive-route-1"}""") = body

    @Test
    fun `the routes need the token, a loopback caller and a synthetic identity that is not owner-jacob, and Drive is not touched otherwise`() = withEnv { m, dir, g, shelf ->
        var asked = 0
        val counting = { asked++; shelf }
        testApplication {
            application { module(m, dir, counting) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/debug/sources/drive") { setBody(post()) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/debug/drive/files").status)
            val owner = client.post("/debug/sources/drive") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(post("""{"syntheticUserId":"owner-jacob"}""")) }
            assertEquals(HttpStatusCode.Forbidden, owner.status)
        }
        testApplication {
            application { module(m, dir, counting, isLoopback = { false }) }
            for (b in listOf("not json", "{}", post())) assertEquals(HttpStatusCode.Forbidden, client.post("/debug/sources/drive") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(b) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/debug/drive/files") { header(HttpHeaders.Authorization, auth) }.status)
        }
        assertEquals(0, asked, "the shelf is not even built for a refused caller")
        assertEquals(0, g.requests.size); assertEquals(0L, n(m, "Source"))
    }

    @Test
    fun `without configuration the route says what is missing and touches nothing`() = withEnv { m, dir, g, _ ->
        testApplication {
            application { module(m, dir, { null }) }
            val r = client.post("/debug/sources/drive") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(post()) }
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            val msg = r.bodyAsText()
            assertTrue(msg.contains("GOOGLE_DRIVE_CREDENTIALS_FILE") && msg.contains("GOOGLE_DRIVE_FOLDER_ID"), msg)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/debug/drive/files") { header(HttpHeaders.Authorization, auth) }.status)
        }
        assertEquals(0, g.requests.size); assertEquals(0L, n(m, "Source"))
    }

    @Test
    fun `a folder is ingested, shown with Drive id, link, version and page for every chunk, and ingested again with nothing added`() = withEnv { m, dir, g, shelf ->
        g.pdf("id-a", "SEGC Equity Audit Evaluation Report.pdf", PdfFixtures.board(), folderId, version = 5)
        g.pdf("id-b", "District wide scenarios.pdf", PdfFixtures.scenarios(), folderId, version = 2)
        testApplication {
            application { module(m, dir, { shelf }) }
            suspend fun ingest(): JsonObject = Json.parseToJsonElement(client.post("/debug/sources/drive") {
                header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(post())
            }.bodyAsText()).jsonObject

            val listed = Json.parseToJsonElement(client.get("/debug/drive/files") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonObject
            assertEquals(folderId, listed["folderId"]!!.jsonPrimitive.content)
            assertEquals(setOf("SEGC Equity Audit Evaluation Report.pdf", "District wide scenarios.pdf"), listed["files"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())

            val first = ingest()
            assertEquals("2", first["created"]!!.jsonPrimitive.content)
            val a = first["files"]!!.jsonArray.map { it.jsonObject }.single { it["fileId"]!!.jsonPrimitive.content == "id-a" }
            val uid = a["sourceUid"]!!.jsonPrimitive.content
            assertEquals("3", a["pageCount"]!!.jsonPrimitive.content); assertEquals("full", a["readMode"]!!.jsonPrimitive.content)

            val prov = Json.parseToJsonElement(client.get("/debug/sources/$uid/provenance?syntheticUserId=drive-route-1") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).jsonObject
            val drive = prov["source"]!!.jsonObject["drive"]!!.jsonObject
            assertEquals("id-a", drive["fileId"]!!.jsonPrimitive.content)
            assertEquals("https://drive.google.com/file/d/id-a/view?usp=drivesdk", drive["webViewLink"]!!.jsonPrimitive.content)
            assertEquals("5", drive["version"]!!.jsonPrimitive.content); assertEquals("SEGC Equity Audit Evaluation Report.pdf", drive["fileName"]!!.jsonPrimitive.content)
            assertEquals("gdrive:id-a", prov["source"]!!.jsonObject["locator"]!!.jsonPrimitive.content)
            val chunks = prov["chunks"]!!.jsonArray.map { it.jsonObject }
            assertTrue(chunks.isNotEmpty() && chunks.all { it["page"]!!.jsonPrimitive.content.toInt() in 1..3 })

            val before = listOf("Source", "Chunk", "CONTAINS", "TRUSTS").map { n(m, it) }
            val second = ingest()
            assertEquals("0", second["created"]!!.jsonPrimitive.content); assertEquals("2", second["unchanged"]!!.jsonPrimitive.content)
            assertEquals(before, listOf("Source", "Chunk", "CONTAINS", "TRUSTS").map { n(m, it) })
        }
    }

    @Test
    fun `a Drive failure is a 502 that carries the status only, and a bad credentials file a 503 that names the rule only`() = withEnv { m, dir, _, _ ->
        val failing = object : DriveShelf {
            override val folderId = "F"
            override fun listPdfs(): List<DriveFile> = throw DriveApiException(403, "drive request failed (status 403)")
            override fun ingest(userEmail: String): DriveIngestReport = throw DriveApiException(403, "drive request failed (status 403)")
        }
        val badCreds = object : DriveShelf {
            override val folderId = "F"
            override fun listPdfs(): List<DriveFile> = throw DriveConfigException("the credentials file must be mode 600 (owner read and write only)")
            override fun ingest(userEmail: String): DriveIngestReport = throw DriveConfigException("the credentials file must be mode 600 (owner read and write only)")
        }
        testApplication {
            application { module(m, dir, { failing }) }
            val r = client.post("/debug/sources/drive") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(post()) }
            assertEquals(HttpStatusCode.BadGateway, r.status); assertTrue(r.bodyAsText().contains("403"))
        }
        testApplication {
            application { module(m, dir, { badCreds }) }
            val r = client.post("/debug/sources/drive") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(post()) }
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status); assertTrue(r.bodyAsText().contains("600"))
            assertFalse(r.bodyAsText().contains(FAKE_REFRESH))
        }
    }
}
