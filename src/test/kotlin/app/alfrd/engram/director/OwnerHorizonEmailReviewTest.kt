package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.configureOwnerHorizonRoutes
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.EmailGraph
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceStore
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private class QuietEmailReviewProbe : SelfStateProbe {
    override fun servers() = listOf(ServerStatus("text reader", 8081, true, 1))
    override fun memory() = MemoryUse(1, 2, 3, 4)
}

/** Review of the email bridge: one message however its Message-ID is written, bad addresses refused, an id that is both a page and an email, and the author of an email item. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerHorizonEmailReviewTest {
    private val tmp: Path = Files.createTempDirectory("engram-owner-email-review-")
    private lateinit var manager: DatabaseManager
    private lateinit var token: String
    private lateinit var email: String
    private lateinit var startup: OwnerHorizonStartup

    @BeforeAll
    fun setUp() {
        manager = DatabaseManager(tmp.resolve("db").toString())
        SchemaBootstrap.bootstrap(manager.getDatabase())
        email = DebugConverseService.resolveUserId("owner-email-r")
        val db = manager.getDatabase()
        for ((name, addr) in listOf("owner-email-r" to email, "other-email-r" to DebugConverseService.resolveUserId("other-email-r"))) {
            db.transaction {
                db.newVertex("User").apply {
                    set("uid", UUID.randomUUID().toString()); set("username", name); set("email", addr)
                    set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
                }
            }
        }
        startup = bootstrapOwnerHorizon(
            db, NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false),
            mapOf(ENV_OWNER_IDENTITY to "owner-email-r", ENV_OWNER_TOKEN_FILE to tmp.resolve("cfg").resolve("owner-token").toString()), QuietEmailReviewProbe(),
        )
        token = (startup as OwnerHorizonStartup.Running).token
    }

    @AfterAll
    fun tearDown() { manager.close(); tmp.toFile().deleteRecursively() }

    private fun app(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            configureOwnerHorizonRoutes(startup) { true }
        }
        block(client)
    }

    private fun q(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

    private suspend fun HttpClient.send(ops: String = "[]", sources: String? = null): JsonObject {
        val body = buildString {
            append("{")
            if (sources != null) append("\"sources\":$sources,")
            append("\"operations\":$ops}")
        }
        val r = post("/owner/horizon") { header(HttpHeaders.Authorization, "Bearer $token"); header(HttpHeaders.ContentType, "application/json"); setBody(body) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private fun count(sql: String): Long = manager.getDatabase().query("sql", sql).use { it.next().getProperty<Number>("n").toLong() }
    private fun n(type: String) = count("SELECT count(*) AS n FROM `$type`")
    private fun sourcedItems() = count("SELECT count(*) AS n FROM ASSERTS WHERE @out.type = 'channel_sourced'")

    private fun JsonObject.src(i: Int) = this["sources"]!!.jsonArray[i].jsonObject
    private fun JsonObject.stored(i: Int) = src(i)["stored"]!!.jsonPrimitive.boolean
    private fun JsonObject.outcome(i: Int) = src(i)["outcome"]?.jsonPrimitive?.content
    private fun JsonObject.detail(i: Int) = this["operations"]!!.jsonArray[i].jsonObject["detail"]!!.jsonPrimitive.content
    private fun JsonObject.applied(i: Int) = this["operations"]!!.jsonArray[i].jsonObject["applied"]!!.jsonPrimitive.boolean

    private fun mail(id: String, text: String = "The caterer wants a deposit of 500 euros by Friday.", extra: String = "") =
        """{"id":${q(id)},"title":"Caterer","kind":"email_message","subject":"Caterer","from":{"address":"Boss@Y.org","name":"Boss  Person"},"to":["sam@y.org"],"text":${q(text)}$extra}"""

    private fun op(id: String, quote: String) = """{"verb":"record_item","source":${q(id)},"kind":"fact","quote":${q(quote)}}"""

    @Test
    fun `one message is one Email whether its Message-ID has angle brackets, spaces or neither, and it is cited either way`() = app { c ->
        val before = n("Email")
        val a = c.send("[${op("<br-1@y.org>", "deposit of 500 euros")}]", "[${mail("<br-1@y.org>")}]")
        assertEquals("new", a.outcome(0)); assertEquals("br-1@y.org", a.src(0)["id"]!!.jsonPrimitive.content, "stored and reported without the brackets")
        assertTrue(a.applied(0), a.toString())
        for (form in listOf("br-1@y.org", "  <br-1@y.org>  ", "< br-1@y.org >")) {
            val r = c.send("[${op(form, "deposit of 500 euros")}]", "[${mail(form)}]")
            assertEquals("repeat", r.outcome(0), form)
        }
        assertEquals(before + 1, n("Email"), "four spellings, one Email")
        val addresses = n("EmailAddress")
        val dup = c.send("[]", "[${mail("<br-2@y.org>")},${mail("br-2@y.org")}]")
        assertEquals("new", dup.outcome(0)); assertEquals("refused: duplicate_source_id", dup.src(1)["detail"]!!.jsonPrimitive.content)
        assertEquals(before + 2, n("Email")); assertEquals(addresses, n("EmailAddress"), "the same two addresses, reused") // boss and sam are shared with the first
        // Only one outer pair goes, and the store does it for every caller, not only the tool.
        assertEquals("<x@y.org>", EmailGraph.messageId("<<x@y.org>>"))
        assertEquals("", EmailGraph.messageId(" <> "))
        val store = SourceStore(manager.getDatabase())
        val first = store.ingest(email, SourceInput(type = "email_message", locator = "<direct-1@y.org>", title = "Direct", text = "Sent straight to the store, twice over."))
        val second = store.ingest(email, SourceInput(type = "email_message", locator = "direct-1@y.org", title = "Direct", text = "Sent straight to the store, twice over."))
        assertEquals(first.source.uid, second.source.uid); assertFalse(second.created); assertEquals("direct-1@y.org", first.source.locator)
    }

    @Test
    fun `a sender or recipient that is not an address is refused and nothing is written`() = app { c ->
        val counts = listOf("Source", "Email", "EmailAddress", "SENT_TO").map { n(it) }
        val cases = listOf(
            mail("<bad-1@y.org>").replace(""""from":{"address":"Boss@Y.org","name":"Boss  Person"}""", """"from":{"address":"not an address"}""") to "refused: invalid_sender",
            mail("<bad-2@y.org>").replace(""""from":{"address":"Boss@Y.org","name":"Boss  Person"}""", """"from":"Boss <boss@y.org>"""") to "refused: invalid_sender",
            mail("<bad-3@y.org>").replace(""""to":["sam@y.org"]""", """"to":["sam@y.org","mailto:lee@y.org"]""") to "refused: invalid_recipients",
            mail("<bad-4@y.org>").replace(""""to":["sam@y.org"]""", """"to":[{"address":"undisclosed-recipients:;"}]""") to "refused: invalid_recipients",
            mail("<bad-5@y.org>").replace(""""to":["sam@y.org"]""", """"to":[42]""") to "refused: invalid_recipients",
        )
        for ((entry, reason) in cases) {
            val j = c.send("[]", "[$entry]")
            assertFalse(j.stored(0), j.toString()); assertEquals(reason, j.src(0)["detail"]!!.jsonPrimitive.content)
        }
        assertEquals(counts, listOf("Source", "Email", "EmailAddress", "SENT_TO").map { n(it) })
        // A bare address string is a recipient, and the sender's name is kept clean.
        val ok = c.send("[]", "[${mail("<ok-1@y.org>")}]")
        assertTrue(ok.stored(0)); assertEquals("Boss Person", SourceStore(manager.getDatabase()).let { s -> s.emailView(email, s.currentByLocator(email, "email_message", "ok-1@y.org")!!.uid)!!.fromName })
    }

    @Test
    fun `a cited id is a Notion page first, then an email, and another user's id is neither`() = app { c ->
        val both = "11111111-2222-3333-4444-5555555555aa"
        val page = c.send("[]", """[{"id":"$both","title":"Venue page","kind":"notion_page","text":"The page says the deposit is two hundred euros."}]""")
        assertTrue(page.stored(0))
        val mailed = c.send("[]", "[${mail(both, text = "The email says the caterer wants six hundred euros.")}]")
        assertTrue(mailed.stored(0), mailed.toString()); assertEquals("new", mailed.outcome(0))
        // One id, two sources: the page answers first, and a quote only the email has is not found (it does not fall through to the email).
        val fromPage = c.send("[${op(both, "the deposit is two hundred euros")}]")
        assertTrue(fromPage.applied(0), fromPage.toString()); assertTrue(fromPage.detail(0).contains("Venue page"), fromPage.detail(0))
        val fromEmailQuote = c.send("[${op(both, "caterer wants six hundred euros")}]")
        assertFalse(fromEmailQuote.applied(0)); assertEquals("quote_not_in_source", fromEmailQuote.detail(0))
        // An email whose Message-ID is not a page id is found as an email.
        val onlyMail = c.send("[]", "[${mail("<only-mail-1@y.org>", text = "Nobody else has this line about marquees.")}]")
        assertTrue(onlyMail.stored(0))
        assertTrue(c.send("[${op("only-mail-1@y.org", "this line about marquees")}]").applied(0))
        // Another user's email, even with its exact Message-ID and text, is unknown here.
        val other = DebugConverseService.resolveUserId("other-email-r")
        SourceStore(manager.getDatabase()).ingest(other, SourceInput(type = "email_message", locator = "theirs-r@y.org", title = "Theirs", text = "A private line of theirs.", email = EmailGraph.Facts(subject = "Theirs")))
        val theirs = c.send("[${op("<theirs-r@y.org>", "A private line of theirs")}]")
        assertFalse(theirs.applied(0)); assertEquals("unknown_source", theirs.detail(0))
    }

    @Test
    fun `an item from an email is attributed to its sender, and a page item still reads author unknown`() = app { c ->
        c.send("[${op("<who-1@y.org>", "deposit of 500 euros")}]", "[${mail("<who-1@y.org>")}]")
        c.send("[]", """[{"id":"11111111-2222-3333-4444-5555555555bb","title":"Plan page","kind":"notion_page","text":"Book the marquee on Friday."}]""")
        c.send("""[{"verb":"record_item","source":"11111111-2222-3333-4444-5555555555bb","kind":"fact","quote":"Book the marquee on Friday"}]""")
        val claimants = manager.getDatabase().query("sql", "SELECT claimant FROM QUOTES").use { rs -> rs.asSequence().map { it.getProperty<String>("claimant") }.toList() }
        assertTrue("Boss Person (in Caterer)" in claimants, claimants.toString())
        assertTrue("source: Plan page (author unknown)" in claimants, claimants.toString())
    }
}
