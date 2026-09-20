package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.ZoneId
import java.util.UUID

/**
 * [HorizonOperationApplier] and [ViewSnapshotBuilder] against a real database: each verb's actual
 * effect on the graph, that a replayed plan converges instead of duplicating, and that a snapshot
 * only ever shows (and only ever lets the model refer to) the caller's own things.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HorizonOperationApplierTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var graph: HorizonGraphStore
    private lateinit var applier: HorizonOperationApplier
    private lateinit var builder: ViewSnapshotBuilder
    private val zone = ZoneId.of("UTC")

    @BeforeAll
    fun setUp() {
        dbManager = DatabaseManager("./data/test-horizon-applier-${System.currentTimeMillis()}")
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        structure = ArcadeHorizonStructureStore(dbManager.getDatabase())
        graph = ArcadeHorizonGraphStore(dbManager.getDatabase())
        applier = HorizonOperationApplier(structure, graph)
        builder = ViewSnapshotBuilder(structure)
    }

    @AfterAll
    fun tearDown() {
        dbManager.close()
        File("./data").listFiles()?.filter { it.name.startsWith("test-horizon-applier-") }?.forEach { it.deleteRecursively() }
    }

    private fun newUser(): String {
        val email = "applier-${UUID.randomUUID()}@example.test"
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "applier"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        return email
    }

    /**
     * RELATED_TO edges of [relationType] from [outUid] to [inUid], counted by listing and filtering
     * here — not `SELECT count(*)`: ArcadeDB 25.1.1 answers a count that combines an index prefix
     * (`relationType`) with another predicate from the index range alone, ignoring the other
     * predicate (seen returning every `supersedes` edge in the database for one item).
     */
    private fun relatedEdges(relationType: String, outUid: String? = null, inUid: String? = null): Int =
        dbManager.getDatabase().query("sql", "SELECT relationType as t, @out.uid as o, @in.uid as i FROM RELATED_TO").use { rs ->
            generateSequence { if (rs.hasNext()) rs.next().toMap() else null }
                .count { it["t"] == relationType && (outUid == null || it["o"] == outUid) && (inUid == null || it["i"] == inUid) }
        }

    private suspend fun apply(user: String, vararg ops: HorizonOperation, requestId: String = UUID.randomUUID().toString()) =
        applier.apply(user, 1, requestId, ops.toList())

    // ── Each verb's effect ───────────────────────────────────────────────────

    @Test
    fun `record_item creates an item of the right kind, filed and tied to its entities`() = runBlocking {
        val user = newUser()
        val project = structure.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!

        val result = apply(user, RecordItem(ItemKind.TASK, "book the room", projectUid = project.uid, dueAt = 5_000L, about = listOf("room", "Sam"))).single()

        assertTrue(result.applied)
        assertEquals("book the room", result.resultText)
        val item = structure.getItem(user, result.resultUid!!)!!
        assertEquals(ItemKind.TASK, item.kind)
        assertEquals(project.uid, item.projectUid)
        assertEquals(5_000L, item.dueAt)
        assertEquals(setOf("room", "Sam"), item.about.toSet())
    }

    @Test
    fun `a title becomes the item's text and the user's own words are kept as its source`() = runBlocking {
        val user = newUser()

        val result = apply(user, RecordItem(ItemKind.TASK, "I never got around to ordering the soil", title = "Order the soil")).single()

        assertEquals("Order the soil", result.resultText, "propagation and the reply see the label")
        assertEquals("Recorded task: \"Order the soil\"", result.summary)
        val item = structure.getItem(user, result.resultUid!!)!!
        assertEquals("Order the soil", item.text)
        assertEquals("I never got around to ordering the soil", item.sourceQuote)
    }

    @Test
    fun `without a title the text is the user's words and no separate source is stored`() = runBlocking {
        val user = newUser()
        val item = structure.getItem(user, apply(user, RecordItem(ItemKind.TASK, "book the room")).single().resultUid!!)!!
        assertEquals("book the room", item.text)
        assertNull(item.sourceQuote)
    }

    @Test
    fun `a corrected item takes the correction's title and keeps the words that corrected it`() = runBlocking {
        val user = newUser()
        val old = structure.createItem(user, 1, ItemKind.EVENT, "Sister's birthday", dueAt = 1_000L)!!

        val result = apply(user, CorrectItem(old.uid, "Actually the birthday is the 25th, not the 24th", dueAt = 2_000L, title = "Sister's birthday")).single()

        val corrected = structure.getItem(user, result.resultUid!!)!!
        assertEquals("Sister's birthday", corrected.text)
        assertEquals("Actually the birthday is the 25th, not the 24th", corrected.sourceQuote)
        assertEquals("Corrected \"Sister's birthday\" to \"Sister's birthday\"", result.summary)
    }

    @Test
    fun `a project created earlier in the same response can be filed under by name`() = runBlocking {
        val user = newUser()
        val results = apply(
            user,
            CreateContainer(ContainerKind.PROJECT, "Kitchen remodel", namedByUser = true),
            RecordItem(ItemKind.TASK, "order the cabinets", projectName = "kitchen REMODEL"),
        )

        assertTrue(results.all { it.applied })
        val project = structure.listContainers(user, ContainerKind.PROJECT).single()
        assertFalse(project.tentative)
        assertEquals(listOf("order the cabinets"), structure.itemsIn(user, project.uid).map { it.text })
    }

    @Test
    fun `a container the user did not name is stored as tentative`() = runBlocking {
        val user = newUser()
        apply(user, CreateContainer(ContainerKind.PROJECT, "Travel planning", namedByUser = false))
        assertTrue(structure.listContainers(user).single().tentative)
    }

    @Test
    fun `resolve and reopen change status, and a fact cannot be resolved`() = runBlocking {
        val user = newUser()
        val task = structure.createItem(user, 1, ItemKind.TASK, "send the invoice")!!
        val fact = structure.createItem(user, 1, ItemKind.FACT, "a plain fact")!!

        val done = apply(user, ResolveItem(task.uid, "I sent it")).single()
        assertTrue(done.applied)
        assertTrue(done.summary.startsWith("Marked done"))
        assertEquals("resolved", structure.getItem(user, task.uid)!!.status)

        assertTrue(apply(user, ReopenItem(task.uid, "not actually done")).single().applied)
        assertEquals("open", structure.getItem(user, task.uid)!!.status)

        val refused = apply(user, ResolveItem(fact.uid, "done")).single()
        assertFalse(refused.applied, "the applier re-checks what the store allows, not just what the parser accepted")
    }

    @Test
    fun `a correction supersedes rather than overwrites, keeping where it was filed and what it was about`() = runBlocking {
        val user = newUser()
        val project = structure.createContainer(user, 1, ContainerKind.PROJECT, "Party")!!
        val old = structure.createItem(user, 1, ItemKind.EVENT, "Sister's birthday is the 21st", dueAt = 1_000L, projectUid = project.uid)!!
        structure.linkAbout(user, 1, old.uid, "sister")

        val result = apply(user, CorrectItem(old.uid, "Sister's birthday is the 24th", dueAt = 2_000L)).single()

        assertTrue(result.applied)
        val corrected = structure.getItem(user, result.resultUid!!)!!
        assertEquals(ItemKind.EVENT, corrected.kind)
        assertEquals("Sister's birthday is the 24th", corrected.text)
        assertEquals(2_000L, corrected.dueAt)
        assertEquals(project.uid, corrected.projectUid)
        assertEquals(listOf("sister"), corrected.about)
        // Nothing was lost: the old statement is still in the graph, closed, and the new one supersedes it.
        assertEquals("resolved", structure.getItem(user, old.uid)!!.status)
        assertEquals("Sister's birthday is the 21st", structure.getItem(user, old.uid)!!.text)
        assertEquals(1, relatedEdges("supersedes", outUid = corrected.uid, inUid = old.uid))
    }

    @Test
    fun `a correction keeps the old date when the correction gives none`() = runBlocking {
        val user = newUser()
        val old = structure.createItem(user, 1, ItemKind.TASK, "send the slides", dueAt = 7_000L)!!
        val result = apply(user, CorrectItem(old.uid, "send the slides to Sam")).single()
        assertEquals(7_000L, structure.getItem(user, result.resultUid!!)!!.dueAt)
    }

    @Test
    fun `file, focus, attention and link each do what they say`() = runBlocking {
        val user = newUser()
        val project = structure.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val domain = structure.createContainer(user, 1, ContainerKind.DOMAIN, "Work")!!
        val a = structure.createItem(user, 1, ItemKind.TASK, "first")!!
        val b = structure.createItem(user, 1, ItemKind.TASK, "second")!!

        val results = apply(
            user,
            FileItem(a.uid, project.uid, "move it"),
            FileItem(project.uid, domain.uid, "demo is work"),
            SetFocus(project.uid, "work on demo"),
            SetAttention(b.uid, AttentionDirective.SUPPRESSED, "stop bringing that up"),
            LinkItems(a.uid, b.uid, "keep together"),
        )

        assertTrue(results.all { it.applied }, results.toString())
        assertEquals(project.uid, structure.getItem(user, a.uid)!!.projectUid)
        assertEquals(domain.uid, structure.getContainer(user, project.uid)!!.parentUid)
        assertEquals(listOf(project.uid), structure.getFocus(user).map { it.targetUid })
        assertEquals(FocusMode.PINNED, structure.getFocus(user).single().mode)
        assertEquals(AttentionDirective.SUPPRESSED, structure.getItem(user, b.uid)!!.attention)
        assertEquals(1, relatedEdges("relevant_to", outUid = a.uid, inUid = b.uid))
    }

    @Test
    fun `release_focus clears the pin and the nudges`() = runBlocking {
        val user = newUser()
        val a = structure.createContainer(user, 1, ContainerKind.PROJECT, "A")!!
        val b = structure.createContainer(user, 1, ContainerKind.PROJECT, "B")!!
        structure.setFocus(user, 1, a.uid, FocusMode.PINNED)
        structure.setFocus(user, 1, b.uid, FocusMode.NUDGE)

        assertTrue(apply(user, ReleaseFocus("zoom out")).single().applied)
        assertTrue(structure.getFocus(user).isEmpty())
    }

    @Test
    fun `hold_for_choice writes nothing to the graph`() = runBlocking {
        val user = newUser()
        val result = apply(user, HoldForChoice("I'm really stressed about the demo")).single()
        assertTrue(result.applied)
        assertNull(result.resultUid)
        assertTrue(structure.listRecentItems(user, 10).isEmpty())
    }

    // ── Safety and repetition ────────────────────────────────────────────────

    @Test
    fun `operations cannot touch another users things even given their uids`() = runBlocking {
        val owner = newUser()
        val intruder = newUser()
        val task = structure.createItem(owner, 1, ItemKind.TASK, "owner's task")!!
        val project = structure.createContainer(owner, 1, ContainerKind.PROJECT, "Owner's project")!!

        val results = apply(
            intruder,
            ResolveItem(task.uid, "done"),
            CorrectItem(task.uid, "hijacked"),
            SetFocus(project.uid, "focus"),
            SetAttention(task.uid, AttentionDirective.SUPPRESSED, "hide it"),
            FileItem(task.uid, project.uid, "move"),
        )

        assertTrue(results.none { it.applied }, results.toString())
        assertEquals("open", structure.getItem(owner, task.uid)!!.status)
        assertEquals("owner's task", structure.getItem(owner, task.uid)!!.text)
        assertTrue(structure.getFocus(owner).isEmpty() && structure.getFocus(intruder).isEmpty())
    }

    @Test
    fun `replaying a whole plan converges on the same graph`() = runBlocking {
        val user = newUser()
        val old = structure.createItem(user, 1, ItemKind.TASK, "send the slides")!!
        val plan = listOf(
            CreateContainer(ContainerKind.PROJECT, "Demo", namedByUser = true),
            RecordItem(ItemKind.TASK, "book the room", projectName = "Demo", about = listOf("room")),
            CorrectItem(old.uid, "send the slides to Sam"),
            ResolveItem(old.uid, "sent"),
        )

        val first = applier.apply(user, 1, "req-replay", plan)
        val second = applier.apply(user, 1, "req-replay", plan)

        assertEquals(first.map { it.resultUid }, second.map { it.resultUid }, "the same items come back")
        assertEquals(1, structure.listContainers(user, ContainerKind.PROJECT).size)
        assertEquals(1, structure.listRecentItems(user, 20).count { it.text == "book the room" })
        assertEquals(1, structure.listRecentItems(user, 20).count { it.text == "send the slides to Sam" })
        assertEquals(1, relatedEdges("supersedes", inUid = old.uid), "a replayed correction must not stack a second supersedes edge")
    }

    @Test
    fun `an operation that throws is reported as failed and does not stop the rest`() = runBlocking {
        val user = newUser()
        val exploding = object : HorizonStructureStore by structure {
            override suspend fun resolve(userEmail: String, cycleSeq: Long, targetUid: String, resolved: Boolean): Boolean =
                throw IllegalStateException("boom")
        }
        val results = HorizonOperationApplier(exploding, graph).apply(
            user, 1, "req-boom", listOf(ResolveItem("x", "done"), RecordItem(ItemKind.FACT, "still recorded")),
        )
        assertEquals(listOf(false, true), results.map { it.applied })
    }

    // ── The snapshot the model is shown ──────────────────────────────────────

    @Test
    fun `a snapshot numbers the users containers and items, shows the pinned focus, and resolves refs back to uids`() = runBlocking {
        val user = newUser()
        val work = structure.createContainer(user, 1, ContainerKind.DOMAIN, "Work")!!
        val demo = structure.createContainer(user, 1, ContainerKind.PROJECT, "Demo", parentDomainUid = work.uid)!!
        val garden = structure.createContainer(user, 1, ContainerKind.PROJECT, "Garden")!!
        val invoice = structure.createItem(user, 2, ItemKind.TASK, "send the invoice", projectUid = demo.uid, dueAt = 1_800_000_000_000L)!!
        structure.createItem(user, 3, ItemKind.FACT, "sister is allergic to peanuts")
        structure.setFocus(user, 4, demo.uid, FocusMode.PINNED)

        val snapshot = builder.build(user, zone)

        assertEquals(listOf("c1 Work", "c2 Demo", "c3 Garden"), snapshot.containers.map { "${it.ref} ${it.name}" })
        assertEquals("c1", snapshot.containers[1].parentRef)
        assertEquals(work.uid, snapshot.uidFor("c1"))
        assertEquals(demo.uid, snapshot.uidFor(" c2 "))
        assertNull(snapshot.uidFor("c9"))
        assertNull(snapshot.uidFor(demo.uid), "a uid is not a ref")
        assertEquals(listOf("pinned -> c2 Demo (project)"), snapshot.focusLines)
        val invoiceLine = snapshot.items.single { it.uid == invoice.uid }
        assertEquals("c2", invoiceLine.projectRef)
        val text = snapshot.render()
        assertTrue(text.contains("Focus: pinned -> c2 Demo (project)"), text)
        assertTrue(text.contains("task \"send the invoice\" [in c2] due 2027-"), text)
        assertTrue(text.contains("fact \"sister is allergic to peanuts\""), text)
        assertNotNull(garden)
    }

    @Test
    fun `the focused project's items are always shown even when many newer items exist`() = runBlocking {
        val user = newUser()
        val demo = structure.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val old = structure.createItem(user, 1, ItemKind.FACT, "an old fact in the focused project", projectUid = demo.uid)!!
        repeat(20) { structure.createItem(user, 2, ItemKind.FACT, "noise $it") }
        structure.setFocus(user, 3, demo.uid, FocusMode.PINNED)

        val snapshot = ViewSnapshotBuilder(structure, maxItems = 6).build(user, zone)

        assertEquals(6, snapshot.items.size, "bounded")
        assertNotNull(snapshot.itemByUid(old.uid), "the focus's own items come first, so the cap never squeezes them out")
    }

    @Test
    fun `nudges are not shown as focus, and finished or archived containers are left out`() = runBlocking {
        val user = newUser()
        val live = structure.createContainer(user, 1, ContainerKind.PROJECT, "Live")!!
        val done = structure.createContainer(user, 1, ContainerKind.PROJECT, "Finished")!!
        structure.resolve(user, 2, done.uid)
        structure.setFocus(user, 3, live.uid, FocusMode.NUDGE)

        val snapshot = builder.build(user, zone)

        assertEquals(listOf("Live"), snapshot.containers.map { it.name })
        assertTrue(snapshot.focusLines.isEmpty(), "a nudge is mechanical bookkeeping, not something the model is shown or told to set")
    }

    @Test
    fun `a snapshot never contains another users containers or items`() = runBlocking {
        val a = newUser()
        val b = newUser()
        structure.createContainer(a, 1, ContainerKind.PROJECT, "A's secret project")
        structure.createItem(a, 1, ItemKind.FACT, "A's private fact")

        val snapshot = builder.build(b, zone)

        assertTrue(snapshot.containers.isEmpty() && snapshot.items.isEmpty())
        assertEquals("Snapshot: nothing recorded yet.\nFocus: none (zoomed out)", snapshot.render())
    }

    @Test
    fun `a pending item and a pending statement are surfaced to the model with the refs to act on them`() = runBlocking {
        val user = newUser()
        repeat(15) { structure.createItem(user, 1, ItemKind.FACT, "filler $it") }
        val unfiled = structure.createItem(user, 2, ItemKind.TASK, "review the budget")!!

        val snapshot = ViewSnapshotBuilder(structure, maxItems = 4).build(user, zone, pendingFilingItemUid = unfiled.uid, pendingEmotionQuote = "I'm so stressed")

        val ref = snapshot.items.first { it.uid == unfiled.uid }.ref
        assertEquals("i1", ref, "the pending item goes first so the cap can't drop it")
        val text = snapshot.render()
        assertTrue(text.contains("Pending: last turn the user was asked which project $ref belongs to"), text)
        assertTrue(text.contains("Pending: the user said \"I'm so stressed\""), text)
    }
}
