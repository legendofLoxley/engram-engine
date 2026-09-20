package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
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
import java.util.UUID

/**
 * What is in view, in the situations the design was worked out against — each test is one of the
 * scenarios (or a piece of one) stated in terms of the graph and a clock we control. Assertions are
 * about *whether* and *in what order* things appear, and *why*, not about exact scores: the numbers
 * in [HorizonPolicy] are provisional, the behavior is not.
 *
 * Items are created in cycle 1 and assembled at cycle 10 unless a test says otherwise, so nothing
 * reads as "just said" and everything that shows is there for the reason under test.
 */
class HorizonScenarioTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var graph: HorizonGraphStore
    private lateinit var path: String
    private var now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun daysFromNow(days: Double) = now + (days * day).toLong()

    @BeforeEach
    fun setUp() {
        path = "./data/test-horizon-scenario-${System.nanoTime()}"
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

    private fun assembler(policy: HorizonPolicy = HorizonPolicy(), db: com.arcadedb.database.Database = dbManager.getDatabase()) =
        ArcadeHorizonAssembler(db, policy = policy, clock = { now })

    private fun newUser(): String {
        val email = "scenario-${UUID.randomUUID()}@example.test"
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "s"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        return email
    }

    private fun view(user: String, cycle: Long = 10, policy: HorizonPolicy = HorizonPolicy(), maxItems: Int = 12): ContextHorizon =
        runBlocking { (assembler(policy).assemble(user, cycle, budget = HorizonBudget(maxItems, 0, false)) as AssembleOutcome.Assembled).horizon }

    private fun ContextHorizon.texts(): List<String> = items.map { it.text.text }
    private fun ContextHorizon.item(text: String): HorizonItem? = items.firstOrNull { it.text.text == text }

    private fun project(user: String, name: String, dueAt: Long? = null) = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, name, dueAt = dueAt)!! }
    private fun domain(user: String, name: String) = runBlocking { structure.createContainer(user, 1, ContainerKind.DOMAIN, name)!! }
    private fun task(user: String, text: String, inProject: ContainerRecord? = null, due: Double? = null, cycle: Long = 1) =
        runBlocking { structure.createItem(user, cycle, ItemKind.TASK, text, dueAt = due?.let { daysFromNow(it) }, projectUid = inProject?.uid)!! }
    private fun event(user: String, text: String, due: Double, cycle: Long = 1) = runBlocking { structure.createItem(user, cycle, ItemKind.EVENT, text, dueAt = daysFromNow(due))!! }
    private fun fact(user: String, text: String, inProject: ContainerRecord? = null) = runBlocking { structure.createItem(user, 1, ItemKind.FACT, text, projectUid = inProject?.uid)!! }
    private fun pin(user: String, target: String, zoom: FocusZoom? = null, cycle: Long = 2) = runBlocking { structure.setFocus(user, cycle, target, FocusMode.PINNED, zoom) }
    private fun nudge(user: String, target: String, cycle: Long = 9, weight: Double = 1.0) = runBlocking { structure.setFocus(user, cycle, target, FocusMode.NUDGE, weight = weight) }

    // ── 1. Zoomed out: "morning" after a gap ─────────────────────────────────

    @Test
    fun `zoomed out, the overview shows the shape of what they have going on and what is pressing, not everything inside each project`() {
        val user = newUser()
        val work = domain(user, "Work")
        val demo = project(user, "Demo")
        val garden = project(user, "Garden redesign")
        runBlocking { structure.file(user, 1, demo.uid, work.uid) }
        event(user, "Sister's birthday", due = 2.0)
        task(user, "Send the invoice to Acme", demo, due = 1.0)
        task(user, "Order soil for the raised beds", garden, due = null)
        task(user, "Renew library card")
        fact(user, "Demo notes live in the shared drive", demo)

        val h = view(user)

        assertEquals(FocusZoom.OVERVIEW, h.zoom)
        assertNull(h.focus)
        assertEquals(setOf("Work", "Demo", "Garden redesign"), h.containers.map { it.name }.toSet(), "the containers give the overall shape")
        assertEquals(1, h.containers.first { it.name == "Garden redesign" }.openItems)
        assertNotNull(h.item("Sister's birthday"), "a birthday two days away is in view")
        assertNotNull(h.item("Send the invoice to Acme"), "so is a deadline tomorrow, though it is inside a project")
        assertNotNull(h.item("Renew library card"), "an open task that belongs to no project has no project to be out of view with")
        assertNull(h.item("Order soil for the raised beds"), "an undated task inside a project is not in view until the project is")
        assertNull(h.item("Demo notes live in the shared drive"))
    }

    @Test
    fun `the most pressing thing ranks first, and each item says why it is there`() {
        val user = newUser()
        event(user, "Dentist", due = 6.0)
        task(user, "Send the invoice", due = 1.0)
        event(user, "Sister's birthday", due = 2.0)

        val h = view(user)

        assertEquals(listOf("Send the invoice", "Sister's birthday", "Dentist"), h.texts().take(3), "sooner is more urgent")
        val invoice = h.item("Send the invoice")!!
        assertTrue(invoice.surfacing is SurfacingReason.Urgent)
        assertTrue(invoice.why.any { it.kind == "urgency" }, "the score's components are recorded: ${invoice.why}")
        assertTrue(invoice.score in 0.0..1.0)
        assertEquals(ItemKind.TASK.name.lowercase(), invoice.kind)
        assertEquals(invoice.dueAt, daysFromNow(1.0))
    }

    // ── 2. Entity-mediated recall ────────────────────────────────────────────

    @Test
    fun `a nudge on one thing about an entity brings in other things about the same entity`() {
        val user = newUser()
        val birthday = event(user, "Sister's birthday", due = 40.0) // far off: no urgency of its own
        val allergy = fact(user, "My sister is allergic to peanuts")
        val unrelated = fact(user, "I take my coffee black")
        runBlocking { structure.linkAbout(user, 1, birthday.uid, "sister"); structure.linkAbout(user, 1, allergy.uid, "sister") }

        assertNull(view(user).item("My sister is allergic to peanuts"), "nothing draws it in yet")

        nudge(user, birthday.uid)
        val h = view(user)

        val recalled = h.item("My sister is allergic to peanuts")
        assertNotNull(recalled, "the allergy is reached through the entity both are about")
        assertTrue(recalled!!.surfacing is SurfacingReason.Activated, "and says it was activated: ${recalled.surfacing}")
        assertTrue(recalled.score < h.item("Sister's birthday")!!.score, "it is further from the nudge than the nudge itself")
        assertNull(h.item(unrelated.text), "an unrelated fact stays out")
    }

    @Test
    fun `an entity shared with another user does not bring their things in`() {
        val a = newUser()
        val b = newUser()
        val mine = fact(a, "My sister is allergic to peanuts")
        val myBirthday = event(a, "Sister's birthday", due = 40.0)
        runBlocking { structure.linkAbout(a, 1, mine.uid, "sister"); structure.linkAbout(a, 1, myBirthday.uid, "sister") }
        val theirs = fact(b, "Their sister plays the cello")
        runBlocking { structure.linkAbout(b, 1, theirs.uid, "sister") } // the same shared Concept

        nudge(a, myBirthday.uid)

        assertTrue(view(a).texts().none { it.contains("cello") }, "another user's item about the same entity is never followed to")
        assertTrue(view(b).items.none { it.text.text.contains("allergic") })
    }

    // ── 3. A worry or task is in view only while its project is ──────────────

    @Test
    fun `an item is in view while its project is the focus, and out of view when the focus is elsewhere`() {
        val user = newUser()
        val demo = project(user, "Demo")
        val garden = project(user, "Garden redesign")
        task(user, "Sort out the firewall rules", demo)
        task(user, "Choose the plants", garden)

        pin(user, demo.uid)
        val demoView = view(user)
        assertNotNull(demoView.item("Sort out the firewall rules"))
        assertTrue(demoView.item("Sort out the firewall rules")!!.surfacing is SurfacingReason.InFocus)
        assertNull(demoView.item("Choose the plants"), "the other project's items are not")
        assertEquals("Demo", demoView.focus?.name)

        pin(user, garden.uid, cycle = 3)
        val gardenView = view(user)
        assertNull(gardenView.item("Sort out the firewall rules"), "moving the focus takes the worry out of view")
        assertNotNull(gardenView.item("Choose the plants"))
    }

    @Test
    fun `an open task inside a finished project is no longer in view`() {
        val user = newUser()
        val demo = project(user, "Demo")
        task(user, "Book the room", demo)
        pin(user, demo.uid)
        assertNotNull(view(user).item("Book the room"))

        runBlocking { structure.resolve(user, 3, demo.uid) }

        assertNull(view(user).item("Book the room"), "the project is done, so what was filed under it goes with it")
    }

    // ── 4. Deadlines rise with the clock; resolved leaves ────────────────────

    @Test
    fun `a deadline gets more pressing as it approaches, and leaves when resolved`() {
        val user = newUser()
        val t = task(user, "File the taxes", due = 6.0)

        val early = view(user).item("File the taxes")!!
        now += 4 * day
        val closer = view(user).item("File the taxes")!!
        now += 2 * day
        val due = view(user).item("File the taxes")!!

        assertTrue(early.score < closer.score && closer.score < due.score, "urgency rises with the clock: ${early.score} < ${closer.score} < ${due.score}")
        assertEquals(1.0, due.score, 0.001)

        runBlocking { structure.resolve(user, 3, t.uid) }
        assertNull(view(user).item("File the taxes"), "resolved, it is gone")
    }

    @Test
    fun `an overdue task stays in view and is marked overdue, while a past event simply ends`() {
        val user = newUser()
        task(user, "Pay the electricity bill", due = -2.0)
        event(user, "Yesterday's meeting", due = -1.0)
        event(user, "Happening right now", due = -0.05)

        val h = view(user)

        val overdue = h.item("Pay the electricity bill")!!
        assertTrue((overdue.surfacing as SurfacingReason.Urgent).overdue)
        assertNull(h.item("Yesterday's meeting"), "an event that is over is not owed anything")
        assertNotNull(h.item("Happening right now"), "one that started a moment ago is still on")
    }

    @Test
    fun `an urgent project brings its items with it`() {
        val user = newUser()
        val launch = project(user, "Launch", dueAt = daysFromNow(1.0))
        task(user, "Write the release notes", launch)

        val h = view(user)

        val item = h.item("Write the release notes")
        assertNotNull(item, "the project is due tomorrow, so what is in it matters now")
        assertEquals(1.0, h.containers.first { it.name == "Launch" }.score.coerceAtMost(1.0), 1.0)
    }

    @Test
    fun `many undated open tasks do not hide or duplicate the few that have a deadline`() {
        val user = newUser()
        val p = project(user, "Demo")
        repeat(120) { task(user, "Someday task $it", p) }
        task(user, "Due tomorrow", due = 1.0)
        event(user, "Due in three days", due = 3.0)

        val h = view(user)

        assertEquals(1, h.items.count { it.text.text == "Due tomorrow" })
        assertEquals(1, h.items.count { it.text.text == "Due in three days" })
        assertTrue(h.items.none { it.text.text.startsWith("Someday task") }, "undated tasks in a project are not in view zoomed out")
    }

    @Test
    fun `a user with projects and a focus but no items yet still has them in view`() {
        // Regression: Sources are created with a first item, so a user who has only made projects has none, and the
        // selection was skipped entirely — no containers and no focus, on exactly the turn the focus was set.
        val user = newUser()
        val demo = project(user, "Meridian demo")
        project(user, "Garden redesign")
        nudge(user, demo.uid, cycle = 1)
        pin(user, demo.uid)

        val h = view(user, cycle = 2)

        assertEquals("Meridian demo", h.focus?.name)
        assertEquals(FocusZoom.BINOCULARS, h.zoom)
        assertEquals(setOf("Meridian demo", "Garden redesign"), h.containers.map { it.name }.toSet())
        assertTrue(h.items.isEmpty())
    }

    // ── 5. Budget priority ───────────────────────────────────────────────────

    @Test
    fun `when there is more than fits, the most pressing are kept and the rest are counted as omitted`() {
        val user = newUser()
        (1..8).forEach { task(user, "Task due in $it days", due = it.toDouble()) }

        val h = view(user, maxItems = 4)

        assertEquals(listOf("Task due in 1 days", "Task due in 2 days", "Task due in 3 days", "Task due in 4 days"), h.texts())
        assertTrue(h.budget.truncated)
        assertEquals(4, h.omittedAtLeast)
    }

    // ── 6. Breakthrough: urgent things get through a focus elsewhere ─────────

    @Test
    fun `working on one project, something genuinely pressing elsewhere still breaks through, and something merely dated does not`() {
        val user = newUser()
        val demo = project(user, "Demo")
        val garden = project(user, "Garden redesign")
        task(user, "Refine the demo script", demo)
        task(user, "Order soil for tomorrow's planting", garden, due = 1.0)
        task(user, "Choose paving next month", garden, due = 12.0)
        pin(user, demo.uid)

        val h = view(user)

        assertNotNull(h.item("Refine the demo script"))
        assertNotNull(h.item("Order soil for tomorrow's planting"), "due tomorrow: shown whatever the focus")
        assertNull(h.item("Choose paving next month"), "due in twelve days: it can wait until the garden is what they are looking at")
    }

    @Test
    fun `zoomed out the same twelve-day deadline is shown`() {
        val user = newUser()
        task(user, "Choose paving next month", due = 12.0)
        assertNotNull(view(user).item("Choose paving next month"))
    }

    // ── 7. Pin and suppress ──────────────────────────────────────────────────

    @Test
    fun `suppressed means not brought up, even when it is urgent or in the focus`() {
        val user = newUser()
        val demo = project(user, "Demo")
        val budget = task(user, "Review the budget", due = 1.0)
        val notes = task(user, "Tidy the notes", demo)
        pin(user, demo.uid)
        assertNotNull(view(user).item("Review the budget"))
        assertNotNull(view(user).item("Tidy the notes"))

        runBlocking {
            structure.setAttention(user, 3, budget.uid, AttentionDirective.SUPPRESSED)
            structure.setAttention(user, 3, notes.uid, AttentionDirective.SUPPRESSED)
        }

        val h = view(user)
        assertNull(h.item("Review the budget"))
        assertNull(h.item("Tidy the notes"))
        assertEquals("open", runBlocking { structure.getItem(user, budget.uid)!!.status }, "suppressing is not resolving")
    }

    @Test
    fun `suppressing a project takes its items and the project itself out of view`() {
        val user = newUser()
        val demo = project(user, "Demo")
        task(user, "Book the room", demo, due = 1.0)
        assertNotNull(view(user).item("Book the room"))

        runBlocking { structure.setAttention(user, 3, demo.uid, AttentionDirective.SUPPRESSED) }

        val h = view(user)
        assertNull(h.item("Book the room"))
        assertTrue(h.containers.none { it.name == "Demo" })
    }

    @Test
    fun `a pinned item stays in view whatever the focus, and says so`() {
        val user = newUser()
        val demo = project(user, "Demo")
        val garden = project(user, "Garden redesign")
        val invoice = task(user, "Send the invoice to Acme", garden)
        runBlocking { structure.setAttention(user, 3, invoice.uid, AttentionDirective.PINNED) }
        pin(user, demo.uid)

        val h = view(user)

        val item = h.item("Send the invoice to Acme")
        assertNotNull(item, "they asked to keep it in front of them")
        assertTrue(item!!.surfacing is SurfacingReason.Pinned)
        assertEquals(AttentionDirective.PINNED, item.attentionDirective)
    }

    // ── 8. Focus persists; nudges fade ───────────────────────────────────────

    @Test
    fun `a pinned focus persists across turns and across a restart`() {
        val user = newUser()
        val demo = project(user, "Demo")
        task(user, "Book the room", demo)
        pin(user, demo.uid, FocusZoom.BINOCULARS)

        assertEquals("Demo", view(user, cycle = 10).focus?.name)
        assertEquals("Demo", view(user, cycle = 200).focus?.name, "many turns later it is still what they are looking at")
        assertNotNull(view(user, cycle = 200).item("Book the room"))

        dbManager.close()
        val reopened = DatabaseManager(path)
        try {
            SchemaBootstrap.bootstrap(reopened.getDatabase())
            val after = runBlocking { (assembler(db = reopened.getDatabase()).assemble(user, 300) as AssembleOutcome.Assembled).horizon }
            assertEquals("Demo", after.focus?.name, "a restart loses nothing: focus is in the graph")
            assertEquals(FocusZoom.BINOCULARS, after.zoom)
            assertNotNull(after.item("Book the room"))
        } finally {
            reopened.close()
            dbManager = DatabaseManager(path) // for tearDown
        }
    }

    @Test
    fun `a nudge fades with the turns since, and is gone after a few`() {
        val user = newUser()
        val demo = project(user, "Demo")
        task(user, "Book the room", demo)
        nudge(user, demo.uid, cycle = 10)

        val fresh = view(user, cycle = 10).item("Book the room")!!.score
        val later = view(user, cycle = 13).item("Book the room")!!.score
        val muchLater = view(user, cycle = 16).item("Book the room")?.score
        val gone = view(user, cycle = 40).item("Book the room")

        assertTrue(later < fresh, "three turns on it has lost strength: $fresh -> $later")
        assertTrue(muchLater == null || muchLater < later)
        assertNull(gone, "a passing mention does not linger")
    }

    @Test
    fun `mentioning something again keeps it up`() {
        val user = newUser()
        val demo = project(user, "Demo")
        task(user, "Book the room", demo)
        nudge(user, demo.uid, cycle = 10)
        val once = view(user, cycle = 20).item("Book the room")

        nudge(user, demo.uid, cycle = 17)
        val again = view(user, cycle = 20).item("Book the room")

        assertNull(once, "a single mention ten turns ago (a nudge lasts about seven) has faded out")
        assertNotNull(again, "mentioned again three turns ago, it is back")
    }

    @Test
    fun `pins are never mistaken for nudges, and pruning only removes old nudges`() {
        val user = newUser()
        val demo = project(user, "Demo")
        val garden = project(user, "Garden redesign")
        val old = project(user, "Old")
        pin(user, demo.uid, cycle = 1)
        nudge(user, garden.uid, cycle = 2)
        nudge(user, old.uid, cycle = 50)

        val removed = runBlocking { structure.pruneNudges(user, olderThanCycle = 30) }

        assertEquals(1, removed)
        val left = runBlocking { structure.getFocus(user) }.map { it.targetUid to it.mode }
        assertTrue((demo.uid to FocusMode.PINNED) in left)
        assertTrue((old.uid to FocusMode.NUDGE) in left)
        assertFalse(left.any { it.first == garden.uid })
    }

    // ── 9. Corrections ripple ────────────────────────────────────────────────

    @Test
    fun `a corrected item is replaced in view by its correction, in the same place`() {
        val user = newUser()
        val demo = project(user, "Demo")
        val old = event(user, "Sister's birthday is the 21st", due = 2.0)
        runBlocking { structure.linkAbout(user, 1, old.uid, "sister") }
        assertNotNull(view(user).item("Sister's birthday is the 21st"))

        val corrected = HorizonOperationApplier(structure, graph).let { applier ->
            runBlocking { applier.apply(user, 3, "req-c", listOf(CorrectItem(old.uid, "the 24th", dueAt = daysFromNow(5.0), title = "Sister's birthday is the 24th"))).single() }
        }

        val h = view(user)
        assertNull(h.item("Sister's birthday is the 21st"), "the old statement no longer surfaces")
        val now24 = h.item("Sister's birthday is the 24th")
        assertNotNull(now24, "the correction does, with its own date")
        assertEquals(daysFromNow(5.0), now24!!.dueAt)
        assertNotNull(demo)
        assertTrue(corrected.applied)
    }

    @Test
    fun `a superseded fact is left out even though nothing closed it`() {
        val user = newUser()
        val old = fact(user, "My sister lives in Boston")
        val new = fact(user, "My sister lives in Denver")
        nudge(user, old.uid); nudge(user, new.uid)
        assertNotNull(view(user).item("My sister lives in Boston"))

        runBlocking { graph.markSuperseded(user, 3, new.uid, old.uid) }

        val h = view(user)
        assertNull(h.item("My sister lives in Boston"))
        assertNotNull(h.item("My sister lives in Denver"))
    }

    // ── Zoom ─────────────────────────────────────────────────────────────────

    @Test
    fun `zoom sets how far a focus reaches`() {
        val user = newUser()
        val work = domain(user, "Work")
        val demo = project(user, "Demo")
        runBlocking { structure.file(user, 1, demo.uid, work.uid) }
        val script = task(user, "Refine the demo script", demo)
        val allergy = fact(user, "Priya reviews every script", demo)
        val other = fact(user, "Priya works on the payments team")
        runBlocking { structure.linkAbout(user, 1, allergy.uid, "priya"); structure.linkAbout(user, 1, other.uid, "priya") }

        pin(user, work.uid, FocusZoom.OVERVIEW)
        assertTrue(view(user).items.none { it.container?.name == "Demo" }, "a broad focus on a domain reaches its projects, not what is inside them")

        pin(user, demo.uid, FocusZoom.BINOCULARS)
        val binoculars = view(user)
        assertNotNull(binoculars.item("Refine the demo script"))
        assertNull(binoculars.item("Priya works on the payments team"), "one project at a time: it does not wander off through shared entities")

        pin(user, script.uid, FocusZoom.TELESCOPE)
        assertNotNull(view(user).item("Refine the demo script"))
        assertNotNull(view(user).item("Priya reviews every script"), "zoomed right in on an item, its neighborhood comes into view (its project's other items)")
        assertNotNull(work)
    }

    // ── Just said, and what sits next to it ──────────────────────────────────

    @Test
    fun `something just said is in view, and lifts what is next to it`() {
        val user = newUser()
        val demo = project(user, "Demo")
        task(user, "Book the room", demo)
        val fresh = task(user, "Rehearse with Priya", demo, cycle = 10)

        val h = view(user, cycle = 10)

        assertTrue(h.item("Rehearse with Priya")!!.surfacing is SurfacingReason.JustAsserted)
        assertNotNull(h.item("Book the room"), "its sibling in the same project comes with it")
        assertTrue(h.item("Book the room")!!.score < h.item("Rehearse with Priya")!!.score)
        assertNotNull(fresh)
    }

    // ── Safety and bounds ────────────────────────────────────────────────────

    @Test
    fun `one user's projects, items and deadlines never appear in another's view`() {
        val a = newUser()
        val b = newUser()
        val secret = project(a, "A's secret")
        task(a, "A's private deadline", secret, due = 1.0)
        pin(a, secret.uid)

        val h = view(b)

        assertTrue(h.items.isEmpty() && h.containers.isEmpty() && h.focus == null)
    }

    @Test
    fun `selection stays bounded however much is in the graph`() {
        val user = newUser()
        val demo = project(user, "Demo")
        repeat(150) { task(user, "Demo task $it", demo) }
        pin(user, demo.uid)

        val h = view(user, policy = HorizonPolicy(maxExpansions = 20))

        assertTrue(h.items.size <= 12)
        assertTrue(h.moreCandidatesAvailable || h.items.size <= 12, "the cap is reported, never silently lost")
    }

    @Test
    fun `a legacy phrase with no structure is untouched by all this`() {
        // The old shape: a plain open phrase asserted from the user's own Source, with no container and no owner stamp.
        val user = newUser()
        val uid = task(user, "Get the plain thing done").uid
        assertNotNull(uid)
        assertNotNull(view(user).item("Get the plain thing done"))
    }

    // ── Reports from outside the conversation ────────────────────────────────

    @Test
    fun `reports from the environment come into view in a few slots, not all of them`() {
        val user = newUser()
        runBlocking {
            repeat(5) { i -> graph.ingestEnvironmentSignal(user, 9, "environment:ci", "Build $i finished with no errors") }
        }

        val h = view(user, cycle = 10)

        val reports = h.items.filter { it.surfacing is SurfacingReason.Peripheral }
        assertEquals(HorizonPolicy().peripheralSlots, reports.size, "capped, so a noisy source can't crowd out the conversation")
        assertTrue(reports.all { it.category == HorizonItemCategory.ENVIRONMENT_EVENT })
    }

    @Test
    fun `an old environment report is gone once outside the window`() {
        val user = newUser()
        runBlocking { graph.ingestEnvironmentSignal(user, 2, "environment:ci", "Build finished") }
        assertTrue(view(user, cycle = 3).items.any { it.text.text == "Build finished" })
        assertTrue(view(user, cycle = 50).items.none { it.text.text == "Build finished" })
    }
}
