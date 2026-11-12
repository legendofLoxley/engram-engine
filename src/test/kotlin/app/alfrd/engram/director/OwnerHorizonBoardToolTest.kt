package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.configureOwnerHorizonRoutes
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.AttentionDirective
import app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind
import app.alfrd.engram.cognitive.pipeline.horizon.DueKind
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPolicy
import app.alfrd.engram.cognitive.pipeline.horizon.ItemExtras
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private class QuietBoardProbe : SelfStateProbe {
    override fun servers() = listOf(ServerStatus("text reader", 8081, true, 1))
    override fun memory() = MemoryUse(1, 2, 3, 4)
}

/**
 * The owner's Horizon tool on a real scratch graph, measuring the WHOLE response (horizon text plus the refs listing, plus the rest of the JSON), not just the
 * board: the response must not grow with the number of projects, every ref it shows must work in the next call, and a project the board does not show
 * must still be reachable through its area.
 */
class OwnerHorizonBoardToolTest {
    private val day = 86_400_000L
    private val policy = HorizonPolicy()

    /** A fresh owner with [projects] projects spread over 8 areas (5 quiet tasks each), and three that need attention. */
    private inner class Owner(val projects: Int, seed: (ArcadeHorizonStructureStore, String, com.arcadedb.database.Database) -> Unit = { _, _, _ -> }) {
        val tmp: Path = Files.createTempDirectory("owner-board")
        val manager = DatabaseManager(tmp.resolve("db").toString())
        val email: String
        val startup: OwnerHorizonStartup
        val token: String

        init {
            SchemaBootstrap.bootstrap(manager.getDatabase())
            email = DebugConverseService.resolveUserId("owner-board")
            val db = manager.getDatabase()
            db.transaction {
                db.newVertex("User").apply {
                    set("uid", UUID.randomUUID().toString()); set("username", "owner-board"); set("email", email)
                    set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
                }
            }
            val store = ArcadeHorizonStructureStore(db)
            runBlocking {
                val areas = (1..8).map { store.createContainer(email, 1, ContainerKind.DOMAIN, "Area $it")!! }
                (1..projects).forEach { i ->
                    val p = store.createContainer(email, 1, ContainerKind.PROJECT, "Quiet project $i", parentDomainUid = areas[i % 8].uid)!!
                    (1..5).forEach { t -> store.createItem(email, 1, ItemKind.TASK, "Quiet task $i-$t", projectUid = p.uid) }
                }
                listOf("Williams" to "File the Williams permit", "Meridian" to "Send the Meridian deck", "Garden" to "Order the garden soil").forEach { (name, task) ->
                    val p = store.createContainer(email, 1, ContainerKind.PROJECT, name, parentDomainUid = areas[0].uid)!!
                    store.createItem(email, 1, ItemKind.TASK, task, dueAt = System.currentTimeMillis() - 2 * day, projectUid = p.uid, extras = ItemExtras(dueKind = DueKind.HARD))
                }
                seed(store, email, db)
            }
            startup = bootstrapOwnerHorizon(
                db, NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(), DirectorTickStartup.Disabled("off", false),
                mapOf(ENV_OWNER_IDENTITY to "owner-board", ENV_OWNER_TOKEN_FILE to tmp.resolve("cfg").resolve("owner-token").toString()), QuietBoardProbe(),
            )
            token = (startup as OwnerHorizonStartup.Running).token
        }

        fun close() { manager.close(); tmp.toFile().deleteRecursively() }

        fun app(block: suspend ApplicationTestBuilder.(Client) -> Unit) = testApplication {
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureOwnerHorizonRoutes(startup) { true }
            }
            block(Client(client, token))
        }
    }

    private class Client(val http: HttpClient, val token: String) {
        /** The raw response text, and the parsed object. */
        suspend fun call(message: String?, ops: String = "[]"): Pair<String, JsonObject> {
            val body = buildString {
                append("{")
                if (message != null) append("\"message\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), message)},")
                append("\"operations\":$ops}")
            }
            val r = http.post("/owner/horizon") { header(HttpHeaders.Authorization, "Bearer $token"); header(HttpHeaders.ContentType, "application/json"); setBody(body) }
            assertEquals(HttpStatusCode.OK, r.status)
            val text = r.bodyAsText()
            return text to Json.parseToJsonElement(text).jsonObject
        }
    }

    private fun JsonObject.horizon() = this["horizon"]!!.jsonPrimitive.content
    private fun JsonObject.refs() = this["refs"]!!.jsonPrimitive.content
    private fun JsonObject.applied(i: Int) = this["operations"]!!.jsonArray[i].jsonObject["applied"]!!.jsonPrimitive.boolean
    private fun JsonObject.detail(i: Int) = this["operations"]!!.jsonArray[i].jsonObject["detail"]!!.jsonPrimitive.content
    private fun tokens(text: String) = Math.ceil(text.length / policy.charsPerToken).toInt()

    @Test
    fun `the whole response, text and refs, does not grow with the number of projects`() {
        val rows = listOf(1, 37, 397).map { quiet ->
            val owner = Owner(quiet)
            try {
                var row: Triple<Int, Int, Int>? = null
                owner.app { c ->
                    val (raw, j) = c.call(null)
                    row = Triple(quiet + 3, tokens(raw), tokens(j.refs()))
                    println("TOOL-SIZE projects=${quiet + 3} response_tokens=${tokens(raw)} response_chars=${raw.length} horizon_tokens=${tokens(j.horizon())} refs_tokens=${tokens(j.refs())} refs_lines=${j.refs().lines().size}")
                }
                row!!
            } finally { owner.close() }
        }
        val (small, medium, large) = rows
        assertTrue(Math.abs(large.second - medium.second) <= 40, "400 projects (${large.second} tokens) costs about what 40 (${medium.second}) does")
        assertTrue(Math.abs(large.third - medium.third) <= 20, "and so does the refs listing (${large.third} vs ${medium.third})")
        assertTrue(large.second <= policy.boardTokenBudget + 400, "the whole response at 400 projects is the board plus a short refs listing: ${large.second} tokens")
        assertTrue(small.second <= large.second, "no response is smaller at 400 than at 4")
    }

    @Test
    fun `a project the board does not show is reachable through its area, and every ref shown works on the next call`() {
        val owner = Owner(37)
        try {
            owner.app { c ->
                val (_, first) = c.call(null)
                val refs = first.refs()
                assertFalse("Quiet project 7" in first.horizon() || "Quiet project 7 " in refs, "a quiet project is on neither the board nor the refs listing:\n$refs")
                assertTrue("Williams" in first.horizon() && "Williams" in refs, "the projects on the board are in the listing")
                // The area it is filed under (7 % 8 = 7, so Area 8 of 1..8: areas[7]) is listed.
                val areaRef = Regex("""\b(c\d+) Area 8 \(domain""").find(refs)?.groupValues?.get(1) ?: error("no ref for Area 8 in:\n$refs")
                val inArea = c.call("Let's look at that area.", """[{"verb":"set_focus","target":"$areaRef","quote":"look at that area"}]""").second
                assertTrue(inArea.applied(0), inArea.toString())
                val projectRef = Regex("""\b(c\d+) Quiet project 7 \(project""").find(inArea.refs())?.groupValues?.get(1) ?: error("zooming into the area did not give a ref for the project:\n${inArea.refs()}")
                val inProject = c.call("Now that project.", """[{"verb":"set_focus","target":"$projectRef","quote":"that project"}]""").second
                assertTrue(inProject.applied(0), inProject.toString())
                assertTrue("Quiet task 7-1" in inProject.horizon(), "the project opens: ${inProject.horizon()}")
                c.call("Back to everything.", """[{"verb":"release_focus","quote":"Back to everything"}]""")

                // Every ref shown on the zoomed-out view is accepted by a verb on the very next call.
                val shown = c.call(null).second.refs()
                val handles = Regex("""^\s+([ci]\d+) """, RegexOption.MULTILINE).findAll(shown).map { it.groupValues[1] }.toList()
                assertTrue(handles.size >= 10, "the listing has refs: $handles")
                handles.forEach { ref ->
                    val r = c.call("Nothing to change.", """[{"verb":"set_attention","item":"$ref","attention":"normal","quote":"Nothing to change"}]""").second
                    assertTrue(r.applied(0), "$ref was shown but not accepted: ${r.detail(0)}")
                }
            }
        } finally { owner.close() }
    }

    @Test
    fun `an item the board lists only because it needs attention keeps its ref on the next call`() {
        // The overdue task is the oldest of 40: it is outside the recent and open pools the listing always carries, so it has a ref only because the board names it.
        val owner = Owner(0) { store, email, db ->
            runBlocking {
                val p = store.createContainer(email, 1, ContainerKind.PROJECT, "Permits")!!
                store.createItem(email, 1, ItemKind.TASK, "Renew the boiler permit", dueAt = System.currentTimeMillis() - 5 * day, projectUid = p.uid, extras = ItemExtras(dueKind = DueKind.HARD))
                Thread.sleep(5)
                (1..40).forEach { store.createItem(email, 1, ItemKind.TASK, "Filler task $it", projectUid = p.uid); }
            }
        }
        try {
            owner.app { c ->
                val (_, read) = c.call(null)
                val line = read.refs().lines().firstOrNull { "Renew the boiler permit" in it } ?: error("the board names the task but the listing has no ref for it:\n${read.refs()}")
                val ref = Regex("""\b(i\d+)\b""").find(line)!!.groupValues[1]
                assertTrue(read.horizon().contains("Renew the boiler permit"), read.horizon())
                val r = c.call("It is still live.", """[{"verb":"confirm_item","item":"$ref","quote":"still live"}]""").second
                assertTrue(r.applied(0), "the ref the response showed was refused on the next call: ${r.detail(0)}")
            }
        } finally { owner.close() }
    }

    @Test
    fun `a focused project of 100 items is held to the zoom budget, says how many it left out, and keeps what presses`() {
        val owner = Owner(0) { store, email, db ->
            runBlocking {
                val p = store.createContainer(email, 1, ContainerKind.PROJECT, "Big project")!!
                (1..95).forEach { store.createItem(email, 1, ItemKind.TASK, "Routine task number $it for the big project", projectUid = p.uid) }
                (1..5).forEach { store.createItem(email, 1, ItemKind.TASK, "Overdue promise $it for the big project", dueAt = System.currentTimeMillis() - 3 * day, projectUid = p.uid, extras = ItemExtras(dueKind = DueKind.HARD)) }
            }
        }
        try {
            owner.app { c ->
                val ref = Regex("""\b(c\d+) Big project \(project""").find(c.call(null).second.refs())?.groupValues?.get(1) ?: error("no ref for Big project")
                val focused = c.call("Let's work on the big project.", """[{"verb":"set_focus","target":"$ref","quote":"work on the big project"}]""").second.horizon()
                val section = focused.substringAfter("Contextual awareness")
                // An overdue item is also in the "also due" block, so count distinct items, not mentions.
                val listed = Regex("""Routine task number \d+|Overdue promise \d""").findAll(section).map { it.value }.toSet().size
                println("FOCUS-SIZE section_tokens=${tokens(section)} chars=${section.length} budget=${policy.zoomTokenBudget} items_listed=$listed")
                assertTrue(tokens(section) <= policy.zoomTokenBudget, "the focused view is ${tokens(section)} tokens")
                val left = Regex("""(\d+) more items? (?:were|was) left out""").find(section)?.groupValues?.get(1)?.toInt() ?: error("it does not say how many it left out:\n$section")
                assertEquals(100, listed + left, "listed plus left out is every item")
                (1..5).forEach { assertTrue("Overdue promise $it" in section, "what presses is kept: $it") }
            }
        } finally { owner.close() }
    }

    @Test
    fun `open and hidden counts say the same thing on the board, in the focused view and in the area roll-up`() {
        val owner = Owner(0) { store, email, db ->
            runBlocking {
                // Pantry needs attention (a hard overdue task), has 4 more plain open items, and 5 suppressed ones: 5 open, 5 hidden.
                val pantry = store.createContainer(email, 1, ContainerKind.PROJECT, "Pantry")!!
                store.createItem(email, 1, ItemKind.TASK, "Renew the pantry licence", dueAt = System.currentTimeMillis() - 2 * day, projectUid = pantry.uid, extras = ItemExtras(dueKind = DueKind.HARD))
                (1..4).forEach { store.createItem(email, 1, ItemKind.TASK, "Plain pantry task $it", projectUid = pantry.uid) }
                (1..5).forEach { i -> store.createItem(email, 1, ItemKind.TASK, "Hidden pantry task $i", projectUid = pantry.uid)!!.let { store.setAttention(email, 2, it.uid, AttentionDirective.SUPPRESSED) } }
                // An area of two quiet projects: Larder has 3 open and 2 hidden, Shed has 4 open.
                val home = store.createContainer(email, 1, ContainerKind.DOMAIN, "Home")!!
                val larder = store.createContainer(email, 1, ContainerKind.PROJECT, "Larder", parentDomainUid = home.uid)!!
                val shed = store.createContainer(email, 1, ContainerKind.PROJECT, "Shed", parentDomainUid = home.uid)!!
                (1..3).forEach { store.createItem(email, 1, ItemKind.TASK, "Larder task $it", projectUid = larder.uid) }
                // Suppressed directly, so no change record makes Larder "changed since last read": it stays quiet and rolls up into Home.
                (1..2).forEach { i -> store.createItem(email, 1, ItemKind.TASK, "Hidden larder task $i", projectUid = larder.uid) }
                db.transaction { db.command("sql", "UPDATE Task SET attention = 'suppressed' WHERE text LIKE 'Hidden larder task%'") }
                (1..4).forEach { store.createItem(email, 1, ItemKind.TASK, "Shed task $it", projectUid = shed.uid) }
            }
        }
        try {
            owner.app { c ->
                val (_, board) = c.call(null)
                val text = board.horizon()
                println("COUNTS-BOARD\n${text.lines().filter { "Pantry" in it || "Home" in it }.joinToString("\n")}")
                val onBoard = Regex("""- c\d+ Pantry[^\n]*?: (\d+) open(?:, (\d+) hidden)?""").find(text) ?: error("Pantry is not on the board:\n$text")
                assertEquals("5", onBoard.groupValues[1], "the board counts open items only:\n$text")
                assertEquals("5", onBoard.groupValues[2], "and says how many are hidden:\n$text")

                // The focused view says the same.
                val pantryRef = Regex("""\b(c\d+) Pantry \(project""").find(board.refs())?.groupValues?.get(1) ?: error("no Pantry ref")
                val focused = c.call("Let's work on the pantry.", """[{"verb":"set_focus","target":"$pantryRef","quote":"work on the pantry"}]""").second.horizon()
                val inView = Regex("""focused on the project "Pantry" \((\d+) open(?:, (\d+) hidden)?""").find(focused) ?: error("no count in the focused view:\n$focused")
                assertEquals(onBoard.groupValues[1], inView.groupValues[1], "open: board and focused view agree:\n$focused")
                assertEquals(onBoard.groupValues[2], inView.groupValues[2], "hidden: board and focused view agree")
                assertEquals(5, focused.lines().count { "suppressed at their request" in it }, "and the hidden items it lists are those five:\n$focused")
                c.call("Back to everything.", """[{"verb":"release_focus","quote":"Back to everything"}]""")

                // The area roll-up follows the same rule: hidden items are not in its open number, and it equals the sum over the area's projects.
                val rollup = Regex("""Home: 2 projects, (\d+) open""").find(text)?.groupValues?.get(1)?.toInt() ?: error("no roll-up for Home:\n$text")
                assertEquals(7, rollup, "3 + 4 open, the 2 hidden are not counted")
                val homeRef = Regex("""\b(c\d+) Home \(domain""").find(board.refs())?.groupValues?.get(1) ?: error("no Home ref")
                val area = c.call("Look at the home area.", """[{"verb":"set_focus","target":"$homeRef","quote":"home area"}]""").second.horizon()
                val larder = Regex("""In "Larder" \(project\):\n- (\d+) open(?:, (\d+) hidden)?""").find(area) ?: error("no Larder count in the area view:\n$area")
                val shed = Regex("""In "Shed" \(project\):\n- (\d+) open""").find(area) ?: error("no Shed count in the area view:\n$area")
                assertEquals("3", larder.groupValues[1]); assertEquals("2", larder.groupValues[2]); assertEquals("4", shed.groupValues[1])
                assertEquals(rollup, larder.groupValues[1].toInt() + shed.groupValues[1].toInt(), "roll-up equals the sum of the focused counts")
            }
        } finally { owner.close() }
    }
}
