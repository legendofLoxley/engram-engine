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

private class QuietEmailProbe : SelfStateProbe {
    override fun servers() = listOf(ServerStatus("text reader", 8081, true, 1))
    override fun memory() = MemoryUse(1, 2, 3, 4)
}

/** An email recorded through the owner's Horizon tool: the `sources` entry of kind email_message, an item citing its body, repeats, and what stays as it was. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerHorizonEmailTest {
    private val tmp: Path = Files.createTempDirectory("engram-owner-email-")
    private lateinit var manager: DatabaseManager
    private lateinit var token: String
    private lateinit var email: String
    private lateinit var startup: OwnerHorizonStartup

    @BeforeAll
    fun setUp() {
        manager = DatabaseManager(tmp.resolve("db").toString())
        SchemaBootstrap.bootstrap(manager.getDatabase())
        email = DebugConverseService.resolveUserId("owner-email")
        val db = manager.getDatabase()
        for ((name, addr) in listOf("owner-email" to email, "other-email" to DebugConverseService.resolveUserId("other-email"))) {
            db.transaction {
                db.newVertex("User").apply {
                    set("uid", UUID.randomUUID().toString()); set("username", name); set("email", addr)
                    set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
                }
            }
        }
        startup = bootstrapOwnerHorizon(
            db, NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false),
            mapOf(ENV_OWNER_IDENTITY to "owner-email", ENV_OWNER_TOKEN_FILE to tmp.resolve("cfg").resolve("owner-token").toString()), QuietEmailProbe(),
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

    private val m1 = "<offsite-1@x.org>"
    private val m2 = "<offsite-2@x.org>"
    private val body1 = "Hi Sam,\n\nThe venue costs 3,000 euros for the offsite.\n\nPlease book the venue by Friday."
    private val body2 = "Booked.\n\nThe catering is confirmed for the offsite.\n\nOn Mon, 2 Mar 2026 at 09:00, Ana <ana@x.org> wrote:\n> The venue costs 3,000 euros for the offsite.\n> Please book the venue by Friday."

    private fun first(text: String = body1, id: String = m1) =
        """{"id":${q(id)},"title":"Offsite venue","kind":"email_message","subject":"Offsite venue","sent_at":"2026-03-02T09:00:00Z",
           "gmail_message_id":"g-1","gmail_thread_id":"t-1","from":{"address":"Ana@X.org","name":"Ana"},
           "to":[{"address":"sam@x.org","name":"Sam"},{"address":"lee@x.org"}],"cc":[{"address":"kim@x.org"}],"bcc":[],"text":${q(text)}}"""

    private fun reply() =
        """{"id":${q(m2)},"title":"Re: Offsite venue","kind":"email_message","subject":"Re: Offsite venue","sent_at":"2026-03-02T11:30:00Z",
           "gmail_thread_id":"t-1","from":{"address":"sam@x.org","name":"Sam"},"to":[{"address":"ana@x.org"}],"cc":[],"bcc":[],"text":${q(body2)}}"""

    private fun op(id: String, quote: String, kind: String = "fact") = """{"verb":"record_item","source":${q(id)},"kind":"$kind","quote":${q(quote)}}"""

    @Test
    fun `two emails are recorded through the tool, with address nodes, edges and an item citing each body`() = app { c ->
        // Its own Message-IDs and its own domain, so the counts below are this test's alone.
        val a1 = "<pair-1@y.org>"; val a2 = "<pair-2@y.org>"
        fun mine(s: String) = s.replace("Ana@X.org", "ana@y.org").replace("@x.org", "@y.org")
        val before = listOf("Email", "EmailAddress", "SENT_BY", "SENT_TO").map { n(it) }
        val items = sourcedItems()

        val j1 = c.send("[${op(a1, "The venue costs 3,000 euros for the offsite")}]", "[${mine(first(id = a1))}]")
        assertTrue(j1.stored(0), j1.toString()); assertEquals("new", j1.outcome(0))
        assertTrue(j1.applied(0), j1.toString())
        assertTrue(j1.detail(0).contains("from the source \"Offsite venue\""), j1.detail(0))

        val j2 = c.send("[${op(a2, "The catering is confirmed for the offsite")}]", "[${mine(reply().replace(m2, a2))}]")
        assertTrue(j2.stored(0), j2.toString()); assertEquals("new", j2.outcome(0))
        assertTrue(j2.applied(0), j2.toString())

        // Two Emails; the addresses are ana, sam, lee, kim (sam and ana are shared with the reply, so one node each); 2 sent-by; 4 sent-to.
        assertEquals(before[0] + 2, n("Email")); assertEquals(before[1] + 4, n("EmailAddress"))
        assertEquals(before[2] + 2, n("SENT_BY")); assertEquals(before[3] + 4, n("SENT_TO"))
        assertEquals(items + 2, sourcedItems())

        val store = SourceStore(manager.getDatabase())
        val e1 = store.currentByLocator(email, "email_message", EmailGraph.messageId(a1))!!
        val v1 = store.emailView(email, e1.uid)!!
        assertEquals("ana@y.org", v1.from); assertEquals(listOf("lee@y.org", "sam@y.org"), v1.to.sorted()); assertEquals(listOf("kim@y.org"), v1.cc)
        assertEquals("Offsite venue", v1.subject)
        assertEquals("claude", e1.suppliedBy); assertEquals(false, e1.readByDirector)
        assertEquals("t-1", e1.threadId)
        val quotes = manager.getDatabase().query("sql", "SELECT draftQuote AS q FROM QUOTES WHERE @in.sourceUid = :u", mapOf("u" to e1.uid)).use { rs -> rs.asSequence().map { it.getProperty<String>("q") }.toList() }
        assertEquals(listOf("The venue costs 3,000 euros for the offsite"), quotes)
        val e2 = store.currentByLocator(email, "email_message", EmailGraph.messageId(a2))!!
        assertEquals("sam@y.org", store.emailView(email, e2.uid)!!.from)
    }

    @Test
    fun `a quote from the quoted history of a reply is not in the source`() = app { c ->
        c.send("[]", "[${reply()}]")
        val j = c.send("[${op(m2, "Please book the venue by Friday")}]")
        assertFalse(j.applied(0)); assertEquals("quote_not_in_source", j.detail(0))
    }

    @Test
    fun `the same email again writes nothing and reports a repeat`() = app { c ->
        c.send("[${op(m1, "Please book the venue by Friday", "task")}]", "[${first()}]")
        val counts = listOf("Source", "Email", "EmailAddress", "Chunk", "SENT_BY", "SENT_TO", "QUOTES", "ASSERTS").map { n(it) }
        val items = sourcedItems()
        val j = c.send("[${op(m1, "Please book the venue by Friday", "task")}]", "[${first()}]")
        assertTrue(j.stored(0)); assertEquals("repeat", j.outcome(0))
        assertNull(j.src(0)["sourceTextDiffered"], "only reported when true")
        assertEquals(counts, listOf("Source", "Email", "EmailAddress", "Chunk", "SENT_BY", "SENT_TO", "QUOTES", "ASSERTS").map { n(it) })
        assertEquals(items, sourcedItems())
    }

    @Test
    fun `the same Message-ID with a changed body is reported, keeps the first text, and a quote only the new body has is refused`() = app { c ->
        c.send("[]", "[${first()}]")
        val counts = listOf("Source", "Email", "EmailAddress", "Chunk", "SENT_BY", "SENT_TO", "QUOTES").map { n(it) }
        val changed = body1 + "\n\nP.S. The deposit is due on Monday."
        val j = c.send("[${op(m1, "The deposit is due on Monday")}]", "[${first(changed)}]")
        assertTrue(j.stored(0)); assertEquals("repeat_text_differed", j.outcome(0))
        assertTrue(j.src(0)["sourceTextDiffered"]!!.jsonPrimitive.boolean, j.toString())
        assertTrue(j.src(0)["detail"]!!.jsonPrimitive.content.contains("first text is kept"))
        assertFalse(j.applied(0)); assertEquals("quote_not_in_source", j.detail(0))
        assertEquals(counts, listOf("Source", "Email", "EmailAddress", "Chunk", "SENT_BY", "SENT_TO", "QUOTES").map { n(it) })
        val store = SourceStore(manager.getDatabase())
        val texts = store.chunks(email, store.currentByLocator(email, "email_message", EmailGraph.messageId(m1))!!.uid).joinToString("\n") { it.text }
        assertTrue(texts.contains("book the venue by Friday") && !texts.contains("deposit"), texts)
    }

    @Test
    fun `an email that is malformed or over the limits is refused and writes nothing`() = app { c ->
        val counts = listOf("Source", "Email", "EmailAddress", "SENT_TO").map { n(it) }
        val many = (1..501).joinToString(",") { """{"address":"r$it@x.org"}""" }
        val cases = listOf(
            first().replace(""""text":""", """"chunks":[{"text":"x"}],"x":""") to "refused: send_text_for_email",
            first().replace(""""sent_at":"2026-03-02T09:00:00Z"""", """"sent_at":"yesterday-ish"""") to "refused: invalid_sent_at",
            first().replace(""""from":{"address":"Ana@X.org","name":"Ana"}""", """"from":{"name":"Ana"}""") to "refused: invalid_sender",
            first().replace(""""bcc":[]""", """"bcc":"sam@x.org"""") to "refused: invalid_recipients",
            first().replace(""""bcc":[]""", """"bcc":[$many]""").replace("<offsite-1@x.org>", "<big@x.org>") to "refused: too_many_recipients",
            first(text = "  ", id = "<blank@x.org>") to "refused: empty_text",
        )
        for ((entry, reason) in cases) {
            val j = c.send("[]", "[$entry]")
            assertFalse(j.stored(0), j.toString()); assertEquals(reason, j.src(0)["detail"]!!.jsonPrimitive.content)
        }
        assertEquals(counts, listOf("Source", "Email", "EmailAddress", "SENT_TO").map { n(it) })
        val dup = c.send("[]", "[${first(id = "<dup@x.org>")},${first(id = "<dup@x.org>")}]")
        assertEquals("refused: duplicate_source_id", dup.src(1)["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an email another user stored cannot be cited, and an unknown kind is still refused`() = app { c ->
        val other = DebugConverseService.resolveUserId("other-email")
        SourceStore(manager.getDatabase()).ingest(
            other, SourceInput(type = "email_message", locator = "<theirs@x.org>", title = "Theirs", text = "A private line of theirs.", email = EmailGraph.Facts(subject = "Theirs")),
        )
        val j = c.send("[${op("<theirs@x.org>", "A private line of theirs")}]")
        assertFalse(j.applied(0)); assertEquals("unknown_source", j.detail(0))
        val k = c.send("[]", """[{"id":"x","title":"t","kind":"pdf","text":"body"}]""")
        assertEquals("refused: unsupported_source_kind", k.src(0)["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a Notion page report is as it was, with no email fields`() = app { c ->
        val j = c.send("[]", """[{"id":"11111111-2222-3333-4444-555555555556","title":"Plan","kind":"notion_page","text":"Book the room on Friday."}]""")
        assertTrue(j.stored(0)); assertNull(j.src(0)["outcome"]); assertNull(j.src(0)["sourceTextDiffered"])
        assertEquals("stored: new source", j.src(0)["detail"]!!.jsonPrimitive.content)
    }
}
