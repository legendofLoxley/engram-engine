package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.cognitive.pipeline.HorizonItemsRenderer
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.director.ChangeMarkerReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import java.util.UUID

/**
 * Item state (open, active, waiting, done, dropped) and the time fields, against a real scratch database and a clock the test controls:
 * the migration of existing items, the transitions, what the Horizon shows and what it calls overdue, and the hard/soft labels.
 */
class HorizonStateTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var graph: HorizonGraphStore
    private lateinit var path: String
    private var now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun daysFromNow(days: Double) = now + (days * day).toLong()

    @BeforeEach
    fun setUp() {
        path = "./data/test-horizon-state-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        structure = ArcadeHorizonStructureStore(dbManager.getDatabase())
        graph = ArcadeHorizonGraphStore(dbManager.getDatabase())
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(path).deleteRecursively()
    }

    private fun newUser(): String {
        val email = "state-${UUID.randomUUID()}@example.test"
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "s"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        return email
    }

    private fun view(user: String, cycle: Long = 10): ContextHorizon =
        runBlocking { (ArcadeHorizonAssembler(dbManager.getDatabase(), clock = { now }).assemble(user, cycle, budget = HorizonBudget(12, 0, false)) as AssembleOutcome.Assembled).horizon }

    private fun ContextHorizon.item(text: String): HorizonItem? = items.firstOrNull { it.text.text == text }

    private fun project(user: String, name: String) = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, name)!! }
    private fun task(user: String, text: String, project: ContainerRecord? = null, due: Double? = null, extras: ItemExtras = ItemExtras()) =
        runBlocking { structure.createItem(user, 1, ItemKind.TASK, text, dueAt = due?.let { daysFromNow(it) }, projectUid = project?.uid, extras = extras)!! }
    private fun setState(user: String, uid: String, state: WorkState, nudge: Double? = null, waitingOn: String? = null) =
        runBlocking { structure.setState(user, 2, uid, state, nudge?.let { TimeValue(daysFromNow(it)) }, waitingOn) }
    private fun get(user: String, uid: String) = runBlocking { structure.getItem(user, uid)!! }

    // ── Migration: existing items keep their meaning ─────────────────────────

    @Test
    fun `items stored before state existed read as open and done, and nothing is rewritten`() {
        val user = newUser()
        val open = task(user, "Legacy open")
        val closed = task(user, "Legacy closed")
        // Make both look exactly as they were stored before this change: a coarse status and no state at all.
        val db = dbManager.getDatabase()
        db.transaction {
            db.command("sql", "UPDATE Task REMOVE state, stateFromSource WHERE uid IN [:a, :b]", mapOf("a" to open.uid, "b" to closed.uid))
            db.command("sql", "UPDATE Task SET status = 'resolved', resolvedAt = 5 WHERE uid = :b", mapOf("b" to closed.uid))
        }

        // Bootstrapping again (what a deploy does) adds the properties and leaves the data as it was.
        SchemaBootstrap.bootstrap(db)

        assertNull(get(user, open.uid).state, "no state was written")
        assertEquals(WorkState.OPEN, get(user, open.uid).workState)
        assertEquals(WorkState.DONE, get(user, closed.uid).workState)
        assertEquals("resolved", get(user, closed.uid).status)
        val h = view(user)
        assertNotNull(h.item("Legacy open"))
        assertNull(h.item("Legacy closed"), "a resolved item is still out of view")
        assertEquals("open", h.item("Legacy open")!!.state, "and the open one reads as open")
        // Legacy items are listed as open for the pools that ask for open items only.
        assertEquals(listOf("Legacy open"), runBlocking { structure.listRecentItems(user, 10, openOnly = true) }.map { it.text })
    }

    @Test
    fun `the schema gains the state and time properties additively`() {
        val schema = dbManager.getDatabase().schema
        for (type in listOf("Task", "Event", "Question")) {
            for (prop in listOf("state", "dueKind", "startsAt", "nudgeAt", "waitingOn", "dueHasTime")) {
                assertTrue(schema.getType(type).existsProperty(prop), "$type.$prop")
            }
        }
        assertTrue(schema.getType("Phrase").existsProperty("happenedAt"))
    }

    @Test
    fun `the memory marker reads the same before and after the schema change, and state changes add no Task vertices`() {
        val user = newUser()
        val a = task(user, "One"); task(user, "Two"); val c = task(user, "Three")
        val db = dbManager.getDatabase()
        val reader = ChangeMarkerReader(db)
        val before = reader.read(user)!!
        SchemaBootstrap.bootstrap(db) // what a deploy runs: adds the new properties
        assertEquals(before.counts, reader.read(user)!!.counts, "bootstrapping the new properties changes no count")

        fun vertices() = db.query("sql", "SELECT count(*) AS n FROM Task").use { it.next().getProperty<Number>("n").toLong() }
        assertEquals(3L, vertices())
        setState(user, a.uid, WorkState.WAITING, nudge = 3.0, waitingOn = "Bob")
        setState(user, c.uid, WorkState.DROPPED)
        assertEquals(3L, vertices(), "a state change rewrites an item, it never adds or removes one")
    }

    // ── Transitions ──────────────────────────────────────────────────────────

    @Test
    fun `set_state moves an item through every state and back, resolve and reopen being done and open`() {
        val user = newUser()
        val t = task(user, "Draft the proposal")
        assertEquals(WorkState.OPEN, get(user, t.uid).workState)

        assertTrue(setState(user, t.uid, WorkState.ACTIVE))
        assertEquals(WorkState.ACTIVE, get(user, t.uid).workState)
        assertEquals("open", get(user, t.uid).status, "the coarse status stays open while it is worked on")

        assertTrue(setState(user, t.uid, WorkState.WAITING, nudge = 5.0, waitingOn = "legal review"))
        val waiting = get(user, t.uid)
        assertEquals(WorkState.WAITING, waiting.workState)
        assertEquals("legal review", waiting.waitingOn)
        assertEquals(daysFromNow(5.0), waiting.nudgeAt)

        // Leaving waiting clears what only waiting uses.
        assertTrue(setState(user, t.uid, WorkState.ACTIVE))
        assertNull(get(user, t.uid).waitingOn); assertNull(get(user, t.uid).nudgeAt)

        assertTrue(setState(user, t.uid, WorkState.DONE))
        assertEquals("resolved", get(user, t.uid).status)
        assertTrue(runBlocking { structure.resolve(user, 3, t.uid, resolved = false) }, "reopen_item is set_state open")
        assertEquals(WorkState.OPEN, get(user, t.uid).workState)
        assertEquals("open", get(user, t.uid).status)

        assertTrue(runBlocking { structure.resolve(user, 4, t.uid, resolved = true) }, "resolve_item is set_state done")
        assertEquals(WorkState.DONE, get(user, t.uid).workState)

        assertTrue(setState(user, t.uid, WorkState.DROPPED))
        assertEquals(WorkState.DROPPED, get(user, t.uid).workState)
        assertEquals("resolved", get(user, t.uid).status)
        assertTrue(setState(user, t.uid, WorkState.DROPPED), "the same state again is a no-op that succeeds")
    }

    @Test
    fun `a fact has no state, and another user's item cannot be changed`() {
        val user = newUser(); val other = newUser()
        val fact = runBlocking { structure.createItem(user, 1, ItemKind.FACT, "The office is on Main Street")!! }
        assertFalse(setState(user, fact.uid, WorkState.DONE))
        val t = task(user, "Mine")
        assertFalse(setState(other, t.uid, WorkState.DONE))
        assertEquals(WorkState.OPEN, get(user, t.uid).workState)
    }

    // ── What the Horizon shows ───────────────────────────────────────────────

    @Test
    fun `done and dropped items never appear, even overdue`() {
        val user = newUser()
        val done = task(user, "Finished thing", due = -3.0)
        val dropped = task(user, "Abandoned thing", due = -3.0)
        task(user, "Live thing", due = -3.0)
        setState(user, done.uid, WorkState.DONE); setState(user, dropped.uid, WorkState.DROPPED)

        val h = view(user)

        assertNull(h.item("Finished thing")); assertNull(h.item("Abandoned thing"))
        assertNotNull(h.item("Live thing"))
    }

    @Test
    fun `a waiting item is hidden outside its project's focus, and shown when the project is focused`() {
        val user = newUser()
        val demo = project(user, "Demo")
        val other = project(user, "Garden")
        val waiting = task(user, "Hear back from the venue", demo, extras = ItemExtras(state = WorkState.WAITING, waitingOn = "the venue", nudge = TimeValue(daysFromNow(10.0))))
        task(user, "Plain demo task", demo)

        assertNull(view(user).item("Hear back from the venue"), "zoomed out, a waiting item stays out of view")

        runBlocking { structure.setFocus(user, 2, other.uid, FocusMode.PINNED) }
        assertNull(view(user).item("Hear back from the venue"), "focus on another project does not bring it")

        runBlocking { structure.setFocus(user, 3, demo.uid, FocusMode.PINNED) }
        val h = view(user)
        assertNotNull(h.item("Hear back from the venue"), "focused on its project, it shows")
        assertEquals("waiting", h.item("Hear back from the venue")!!.state)
        assertEquals("the venue", h.item("Hear back from the venue")!!.waitingOn)
        assertEquals(waiting.uid, h.item("Hear back from the venue")!!.sourceRefs.first().phraseUid)
        assertTrue(HorizonItemsRenderer.render(h, ZoneOffset.UTC).any { "[waiting on the venue]" in it.renderedLine })
    }

    @Test
    fun `a waiting item comes into view on its nudge date, whatever the focus, and says it is a check-in`() {
        val user = newUser()
        val demo = project(user, "Demo")
        task(user, "Hear back from the venue", demo, extras = ItemExtras(state = WorkState.WAITING, waitingOn = "the venue", nudge = TimeValue(daysFromNow(2.0))))

        assertNull(view(user).item("Hear back from the venue"), "before the date")

        now = daysFromNow(2.0) + 1000
        val h = view(user)
        val item = h.item("Hear back from the venue")
        assertNotNull(item, "once the date has arrived")
        assertTrue(item!!.surfacing is SurfacingReason.CheckIn)
        assertTrue(HorizonItemsRenderer.render(h, ZoneOffset.UTC).first { "venue" in it.renderedLine }.renderedLine.contains("check in"))
    }

    @Test
    fun `overdue is for open and active items only`() {
        val user = newUser()
        val demo = project(user, "Demo")
        task(user, "Open and late", due = -4.0)
        val active = task(user, "Active and late", due = -4.0); setState(user, active.uid, WorkState.ACTIVE)
        // A waiting item that is in view (its project is focused) is not called overdue.
        task(user, "Waiting and late", demo, due = -4.0, extras = ItemExtras(state = WorkState.WAITING, waitingOn = "Sam"))
        runBlocking { structure.setFocus(user, 2, demo.uid, FocusMode.PINNED) }

        val h = view(user)
        val lines = HorizonItemsRenderer.render(h, ZoneOffset.UTC).associateBy { it.renderedLine.substringAfter('"').substringBefore('"') }

        assertTrue(lines.getValue("Open and late").renderedLine.contains("overdue"))
        assertTrue(lines.getValue("Active and late").renderedLine.contains("overdue"))
        assertTrue(lines.getValue("Active and late").renderedLine.contains("[active]"))
        assertNotNull(h.item("Waiting and late"))
        assertFalse(lines.getValue("Waiting and late").renderedLine.contains("overdue"), lines.getValue("Waiting and late").renderedLine)
        assertFalse(h.item("Waiting and late")!!.surfacing is SurfacingReason.Urgent)
    }

    @Test
    fun `hard and soft show next to a due date, soft being the default`() {
        val user = newUser()
        task(user, "Promise to the client", due = 2.0, extras = ItemExtras(dueKind = DueKind.HARD))
        task(user, "Nice to have by then", due = 2.0)
        task(user, "Soft said outright", due = 1.0, extras = ItemExtras(dueKind = DueKind.SOFT))

        val h = view(user)
        val lines = HorizonItemsRenderer.render(h, ZoneOffset.UTC).associate { it.renderedLine.substringAfter('"').substringBefore('"') to it.renderedLine }

        assertTrue(lines.getValue("Promise to the client").contains(", hard)"), lines.getValue("Promise to the client"))
        assertTrue(lines.getValue("Nice to have by then").contains(", soft)"), lines.getValue("Nice to have by then"))
        assertTrue(lines.getValue("Soft said outright").contains(", soft)"))
        assertEquals("hard", h.item("Promise to the client")!!.dueKind)
    }

    @Test
    fun `the precision given is kept, a date or a date and time`() {
        val user = newUser()
        val dateOnly = task(user, "By the day", due = 1.0)
        val withTime = runBlocking { structure.createItem(user, 1, ItemKind.TASK, "At a time", dueAt = daysFromNow(1.0), extras = ItemExtras(dueHasTime = true, starts = TimeValue(daysFromNow(0.5), true), happened = TimeValue(daysFromNow(-1.0))))!! }
        assertFalse(get(user, dateOnly.uid).dueHasTime)
        assertTrue(get(user, withTime.uid).dueHasTime)
        assertTrue(get(user, withTime.uid).startsHasTime)
        assertFalse(get(user, withTime.uid).happenedHasTime)
        assertEquals(daysFromNow(-1.0), get(user, withTime.uid).happenedAt)
    }

    // ── The snapshot the model is shown ──────────────────────────────────────

    @Test
    fun `the snapshot marks state and hard or soft, and a waiting item stays nameable only through the recent slots, not the open pool`() {
        val user = newUser()
        val demo = project(user, "Demo")
        task(user, "Promise", due = 3.0, extras = ItemExtras(dueKind = DueKind.HARD))
        task(user, "Working on it", extras = ItemExtras(state = WorkState.ACTIVE))
        task(user, "Held up", demo, extras = ItemExtras(state = WorkState.WAITING, waitingOn = "Sam", nudge = TimeValue(daysFromNow(9.0))))
        task(user, "Ready for a check-in", extras = ItemExtras(state = WorkState.WAITING, waitingOn = "Kim", nudge = TimeValue(daysFromNow(-1.0))))

        val text = runBlocking { ViewSnapshotBuilder(structure, clock = { now }).build(user, ZoneOffset.UTC) }.render()

        assertTrue(Regex("""Promise" .*due \d{4}-\d{2}-\d{2} hard""").containsMatchIn(text), text)
        assertTrue(text.contains("(active)"), text)
        assertTrue(text.contains("(waiting on Kim, check in "), text)
        assertTrue(text.contains("Held up") && text.contains("(waiting on Sam"), "recent, so it can still be named to move it on: $text")
        // Pushed out of the recent slots by newer items, a waiting item outside focus is no longer offered.
        repeat(8) { task(user, "Newer open task $it") }
        val crowded = runBlocking { ViewSnapshotBuilder(structure, clock = { now }).build(user, ZoneOffset.UTC) }.render()
        assertFalse(crowded.contains("Held up"), crowded)

        runBlocking { structure.setFocus(user, 2, demo.uid, FocusMode.PINNED) }
        val focused = runBlocking { ViewSnapshotBuilder(structure, clock = { now }).build(user, ZoneOffset.UTC) }.render()
        assertTrue(focused.contains("Held up") && focused.contains("(waiting on Sam"), focused)
    }

    // ── Verbs ────────────────────────────────────────────────────────────────

    private fun snapshotOf(user: String) = runBlocking { ViewSnapshotBuilder(structure, clock = { now }).build(user, ZoneOffset.UTC) }

    private fun ops(vararg op: JsonObject) = buildJsonObject { put("ops", kotlinx.serialization.json.JsonArray(op.toList())) }
    private fun op(verb: String, vararg fields: Pair<String, String>) = buildJsonObject { put("verb", verb); fields.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `set_state is parsed with the same quote guard, the cap, and the negation guard for done`() {
        val user = newUser()
        val t = task(user, "Send the invoice")
        val snap = snapshotOf(user)
        val ref = snap.items.first().ref
        val say = "I am waiting on Dana about the invoice until Friday."

        fun parse(vararg o: JsonObject, utterance: String = say) = OperationParser.parse(ops(*o), utterance, snap, ZoneOffset.UTC)

        val ok = parse(op("set_state", "item" to ref, "state" to "waiting", "waiting_on" to "Dana", "nudge" to "2026-12-05", "quote" to "waiting on Dana"))
        val parsed = ok.operations.single() as SetState
        assertEquals(WorkState.WAITING, parsed.state); assertEquals("Dana", parsed.waitingOn); assertEquals(t.uid, parsed.targetUid)

        assertEquals("ungrounded_quote", parse(op("set_state", "item" to ref, "state" to "waiting", "quote" to "waiting on Erin")).rejections.single().reason)
        assertEquals("unknown_ref", parse(op("set_state", "item" to "i99", "state" to "done", "quote" to "waiting on Dana")).rejections.single().reason)
        assertEquals("unknown_state", parse(op("set_state", "item" to ref, "state" to "snoozed", "quote" to "waiting on Dana")).rejections.single().reason)
        assertEquals(
            "negation_marker_detected",
            parse(op("set_state", "item" to ref, "state" to "done", "quote" to "sending the invoice"), utterance = "I never got around to sending the invoice.").rejections.single().reason,
        )
        assertTrue(parse(op("set_state", "item" to ref, "state" to "done", "quote" to "sent the invoice"), utterance = "I sent the invoice.").operations.single() is SetState)

        val nine = (1..9).map { op("set_state", "item" to ref, "state" to "active", "quote" to "waiting on Dana") }
        val capped = parse(*nine.toTypedArray())
        assertEquals("too_many_operations", capped.rejections.last().reason, "the cap is still 8")
    }

    @Test
    fun `record_item takes state, hard or soft, start, check-in and waiting-on, with the precision given`() {
        val user = newUser()
        val snap = snapshotOf(user)
        val say = "I have to file the report by 2026-12-01T17:00 but I am waiting on the numbers from Priya."
        val parsed = OperationParser.parse(
            ops(
                op(
                    "record_item", "kind" to "task", "quote" to "file the report", "due" to "2026-12-01T17:00", "due_kind" to "hard", "state" to "waiting",
                    "waiting_on" to "Priya", "nudge" to "2026-11-30", "starts" to "2026-11-29T09:00",
                ),
            ),
            say, snap, ZoneOffset.UTC,
        ).operations.single() as RecordItem
        assertEquals(DueKind.HARD, parsed.dueKind); assertEquals(WorkState.WAITING, parsed.state); assertEquals("Priya", parsed.waitingOn)
        assertTrue(parsed.dueHasTime); assertTrue(parsed.startsHasTime); assertFalse(parsed.nudgeHasTime)

        val bad = OperationParser.parse(ops(op("record_item", "kind" to "task", "quote" to "file the report", "state" to "snoozed")), say, snap, ZoneOffset.UTC)
        assertEquals("unknown_state", bad.rejections.single().reason)
        val badKind = OperationParser.parse(ops(op("record_item", "kind" to "task", "quote" to "file the report", "due_kind" to "firm")), say, snap, ZoneOffset.UTC)
        assertEquals("unknown_due_kind", badKind.rejections.single().reason)
    }

    @Test
    fun `applying set_state reports waiting without waiting_on, and correct_item changes due, kind and nudge`() {
        val user = newUser()
        val applier = HorizonOperationApplier(structure, graph)
        val t = task(user, "Send the invoice", due = 2.0)

        val waiting = runBlocking { applier.apply(user, 2, "r1", listOf(SetState(t.uid, WorkState.WAITING, "waiting")), ZoneOffset.UTC) }.single()
        assertTrue(waiting.applied)
        assertTrue(waiting.summary.contains("nothing says who or what it waits on"), waiting.summary)
        assertEquals(WorkState.WAITING, get(user, t.uid).workState)

        val withWho = runBlocking { applier.apply(user, 3, "r2", listOf(SetState(t.uid, WorkState.WAITING, "waiting on Dana", nudgeAt = daysFromNow(4.0), waitingOn = "Dana")), ZoneOffset.UTC) }.single()
        assertTrue(withWho.summary.contains("on Dana") && !withWho.summary.contains("nothing says"), withWho.summary)

        // Resolve and reopen still work, through the same state.
        runBlocking { applier.apply(user, 4, "r3", listOf(ResolveItem(t.uid, "done")), ZoneOffset.UTC) }
        assertEquals(WorkState.DONE, get(user, t.uid).workState)
        runBlocking { applier.apply(user, 5, "r4", listOf(ReopenItem(t.uid, "not done")), ZoneOffset.UTC) }
        assertEquals(WorkState.OPEN, get(user, t.uid).workState)

        val b = task(user, "Book the room", due = 5.0, extras = ItemExtras(state = WorkState.WAITING, waitingOn = "facilities"))
        val corrected = runBlocking {
            applier.apply(user, 6, "r5", listOf(CorrectItem(b.uid, "make that hard", dueAt = daysFromNow(6.0), title = "Book the room", dueKind = DueKind.HARD, nudgeAt = daysFromNow(3.0))), ZoneOffset.UTC)
        }.single()
        assertTrue(corrected.applied, corrected.summary)
        val fresh = get(user, corrected.resultUid!!)
        assertEquals(DueKind.HARD, fresh.dueKind); assertEquals(daysFromNow(6.0), fresh.dueAt); assertEquals(daysFromNow(3.0), fresh.nudgeAt)
        assertEquals(WorkState.WAITING, fresh.workState, "a correction keeps where the item stood")
        assertEquals("facilities", fresh.waitingOn)
        assertEquals(WorkState.DONE, get(user, b.uid).workState, "and closes the one it replaces, as before")
    }

    private fun parseJson(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject
}
