package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.configureOwnerHorizonRoutes
import app.alfrd.engram.cognitive.pipeline.Interpreter
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshot
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshotBuilder
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.nio.file.attribute.PosixFilePermissions
import java.time.ZoneId
import java.util.UUID

private class QuietProbe : SelfStateProbe {
    override fun servers() = listOf(ServerStatus("text reader", 8081, true, 1))
    override fun memory() = MemoryUse(1, 2, 3, 4)
}

/** The owner's Horizon tool end to end, against a real database: the route's refusals, then each verb through the guards. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerHorizonTest {
    private val tmp: Path = Files.createTempDirectory("engram-owner-horizon-")
    private lateinit var manager: DatabaseManager
    private lateinit var tokenPath: Path
    private lateinit var token: String
    private lateinit var email: String
    private lateinit var startup: OwnerHorizonStartup
    private val zone = ZoneId.systemDefault()

    @BeforeAll
    fun setUp() {
        manager = DatabaseManager(tmp.resolve("db").toString())
        SchemaBootstrap.bootstrap(manager.getDatabase())
        email = DebugConverseService.resolveUserId("owner-test")
        val db = manager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "owner-test"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        tokenPath = tmp.resolve("cfg").resolve("owner-token")
        startup = bootstrapOwnerHorizon(
            db, NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false),
            mapOf(ENV_OWNER_IDENTITY to "owner-test", ENV_OWNER_TOKEN_FILE to tokenPath.toString()), QuietProbe(),
        )
        token = (startup as OwnerHorizonStartup.Running).token
    }

    @AfterAll
    fun tearDown() { manager.close(); tmp.toFile().deleteRecursively() }

    private fun app(s: OwnerHorizonStartup = startup, loopback: Boolean = true, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            configureOwnerHorizonRoutes(s) { loopback }
        }
        block(client)
    }

    private suspend fun HttpClient.horizon(body: String?, auth: String? = "Bearer $token"): HttpResponse = post("/owner/horizon") {
        auth?.let { header(HttpHeaders.Authorization, it) }
        if (body != null) { header(HttpHeaders.ContentType, "application/json"); setBody(body) }
    }

    private fun json(r: String) = Json.parseToJsonElement(r).jsonObject

    /** Everything a write would change, as the owner's change marker counts it. */
    private fun fingerprint(): String = ChangeMarkerReader(manager.getDatabase()).read(email)!!.let { "${it.lastCycleSeq} ${it.counts}" } +
        " utterances=" + manager.getDatabase().query("sql", "SELECT count(*) AS n FROM Utterance").use { it.next().getProperty<Number>("n") }

    private fun snapshot(): ViewSnapshot = runBlocking { ViewSnapshotBuilder(ArcadeHorizonStructureStore(manager.getDatabase())).build(email, zone) }
    private fun itemRef(text: String) = snapshot().items.first { it.text.contains(text) }.ref
    private fun containerRef(name: String) = snapshot().containers.first { it.name == name }.ref

    private fun quoted(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

    private suspend fun HttpClient.send(message: String?, ops: String): JsonObject {
        val body = buildString { append("{"); if (message != null) append("\"message\":${quoted(message)},"); append("\"operations\":$ops}") }
        val r = horizon(body)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())
    }

    private fun JsonObject.op(i: Int) = this["operations"]!!.jsonArray[i].jsonObject
    private fun JsonObject.applied(i: Int) = op(i)["applied"]!!.jsonPrimitive.boolean
    private fun JsonObject.detail(i: Int) = op(i)["detail"]!!.jsonPrimitive.content

    // ── Route refusals ───────────────────────────────────────────────────────

    @Test
    fun `missing or wrong token is 401 and writes nothing`() = app { c ->
        val before = fingerprint()
        assertEquals(HttpStatusCode.Unauthorized, c.horizon("""{"message":"hello there"}""", auth = null).status)
        assertEquals(HttpStatusCode.Unauthorized, c.horizon("""{"message":"hello there"}""", auth = "Bearer nope").status)
        assertEquals(HttpStatusCode.Unauthorized, c.horizon(null, auth = "Basic $token").status)
        assertEquals(before, fingerprint())
    }

    @Test
    fun `a caller that is not on loopback is 403 even with the right token`() = app(loopback = false) { c ->
        assertEquals(HttpStatusCode.Forbidden, c.horizon(null).status)
        assertEquals(HttpStatusCode.Forbidden, c.horizon(null, auth = null).status)
    }

    @Test
    fun `a route that is not serving says 503 and why`() = app(OwnerHorizonStartup.Disabled("OWNER_IDENTITY is not set")) { c ->
        val r = c.horizon(null)
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("OWNER_IDENTITY is not set"))
    }

    @Test
    fun `an unknown field such as goals is refused, not ignored`() = app { c ->
        val before = fingerprint()
        val r = c.horizon("""{"goals":[{"text":"x"}]}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("goals"))
        assertEquals(HttpStatusCode.BadRequest, c.horizon("""[1]""").status)
        assertEquals(HttpStatusCode.BadRequest, c.horizon("""{"message":5}""").status)
        assertEquals(HttpStatusCode.BadRequest, c.horizon("""{"operations":"x"}""").status)
        assertEquals(before, fingerprint())
    }

    // ── Reads and the ledger ─────────────────────────────────────────────────

    @Test
    fun `an empty body is a pure read - no writes and no cycle`() = app { c ->
        val before = fingerprint()
        for (body in listOf(null, "", "{}", """{"message":"   ","operations":[]}""")) {
            val r = c.horizon(body)
            assertEquals(HttpStatusCode.OK, r.status)
            val j = json(r.bodyAsText())
            assertNull(j["cycleSeq"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
            assertEquals(0, j["operations"]!!.jsonArray.size)
            assertTrue(j["horizon"]!!.jsonPrimitive.content.contains("Your situation:"))
            assertTrue(j["horizon"]!!.jsonPrimitive.content.contains("Contextual awareness"))
        }
        assertEquals(before, fingerprint())
    }

    @Test
    fun `the horizon response is a channel rendering - no alfrd identity or Director or Actor role, but situation, marker, Horizon and refs`() = app { c ->
        val j = json(c.horizon(null).bodyAsText())
        val h = j["horizon"]!!.jsonPrimitive.content
        assertTrue(h.startsWith(DirectorPromptRenderer.CHANNEL_PREAMBLE), h.take(300))
        for (forbidden in listOf("You are alfrd", "conversational Director", "Director", "Actor", "This is your standing context", "no one is speaking to you")) {
            assertTrue(forbidden !in h, "channel response must not contain \"$forbidden\"")
        }
        assertTrue("Your situation:" in h)
        assertTrue("Memory marker:" in h)
        assertTrue("Contextual awareness" in h)
        assertTrue(j["refs"]!!.jsonPrimitive.content.isNotBlank(), "the refs snapshot is still returned")
    }

    @Test
    fun `a message lands in the ledger as a user utterance on channel claude and allocates a cycle`() = app { c ->
        val before = ChangeMarkerReader(manager.getDatabase()).read(email)!!.lastCycleSeq
        val j = c.send("Remember that the quarterly review is on my mind.", "[]")
        assertEquals(before + 1, j["cycleSeq"]!!.jsonPrimitive.content.toLong())
        val rows = manager.getDatabase().query("sql", "SELECT FROM Utterance WHERE channel = 'claude' AND userId = :u", mapOf("u" to email)).use { rs ->
            generateSequence { if (rs.hasNext()) rs.next().toElement().asVertex() else null }.toList().map { Triple(it.getString("role"), it.getString("text"), it.getString("channel")) }
        }
        assertEquals(listOf(Triple("user", "Remember that the quarterly review is on my mind.", "claude")), rows.takeLast(1))
        // A message alone records no item and calls no model.
        assertEquals(0, j["operations"]!!.jsonArray.size)
    }

    // ── Each verb through the guards ─────────────────────────────────────────

    @Test
    fun `each verb applies through the guards, asserted by a claude_session source`() = app { c ->
        // create_container + record_item filed under it, in one call (the project is named by its exact name)
        val msg1 = "Start a project called Garden Rebuild. I need to order the soil for the beds. Also the shed key is in the blue drawer."
        val j1 = c.send(msg1, """[
          {"verb":"create_container","kind":"project","name":"Garden Rebuild","named_by_user":true,"quote":"Start a project called Garden Rebuild"},
          {"verb":"record_item","kind":"task","quote":"I need to order the soil for the beds","project":"Garden Rebuild"},
          {"verb":"record_item","kind":"fact","quote":"the shed key is in the blue drawer"}]""")
        assertTrue(j1.applied(0) && j1.applied(1) && j1.applied(2), j1.toString())
        val sourceTypes = manager.getDatabase().query("sql", "SELECT @out.type AS t FROM ASSERTS WHERE @in.text LIKE '%soil%'").use { rs ->
            generateSequence { if (rs.hasNext()) rs.next().getProperty<String>("t") else null }.toList()
        }
        assertEquals(listOf("claude_session"), sourceTypes, "the item is asserted by the claude_session source")
        val trusted = manager.getDatabase().query("sql", "SELECT FROM Source WHERE type = 'claude_session'").use { it.hasNext() }
        assertTrue(trusted)
        assertEquals(1, snapshot().items.count { it.text.contains("soil") }, "the Source's items are visible to the next call's refs")
        assertTrue(snapshot().items.first { it.text.contains("soil") }.projectRef == containerRef("Garden Rebuild"), "filed under the project it named")

        // set_attention pin, link_items, file_item (a fact into the project), set_focus, release_focus
        val soil = itemRef("soil"); val key = itemRef("shed key"); val proj = containerRef("Garden Rebuild")
        val msg2 = "Keep the soil order up front. The soil order and the shed key go together. Move the shed key into Garden Rebuild. Let's work on Garden Rebuild now."
        val j2 = c.send(msg2, """[
          {"verb":"set_attention","item":"$soil","attention":"pinned","quote":"Keep the soil order up front"},
          {"verb":"link_items","item":"$soil","other_item":"$key","quote":"The soil order and the shed key go together"},
          {"verb":"file_item","item":"$key","into":"$proj","quote":"Move the shed key into Garden Rebuild"},
          {"verb":"set_focus","target":"$proj","quote":"Let's work on Garden Rebuild now"}]""")
        for (i in 0..3) assertTrue(j2.applied(i), j2.toString())
        assertEquals(proj, snapshot().focusLines.single().substringAfter("-> ").substringBefore(" "))
        assertTrue(snapshot().items.first { it.text.contains("shed key") }.projectRef == proj)

        val j3 = c.send("Show me the big picture.", """[{"verb":"release_focus","quote":"Show me the big picture"}]""")
        assertTrue(j3.applied(0)); assertTrue(snapshot().focusLines.isEmpty())

        // correct_item (supersedes), resolve_item, reopen_item
        val soil2 = itemRef("soil")
        val j4 = c.send("Actually it is the compost I need to order for the beds.", """[{"verb":"correct_item","item":"$soil2","title":"Order the compost","quote":"it is the compost I need to order for the beds"}]""")
        assertTrue(j4.applied(0), j4.toString())
        val compost = itemRef("compost")
        val j5 = c.send("The compost is sorted, it arrived.", """[{"verb":"resolve_item","item":"$compost","quote":"The compost is sorted, it arrived"}]""")
        assertTrue(j5.applied(0), j5.toString())
        assertEquals("resolved", snapshot().items.first { it.text.contains("compost") }.status)
        val j6 = c.send("The compost is not done after all, the delivery was wrong.", """[{"verb":"reopen_item","item":"$compost","quote":"The compost is not done after all"}]""")
        assertTrue(j6.applied(0), j6.toString())
        assertEquals("open", snapshot().items.first { it.text.contains("compost") }.status)

        // set_attention suppressed and normal
        val j7 = c.send("Stop bringing up the compost. Actually bring it back to normal.", """[
          {"verb":"set_attention","item":"$compost","attention":"suppressed","quote":"Stop bringing up the compost"},
          {"verb":"set_attention","item":"$compost","attention":"normal","quote":"bring it back to normal"}]""")
        assertTrue(j7.applied(0) && j7.applied(1), j7.toString())

        // the horizon comes back refreshed after the writes, and says what changed
        assertTrue(j7["horizon"]!!.jsonPrimitive.content.contains("Changed since the last tick: lastCycleSeq"))
    }

    @Test
    fun `a refused operation reports the guard's reason and writes nothing`() = app { c ->
        val before = fingerprint().substringBefore(" utterances=")
        // ungrounded quote, unknown ref, negated resolve, unknown verb, held-for-choice
        val j = c.send(
            "I never got around to the gutters.",
            """[
              {"verb":"record_item","kind":"task","quote":"something the user never said"},
              {"verb":"resolve_item","item":"i99","quote":"I never got around to the gutters"},
              {"verb":"frobnicate","quote":"I never got around to the gutters"},
              {"verb":"hold_for_choice","quote":"I never got around to the gutters"}]""",
        )
        val reasons = j["operations"]!!.jsonArray.map { it.jsonObject["detail"]!!.jsonPrimitive.content }
        assertEquals(4, reasons.size)
        assertTrue(j["operations"]!!.jsonArray.none { it.jsonObject["applied"]!!.jsonPrimitive.boolean })
        assertTrue(reasons.containsAll(listOf("ungrounded_quote", "unknown_ref", "unknown_verb", "not_available_on_owner_route")), reasons.toString())
        // Only the cycle counter and the ledger moved: no Phrase, Task, container or Source was added.
        val after = fingerprint().substringBefore(" utterances=")
        assertEquals(before.substringAfter(" "), after.substringAfter(" "))
    }

    @Test
    fun `operations without a message are refused - there is nothing for a quote to be found in`() = app { c ->
        val j = c.send(null, """[{"verb":"record_item","kind":"fact","quote":"anything at all"}]""")
        assertFalse(j.applied(0)); assertEquals("ungrounded_quote", j.detail(0))
    }

    @Test
    fun `a negated resolve is refused by the existing negation guard`() = app { c ->
        c.send("I need to call the plumber about the leak.", """[{"verb":"record_item","kind":"task","quote":"I need to call the plumber about the leak"}]""")
        val plumber = itemRef("plumber")
        val j = c.send("I never called the plumber.", """[{"verb":"resolve_item","item":"$plumber","quote":"called the plumber"}]""")
        assertFalse(j.applied(0)); assertEquals("negation_marker_detected", j.detail(0))
        assertEquals("open", snapshot().items.first { it.text.contains("plumber") }.status)
    }

    // ── The schema the MCP tool documents is the code's ──────────────────────

    @Test
    fun `mcp ops schema file is exactly what the interpreter's verbs define`() {
        val generated = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), Interpreter.OWNER_OPS_SCHEMA as JsonObject) + "\n"
        val file = Path.of("mcp", "ops.schema.json")
        if (System.getenv("UPDATE_OPS_SCHEMA") == "1") Files.writeString(file, generated)
        assertEquals(generated, Files.readString(file), "mcp/ops.schema.json is stale: run the tests with UPDATE_OPS_SCHEMA=1")
        assertFalse(generated.contains("hold_for_choice"))
        for (verb in listOf("record_item", "resolve_item", "reopen_item", "correct_item", "file_item", "create_container", "set_focus", "release_focus", "set_attention", "link_items"))
            assertTrue(generated.contains("\"$verb\""), verb)
    }

    // ── The token file ───────────────────────────────────────────────────────

    @Test
    fun `the owner token file is created 600, reused, and refused when group or others can read it`() {
        val p = tmp.resolve("tok").resolve("owner-token")
        val made = OwnerToken.loadOrCreate(p) as OwnerTokenResult.Ready
        assertTrue(made.created)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(p)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(p.parent)))
        val again = OwnerToken.loadOrCreate(p) as OwnerTokenResult.Ready
        assertFalse(again.created); assertEquals(made.token, again.token)
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-r-----"))
        val refused = OwnerToken.loadOrCreate(p)
        assertTrue(refused is OwnerTokenResult.Refused)
        assertFalse((refused as OwnerTokenResult.Refused).reason.contains(made.token))
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"))
        Files.writeString(p, "")
        assertTrue(OwnerToken.loadOrCreate(p) is OwnerTokenResult.Refused)
    }

    @Test
    fun `no OWNER_IDENTITY means the route is off, and a loose token file turns it off with a reason`() {
        val off = bootstrapOwnerHorizon(manager.getDatabase(), NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false), emptyMap(), QuietProbe())
        assertTrue(off is OwnerHorizonStartup.Disabled)
        val loose = tmp.resolve("loose-token")
        Files.writeString(loose, "0123456789abcdef0123456789abcdef"); Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rw-rw----"))
        val refused = bootstrapOwnerHorizon(
            manager.getDatabase(), NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false),
            mapOf(ENV_OWNER_IDENTITY to "owner-test", ENV_OWNER_TOKEN_FILE to loose.toString()), QuietProbe(),
        )
        assertTrue(refused is OwnerHorizonStartup.Disabled && refused.reason.contains("mode 600"))
    }
}
