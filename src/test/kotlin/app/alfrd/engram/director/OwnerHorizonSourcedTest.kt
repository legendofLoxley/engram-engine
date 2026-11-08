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

private class QuietSourcedProbe : SelfStateProbe {
    override fun servers() = listOf(ServerStatus("text reader", 8081, true, 1))
    override fun memory() = MemoryUse(1, 2, 3, 4)
}

/** Sourced writes on the owner's Horizon tool, against a real (scratch) database: a document a channel supplies, items filed from it, and how they read. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerHorizonSourcedTest {
    private val tmp: Path = Files.createTempDirectory("engram-owner-sourced-")
    private lateinit var manager: DatabaseManager
    private lateinit var token: String
    private lateinit var email: String
    private lateinit var startup: OwnerHorizonStartup
    private val zone = ZoneId.systemDefault()

    private val pageId = "11111111-2222-3333-4444-555555555555"
    private val pageText = "# Offsite plan\n\nBook the venue for the offsite by Friday.\n\nThe budget is 5000 dollars for the offsite."

    @BeforeAll
    fun setUp() {
        manager = DatabaseManager(tmp.resolve("db").toString())
        SchemaBootstrap.bootstrap(manager.getDatabase())
        email = DebugConverseService.resolveUserId("owner-sourced")
        val db = manager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "owner-sourced"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        startup = bootstrapOwnerHorizon(
            db, NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false),
            mapOf(ENV_OWNER_IDENTITY to "owner-sourced", ENV_OWNER_TOKEN_FILE to tmp.resolve("cfg").resolve("owner-token").toString()), QuietSourcedProbe(),
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

    private fun source(text: String = pageText, id: String = pageId, title: String = "Offsite plan") =
        """[{"id":"$id","title":${q(title)},"kind":"notion_page","url":"https://notion.so/offsite","text":${q(text)},"last_edited":"2026-11-07T10:00:00Z"}]"""

    private fun sourcedOp(quote: String, kind: String = "task", extra: String = "") =
        """{"verb":"record_item","source":"$pageId","kind":"$kind","quote":${q(quote)}$extra}"""

    private fun JsonObject.op(i: Int) = this["operations"]!!.jsonArray[i].jsonObject
    private fun JsonObject.applied(i: Int) = op(i)["applied"]!!.jsonPrimitive.boolean
    private fun JsonObject.detail(i: Int) = op(i)["detail"]!!.jsonPrimitive.content
    private fun JsonObject.horizon() = this["horizon"]!!.jsonPrimitive.content
    private fun JsonObject.src(i: Int) = this["sources"]!!.jsonArray[i].jsonObject

    private fun count(sql: String): Long = manager.getDatabase().query("sql", sql).use { it.next().getProperty<Number>("n").toLong() }
    private fun sourceCount() = count("SELECT count(*) AS n FROM Source WHERE type = 'notion_page'")
    private fun chunkCount() = count("SELECT count(*) AS n FROM Chunk")
    private fun sourcedItems() = count("SELECT count(*) AS n FROM ASSERTS WHERE @out.type = 'channel_sourced'")
    private fun utterances() = count("SELECT count(*) AS n FROM Utterance")

    @Test
    fun `a sourced operation whose quote is in the source is applied and shows as from the source, not as the owner's statement`() = app { c ->
        val utterancesBefore = utterances()
        val itemsBefore = sourcedItems()
        val j = c.send(null, "[${sourcedOp("Book the venue for the offsite by Friday", extra = ""","title":"Book the venue"""")}]", source())
        assertTrue(j.src(0)["stored"]!!.jsonPrimitive.boolean, j.toString())
        assertTrue(j.applied(0), j.toString())
        assertTrue(j.detail(0).contains("from the source \"Offsite plan\""), j.detail(0))
        assertTrue(j.detail(0).contains("not recorded as the owner's statement"))
        assertEquals("Offsite plan", j.op(0)["source"]!!.jsonPrimitive.content)
        assertEquals(itemsBefore + 1, sourcedItems())
        assertEquals(utterancesBefore, utterances(), "no utterance: the owner said nothing")

        // The standing Horizon reads the item as the document's: title, location, and not the owner's commitment.
        val h = c.send(null, "[]").horizon()
        val line = h.lines().first { it.contains("Book the venue") }
        assertTrue(line.contains("from \"Offsite plan\""), line)
        assertTrue(line.contains("not the owner's own statement or commitment unless they adopt it"), line)
        assertTrue(line.contains("not read by the Director"), line)
        assertTrue("From documents supplied by a channel" in h)

        // The graph: asserted by a channel_sourced Source, QUOTES with writer claude_session and a source claimant, supplied by the channel, unread by the Director.
        val quotes = manager.getDatabase().query("sql", "SELECT writer, claimant FROM QUOTES WHERE @out.text LIKE '%Book the venue%'").use { rs -> rs.next().toMap() }
        assertEquals("claude_session", quotes["writer"])
        assertEquals("source: Offsite plan (author unknown)", quotes["claimant"])
        val src = manager.getDatabase().query("sql", "SELECT FROM Source WHERE type = 'notion_page' AND name = 'Offsite plan'").use { it.next().toElement().asVertex() }
        assertEquals("claude", src.get("suppliedBy")); assertEquals(false, src.get("readByDirector"))
        assertEquals("11111111222233334444555555555555", src.get("locator"), "the page id is the stable locator")
        assertEquals("https://notion.so/offsite", src.get("url"))
        val trust = manager.getDatabase().query("sql", "SELECT scores FROM ASSERTS WHERE @out.type = 'channel_sourced' AND @in.text LIKE '%Book the venue%'").use { it.next().getProperty<String>("scores") }
        assertEquals("[]", trust, "a document's words raise no trust of the owner's")
    }

    @Test
    fun `a quote that is not in the source is refused, even when it is in the message`() = app { c ->
        val items = sourcedItems()
        val j = c.send(
            "Order the catering for the offsite.",
            "[${sourcedOp("Order the catering for the offsite")},${sourcedOp("something the page never says")},${sourcedOp("anything").replace(pageId, "99999999999999999999999999999999")}]",
            source(),
        )
        assertEquals("quote_not_in_source", j.detail(0)); assertEquals("quote_not_in_source", j.detail(1)); assertEquals("unknown_source", j.detail(2))
        assertTrue((0..2).none { j.applied(it) })
        assertEquals(items, sourcedItems())
        // Only record_item may cite a source.
        val k = c.send(null, """[{"verb":"resolve_item","source":"$pageId","item":"i1","quote":"Book the venue"}]""")
        assertEquals("source_only_for_record_item", k.detail(0))
    }

    @Test
    fun `re-sending the same text adds nothing and changed text supersedes`() = app { c ->
        val id2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val text1 = "# Trip\n\nPack the tent before Saturday.\n\nThe campsite opens at noon."
        val first = c.send(null, "[]", source(text1, id2, "Trip"))
        assertEquals("stored: new source", first.src(0)["detail"]!!.jsonPrimitive.content)
        val (s0, c0) = sourceCount() to chunkCount()

        val again = c.send(null, "[]", source(text1, id2, "Trip"))
        assertTrue(again.src(0)["detail"]!!.jsonPrimitive.content.startsWith("unchanged"), again.toString())
        assertEquals(s0, sourceCount()); assertEquals(c0, chunkCount())
        // A same-wording page id with dashes dropped or upper-cased is the same page.
        c.send(null, "[]", source(text1, id2.replace("-", "").uppercase(), "Trip"))
        assertEquals(s0, sourceCount())

        // An item filed from it, repeated, adds nothing either.
        val op = """{"verb":"record_item","source":"$id2","kind":"task","quote":"Pack the tent before Saturday"}"""
        assertTrue(c.send(null, "[$op]").applied(0))
        val items = sourcedItems()
        val replay = c.send(null, "[$op]")
        assertTrue(replay.applied(0)); assertTrue(replay.detail(0).startsWith("Already recorded from this source"), replay.detail(0))
        assertEquals(items, sourcedItems())

        // Changed text: a new Source; the earlier one is kept and marked superseded; citing the old wording now fails, the new wording works.
        val text2 = "# Trip\n\nPack the tent and the stove before Saturday.\n\nThe campsite opens at noon."
        val changed = c.send(null, "[]", source(text2, id2, "Trip"))
        assertTrue(changed.src(0)["detail"]!!.jsonPrimitive.content.contains("superseded"), changed.toString())
        assertEquals(s0 + 1, sourceCount())
        val old = manager.getDatabase().query("sql", "SELECT FROM Source WHERE type = 'notion_page' AND supersededBy IS NOT NULL").use { it.next().toElement().asVertex() }
        assertEquals("aaaaaaaabbbbccccddddeeeeeeeeeeee", old.get("locator"))
        val gone = c.send(null, """[{"verb":"record_item","source":"$id2","kind":"fact","quote":"The campsite opens at noon. Bring tea."}]""")
        assertEquals("quote_not_in_source", gone.detail(0))
        val now = c.send(null, """[{"verb":"record_item","source":"$id2","kind":"task","quote":"Pack the tent and the stove before Saturday"}]""")
        assertTrue(now.applied(0), now.toString())
        // The item the old text produced is still there and now says the document has changed.
        assertTrue(c.send(null, "[]").horizon().lines().first { it.contains("Pack the tent before") }.contains("the document has since changed"))

        // Going back to the earlier text makes it current again, without a new Source.
        val back = c.send(null, "[]", source(text1, id2, "Trip"))
        assertTrue(back.src(0)["detail"]!!.jsonPrimitive.content.contains("current again"), back.toString())
        assertEquals(s0 + 1, sourceCount())
    }

    @Test
    fun `a malformed source is refused with a reason and writes nothing`() = app { c ->
        val s = sourceCount()
        val j = c.send(
            null, "[]",
            """[{"id":"x1","title":"A","kind":"google_doc","text":"hello"},{"id":"x2","title":"B","kind":"notion_page"},
                {"id":"x3","title":"C","kind":"notion_page","text":"t","chunks":[{"text":"t"}]},{"id":"","title":"D","kind":"notion_page","text":"d"}]""",
        )
        val details = (0..3).map { j.src(it)["detail"]!!.jsonPrimitive.content }
        assertEquals(listOf("refused: unsupported_source_kind", "refused: send_either_text_or_chunks", "refused: send_either_text_or_chunks", "refused: missing_id"), details)
        assertEquals(s, sourceCount())
    }

    @Test
    fun `chunks the channel cut itself are stored as given and can be cited`() = app { c ->
        val id = "chunked-page"
        val j = c.send(
            null,
            """[{"verb":"record_item","source":"$id","kind":"fact","quote":"Parking is behind the hall"}]""",
            """[{"id":"$id","title":"Venue","kind":"notion_page","chunks":[{"text":"Doors open at 9.","location":"section: Access"},{"text":"Parking is behind the hall.","location":"section: Parking"}]}]""",
        )
        assertEquals(2, j.src(0)["chunks"]!!.jsonPrimitive.content.toInt())
        assertTrue(j.applied(0), j.toString())
        assertEquals("section: Parking", j.op(0)["location"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a sourced item is never presented as the owner's words, and only a message-grounded operation adopts it`() = app { c ->
        val id = "22222222-2222-2222-2222-222222222222"
        val sourceOp = """{"verb":"record_item","source":"$id","kind":"task","quote":"Renew the storage contract by the 30th"}"""
        val r = c.send(null, "[$sourceOp]", source("# Admin\n\nRenew the storage contract by the 30th.", id, "Admin notes"))
        assertTrue(r.applied(0), r.toString())
        assertFalse(r.detail(0).contains("what you said") || r.detail(0).contains("you said"))
        val h = c.send(null, "[]").horizon()
        val line = h.lines().first { it.contains("storage contract") }
        assertFalse(line.contains("noted earlier") && !line.contains("Admin notes"), line)
        assertTrue(line.contains("not the owner's own statement"), line)

        // The owner then says the same thing: that is their own item, not "already on the list" as the document's.
        val adopt = c.send("I need to renew the storage contract by the 30th.", """[{"verb":"record_item","kind":"task","quote":"I need to renew the storage contract by the 30th"}]""")
        assertTrue(adopt.applied(0), adopt.toString())
        assertTrue(adopt.detail(0).startsWith("Recorded task"), adopt.detail(0))
        val types = manager.getDatabase().query("sql", "SELECT @out.type AS t FROM ASSERTS WHERE @in.text LIKE '%storage contract%'").use { rs ->
            generateSequence { if (rs.hasNext()) rs.next().getProperty<String>("t") else null }.toList().sorted()
        }
        assertEquals(listOf("channel_sourced", "claude_session"), types)
    }

    @Test
    fun `a project named in the quote is inferred from the source's words, and the summary says so`() = app { c ->
        c.send("Start a project called Offsite Logistics.", """[{"verb":"create_container","kind":"project","name":"Offsite Logistics","named_by_user":true,"quote":"Start a project called Offsite Logistics"}]""")
        val id = "33333333-3333-3333-3333-333333333333"
        val r = c.send(null, """[{"verb":"record_item","source":"$id","kind":"task","quote":"Confirm Offsite Logistics with the hotel"}]""", source("# Notes\n\nConfirm Offsite Logistics with the hotel.", id, "Hotel notes"))
        assertTrue(r.applied(0), r.toString())
        assertTrue(r.detail(0).contains("filed under \"Offsite Logistics\", from the source's words"), r.detail(0))
        assertFalse(r.detail(0).contains("from what you said"))
    }

    @Test
    fun `the operation cap counts sourced operations too`() = app { c ->
        val ops = (1..9).joinToString(",") { sourcedOp("The budget is 5000 dollars for the offsite", kind = "fact") }
        val j = c.send(null, "[$ops]", source())
        assertEquals("too_many_operations", j.detail(8))
    }

    @Test
    fun `every domain and project has a ref however many there are, and a project's parent is always shown`() {
        val structure = ArcadeHorizonStructureStore(manager.getDatabase())
        val mail = DebugConverseService.resolveUserId("owner-many")
        val db = manager.getDatabase()
        db.transaction { db.newVertex("User").apply { set("uid", UUID.randomUUID().toString()); set("username", "owner-many"); set("email", mail); set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save() } }
        runBlocking {
            // Four domains, each with six projects: 28 containers, twice the old cap of 15.
            val domains = (1..4).map { structure.createContainer(mail, 1, ContainerKind.DOMAIN, "Domain $it", null, tentative = false)!! }
            for (p in 1..6) for ((d, dom) in domains.withIndex()) structure.createContainer(mail, 1, ContainerKind.PROJECT, "Project ${d + 1}.$p", dom.uid, tentative = false)
            val snap = ViewSnapshotBuilder(structure).build(mail, zone)
            assertEquals(28, snap.containers.size, "every container has a ref")
            assertEquals(28, snap.containers.map { it.ref }.toSet().size)
            val shown = snap.containers.associateBy { it.ref }
            snap.containers.filter { it.kind == ContainerKind.PROJECT }.forEach { p ->
                assertNotNull(p.parentRef, "${p.name} lost its parent"); assertEquals(ContainerKind.DOMAIN, shown.getValue(p.parentRef!!).kind)
            }
            assertTrue(snap.containers.any { it.name == "Project 1.1" }, "the oldest project is still there")
            val small = DebugConverseService.resolveUserId("owner-few")
            db.transaction { db.newVertex("User").apply { set("uid", UUID.randomUUID().toString()); set("username", "owner-few"); set("email", small); set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save() } }
            val d = structure.createContainer(small, 1, ContainerKind.DOMAIN, "Home", null, tentative = false)!!
            structure.createContainer(small, 1, ContainerKind.PROJECT, "Garden", d.uid, tentative = false)
            val few = ViewSnapshotBuilder(structure).build(small, zone)
            assertEquals(listOf("Home", "Garden"), few.containers.map { it.name }); assertNull(few.containers[0].parentRef); assertEquals("c1", few.containers[1].parentRef)
        }
    }

    // ── Dedup is scoped to the source ────────────────────────────────────────

    private fun sourceFor(id: String, title: String, text: String) = source(text, id, title)

    private fun taskFrom(id: String, quote: String, title: String, due: String? = null) =
        """{"verb":"record_item","source":"$id","kind":"task","quote":${q(quote)},"title":${q(title)}${due?.let { ""","due":"$it"""" } ?: ""}}"""

    @Test
    fun `similar items from two different sources stay separate, and the first one's date does not move`() = app { c ->
        val a = "aaaa0000-0000-0000-0000-00000000000a"; val b = "bbbb0000-0000-0000-0000-00000000000b"
        val textA = "# Tracker A\n\nStatus: Open; Priority: High; Ship the quarterly budget report to finance."
        val textB = "# Tracker B\n\nStatus: Open; Priority: Low; Ship the quarterly budget report to finance."
        val ra = c.send(null, "[${taskFrom(a, "Ship the quarterly budget report to finance", "Ship budget report", "2026-12-01")}]", sourceFor(a, "Tracker A", textA))
        assertTrue(ra.applied(0), ra.toString())
        val rb = c.send(null, "[${taskFrom(b, "Ship the quarterly budget report to finance", "Ship budget report", "2026-12-20")}]", sourceFor(b, "Tracker B", textB))
        assertTrue(rb.applied(0), rb.toString())
        assertTrue(rb.detail(0).startsWith("Recorded task"), "a new item, not a merge: ${rb.detail(0)}")
        val rows = manager.getDatabase().query("sql", "SELECT text, dueAt FROM Task WHERE text = 'Ship budget report'").use { rs ->
            generateSequence { if (rs.hasNext()) rs.next().toMap() else null }.toList()
        }
        assertEquals(2, rows.size, "two items")
        assertEquals(2, rows.map { it["dueAt"] }.toSet().size, "each keeps its own date")
        // The two items each point at their own source.
        val srcs = manager.getDatabase().query("sql", "SELECT @in.sourceUid AS s FROM QUOTES WHERE @out.text = 'Ship budget report'").use { rs ->
            generateSequence { if (rs.hasNext()) rs.next().toMap()["s"] as String else null }.toSet()
        }
        assertEquals(2, srcs.size)
    }

    @Test
    fun `a sourced operation never merges into something the owner said`() = app { c ->
        val id = "cccc0000-0000-0000-0000-00000000000c"
        c.send("I need to file the annual tax return.", """[{"verb":"record_item","kind":"task","quote":"I need to file the annual tax return"}]""")
        val r = c.send(null, "[${taskFrom(id, "File the annual tax return", "File annual tax return", "2026-04-15")}]", sourceFor(id, "Admin list", "# Admin\n\nFile the annual tax return by April."))
        assertTrue(r.applied(0), r.toString())
        assertTrue(r.detail(0).startsWith("Recorded task"), r.detail(0))
        val types = manager.getDatabase().query("sql", "SELECT @out.type AS t FROM ASSERTS WHERE @in.text LIKE '%tax return%'").use { rs ->
            generateSequence { if (rs.hasNext()) rs.next().getProperty<String>("t") else null }.toList().sorted()
        }
        assertEquals(listOf("channel_sourced", "claude_session"), types)
    }

    @Test
    fun `a repeat from the same source is the same item, and a different date is reported, not applied`() = app { c ->
        val id = "dddd0000-0000-0000-0000-00000000000d"
        val text = "# Plan\n\nRenew the domain registration by March.\n\nRenew domain registration, due in April."
        val first = c.send(null, "[${taskFrom(id, "Renew the domain registration by March", "Renew domain registration", "2026-03-31")}]", sourceFor(id, "Plan", text))
        assertTrue(first.applied(0), first.toString())
        // The very same item again with another date: idempotent, and the difference is reported.
        val replay = c.send(null, "[${taskFrom(id, "Renew the domain registration by March", "Renew domain registration", "2026-04-30")}]")
        assertTrue(replay.applied(0) && replay.detail(0).startsWith("Already recorded from this source"), replay.detail(0))
        assertTrue(replay.detail(0).contains("the source gives a different date") && replay.detail(0).contains("the date was not changed"), replay.detail(0))
        // Another wording of it from the same source: matched to the same item, the date reported and not moved.
        val again = c.send(null, "[${taskFrom(id, "Renew domain registration, due in April", "Renew the domain registration", "2026-04-30")}]")
        assertTrue(again.applied(0), again.toString())
        assertTrue(again.detail(0).startsWith("Already on the list"), again.detail(0))
        assertTrue(again.detail(0).contains("the source gives a different date") && again.detail(0).contains("the date was not changed"), again.detail(0))
        val due = manager.getDatabase().query("sql", "SELECT dueAt FROM Task WHERE text = 'Renew domain registration'").use { rs -> rs.asSequence().map { it.getProperty<Number>("dueAt").toLong() }.toList() }
        assertEquals(1, due.size)
        assertEquals(java.time.LocalDate.parse("2026-03-31").plusDays(1).atStartOfDay(zone).minusNanos(1_000_000).toInstant().toEpochMilli(), due[0], "the date did not move")
    }

    // ── State and time fields ────────────────────────────────────────────────

    private fun JsonObject.refs() = this["refs"]!!.jsonPrimitive.content
    private fun refOf(refs: String, fragment: String): String =
        Regex("""\b(i\d+) \w+ "[^"]*${Regex.escape(fragment)}""").find(refs)?.groupValues?.get(1) ?: error("no ref for '$fragment' in:\n$refs")

    @Test
    fun `a state and a hard deadline claimed by a document read as the document's claim, not the owner's`() = app { c ->
        val id = "eeee0000-0000-0000-0000-00000000000e"
        val text = "# Launch\n\nSign off the press release, in progress, hard deadline 2099-05-01.\n\nCheck the signage budget."
        val r = c.send(
            null,
            """[{"verb":"record_item","source":"$id","kind":"task","quote":"Sign off the press release","title":"Sign off the press release","state":"active","due_kind":"hard","due":"2099-05-01","waiting_on":"nobody"}]""",
            sourceFor(id, "Launch", text),
        )
        assertTrue(r.applied(0), r.toString())
        assertTrue(r.detail(0).contains("active"), r.detail(0))
        val item = manager.getDatabase().query("sql", "SELECT FROM Task WHERE text = 'Sign off the press release'").use { it.next().toElement().asVertex() }
        assertEquals("active", item.get("state")); assertEquals("hard", item.get("dueKind")); assertEquals(true, item.get("stateFromSource"))
        assertNull(item.get("waitingOn"), "only a state and a hard or soft deadline are taken from a source")
        val read = c.send(null, "[]")
        val line = read.horizon().lines().first { it.contains("Sign off the press release") }
        assertTrue(line.contains("[active per the source]"), line)
        assertTrue(line.contains("not the owner's own statement or commitment"), line)
        val snapshotLine = read.refs().lines().first { it.contains("Sign off the press release") }
        assertTrue(snapshotLine.contains("(active, per the source)") && snapshotLine.contains("due 2099-05-01 hard"), snapshotLine)
        // The owner changing the state is their claim: the marker goes.
        val ref = refOf(read.refs(), "Sign off the press release")
        val say = c.send("The press release sign-off is on hold, waiting on legal.", """[{"verb":"set_state","item":"$ref","state":"waiting","waiting_on":"legal","quote":"waiting on legal"}]""")
        assertTrue(say.applied(0), say.toString())
        assertEquals(null, manager.getDatabase().query("sql", "SELECT FROM Task WHERE text = 'Sign off the press release'").use { it.next().toElement().asVertex().get("stateFromSource") })
    }

    @Test
    fun `set_state through the tool keeps the quote guard and the cap, and says when a waiting item names no one`() = app { c ->
        val msg = "I need to chase the printer quote and I am waiting on it."
        val made = c.send(msg, """[{"verb":"record_item","kind":"task","quote":"chase the printer quote","title":"Chase the printer quote","due":"2099-06-01T09:30","due_kind":"hard"}]""")
        assertTrue(made.applied(0), made.toString())
        val line = made.refs().lines().first { it.contains("Chase the printer quote") }
        assertTrue(line.contains("due 2099-06-01 09:30 hard"), line)
        val ref = refOf(made.refs(), "Chase the printer quote")

        // Waiting without a name is allowed, and the result says so.
        val waiting = c.send(msg, """[{"verb":"set_state","item":"$ref","state":"waiting","quote":"I am waiting on it"}]""")
        assertTrue(waiting.applied(0), waiting.toString())
        assertTrue(waiting.detail(0).contains("nothing says who or what it waits on"), waiting.detail(0))
        // The quote guard is the same as for every operation.
        val ungrounded = c.send(msg, """[{"verb":"set_state","item":"$ref","state":"done","quote":"the printer quote arrived"}]""")
        assertFalse(ungrounded.applied(0)); assertEquals("ungrounded_quote", ungrounded.detail(0))
        // resolve_item still works, as set_state done.
        // Refs are positional: take the next one from the latest response.
        val refNow = refOf(waiting.refs(), "Chase the printer quote")
        val done = c.send("The printer quote is sorted.", """[{"verb":"resolve_item","item":"$refNow","quote":"The printer quote is sorted"}]""")
        assertTrue(done.applied(0), done.toString())
        assertEquals("resolved", manager.getDatabase().query("sql", "SELECT FROM Task WHERE text = 'Chase the printer quote'").use { it.next().toElement().asVertex().get("status") })
        assertEquals("done", manager.getDatabase().query("sql", "SELECT FROM Task WHERE text = 'Chase the printer quote'").use { it.next().toElement().asVertex().get("state") })
        // The cap of eight counts the new verb.
        val many = (1..9).joinToString(",") { """{"verb":"set_state","item":"$refNow","state":"waiting","waiting_on":"person $it","quote":"I am waiting on it"}""" }
        val capped = c.send(msg, "[$many]")
        assertEquals("too_many_operations", capped.detail(8))
    }
}
