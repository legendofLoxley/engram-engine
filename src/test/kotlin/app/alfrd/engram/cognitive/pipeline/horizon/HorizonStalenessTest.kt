package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.cognitive.pipeline.HorizonItemsRenderer
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.director.ChangeMarkerReader
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Evidence and staleness, the focused and zoomed-out renderings, and the "also due" block, against a real scratch database and a clock the test
 * controls (2027-01-15 08:00 UTC). Evidence is stamped with the real clock, so each test says when its items were last confirmed.
 */
class HorizonStalenessTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var graph: HorizonGraphStore
    private lateinit var path: String
    private var now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val utc = ZoneOffset.UTC

    @BeforeEach
    fun setUp() {
        path = "./data/test-horizon-staleness-${System.nanoTime()}"
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

    private fun today() = java.time.Instant.ofEpochMilli(now).atZone(utc).toLocalDate()
    /** A date-only due, as the parser stores it for a task: the last moment of that day, [offset] days from today. */
    private fun dayEnd(offset: Long) = today().plusDays(offset).plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli() - 1
    private fun dateOf(offset: Long): LocalDate = today().plusDays(offset)

    private fun newUser(): String {
        val email = "stale-${UUID.randomUUID()}@example.test"
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "s"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        return email
    }

    private fun view(user: String, policy: HorizonPolicy = HorizonPolicy(), cycle: Long = 10): ContextHorizon =
        runBlocking { (ArcadeHorizonAssembler(dbManager.getDatabase(), policy = policy, clock = { now }, zone = utc).assemble(user, cycle, budget = HorizonBudget(12, 0, false)) as AssembleOutcome.Assembled).horizon }

    private fun lines(h: ContextHorizon) = HorizonItemsRenderer.render(h, utc).map { it.renderedLine }
    private fun line(h: ContextHorizon, text: String) = lines(h).firstOrNull { it.contains("\"$text\"") }
    private fun ContextHorizon.item(text: String) = items.firstOrNull { it.text.text == text }

    private fun project(user: String, name: String) = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, name)!! }
    private fun domain(user: String, name: String) = runBlocking { structure.createContainer(user, 1, ContainerKind.DOMAIN, name)!! }
    private fun task(user: String, text: String, project: ContainerRecord? = null, due: Long? = null, extras: ItemExtras = ItemExtras()) =
        runBlocking { structure.createItem(user, 1, ItemKind.TASK, text, dueAt = due, projectUid = project?.uid, extras = extras)!! }
    private fun get(user: String, uid: String) = runBlocking { structure.getItem(user, uid)!! }
    private fun evidence(uid: String, at: Long) {
        val db = dbManager.getDatabase()
        db.transaction { db.command("sql", "UPDATE Phrase SET lastEvidenceAt = :a WHERE uid = :u", mapOf("a" to at, "u" to uid)) }
    }
    private fun clearEvidence(uid: String) {
        val db = dbManager.getDatabase()
        db.transaction { db.command("sql", "UPDATE Phrase REMOVE lastEvidenceAt WHERE uid = :u", mapOf("u" to uid)) }
    }
    private fun evidenceNow(vararg items: ItemRecord) = items.forEach { evidence(it.uid, now) }

    // ── The marker counts vertices, not index rows ───────────────────────────

    @Test
    fun `marker counts are distinct vertices for every type, stable across reads and after updates`() {
        val user = newUser()
        val db = dbManager.getDatabase()
        val dom = domain(user, "Home")
        val proj = project(user, "Garden")
        val t1 = task(user, "One", proj); val t2 = task(user, "Two"); val t3 = task(user, "Three")
        runBlocking {
            structure.createItem(user, 1, ItemKind.QUESTION, "Which pots?")
            structure.createItem(user, 1, ItemKind.EVENT, "Planting day", dueAt = dayEnd(3))
            structure.createItem(user, 1, ItemKind.FACT, "Soil is clay")
        }
        val reader = ChangeMarkerReader(db)
        val first = reader.read(user)!!

        // The truth, from a full scan that uses no owner index: each vertex once.
        fun real(type: String) = db.query("sql", "SELECT uid, ownerEmail FROM $type").use { rs ->
            val out = HashSet<String>(); while (rs.hasNext()) { val m = rs.next().toMap(); if (m["ownerEmail"] == user) out += m["uid"] as String }; out.size.toLong()
        }
        for (type in listOf("Chunk", "Phrase", "Task", "Question", "Event", "Project", "Domain", "ExtractionRun")) {
            assertEquals(real(type), first.counts.getValue(type), "$type is counted once per vertex")
        }
        assertEquals(3L, first.counts.getValue("Task"))
        assertEquals(1L, first.counts.getValue("Domain")); assertEquals(1L, first.counts.getValue("Project"))

        // Repeated reads agree, and changing items (which rewrites their index entries) moves no count.
        assertEquals(first.counts, reader.read(user)!!.counts)
        runBlocking {
            structure.resolve(user, 2, t1.uid, true); structure.resolve(user, 3, t1.uid, false)
            structure.setState(user, 4, t2.uid, WorkState.WAITING, TimeValue(dayEnd(5)), "Sam")
            structure.setAttention(user, 5, t3.uid, AttentionDirective.PINNED)
            structure.setDue(user, 6, t3.uid, dayEnd(2)); structure.touchEvidence(user, 7, t3.uid)
            structure.file(user, 8, t2.uid, proj.uid)
        }
        assertEquals(first.counts, reader.read(user)!!.counts, "updates do not change a count")
        assertEquals(first.counts, reader.read(user)!!.counts)
        // A real addition does.
        task(user, "Four")
        assertEquals(4L, reader.read(user)!!.counts.getValue("Task"))
        assertEquals(dom.uid, dom.uid)
    }

    // ── Evidence ─────────────────────────────────────────────────────────────

    @Test
    fun `evidence is refreshed by record, state, resolve, file, a date change and confirm, and not by pin or suppress`() {
        val user = newUser()
        val proj = project(user, "Garden")
        val old = now - 100 * day
        val t = task(user, "Order the soil")
        assertTrue(get(user, t.uid).evidenceAt > 0L, "recording stamps it")

        fun age() = evidence(t.uid, old)
        fun refreshed(what: String) { assertTrue(get(user, t.uid).evidenceAt > old, "$what refreshes evidence"); age() }
        fun untouched(what: String) { assertEquals(old, get(user, t.uid).evidenceAt, "$what is not evidence") }

        age()
        runBlocking { structure.setAttention(user, 2, t.uid, AttentionDirective.PINNED) }; untouched("pinning")
        runBlocking { structure.setAttention(user, 3, t.uid, AttentionDirective.SUPPRESSED) }; untouched("suppressing")
        runBlocking { structure.setAttention(user, 4, t.uid, null) }; untouched("restoring attention")
        runBlocking { structure.linkAbout(user, 5, t.uid, "soil") }; untouched("linking")

        runBlocking { structure.setState(user, 6, t.uid, WorkState.ACTIVE) }; refreshed("setting state")
        runBlocking { structure.resolve(user, 7, t.uid, true) }; refreshed("resolving")
        runBlocking { structure.resolve(user, 8, t.uid, false) }; refreshed("reopening")
        runBlocking { structure.file(user, 9, t.uid, proj.uid) }; refreshed("filing")
        runBlocking { structure.setDue(user, 10, t.uid, dayEnd(4)) }; refreshed("correcting the date")
        runBlocking { structure.touchEvidence(user, 11, t.uid) }; refreshed("confirming")
        assertEquals(WorkState.ACTIVE.let { get(user, t.uid).workState!! }, get(user, t.uid).workState, "confirming changes nothing else")
    }

    @Test
    fun `an item stored before evidence existed reads its updated time, and a pin does not move that`() {
        val user = newUser()
        val t = task(user, "Legacy")
        val db = dbManager.getDatabase()
        db.transaction { db.command("sql", "UPDATE Task REMOVE lastEvidenceAt SET updatedAt = 5000 WHERE uid = :u", mapOf("u" to t.uid)) }
        assertNull(get(user, t.uid).lastEvidenceAt)
        assertEquals(5000L, get(user, t.uid).evidenceAt, "defaults to when it was last changed")
        runBlocking { structure.setAttention(user, 2, t.uid, AttentionDirective.PINNED) }
        assertEquals(5000L, get(user, t.uid).evidenceAt, "pinning is not evidence, even for an item that was never stamped")
        clearEvidence(t.uid)
        db.transaction { db.command("sql", "UPDATE Task REMOVE updatedAt WHERE uid = :u", mapOf("u" to t.uid)) }
        assertEquals(get(user, t.uid).createdAt, get(user, t.uid).evidenceAt, "or when it was made")
    }

    // ── Staleness ────────────────────────────────────────────────────────────

    @Test
    fun `a soft overdue item with old evidence is a question, a hard one stays overdue, and an undated one is left alone`() {
        val user = newUser()
        val soft = task(user, "Williams safety triggers", due = dayEnd(-30))
        val hard = task(user, "File the permit", due = dayEnd(-30), extras = ItemExtras(dueKind = DueKind.HARD))
        val undated = task(user, "Someday tidy the shed")
        val recent = task(user, "Fresh but late", due = dayEnd(-5))
        evidence(soft.uid, now - 20 * day); evidence(hard.uid, now - 20 * day); evidence(undated.uid, now - 90 * day); evidence(recent.uid, now - 2 * day)

        val h = view(user)

        val q = line(h, "Williams safety triggers")!!
        assertEquals("? \"Williams safety triggers\": due ${dateOf(-30)} (soft), no evidence since ${dateOf(-20)}. Still live?", q)
        val overdue = line(h, "File the permit")!!
        assertTrue(overdue.contains("overdue by 30 days (${dateOf(-30)}, hard)"), overdue)
        assertTrue(overdue.contains("no evidence since ${dateOf(-20)}"), overdue)
        assertFalse(overdue.startsWith("?"), "a hard deadline is never softened")
        val fresh = line(h, "Fresh but late")!!
        assertTrue(fresh.contains("overdue by 5 days") && !fresh.contains("no evidence"), fresh)
        assertTrue(h.item("Someday tidy the shed")?.stale != true, "no due date, nothing to be stale about")
        assertFalse(line(h, "Someday tidy the shed").orEmpty().startsWith("?"))
    }

    @Test
    fun `the staleness window is configurable`() {
        val user = newUser()
        val t = task(user, "Late thing", due = dayEnd(-10))
        evidence(t.uid, now - 5 * day)
        assertTrue(line(view(user), "Late thing")!!.contains("overdue"), "5 days of silence is within the default 14")
        val strict = line(view(user, HorizonPolicy(staleEvidenceDays = 3)), "Late thing")!!
        assertTrue(strict.startsWith("? \"Late thing\""), strict)
        assertEquals(30L, HorizonPolicy.fromEnvironment(mapOf("HORIZON_STALE_DAYS" to "30")).staleEvidenceDays)
        assertEquals(14L, HorizonPolicy.fromEnvironment(mapOf("HORIZON_STALE_DAYS" to "soon")).staleEvidenceDays, "a bad value is ignored")
    }

    @Test
    fun `confirming, dropping, finishing or re-dating a stale item answers the question`() {
        val user = newUser()
        val applier = HorizonOperationApplier(structure, graph)
        fun stale(text: String) = task(user, text, due = dayEnd(-30)).also { evidence(it.uid, now - 40 * day) }
        val a = stale("Confirm me"); val b = stale("Drop me"); val c = stale("Finish me"); val d = stale("Re-date me")
        assertTrue((lines(view(user)).count { it.startsWith("? ") }) == 4)

        runBlocking {
            applier.apply(user, 2, "q1", listOf(ConfirmItem(a.uid, "still live")), utc)
            applier.apply(user, 3, "q2", listOf(SetState(b.uid, WorkState.DROPPED, "dropped")), utc)
            applier.apply(user, 4, "q3", listOf(SetState(c.uid, WorkState.DONE, "done")), utc)
        }
        // The confirmation and the state changes stamp real time, so for the demonstration move the clock to just after them.
        now = System.currentTimeMillis() + day
        val after = view(user)
        val confirmed = line(after, "Confirm me")!!
        assertFalse(confirmed.startsWith("?"), "confirmed, it is a plain overdue item again: $confirmed")
        assertNull(line(after, "Drop me")); assertNull(line(after, "Finish me"))
        assertEquals(get(user, a.uid).workState, WorkState.OPEN, "confirm_item changes only the evidence")
        assertEquals(a.dueAt, get(user, a.uid).dueAt)
        assertNotNull(d)
    }

    // ── Focus renderings ─────────────────────────────────────────────────────

    @Test
    fun `focused on a project, its items are grouped by state, active then open then waiting then hidden`() {
        val user = newUser()
        val p = project(user, "Offsite")
        val open1 = task(user, "Open one", p)
        val waiting = task(user, "Waiting one", p, extras = ItemExtras(state = WorkState.WAITING, waitingOn = "the venue", nudge = TimeValue(dayEnd(9))))
        val active = task(user, "Active one", p, extras = ItemExtras(state = WorkState.ACTIVE))
        val open2 = task(user, "Open two", p)
        val hidden = task(user, "Silenced one", p)
        task(user, "Done one", p).also { runBlocking { structure.resolve(user, 2, it.uid, true) } }
        runBlocking { structure.setAttention(user, 3, hidden.uid, AttentionDirective.SUPPRESSED); structure.setFocus(user, 4, p.uid, FocusMode.PINNED) }
        evidenceNow(open1, waiting, active, open2, hidden)

        val h = view(user)
        val rendered = HorizonItemsRenderer.render(h, utc)
        val headings = rendered.map { it.group }.distinct()
        val base = "In \"Offsite\" (project) — what they are focused on"

        assertEquals(listOf("$base (Active)", "$base (Open)", "$base (Waiting)", "$base (Hidden, suppressed)"), headings, "this order")
        assertEquals(listOf("Active one"), rendered.filter { it.group == "$base (Active)" }.map { it.renderedLine.substringAfter('"').substringBefore('"') })
        assertEquals(setOf("Open one", "Open two"), rendered.filter { it.group == "$base (Open)" }.map { it.renderedLine.substringAfter('"').substringBefore('"') }.toSet())
        val wLine = rendered.single { it.group == "$base (Waiting)" }.renderedLine
        assertTrue(wLine.contains("[waiting on the venue]"), wLine)
        assertTrue(rendered.single { it.group == "$base (Hidden, suppressed)" }.renderedLine.contains("Silenced one"))
        assertNull(line(h, "Done one"))
        assertEquals(1, h.hidden.size)
    }

    @Test
    fun `zoomed out, a project shows its active items and near deadlines and collapses plain open items into a count`() {
        val user = newUser()
        val p = project(user, "Garden")
        val items = (1..5).map { task(user, "Plain open $it", p) }
        val active = task(user, "Digging beds", p, extras = ItemExtras(state = WorkState.ACTIVE))
        val soon = task(user, "Buy seed by Friday", p, due = dayEnd(1))
        val waiting = task(user, "Hear from the nursery", p, extras = ItemExtras(state = WorkState.WAITING, waitingOn = "the nursery", nudge = TimeValue(dayEnd(9))))
        evidenceNow(*items.toTypedArray(), active, soon, waiting)

        val h = view(user)
        val rendered = HorizonItemsRenderer.render(h, utc)
        val section = rendered.filter { it.group == "In \"Garden\" (project)" }.map { it.renderedLine }

        assertNull(h.focus)
        assertTrue(section.any { it.contains("\"Digging beds\"") }, "the active item is listed: $section")
        assertTrue(section.any { it.contains("\"Buy seed by Friday\"") }, "so is the near deadline: $section")
        assertEquals("5 open", section.last(), "the rest are one count")
        assertTrue(rendered.none { it.renderedLine.contains("Plain open") })
        assertTrue(rendered.none { it.renderedLine.contains("Hear from the nursery") }, "waiting stays hidden until its nudge date")
    }

    @Test
    fun `a waiting item comes back at its nudge date even zoomed out`() {
        val user = newUser()
        val p = project(user, "Garden")
        val w = task(user, "Hear from the nursery", p, extras = ItemExtras(state = WorkState.WAITING, waitingOn = "the nursery", nudge = TimeValue(now + 2 * day)))
        evidenceNow(w)
        assertNull(line(view(user), "Hear from the nursery"))
        now += 3 * day
        evidence(w.uid, now)
        assertNotNull(line(view(user), "Hear from the nursery"))
    }

    @Test
    fun `focused on a domain, each of its projects gets a short summary`() {
        val user = newUser()
        val home = domain(user, "Home")
        val garden = project(user, "Garden"); val kitchen = project(user, "Kitchen"); val work = project(user, "Taxes")
        runBlocking { structure.file(user, 2, garden.uid, home.uid); structure.file(user, 3, kitchen.uid, home.uid) }
        val all = (1..3).map { task(user, "Garden task $it", garden) } + (1..2).map { task(user, "Kitchen task $it", kitchen) } + task(user, "Tax task", work)
        evidenceNow(*all.toTypedArray())
        runBlocking { structure.setFocus(user, 4, home.uid, FocusMode.PINNED) }

        val rendered = HorizonItemsRenderer.render(view(user), utc)

        assertEquals(listOf("3 open"), rendered.filter { it.group == "In \"Garden\" (project)" }.map { it.renderedLine }.filter { it.endsWith("open") })
        assertEquals(listOf("2 open"), rendered.filter { it.group == "In \"Kitchen\" (project)" }.map { it.renderedLine }.filter { it.endsWith("open") })
        assertTrue(rendered.none { it.group == "In \"Taxes\" (project)" && it.renderedLine.endsWith("open") }, "a project outside the domain gets no summary")
    }

    // ── Also due ─────────────────────────────────────────────────────────────

    private fun alsoDue(h: ContextHorizon) = h.alsoDue.associateBy { it.text }

    @Test
    fun `also due lists hard deadlines within the window or overdue and soft ones today or tomorrow, each labelled`() {
        val user = newUser()
        val hard = ItemExtras(dueKind = DueKind.HARD)
        val soft = ItemExtras(dueKind = DueKind.SOFT)
        task(user, "Hard overdue", due = dayEnd(-2), extras = hard)
        task(user, "Hard today", due = dayEnd(0), extras = hard)
        task(user, "Hard in 3 days", due = dayEnd(3), extras = hard)
        task(user, "Hard in 4 days", due = dayEnd(4), extras = hard)
        task(user, "Soft today", due = dayEnd(0), extras = soft)
        task(user, "Soft tomorrow", due = dayEnd(1))
        task(user, "Soft in 2 days", due = dayEnd(2), extras = soft)
        task(user, "Soft overdue", due = dayEnd(-1), extras = soft)

        val due = alsoDue(view(user))

        assertEquals(setOf("Hard overdue", "Hard today", "Hard in 3 days", "Soft today", "Soft tomorrow"), due.keys)
        assertEquals("hard", due.getValue("Hard in 3 days").dueKind); assertEquals("soft", due.getValue("Soft tomorrow").dueKind)
        assertTrue(due.getValue("Hard overdue").overdue)
        val block = HorizonItemsRenderer.composeAlsoDue(view(user), utc)!!
        assertTrue(block.lines().first().startsWith("Also due"), block)
        assertTrue(block.contains("\"Hard overdue\" — overdue by 2 days (${dateOf(-2)}, hard)"), block)
        assertTrue(block.contains("\"Soft tomorrow\" — due tomorrow (${dateOf(1)}, soft)"), block)
        assertEquals("Hard overdue", view(user).alsoDue.first().text, "a hard overdue item leads")
    }

    @Test
    fun `also due windows are configuration`() {
        val user = newUser()
        task(user, "Hard in 4 days", due = dayEnd(4), extras = ItemExtras(dueKind = DueKind.HARD))
        task(user, "Soft in 2 days", due = dayEnd(2))
        assertTrue(alsoDue(view(user)).isEmpty())
        val wide = alsoDue(view(user, HorizonPolicy(alsoDueHardDays = 5, alsoDueSoftDays = 2)))
        assertEquals(setOf("Hard in 4 days", "Soft in 2 days"), wide.keys)
        assertEquals(5L, HorizonPolicy.fromEnvironment(mapOf("HORIZON_ALSO_DUE_HARD_DAYS" to "5")).alsoDueHardDays)
    }

    @Test
    fun `also due leaves out done, dropped and waiting items, takes a waiting one at its nudge date, and ignores the focus`() {
        val user = newUser()
        val other = project(user, "Elsewhere")
        val hard = ItemExtras(dueKind = DueKind.HARD)
        val done = task(user, "Done deadline", due = dayEnd(1), extras = hard)
        val dropped = task(user, "Dropped deadline", due = dayEnd(1), extras = hard)
        runBlocking { structure.resolve(user, 2, done.uid, true); structure.setState(user, 3, dropped.uid, WorkState.DROPPED) }
        task(user, "Waiting deadline", due = dayEnd(1), extras = ItemExtras(dueKind = DueKind.HARD, state = WorkState.WAITING, waitingOn = "Sam", nudge = TimeValue(now + 5 * day)))
        task(user, "Waiting and nudged", due = dayEnd(1), extras = ItemExtras(dueKind = DueKind.HARD, state = WorkState.WAITING, waitingOn = "Sam", nudge = TimeValue(now - day)))
        task(user, "Live deadline", due = dayEnd(1), extras = hard)
        runBlocking { structure.setFocus(user, 4, other.uid, FocusMode.PINNED) }

        val due = alsoDue(view(user))

        assertEquals(setOf("Waiting and nudged", "Live deadline"), due.keys, "whatever the focus is")
    }

    @Test
    fun `also due is bounded and says how many more there were`() {
        val user = newUser()
        repeat(14) { task(user, "Deadline $it", due = dayEnd(1), extras = ItemExtras(dueKind = DueKind.HARD)) }
        val h = view(user)
        assertEquals(10, h.alsoDue.size)
        assertEquals(4, h.alsoDueMore)
        val block = HorizonItemsRenderer.composeAlsoDue(h, utc)!!
        assertEquals(10, block.lines().count { it.startsWith("- ") })
        assertTrue(block.contains("4 more due soon, not listed"), block)
    }
}
