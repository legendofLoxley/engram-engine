package app.alfrd.engram.api

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

private const val SOURCE_TEST_TOKEN = "test-debug-token-12345"

class DebugSourceRoutesTest {

    private fun withDb(block: (DatabaseManager) -> Unit) {
        val dir = Files.createTempDirectory("engram-source-routes-").toFile()
        val manager = DatabaseManager(java.io.File(dir, "db").path)
        try { SchemaBootstrap.bootstrap(manager.getDatabase()); block(manager) } finally { manager.close(); dir.deleteRecursively() }
    }

    private fun Application.module(manager: DatabaseManager, isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == SOURCE_TEST_TOKEN) UserIdPrincipal("debug") else null } } }
        configureDebugSourceRoutes(manager.getDatabase(), isLoopback)
    }

    /** A count that needs no transaction on the calling thread. */
    private fun n(manager: DatabaseManager, type: String): Long =
        manager.getDatabase().query("sql", "SELECT count(*) AS n FROM `$type`").use { (it.next().getProperty<Number>("n")).toLong() }

    private val auth = "Bearer $SOURCE_TEST_TOKEN"

    private fun commitBody(user: String = "sources-route-1") = """
        {"syntheticUserId":"$user","source":{"type":"email_message","locator":"<m1@x.org>","title":"Re: Venue","threadId":"t-1",
         "text":"The venue costs 3,000 euros.\n\nOn Mon, 2 Mar 2026 at 09:00, Ana <a@x.org> wrote:\n> Is the venue booked?"},
         "items":[{"kind":"fact","text":"Venue costs 3,000 euros","quote":"The venue costs 3,000 euros.","chunk":0,"claimant":"Ana"}]}
    """.trimIndent()

    @Test
    fun `the routes need the token, a loopback caller and a synthetic identity that is not owner-jacob`() = withDb { manager ->
        testApplication {
            application { module(manager) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/debug/sources/commit") { setBody(commitBody()) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/debug/sources?syntheticUserId=x").status)
            val owner = client.post("/debug/sources/commit") {
                header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(commitBody("owner-jacob"))
            }
            assertEquals(HttpStatusCode.Forbidden, owner.status)
            assertEquals(0, n(manager, "Source"))
        }
        testApplication {
            application { module(manager, isLoopback = { false }) }
            for (r in listOf(
                client.post("/debug/sources/commit") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(commitBody()) },
                client.get("/debug/sources?syntheticUserId=sources-route-1") { header(HttpHeaders.Authorization, auth) },
                client.get("/debug/items/x/provenance?syntheticUserId=sources-route-1") { header(HttpHeaders.Authorization, auth) },
            )) assertEquals(HttpStatusCode.Forbidden, r.status)
            assertEquals(0, n(manager, "Source"))
        }
    }

    @Test
    fun `an email is committed, read back with its chunk and provenance, and committed again with nothing added`() = withDb { manager ->
        testApplication {
            application { module(manager) }
            suspend fun post(): JsonObject = Json.parseToJsonElement(client.post("/debug/sources/commit") {
                header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(commitBody())
            }.bodyAsText()).jsonObject
            suspend fun get(path: String) = Json.parseToJsonElement(client.get(path) { header(HttpHeaders.Authorization, auth) }.bodyAsText())

            val first = post()
            assertEquals("true", first["sourceCreated"]!!.jsonPrimitive.content)
            assertEquals("1", first["chunksCreated"]!!.jsonPrimitive.content)
            assertEquals("1", first["itemsCreated"]!!.jsonPrimitive.content)

            val sources = get("/debug/sources?syntheticUserId=sources-route-1").jsonArray
            val s = sources.single().jsonObject
            assertEquals("t-1", s["threadId"]!!.jsonPrimitive.content)
            val chunk = s["chunks"]!!.jsonArray.single().jsonObject
            assertEquals("The venue costs 3,000 euros.", chunk["text"]!!.jsonPrimitive.content, "quoted history was dropped")
            val itemUid = first["items"]!!.jsonArray.single().jsonObject["uid"]!!.jsonPrimitive.content
            val prov = get("/debug/items/$itemUid/provenance?syntheticUserId=sources-route-1").jsonArray.single().jsonObject
            assertEquals("message, part 1", prov["location"]!!.jsonPrimitive.content)
            assertEquals("director", prov["writer"]!!.jsonPrimitive.content)
            assertEquals("Ana", prov["claimant"]!!.jsonPrimitive.content)

            val before = listOf("Source", "Chunk", "Phrase", "ASSERTS", "QUOTES", "CONTAINS").map { n(manager, it) }
            val second = post()
            assertEquals("false", second["sourceCreated"]!!.jsonPrimitive.content)
            assertEquals("0", second["chunksCreated"]!!.jsonPrimitive.content)
            assertEquals("0", second["itemsCreated"]!!.jsonPrimitive.content)
            assertEquals("1", second["itemsAlreadyThere"]!!.jsonPrimitive.content)
            assertEquals(before, listOf("Source", "Chunk", "Phrase", "ASSERTS", "QUOTES", "CONTAINS").map { n(manager, it) })
        }
    }

    @Test
    fun `a bad item kind or a quote that is not in the chunk is refused and writes nothing`() = withDb { manager ->
        testApplication {
            application { module(manager) }
            val badKind = client.post("/debug/sources/commit") {
                header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json")
                setBody(commitBody().replace("\"kind\":\"fact\"", "\"kind\":\"rumour\""))
            }
            assertEquals(HttpStatusCode.BadRequest, badKind.status)
            val badQuote = Json.parseToJsonElement(client.post("/debug/sources/commit") {
                header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json")
                setBody(commitBody().replace("\"quote\":\"The venue costs 3,000 euros.\"", "\"quote\":\"The venue is free.\""))
            }.bodyAsText()).jsonObject
            assertEquals("0", badQuote["itemsCreated"]!!.jsonPrimitive.content)
            assertFalse(badQuote["items"]!!.jsonArray.single().jsonObject["applied"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(0, n(manager, "Phrase"))
            assertEquals(0, n(manager, "QUOTES"))
            assertTrue(n(manager, "Source") <= 2) // the source itself may be stored; no item is
        }
    }

    @Test
    fun `a caller that is not local gets 403 on commit whatever it sent, before the body is read`() = withDb { manager ->
        testApplication {
            application { module(manager, isLoopback = { false }) }
            for (body in listOf("not json at all", "{}", """{"syntheticUserId":""}""", commitBody())) {
                val r = client.post("/debug/sources/commit") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json"); setBody(body) }
                assertEquals(HttpStatusCode.Forbidden, r.status, body.take(30))
            }
            assertEquals(0L, n(manager, "Source"))
        }
    }

    @Test
    fun `an unknown readMode is a 400 and writes nothing`() = withDb { manager ->
        testApplication {
            application { module(manager) }
            val r = client.post("/debug/sources/commit") {
                header(HttpHeaders.Authorization, auth); header(HttpHeaders.ContentType, "application/json")
                setBody(commitBody().replace("\"title\":\"Re: Venue\"", "\"title\":\"Re: Venue\",\"readMode\":\"skimmed\""))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertEquals(0L, n(manager, "Source"))
        }
    }
}
