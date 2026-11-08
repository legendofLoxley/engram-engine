package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.configureOwnerHorizonRoutes
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshotBuilder
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.UUID

private class QuietStateProbe : SelfStateProbe {
    override fun servers() = listOf(ServerStatus("text reader", 8081, true, 1))
    override fun memory() = MemoryUse(1, 2, 3, 4)
}

/** Evidence, staleness and the also-due block through the owner's Horizon tool, each test on its own scratch database. */
class OwnerHorizonStateTest {
    private lateinit var tmp: Path
    private lateinit var manager: DatabaseManager
    private lateinit var token: String
    private lateinit var email: String
    private lateinit var startup: OwnerHorizonStartup
    private val zone = ZoneId.systemDefault()

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        tmp = Files.createTempDirectory("engram-owner-state-")
        manager = DatabaseManager(tmp.resolve("db").toString())
        SchemaBootstrap.bootstrap(manager.getDatabase())
        email = DebugConverseService.resolveUserId("owner-state")
        val db = manager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "owner-state"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        startup = bootstrapOwnerHorizon(
            db, NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false),
            mapOf(ENV_OWNER_IDENTITY to "owner-state", ENV_OWNER_TOKEN_FILE to tmp.resolve("cfg").resolve("owner-token").toString()), QuietStateProbe(),
        )
        token = (startup as OwnerHorizonStartup.Running).token
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() { manager.close(); tmp.toFile().deleteRecursively() }

    private fun app(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            configureOwnerHorizonRoutes(startup) { true }
        }
        block(client)
    }

    private fun q(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

    private suspend fun HttpClient.send(message: String?, ops: String = "[]", sources: String? = null): JsonObject {
        val body = buildString {
            append("{")
            if (message != null) append("\"message\":${q(message)},")
            if (sources != null) append("\"sources\":$sources,")
            append("\"operations\":$ops}")
        }
        val r = post("/owner/horizon") { header(HttpHeaders.Authorization, "Bearer $token"); header(HttpHeaders.ContentType, "application/json"); setBody(body) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private fun sourceFor(id: String, title: String, text: String) =
        """[{"id":"$id","title":${q(title)},"kind":"notion_page","text":${q(text)}}]"""

    private fun taskFrom(id: String, quote: String, title: String) =
        """{"verb":"record_item","source":"$id","kind":"task","quote":${q(quote)},"title":${q(title)}}"""

    private fun JsonObject.op(i: Int) = this["operations"]!!.jsonArray[i].jsonObject
    private fun JsonObject.applied(i: Int) = op(i)["applied"]!!.jsonPrimitive.boolean
    private fun JsonObject.detail(i: Int) = op(i)["detail"]!!.jsonPrimitive.content
    private fun JsonObject.horizon() = this["horizon"]!!.jsonPrimitive.content
    private fun JsonObject.refs() = this["refs"]!!.jsonPrimitive.content
    private fun refOf(refs: String, fragment: String): String =
        Regex("""\b(i\d+) \w+ "[^"]*${Regex.escape(fragment)}""").find(refs)?.groupValues?.get(1) ?: error("no ref for '$fragment' in:\n$refs")

    // ── Evidence, staleness and the also-due block through the tool ──────────

    private fun taskRow(text: String) = manager.getDatabase().query("sql", "SELECT FROM Task WHERE text = :t", mapOf("t" to text)).use { it.next().toElement().asVertex() }
    private fun ageEvidence(text: String, daysAgo: Long) {
        val db = manager.getDatabase()
        db.transaction { db.command("sql", "UPDATE Task SET lastEvidenceAt = :a WHERE text = :t", mapOf("a" to System.currentTimeMillis() - daysAgo * 86_400_000L, "t" to text)) }
    }
    private fun evidenceOf(text: String) = (taskRow(text).get("lastEvidenceAt") as Number).toLong()

    @Test
    fun `a stale question and an also-due line carry refs the listing gives them, and confirm_item answers the question`() = app { c ->
        val msg = "File the zoning form and book the inspector for tomorrow."
        val tomorrow = java.time.LocalDate.now(zone).plusDays(1)
        val long = c.send(
            msg,
            """[{"verb":"record_item","kind":"task","quote":"File the zoning form","title":"File the zoning form","due":"2020-02-01"},
                {"verb":"record_item","kind":"task","quote":"book the inspector for tomorrow","title":"Book the inspector","due":"$tomorrow","due_kind":"hard"}]""",
        )
        assertTrue(long.applied(0) && long.applied(1), long.toString())
        // Nothing has confirmed the zoning form for a long time.
        ageEvidence("File the zoning form", 60)

        val read = c.send(null, "[]")
        val h = read.horizon()
        val question = h.lines().first { it.contains("\"File the zoning form\"") }
        assertTrue(question.startsWith("- ? \"File the zoning form\": due 2020-02-01 (soft), no evidence since "), question)
        assertTrue(question.endsWith("Still live?" + " [" + refOf(read.refs(), "File the zoning form") + "]"), question)
        val alsoDue = h.substringAfter("Also due").lines().first { it.contains("Book the inspector") }
        val ref = refOf(read.refs(), "Book the inspector")
        assertTrue(alsoDue.startsWith("- $ref \"Book the inspector\" — due tomorrow ($tomorrow, hard)"), alsoDue)

        // "Yes, still live": evidence only, the quote guard as ever.
        val bad = c.send(msg, """[{"verb":"confirm_item","item":"${refOf(read.refs(), "File the zoning form")}","quote":"it is still live"}]""")
        assertEquals("ungrounded_quote", bad.detail(0))
        val before = evidenceOf("File the zoning form")
        val said = "The zoning form is still live."
        val ok = c.send(said, """[{"verb":"confirm_item","item":"${refOf(read.refs(), "File the zoning form")}","quote":"still live"}]""")
        assertTrue(ok.applied(0), ok.toString())
        assertTrue(evidenceOf("File the zoning form") > before)
        assertEquals("open", taskRow("File the zoning form").get("state")); assertEquals(null, taskRow("File the zoning form").get("resolvedAt"))
        assertFalse(c.send(null, "[]").horizon().lines().first { it.contains("\"File the zoning form\"") }.contains("Still live?"), "confirmed, it is not asked about again")
    }

    @Test
    fun `pinning through the tool is not evidence`() = app { c ->
        val msg = "Renew the parking permit."
        val made = c.send(msg, """[{"verb":"record_item","kind":"task","quote":"Renew the parking permit","title":"Renew the parking permit"}]""")
        assertTrue(made.applied(0), made.toString())
        ageEvidence("Renew the parking permit", 40)
        val old = evidenceOf("Renew the parking permit")
        val ref = refOf(made.refs(), "Renew the parking permit")
        val pinned = c.send("Keep the parking permit in front of me.", """[{"verb":"set_attention","item":"$ref","attention":"pinned","quote":"Keep the parking permit in front of me"}]""")
        assertTrue(pinned.applied(0), pinned.toString())
        assertEquals(old, evidenceOf("Renew the parking permit"))
    }

    @Test
    fun `a document sent again confirms what was filed from it, unchanged or not, unless the passage is gone`() = app { c ->
        val id = "ffff0000-0000-0000-0000-00000000000f"
        val text = "# Board\n\nReview the budget before the board meeting.\n\nCollect the signed minutes."
        val first = c.send(null, "[${taskFrom(id, "Review the budget before the board meeting", "Review the board budget")},${taskFrom(id, "Collect the signed minutes", "Collect signed minutes")}]", sourceFor(id, "Board", text))
        assertTrue(first.applied(0) && first.applied(1), first.toString())
        ageEvidence("Review the board budget", 50); ageEvidence("Collect signed minutes", 50)
        val old = evidenceOf("Review the board budget")

        c.send(null, "[]", sourceFor(id, "Board", text)) // the very same text again
        assertTrue(evidenceOf("Review the board budget") > old, "an unchanged re-send confirms")
        assertTrue(evidenceOf("Collect signed minutes") > old)

        ageEvidence("Review the board budget", 50); ageEvidence("Collect signed minutes", 50)
        val changed = "# Board\n\nReview the budget before the board meeting.\n\nThe minutes are now filed elsewhere."
        c.send(null, "[]", sourceFor(id, "Board", changed))
        assertTrue(evidenceOf("Review the board budget") > old, "its passage is still there")
        assertTrue(evidenceOf("Collect signed minutes") < old + 1_000L, "its passage is gone, so nothing confirmed it")
    }
}
