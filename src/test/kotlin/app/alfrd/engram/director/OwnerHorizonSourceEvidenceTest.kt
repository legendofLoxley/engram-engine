package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.configureOwnerHorizonRoutes
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceEditTime
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private class QuietEvidenceProbe : SelfStateProbe {
    override fun servers() = listOf(ServerStatus("text reader", 8081, true, 1))
    override fun memory() = MemoryUse(1, 2, 3, 4)
}

/**
 * A source's `last_edited` is the evidence date of the items filed from it, through the owner's Horizon tool: an old page's tasks are asked about, not raised
 * as overdue; a resend refreshes only when the page is later; an item imported before this can be corrected, but never one the owner has touched.
 */
class OwnerHorizonSourceEvidenceTest {
    private lateinit var tmp: Path
    private lateinit var manager: DatabaseManager
    private lateinit var token: String
    private lateinit var startup: OwnerHorizonStartup
    private val zone = ZoneId.systemDefault()
    private val day = 86_400_000L
    private val pageId = "aaaa1111-2222-3333-4444-555566667777"

    @BeforeEach
    fun setUp() {
        tmp = Files.createTempDirectory("owner-source-evidence")
        manager = DatabaseManager(tmp.resolve("db").toString())
        SchemaBootstrap.bootstrap(manager.getDatabase())
        val email = DebugConverseService.resolveUserId("owner-evidence")
        val db = manager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "owner-evidence"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        startup = bootstrapOwnerHorizon(
            db, NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false),
            mapOf(ENV_OWNER_IDENTITY to "owner-evidence", ENV_OWNER_TOKEN_FILE to tmp.resolve("cfg").resolve("owner-token").toString()), QuietEvidenceProbe(),
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

    /** A page, optionally with a last_edited some time before now. */
    private fun page(text: String, editedDaysAgo: Double?) =
        """[{"id":"$pageId","title":"Williams plan","kind":"notion_page","text":${q(text)}${editedDaysAgo?.let { ""","last_edited":"${Instant.now().minusMillis((it * day).toLong())}"""" } ?: ""}}]"""

    private fun filed(quote: String, title: String = quote, dueDaysAgo: Long? = null) =
        """{"verb":"record_item","source":"$pageId","kind":"task","quote":${q(quote)},"title":${q(title)}${dueDaysAgo?.let { ""","due":"${LocalDate.now(zone).minusDays(it)}"""" } ?: ""}}"""

    private fun row(text: String) = manager.getDatabase().query("sql", "SELECT FROM Task WHERE text = :t", mapOf("t" to text)).use { it.next().toElement().asVertex() }
    private fun evidence(text: String) = (row(text).get("lastEvidenceAt") as Number).toLong()
    private fun created(text: String) = (row(text).get("createdAt") as Number).toLong()
    private fun setEvidence(text: String, at: Long) {
        val db = manager.getDatabase()
        db.transaction { db.command("sql", "UPDATE Task SET lastEvidenceAt = :a WHERE text = :t", mapOf("a" to at, "t" to text)) }
    }
    private fun ago(days: Double) = System.currentTimeMillis() - (days * day).toLong()
    private fun near(expected: Long, actual: Long, slackMs: Long = 2_000L) = Math.abs(expected - actual) <= slackMs

    private val text1 = "# Williams plan\n\nRenew the Williams permit.\n\nOrder the safety triggers.\n\nBook the inspector."

    // ── The task page's acceptance list ──────────────────────────────────────

    @Test
    fun `an item from a page last edited 60 days ago with a date 30 days ago is a still-live question, not an overdue alarm`() = app { c ->
        val r = c.send(null, "[${filed("Renew the Williams permit", dueDaysAgo = 30)}]", page(text1, 60.0))
        assertTrue(r.applied(0), r.toString())
        assertTrue(near(ago(60.0), evidence("Renew the Williams permit")), "evidence starts at the page's last_edited, not at the import")
        assertTrue(created("Renew the Williams permit") - evidence("Renew the Williams permit") > 59 * day)
        val h = c.send(null, "[]").horizon()
        val line = h.lines().first { it.contains("\"Renew the Williams permit\"") }
        // Worded as the question, with the date the page was last edited as the last evidence; not as an overdue alarm. (Asserted on the parts both zoomed-out renderings share.)
        assertTrue(line.contains("Still live?") && line.contains("no evidence since ${Instant.ofEpochMilli(evidence("Renew the Williams permit")).atZone(zone).toLocalDate()}"), line)
        assertFalse(h.lines().any { it.contains("Renew the Williams permit") && it.contains("overdue") }, h)
    }

    @Test
    fun `the same item from a page last edited yesterday is still an overdue alarm`() = app { c ->
        val r = c.send(null, "[${filed("Renew the Williams permit", dueDaysAgo = 30)}]", page(text1, 1.0))
        assertTrue(r.applied(0), r.toString())
        val h = c.send(null, "[]").horizon()
        val line = h.lines().first { it.contains("\"Renew the Williams permit\"") }
        assertTrue(line.contains("overdue") && line.contains("30"), line)
        assertFalse("Still live?" in line, line)
    }

    @Test
    fun `a page sent again refreshes its items only when its last_edited is later`() = app { c ->
        c.send(null, "[${filed("Renew the Williams permit")}]", page(text1, 40.0))
        val first = evidence("Renew the Williams permit")
        assertTrue(near(ago(40.0), first))

        // Unchanged text and the same last_edited: nothing is newer, nothing moves.
        c.send(null, "[]", page(text1, 40.0).replace(Regex(""""last_edited":"[^"]+""""), """"last_edited":"${Instant.ofEpochMilli(first)}""""))
        assertEquals(first, evidence("Renew the Williams permit"), "a restatement is not evidence")

        // Changed text (the passage is still there) and a later last_edited: the item follows the page.
        val changed = text1 + "\n\nNew paragraph added later."
        c.send(null, "[]", page(changed, 5.0))
        val refreshed = evidence("Renew the Williams permit")
        assertTrue(near(ago(5.0), refreshed), "evidence follows the later last_edited")

        // An earlier last_edited than the item now has does not lower it: this item is no longer untouched since it was made.
        c.send(null, "[]", page(changed, 40.0))
        assertEquals(refreshed, evidence("Renew the Williams permit"), "not lowered")
    }

    @Test
    fun `correction path - an item imported at import time and never touched is repaired by sending its page with last_edited, and one the owner confirmed is not lowered`() = app { c ->
        // Imported as before this existed: no last_edited, so evidence starts at the import.
        val r = c.send(null, "[${filed("Renew the Williams permit", dueDaysAgo = 30)},${filed("Order the safety triggers", dueDaysAgo = 30)},${filed("Book the inspector", dueDaysAgo = 30)}]", page(text1, null))
        assertTrue(r.applied(0) && r.applied(1) && r.applied(2), r.toString())
        listOf("Renew the Williams permit", "Order the safety triggers", "Book the inspector").forEach { assertEquals(created(it), evidence(it), "$it starts at the import, as before") }
        Thread.sleep(20)

        // The owner confirms one, and really changes the state of another, after the import.
        val refs = c.send(null, "[]").refs()
        val confirm = c.send("The safety triggers are still live.", """[{"verb":"confirm_item","item":"${refOf(refs, "Order the safety triggers")}","quote":"still live"}]""")
        assertTrue(confirm.applied(0), confirm.toString())
        val active = c.send("The inspector is in progress now.", """[{"verb":"set_state","item":"${refOf(refs, "Book the inspector")}","state":"active","quote":"in progress now"}]""")
        assertTrue(active.applied(0), active.toString())
        val confirmedAt = evidence("Order the safety triggers"); val activeAt = evidence("Book the inspector")
        assertTrue(confirmedAt > created("Order the safety triggers") && activeAt > created("Book the inspector"))

        // The page is sent again, now with the date it was really last edited (60 days ago).
        c.send(null, "[]", page(text1, 60.0))
        assertTrue(near(ago(60.0), evidence("Renew the Williams permit")), "the untouched item is repaired: lowered to the page's last_edited")
        assertEquals(confirmedAt, evidence("Order the safety triggers"), "the owner's confirmation is never lowered")
        assertEquals(activeAt, evidence("Book the inspector"), "nor is a real state change")

        // And the repaired item now reads as a question, not an alarm.
        val h = c.send(null, "[]").horizon()
        assertTrue(h.lines().first { it.contains("\"Renew the Williams permit\"") }.contains("Still live?"), h)
    }

    @Test
    fun `without last_edited an item behaves as before, and a resend confirms it now`() = app { c ->
        c.send(null, "[${filed("Renew the Williams permit")}]", page(text1, null))
        assertEquals(created("Renew the Williams permit"), evidence("Renew the Williams permit"), "evidence starts at the import")
        setEvidence("Renew the Williams permit", ago(50.0))
        c.send(null, "[]", page(text1, null))
        assertTrue(near(System.currentTimeMillis(), evidence("Renew the Williams permit")), "a resend with no last_edited confirms it now, as it always did")
    }

    @Test
    fun `done and dropped items are left alone by a resend with a last_edited`() = app { c ->
        c.send(null, "[${filed("Renew the Williams permit")},${filed("Order the safety triggers")}]", page(text1, 40.0))
        val refs = c.send(null, "[]").refs()
        c.send("Both of those are finished.", """[{"verb":"set_state","item":"${refOf(refs, "Renew the Williams permit")}","state":"done","quote":"finished"},
            {"verb":"set_state","item":"${refOf(refs, "Order the safety triggers")}","state":"dropped","quote":"finished"}]""")
        // Make their evidence old, so a page edited yesterday would raise it if closed items were refreshed.
        setEvidence("Renew the Williams permit", ago(100.0)); setEvidence("Order the safety triggers", ago(100.0))
        val doneBefore = evidence("Renew the Williams permit"); val droppedBefore = evidence("Order the safety triggers")
        c.send(null, "[]", page(text1 + "\n\nEdited.", 1.0))
        assertEquals(doneBefore, evidence("Renew the Williams permit"), "done is unaffected")
        assertEquals(droppedBefore, evidence("Order the safety triggers"), "dropped is unaffected")
    }

    @Test
    fun `a last_edited in the future is bounded by now, and one that cannot be read is treated as missing`() = app { c ->
        val future = """[{"id":"$pageId","title":"Williams plan","kind":"notion_page","text":${q(text1)},"last_edited":"${Instant.now().plusMillis(10 * day)}"}]"""
        c.send(null, "[${filed("Renew the Williams permit")}]", future)
        val created = created("Renew the Williams permit")
        assertTrue(evidence("Renew the Williams permit") <= System.currentTimeMillis(), "never later than now")
        assertTrue(Math.abs(evidence("Renew the Williams permit") - created) <= 2_000L)

        val unreadable = """[{"id":"bbbb1111-2222-3333-4444-555566667777","title":"Other","kind":"notion_page","text":${q("# Other\n\nSend the other thing.")},"last_edited":"yesterday afternoon"}]"""
        c.send(null, """[{"verb":"record_item","source":"bbbb1111-2222-3333-4444-555566667777","kind":"task","quote":"Send the other thing","title":"Send the other thing"}]""", unreadable)
        assertEquals(created("Send the other thing"), evidence("Send the other thing"), "treated as not given")
    }

    @Test
    fun `last_edited is read as an instant, an offset time, a local time or a date, and as nothing otherwise`() {
        assertEquals(Instant.parse("2026-11-07T10:00:00Z").toEpochMilli(), SourceEditTime.parse("2026-11-07T10:00:00Z"))
        assertEquals(Instant.parse("2026-11-07T10:00:00Z").toEpochMilli(), SourceEditTime.parse("2026-11-07T12:00:00+02:00"))
        assertEquals(Instant.parse("2026-11-07T10:00:00Z").toEpochMilli(), SourceEditTime.parse("2026-11-07T10:00:00"))
        assertEquals(Instant.parse("2026-11-07T00:00:00Z").toEpochMilli(), SourceEditTime.parse("2026-11-07"))
        assertEquals(Instant.parse("2026-11-07T10:00:00.123Z").toEpochMilli(), SourceEditTime.parse(" 2026-11-07T10:00:00.123Z "))
        assertNull(SourceEditTime.parse(null)); assertNull(SourceEditTime.parse("")); assertNull(SourceEditTime.parse("yesterday")); assertNull(SourceEditTime.parse("11/07/2026"))
        assertNotNull(SourceEditTime.parse("2026-11-07T10:00:00Z"))
    }
}
