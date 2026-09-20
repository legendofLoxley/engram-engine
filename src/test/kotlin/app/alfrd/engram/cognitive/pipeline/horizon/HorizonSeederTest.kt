package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.cognitive.pipeline.HorizonItemsRenderer
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
import java.time.Duration
import java.util.UUID

/**
 * [HorizonSeeder]: how what someone says becomes nudges (mechanically, from their words), and how a
 * long silence becomes a question. Includes the end-to-end effect — say something, and what it is
 * about comes into view — against the real assembler.
 */
class HorizonSeederTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var graph: HorizonGraphStore
    private lateinit var path: String
    private var now = 1_800_000_000_000L
    private val day = 86_400_000L

    @BeforeEach
    fun setUp() {
        path = "./data/test-horizon-seeder-${System.nanoTime()}"
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

    private fun seeder(policy: HorizonPolicy = HorizonPolicy()) = HorizonSeeder(structure, policy, clock = { now })
    private fun assembler() = ArcadeHorizonAssembler(dbManager.getDatabase(), clock = { now })

    private fun newUser(): String {
        val email = "seeder-${UUID.randomUUID()}@example.test"
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "s"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        return email
    }

    private fun nudged(user: String) = runBlocking { structure.getFocus(user) }.filter { it.mode == FocusMode.NUDGE }.associateBy { it.targetUid }
    private fun seed(user: String, said: String, cycle: Long = 5, applied: List<AppliedOperation> = emptyList()) =
        runBlocking { seeder().seedTurn(user, cycle, said, applied) }
    private fun horizon(user: String, cycle: Long = 5) = runBlocking { (assembler().assemble(user, cycle) as AssembleOutcome.Assembled).horizon }

    // ── Mentions ─────────────────────────────────────────────────────────────

    @Test
    fun `naming a project nudges it, but a longer word that merely contains its name does not`() = runBlocking {
        val user = newUser()
        val demo = structure.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val garden = structure.createContainer(user, 1, ContainerKind.PROJECT, "Garden redesign")!!

        val named = seeder().seedTurn(user, 5, "How is the demo going?", emptyList())
        assertEquals(1, named.mentionedContainers)
        assertEquals(setOf(demo.uid), nudged(user).keys)

        seeder().seedTurn(user, 6, "That was quite a demonstration.", emptyList())
        assertFalse(nudged(user).containsKey(garden.uid))
        assertEquals(1, nudged(user).size, "'demonstration' is not 'demo'")
        assertNotNull(garden)
    }

    @Test
    fun `naming an entity nudges the items about it, which is how an ordinary fact comes back into view`() = runBlocking {
        val user = newUser()
        val allergy = structure.createItem(user, 1, ItemKind.FACT, "Peanuts make her ill")!!
        val birthday = structure.createItem(user, 1, ItemKind.EVENT, "Birthday dinner", dueAt = now + 40 * day)!!
        val coffee = structure.createItem(user, 1, ItemKind.FACT, "I take coffee black")!!
        structure.linkAbout(user, 1, allergy.uid, "sister"); structure.linkAbout(user, 1, birthday.uid, "sister")
        assertNull(horizon(user).items.firstOrNull { it.text.text == "Peanuts make her ill" }, "not in view before anyone mentions her")

        val seeded = seeder().seedTurn(user, 5, "What was my sister allergic to again?", emptyList())

        assertEquals(2, seeded.mentionedEntities)
        assertEquals(setOf(allergy.uid, birthday.uid), nudged(user).keys)
        val h = horizon(user)
        val recalled = h.items.firstOrNull { it.text.text == "Peanuts make her ill" }
        assertNotNull(recalled, "the fact comes back although its own words share nothing with the question")
        assertTrue(recalled!!.surfacing is SurfacingReason.Activated)
        assertTrue(h.items.none { it.text.text == coffee.text })
    }

    @Test
    fun `a two-word entity is matched as a phrase`() = runBlocking {
        val user = newUser()
        val visit = structure.createItem(user, 1, ItemKind.EVENT, "Visiting from Pune", dueAt = now + 40 * day)!!
        structure.linkAbout(user, 1, visit.uid, "cousin Ravi")

        seeder().seedTurn(user, 5, "Is cousin Ravi still coming?", emptyList())

        assertTrue(nudged(user).containsKey(visit.uid))
    }

    @Test
    fun `sharing distinctive words with an item nudges it, in proportion, and a stray common word does not`() = runBlocking {
        val user = newUser()
        val firewall = structure.createItem(user, 1, ItemKind.TASK, "Sort out the firewall rules for the demo")!!
        val paint = structure.createItem(user, 1, ItemKind.TASK, "Buy paint for the garage")!!

        val seeded = seeder().seedTurn(user, 5, "What was worrying me about the firewall rules?", emptyList())

        assertEquals(1, seeded.mentionedItems)
        val nudge = nudged(user).getValue(firewall.uid)
        assertTrue(nudge.weight >= 0.34 && nudge.weight <= 1.0)
        assertFalse(nudged(user).containsKey(paint.uid), "'about', 'what' and 'for' are not distinctive")
    }

    @Test
    fun `at most a few items are nudged by one message`() = runBlocking {
        val user = newUser()
        repeat(20) { structure.createItem(user, 1, ItemKind.FACT, "Firewall note number $it about firewall") }

        val seeded = seeder().seedTurn(user, 5, "firewall firewall", emptyList())

        assertEquals(HorizonPolicy().maxMentionNudges, seeded.mentionedItems)
        assertTrue(nudged(user).size <= HorizonPolicy().maxMentionNudges)
    }

    @Test
    fun `what the interpreter just touched is nudged, but a pin and a held feeling are not`() = runBlocking {
        val user = newUser()
        val task = structure.createItem(user, 1, ItemKind.TASK, "Send the invoice")!!
        val project = structure.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val applied = listOf(
            AppliedOperation(ResolveItem(task.uid, "sent"), true, "Marked done"),
            AppliedOperation(SetFocus(project.uid, "work on demo"), true, "Focus set"),
            AppliedOperation(HoldForChoice("I'm stressed"), true, "Held"),
            AppliedOperation(ResolveItem("nonexistent", "x"), false, "Could not"),
        )

        val seeded = seeder().seedTurn(user, 5, "Okay.", applied)

        assertEquals(1, seeded.touched)
        assertEquals(setOf(task.uid), nudged(user).keys)
    }

    @Test
    fun `a nudge never weakens a pin on the same target`() = runBlocking {
        val user = newUser()
        val demo = structure.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        structure.setFocus(user, 2, demo.uid, FocusMode.PINNED)

        seeder().seedTurn(user, 5, "How is the demo going?", emptyList())

        val focus = structure.getFocus(user).single()
        assertEquals(FocusMode.PINNED, focus.mode)
        assertEquals(1.0, focus.weight)
    }

    @Test
    fun `old nudges are pruned so they cannot pile up`() = runBlocking {
        val user = newUser()
        val old = structure.createContainer(user, 1, ContainerKind.PROJECT, "Old thing")!!
        structure.createContainer(user, 1, ContainerKind.PROJECT, "Demo")
        structure.setFocus(user, 1, old.uid, FocusMode.NUDGE)

        val seeded = seeder().seedTurn(user, 100, "How is the demo going?", emptyList())

        assertEquals(1, seeded.pruned)
        assertFalse(nudged(user).containsKey(old.uid))
    }

    @Test
    fun `nothing is nudged for a message about nothing the user has`() = runBlocking {
        val user = newUser()
        structure.createContainer(user, 1, ContainerKind.PROJECT, "Demo")
        structure.createItem(user, 1, ItemKind.FACT, "My dog is called Newton")

        val seeded = seeder().seedTurn(user, 5, "Hi!", emptyList())

        assertEquals(HorizonSeeder.Seeded(0, 0, 0, 0, 0), seeded)
    }

    @Test
    fun `one user's mention never nudges another user's things, even through a shared entity`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val theirs = structure.createItem(b, 1, ItemKind.FACT, "Their sister plays the cello")!!
        structure.linkAbout(b, 1, theirs.uid, "sister")
        structure.createContainer(b, 1, ContainerKind.PROJECT, "Demo")

        seeder().seedTurn(a, 5, "My sister and the demo", emptyList())

        assertTrue(nudged(a).isEmpty())
        assertTrue(nudged(b).isEmpty())
    }

    // ── Silence ──────────────────────────────────────────────────────────────

    @Test
    fun `a first ever turn, or a short gap, opens no question`() = runBlocking {
        val user = newUser()
        assertFalse(seeder().checkSilence(user, 1, "r1").asked, "there has been no earlier turn to be silent since")

        seeder().recordActivity(user)
        now += 3 * day
        assertFalse(seeder().checkSilence(user, 2, "r2").asked)
        assertTrue(structure.listRecentItems(user, 10).isEmpty())
    }

    @Test
    fun `a long silence opens a question that Alfrd asks, and it shows in the overview`() = runBlocking {
        val user = newUser()
        seeder().recordActivity(user)
        now += 9 * day

        val outcome = seeder().checkSilence(user, 2, "r-return")

        assertTrue(outcome.asked)
        val question = structure.getItem(user, outcome.questionUid!!)!!
        assertEquals(ItemKind.QUESTION, question.kind)
        assertEquals("What have you been up to?", question.text)
        assertEquals("alfrd", question.askedBy)
        assertEquals("open", question.status)

        val item = horizon(user, cycle = 2).items.single { it.text.text == "What have you been up to?" }
        assertEquals("question", item.kind)
        assertEquals("alfrd", item.askedBy)
        val line = HorizonItemsRenderer.render(horizon(user, cycle = 2)).single { it.renderedLine.contains("What have you been up to?") }
        assertTrue(line.renderedLine.contains("a question you want to ask them"), line.renderedLine)
        assertTrue(line.essential)
    }

    @Test
    fun `retrying the same request neither asks twice nor closes the question it opened`() = runBlocking {
        val user = newUser()
        seeder().recordActivity(user)
        now += 9 * day

        val first = seeder().checkSilence(user, 2, "r-retry")
        val again = seeder().checkSilence(user, 2, "r-retry")

        assertEquals(first.questionUid, again.questionUid)
        assertEquals(1, structure.listRecentItems(user, 10).count { it.text == "What have you been up to?" })
        assertEquals("open", structure.getItem(user, first.questionUid!!)!!.status)
    }

    @Test
    fun `the next turn is the answer, so the question is closed and leaves the view`() = runBlocking {
        val user = newUser()
        seeder().recordActivity(user)
        now += 9 * day
        val asked = seeder().checkSilence(user, 2, "r-1")
        seeder().recordActivity(user)
        now += 60_000

        val next = seeder().checkSilence(user, 3, "r-2")

        assertFalse(next.asked)
        assertEquals("resolved", structure.getItem(user, asked.questionUid!!)!!.status)
        assertTrue(horizon(user, cycle = 3).items.none { it.text.text == "What have you been up to?" })
    }

    @Test
    fun `a second long silence asks again, closing the earlier question`() = runBlocking {
        val user = newUser()
        seeder().recordActivity(user)
        now += 9 * day
        val first = seeder().checkSilence(user, 2, "r-1")
        seeder().recordActivity(user)
        now += 20 * day

        val second = seeder().checkSilence(user, 3, "r-2")

        assertTrue(second.asked)
        assertNotNull(second.questionUid)
        assertTrue(first.questionUid != second.questionUid)
        assertEquals("resolved", structure.getItem(user, first.questionUid!!)!!.status)
        assertEquals("open", structure.getItem(user, second.questionUid!!)!!.status)
    }

    @Test
    fun `the threshold is a policy, not a constant`() = runBlocking {
        val user = newUser()
        val quick = HorizonSeeder(structure, HorizonPolicy(silenceThreshold = Duration.ofHours(1)), clock = { now })
        quick.recordActivity(user)
        now += 2 * 3_600_000L
        assertTrue(quick.checkSilence(user, 2, "r-1").asked)
    }

    @Test
    fun `activity only ever moves forward`() = runBlocking {
        val user = newUser()
        seeder().recordActivity(user)
        val first = structure.lastActiveAt(user)!!
        now -= day
        seeder().recordActivity(user)
        assertEquals(first, structure.lastActiveAt(user))
    }
}
