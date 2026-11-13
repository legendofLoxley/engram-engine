package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.configureOwnerHorizonRoutes
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.UUID

private class QuietGoalsProbe : SelfStateProbe {
    override fun servers() = listOf(ServerStatus("text reader", 8081, true, 1))
    override fun memory() = MemoryUse(1, 2, 3, 4)
}

/** Goals through the owner's Horizon tool: proposed from the owner's words, approved only with a quote, locked once approved, shown in the board's Goals line. */
class OwnerHorizonGoalsTest {
    private lateinit var tmp: Path
    private lateinit var manager: DatabaseManager
    private lateinit var token: String
    private lateinit var startup: OwnerHorizonStartup
    private val zone = ZoneId.systemDefault()
    private val day = 86_400_000L
    private val pageId = "aaaa1111-2222-3333-4444-555566667777"

    @BeforeEach
    fun setUp() {
        tmp = Files.createTempDirectory("owner-goals")
        manager = DatabaseManager(tmp.resolve("db").toString())
        SchemaBootstrap.bootstrap(manager.getDatabase())
        val email = DebugConverseService.resolveUserId("owner-goals")
        val db = manager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "owner-goals"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        startup = bootstrapOwnerHorizon(
            db, NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false),
            mapOf(ENV_OWNER_IDENTITY to "owner-goals", ENV_OWNER_TOKEN_FILE to tmp.resolve("cfg").resolve("owner-token").toString()), QuietGoalsProbe(),
        )
        token = (startup as OwnerHorizonStartup.Running).token
    }

    @AfterEach
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

    private fun JsonObject.applied(i: Int) = this["operations"]!!.jsonArray[i].jsonObject["applied"]!!.jsonPrimitive.boolean
    private fun JsonObject.detail(i: Int) = this["operations"]!!.jsonArray[i].jsonObject["detail"]!!.jsonPrimitive.content
    private fun JsonObject.horizon() = this["horizon"]!!.jsonPrimitive.content
    private fun JsonObject.refs() = this["refs"]!!.jsonPrimitive.content
    private fun refOf(refs: String, fragment: String): String =
        Regex("""\b(i\d+) \w+ "[^"]*${Regex.escape(fragment)}""").find(refs)?.groupValues?.get(1) ?: error("no ref for '$fragment' in:\n$refs")

    private fun refOfGoal(refs: String, fragment: String): String =
        Regex("""\b(i\d+) goal "[^"]*${Regex.escape(fragment)}""").find(refs)?.groupValues?.get(1) ?: error("no goal ref for '$fragment' in:\n$refs")
    private fun goalRow(text: String) = manager.getDatabase().query("sql", "SELECT FROM Goal WHERE text = :t", mapOf("t" to text)).use { it.next().toElement().asVertex() }
    private fun JsonObject.reasonOf(i: Int) = this["operations"]!!.jsonArray[i].jsonObject["detail"]!!.jsonPrimitive.content

    @Test
    fun `a goal is proposed from the owner's words, runs only after a quoted approval, and its done-when is locked after`() = app { c ->
        val said = "I want the Williams items reviewed. Done when every open item has a proposal."
        val proposed = c.send(said, """[{"verb":"record_item","kind":"goal","quote":"I want the Williams items reviewed","title":"Review the Williams items","done_when":"every open item has a proposal"}]""")
        assertTrue(proposed.applied(0), proposed.toString())
        assertEquals("proposed", goalRow("Review the Williams items").get("approval"))
        assertTrue("Goals: 1 waiting for approval." in proposed.horizon(), proposed.horizon())

        val ref = refOfGoal(proposed.refs(), "Review the Williams items")
        // Running needs approval, and the refusal says so.
        val early = c.send("Start the Williams review now.", """[{"verb":"set_state","item":"$ref","state":"running","quote":"Start the Williams review now"}]""")
        assertFalse(early.applied(0)); assertTrue("not approved" in early.reasonOf(0), early.toString())
        assertEquals("open", goalRow("Review the Williams items").get("state"))

        // An approval that is a question, a negation or not in the message is refused, and nothing changes.
        assertEquals("question_detected", c.send("Should I go ahead?", """[{"verb":"approve_goal","item":"$ref","quote":"Should I go ahead?"}]""").reasonOf(0))
        assertEquals("negation_marker_detected", c.send("No, do not go ahead with that.", """[{"verb":"approve_goal","item":"$ref","quote":"do not go ahead with that"}]""").reasonOf(0))
        assertEquals("ungrounded_quote", c.send("Sounds fine.", """[{"verb":"approve_goal","item":"$ref","quote":"yes, go ahead"}]""").reasonOf(0))
        assertEquals("proposed", goalRow("Review the Williams items").get("approval"))

        // The owner's own words approve it, and it is kept.
        val approved = c.send("Yes, go ahead with the Williams review.", """[{"verb":"approve_goal","item":"$ref","quote":"go ahead with the Williams review"}]""")
        assertTrue(approved.applied(0), approved.toString())
        assertEquals("approved", goalRow("Review the Williams items").get("approval"))
        assertEquals("go ahead with the Williams review", goalRow("Review the Williams items").get("approvalQuote"))
        val running = c.send("Run it.", """[{"verb":"set_state","item":"$ref","state":"running","quote":"Run it"}]""")
        assertTrue(running.applied(0), running.toString())
        assertEquals("active", goalRow("Review the Williams items").get("state"))
        assertTrue("Goals: 1 running." in running.horizon(), running.horizon())

        // The bar does not move, however it is asked.
        val easier = c.send("Make it easier: done when half are reviewed.", """[{"verb":"edit_goal","item":"$ref","done_when":"half are reviewed","quote":"Make it easier"}]""")
        assertFalse(easier.applied(0)); assertTrue("locked" in easier.reasonOf(0) && "propose a new one" in easier.reasonOf(0), easier.toString())
        val corrected = c.send("Change the goal to something easier.", """[{"verb":"correct_item","item":"$ref","title":"Something easier","quote":"Change the goal to something easier"}]""")
        assertEquals("goal_uses_edit_goal", corrected.reasonOf(0))
        assertEquals("every open item has a proposal", goalRow("Review the Williams items").get("doneWhen"))
        val count = manager.getDatabase().query("sql", "SELECT count(*) AS n FROM Goal").use { it.next().getProperty<Number>("n").toLong() }
        assertEquals(1L, count, "no second goal was written by the correction")

        // stop_when and the budget may still change, and a stopped goal leaves the board.
        assertTrue(c.send("Give it five cycles.", """[{"verb":"edit_goal","item":"$ref","budget":"5 cycles","quote":"Give it five cycles"}]""").applied(0))
        val stopped = c.send("Stop the Williams review.", """[{"verb":"set_state","item":"$ref","state":"stopped","quote":"Stop the Williams review"}]""")
        assertTrue(stopped.applied(0), stopped.toString())
        assertFalse("Goals:" in stopped.horizon(), stopped.horizon())
    }

    @Test
    fun `a goal cannot be filed from a document, and zero goals leave the board as it was`() = app { c ->
        val before = c.send(null, "[]").horizon()
        assertFalse("Goals" in before, before)
        val id = "cccc1111-2222-3333-4444-555566667777"
        val page = """[{"id":"$id","title":"Plan","kind":"notion_page","text":${q("# Plan\n\nReview everything about Williams.")}}]"""
        val r = c.send(null, """[{"verb":"record_item","source":"$id","kind":"goal","quote":"Review everything about Williams"}]""", page)
        assertFalse(r.applied(0), r.toString())
        assertEquals(0L, manager.getDatabase().query("sql", "SELECT count(*) AS n FROM Goal").use { it.next().getProperty<Number>("n").toLong() })
    }
}
