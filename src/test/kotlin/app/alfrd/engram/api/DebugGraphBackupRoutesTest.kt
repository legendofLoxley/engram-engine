package app.alfrd.engram.api

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.LocalGraphBackup
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

private const val BACKUP_TEST_TOKEN = "test-debug-token-12345"

class DebugGraphBackupRoutesTest {

    private fun withDb(block: (DatabaseManager, File) -> Unit) {
        val base = Files.createTempDirectory("engram-backup-route-").toFile()
        val manager = DatabaseManager(File(base, "data/engram-db").path)
        try {
            SchemaBootstrap.bootstrap(manager.getDatabase())
            block(manager, base)
        } finally {
            manager.close()
            base.deleteRecursively()
        }
    }

    private fun Application.module(manager: DatabaseManager, backup: LocalGraphBackup?, isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) {
            bearer("debug-token") { authenticate { c -> if (c.token == BACKUP_TEST_TOKEN) UserIdPrincipal("debug") else null } }
        }
        configureDebugGraphBackupRoutes(manager.getDatabase(), backup, isLoopback)
    }

    @Test
    fun `both routes need the debug token`() = withDb { manager, base ->
        val backup = LocalGraphBackup(manager.getDatabase(), File(base, "backups"), null, File(base, "data/engram-db"))
        testApplication {
            application { module(manager, backup) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/debug/graph-backup").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/debug/graph-counts").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/debug/graph-backup") { header(HttpHeaders.Authorization, "Bearer wrong") }.status)
        }
    }

    @Test
    fun `the backup route returns metadata only, and takes no input that names a path`() = withDb { manager, base ->
        val backup = LocalGraphBackup(manager.getDatabase(), File(base, "backups"), null, File(base, "data/engram-db"))
        testApplication {
            application { module(manager, backup) }
            // A path smuggled in as a query parameter, a header or a body is ignored: the destination is fixed by configuration.
            val r = client.post("/debug/graph-backup?path=/etc&file=../../x") {
                header(HttpHeaders.Authorization, "Bearer $BACKUP_TEST_TOKEN")
                header("X-Path", "/etc")
                setBody("{\"path\":\"/etc\"}")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            val text = r.bodyAsText()
            val body = Json.parseToJsonElement(text).jsonObject
            assertEquals(setOf("fileName", "sizeBytes", "entries", "durationMs", "kept", "pruned", "copiedToSecond"), body.keys)
            assertTrue(body["fileName"]!!.jsonPrimitive.content.matches(Regex("graph-\\d{8}T\\d{6}Z\\.zip")))
            assertFalse(text.contains("/"), "no path in the response: $text")
            assertTrue(File(File(base, "backups/auto"), body["fileName"]!!.jsonPrimitive.content).exists())
            assertFalse(File("/etc/x").exists())
        }
    }

    @Test
    fun `without a backup folder the backup route does not exist`() = withDb { manager, _ ->
        testApplication {
            application { module(manager, null) }
            assertEquals(HttpStatusCode.NotFound, client.post("/debug/graph-backup") { header(HttpHeaders.Authorization, "Bearer $BACKUP_TEST_TOKEN") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/debug/graph-counts") { header(HttpHeaders.Authorization, "Bearer $BACKUP_TEST_TOKEN") }.status)
        }
    }

    @Test
    fun `the counts route gives vertices and edges per type, and whether owner-jacob is present, as numbers and a flag`() = withDb { manager, _ ->
        val db = manager.getDatabase()
        testApplication {
            application { module(manager, null) }
            suspend fun counts() = Json.parseToJsonElement(client.get("/debug/graph-counts") { header(HttpHeaders.Authorization, "Bearer $BACKUP_TEST_TOKEN") }.bodyAsText()).jsonObject
            val before = counts()
            assertEquals("false", before["ownerJacobPresent"]!!.jsonPrimitive.content)
            assertEquals("0", before["vertices"]!!.jsonObject["User"]!!.jsonPrimitive.content)
            db.transaction { db.newVertex("User").set("uid", "u").set("email", "debug+owner-jacob@test.alfrd.internal").set("username", "n").save() }
            val after = counts()
            assertEquals("true", after["ownerJacobPresent"]!!.jsonPrimitive.content)
            assertEquals("1", after["vertices"]!!.jsonObject["User"]!!.jsonPrimitive.content)
            assertTrue(after["edges"]!!.jsonObject.isNotEmpty())
        }
    }

    @Test
    fun `a caller that is not on loopback is refused on both routes, even with the right token`() = withDb { manager, base ->
        val backup = LocalGraphBackup(manager.getDatabase(), File(base, "backups"), null, File(base, "data/engram-db"))
        testApplication {
            application { module(manager, backup, isLoopback = { false }) }
            val auth = "Bearer $BACKUP_TEST_TOKEN"
            assertEquals(HttpStatusCode.Forbidden, client.get("/debug/graph-counts") { header(HttpHeaders.Authorization, auth) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/debug/graph-backup") { header(HttpHeaders.Authorization, auth) }.status)
            assertFalse(File(base, "backups/auto").exists() && File(base, "backups/auto").list()!!.isNotEmpty(), "a refused caller made no backup")
            // Still the token first: a non-loopback caller without it is 401, not 403.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/debug/graph-counts").status)
        }
    }

    @Test
    fun `the default check accepts the test engine's own connection and a forwarded header cannot make a remote caller look local`() = withDb { manager, _ ->
        testApplication {
            application { module(manager, null) }
            // The default check reads the real connection, so a spoofed X-Forwarded-For changes nothing.
            val r = client.get("/debug/graph-counts") { header(HttpHeaders.Authorization, "Bearer $BACKUP_TEST_TOKEN"); header("X-Forwarded-For", "203.0.113.9") }
            assertEquals(HttpStatusCode.OK, r.status)
        }
    }

    @Test
    fun `loopback peers are 127_0_0_1, the IPv6 loopback and its mapped form, and nothing else`() {
        for (ok in listOf("127.0.0.1", "127.1.2.3", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "localhost")) assertTrue(isLoopbackPeer(ok), ok)
        for (no in listOf("192.168.1.20", "172.17.0.1", "10.0.0.5", "203.0.113.9", "0.0.0.0", "fe80::1", "not an address", "")) assertFalse(isLoopbackPeer(no), no)
    }
}
