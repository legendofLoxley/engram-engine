package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SchemaConstraints
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import java.util.UUID

/**
 * Phase A of goals: the goal item kind and its approval, the locked done-when, the board's Goals section, and the constraints. The Director
 * does not exist here yet; nothing in this file runs a goal.
 */
class GoalKindTest {
    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var applier: HorizonOperationApplier
    private lateinit var path: String
    private lateinit var user: String
    private val zone = ZoneOffset.UTC
    private val day = 86_400_000L

    @BeforeEach
    fun setUp() {
        path = "./data/test-goal-kind-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        structure = ArcadeHorizonStructureStore(dbManager.getDatabase())
        applier = HorizonOperationApplier(structure, ArcadeHorizonGraphStore(dbManager.getDatabase()))
        user = "goal-${UUID.randomUUID()}@example.test"
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "g"); set("email", user)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(path).deleteRecursively()
    }

    private fun goal(text: String = "Review the open Williams items", project: ContainerRecord? = null, extras: ItemExtras = ItemExtras()) =
        runBlocking { structure.createItem(user, 1, ItemKind.GOAL, text, projectUid = project?.uid, extras = extras)!! }

    private fun stored(uid: String): ItemRecord = runBlocking { structure.getItem(user, uid)!! }

    private fun vertexProp(uid: String, name: String): Any? {
        val db = dbManager.getDatabase()
        var out: Any? = null
        db.transaction { db.query("sql", "SELECT FROM Goal WHERE uid = :u", mapOf("u" to uid)).use { rs -> if (rs.hasNext()) out = rs.next().toElement().asVertex().get(name) } }
        return out
    }

    // ── The kind ─────────────────────────────────────────────────────────────

    @Test
    fun `a goal is written proposed and open, with its fields, and filed under the project it serves`() {
        val williams = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, "Williams")!! }
        val g = goal(project = williams, extras = ItemExtras(doneWhen = "Every open item has a proposal", stopWhen = "Ten cycles", budget = "10 cycles"))
        val read = stored(g.uid)
        assertEquals(ItemKind.GOAL, read.kind)
        assertEquals("proposed", read.approval)
        assertEquals(WorkState.OPEN, read.workState)
        assertEquals("Every open item has a proposal", read.doneWhen)
        assertEquals("Ten cycles", read.stopWhen)
        assertEquals("10 cycles", read.budget)
        assertEquals(williams.uid, read.projectUid)
    }

    @Test
    fun `a goal inherits the existing verbs and the state words map onto the five states`() {
        val g = goal()
        // The owner's words for a goal's state: running is active, paused is waiting, stopped is dropped.
        val snap = ViewSnapshot(emptyList(), emptyList(), listOf(SnapshotItem("i1", g.uid, ItemKind.GOAL, g.text, "open", null, null, emptyList(), null)), zone = zone)
        fun word(w: String) = (OperationParser.parse(ops(op("set_state", "item" to "i1", "state" to w, "quote" to "go")), "go", snap, zone).operations.single() as SetState).state
        assertEquals(WorkState.ACTIVE, word("running"))
        assertEquals(WorkState.WAITING, word("paused"))
        assertEquals(WorkState.DROPPED, word("stopped"))
        assertEquals(WorkState.DONE, word("done"))
        // set_state on an unapproved goal: paused and stopped are fine, running is not
        assertTrue(runBlocking { structure.setState(user, 2, g.uid, WorkState.WAITING) })
        assertTrue(runBlocking { structure.setState(user, 3, g.uid, WorkState.OPEN) })
    }

    // ── Approval ─────────────────────────────────────────────────────────────

    @Test
    fun `a goal cannot run until the owner approves it, and approval keeps their words`() {
        val g = goal()
        assertFalse(runBlocking { structure.setState(user, 2, g.uid, WorkState.ACTIVE) }, "an unapproved goal cannot be marked running")
        assertEquals(WorkState.OPEN, stored(g.uid).workState)

        assertEquals(GoalApproval.APPROVED, runBlocking { structure.approveGoal(user, 3, g.uid, "yes, go ahead with the Williams review") })
        assertEquals("approved", stored(g.uid).approval)
        assertEquals("yes, go ahead with the Williams review", vertexProp(g.uid, "approvalQuote"))
        assertTrue(vertexProp(g.uid, "approvedAt") is Number)
        assertTrue(runBlocking { structure.setState(user, 4, g.uid, WorkState.ACTIVE) })

        assertEquals(GoalApproval.ALREADY_APPROVED, runBlocking { structure.approveGoal(user, 5, g.uid, "yes, again") })
        assertEquals("yes, go ahead with the Williams review", vertexProp(g.uid, "approvalQuote"), "a second approval does not rewrite the first")
    }

    @Test
    fun `approval without a quote is refused, and only a goal can be approved`() {
        val g = goal()
        assertEquals(GoalApproval.MISSING_QUOTE, runBlocking { structure.approveGoal(user, 2, g.uid, "   ") })
        assertEquals("proposed", stored(g.uid).approval)
        val task = runBlocking { structure.createItem(user, 1, ItemKind.TASK, "Send the invoice")!! }
        assertEquals(GoalApproval.NOT_A_GOAL, runBlocking { structure.approveGoal(user, 3, task.uid, "yes") })
        assertEquals(GoalApproval.NOT_FOUND, runBlocking { structure.approveGoal(user, 3, "no-such-uid", "yes") })
        assertEquals(GoalApproval.NOT_FOUND, runBlocking { structure.approveGoal("someone-else@example.test", 3, g.uid, "yes") })
    }

    @Test
    fun `the parser refuses an approval that is not grounded, is a question, is negated, or names something that is not a goal`() {
        val g = goal()
        val snap = ViewSnapshot(
            emptyList(), emptyList(),
            listOf(
                SnapshotItem("i1", g.uid, ItemKind.GOAL, g.text, "open", null, null, emptyList(), null),
                SnapshotItem("i2", "u-task", ItemKind.TASK, "Send the invoice", "open", null, null, emptyList(), null),
            ),
            zone = zone,
        )
        fun reason(utterance: String, o: JsonObject) = OperationParser.parse(ops(o), utterance, snap, zone).rejections.single().reason
        assertEquals("ungrounded_quote", reason("Sounds fine.", op("approve_goal", "item" to "i1", "quote" to "yes, go ahead")))
        assertEquals("missing_quote", reason("Yes, go ahead.", op("approve_goal", "item" to "i1")))
        assertEquals("question_detected", reason("Should I go ahead?", op("approve_goal", "item" to "i1", "quote" to "Should I go ahead?")))
        assertEquals("negation_marker_detected", reason("No, don't go ahead with it.", op("approve_goal", "item" to "i1", "quote" to "don't go ahead with it")))
        assertEquals("not_a_goal", reason("Yes, go ahead.", op("approve_goal", "item" to "i2", "quote" to "go ahead")))
        val ok = OperationParser.parse(ops(op("approve_goal", "item" to "i1", "quote" to "go ahead")), "Yes, go ahead.", snap, zone)
        assertEquals(listOf<HorizonOperation>(ApproveGoal(g.uid, "go ahead")), ok.operations)
    }

    // ── done_when is locked after approval ───────────────────────────────────

    @Test
    fun `done_when can change while the goal is proposed and cannot after it is approved`() {
        val g = goal(extras = ItemExtras(doneWhen = "All items reviewed"))
        assertEquals(GoalEdit.EDITED, runBlocking { structure.editGoal(user, 2, g.uid, doneWhen = "All open items have a proposal") })
        assertEquals("All open items have a proposal", stored(g.uid).doneWhen)

        runBlocking { structure.approveGoal(user, 3, g.uid, "approved") }
        assertEquals(GoalEdit.DONE_WHEN_LOCKED, runBlocking { structure.editGoal(user, 4, g.uid, doneWhen = "Something easier") })
        assertEquals("All open items have a proposal", stored(g.uid).doneWhen)
        // the refusal is whole: nothing else in the same call is written
        assertEquals(GoalEdit.DONE_WHEN_LOCKED, runBlocking { structure.editGoal(user, 4, g.uid, doneWhen = "Something easier", stopWhen = "Never") })
        assertNull(stored(g.uid).stopWhen)
        // the same words are not a change; stop_when and budget may still change
        assertEquals(GoalEdit.UNCHANGED, runBlocking { structure.editGoal(user, 5, g.uid, doneWhen = "All open items have a proposal") })
        assertEquals(GoalEdit.EDITED, runBlocking { structure.editGoal(user, 6, g.uid, stopWhen = "Five cycles", budget = "5 cycles") })
        assertEquals("Five cycles", stored(g.uid).stopWhen)
    }

    @Test
    fun `through the applier the refusal says why, and correct_item is not a way around it`() {
        val g = goal(extras = ItemExtras(doneWhen = "All reviewed"))
        val results = runBlocking {
            applier.apply(user, 2, "r1", listOf(ApproveGoal(g.uid, "yes"), EditGoal(g.uid, "make it easier", doneWhen = "Half reviewed")), zone)
        }
        assertTrue(results[0].applied)
        assertFalse(results[1].applied)
        assertTrue(results[1].summary.contains("locked"), results[1].summary)
        assertTrue(results[1].summary.contains("propose a new one"), results[1].summary)
        assertEquals("All reviewed", stored(g.uid).doneWhen)

        // correct_item would write a new goal and close the old one; the parser refuses it for a goal
        val snap = ViewSnapshot(emptyList(), emptyList(), listOf(SnapshotItem("i1", g.uid, ItemKind.GOAL, g.text, "open", null, null, emptyList(), null)), zone = zone)
        val parsed = OperationParser.parse(ops(op("correct_item", "item" to "i1", "title" to "Other", "quote" to "make it easier")), "make it easier", snap, zone)
        assertEquals("goal_uses_edit_goal", parsed.rejections.single().reason)
    }

    @Test
    fun `a goal is recorded from the owner's words as proposed, and the goal fields belong to goals only`() {
        val snap = ViewSnapshot(emptyList(), emptyList(), emptyList(), zone = zone)
        val utterance = "I want the Williams items reviewed. Done when every open item has a proposal."
        val parsed = OperationParser.parse(
            ops(op("record_item", "kind" to "goal", "title" to "Review the Williams items", "done_when" to "every open item has a proposal", "quote" to "I want the Williams items reviewed")),
            utterance, snap, zone,
        )
        val record = parsed.operations.single() as RecordItem
        assertEquals(ItemKind.GOAL, record.kind)
        assertEquals("every open item has a proposal", record.doneWhen)
        val applied = runBlocking { applier.apply(user, 2, "r2", listOf(record), zone) }.single()
        assertTrue(applied.applied, applied.summary)
        assertEquals("proposed", stored(applied.resultUid!!).approval)

        fun reason(o: JsonObject) = OperationParser.parse(ops(o), utterance, snap, zone).rejections.single().reason
        assertEquals("goal_starts_proposed", reason(op("record_item", "kind" to "goal", "state" to "active", "quote" to "I want the Williams items reviewed")))
        assertEquals("not_for_a_goal_field", reason(op("record_item", "kind" to "task", "done_when" to "never", "quote" to "I want the Williams items reviewed")))
        assertEquals("question_detected", OperationParser.parse(ops(op("record_item", "kind" to "goal", "quote" to "Review the items?")), "Review the items?", snap, zone).rejections.single().reason)
    }

    // ── The board ────────────────────────────────────────────────────────────

    private fun boardData(goals: List<BoardGoal>, now: Long = 100 * day) = BoardData(
        asOf = now, sinceAt = now - day, sinceIsLookback = false, areas = emptyList(), projects = emptyList(), unfiled = emptyList(), changes = emptyList(), newItems = 0, goals = goals,
    )

    private fun render(goals: List<BoardGoal>, policy: HorizonPolicy = HorizonPolicy()) = BoardComposer(policy, zone).plan(boardData(goals)).renderWithHintedRefs()

    private fun boardGoal(i: Int, approved: Boolean = true, state: WorkState = WorkState.ACTIVE, evidenceDaysAgo: Int = 1) =
        BoardGoal("g$i", "Goal number $i with a sentence of some length", approved, state, 100 * day - evidenceDaysAgo * day)

    @Test
    fun `zero goals renders nothing`() {
        val text = render(emptyList()).text
        assertFalse(text.contains("Goals"), text)
        assertEquals(render(emptyList()).text, BoardComposer(HorizonPolicy(), zone).plan(boardData(emptyList())).renderWithHintedRefs().text)
    }

    @Test
    fun `one goal in each state reads as running, waiting for approval and stalled`() {
        val rendered = render(
            listOf(
                boardGoal(1),
                boardGoal(2, approved = false, state = WorkState.OPEN),
                boardGoal(3, state = WorkState.WAITING),
                boardGoal(4, evidenceDaysAgo = 60),
            ),
        )
        val text = rendered.text
        assertTrue(text.contains("Goals: 1 running, 1 waiting for approval, 2 stalled."), text)
        assertTrue(text.contains("waiting for approval"), text)
        assertTrue(text.contains("stalled, paused"), text)
        assertTrue(text.contains("stalled, no evidence since"), text)
        assertTrue(rendered.itemUids.containsAll(listOf("g1", "g2", "g3", "g4")), "each goal line is openable by its ref")
        assertTrue(rendered.tokens <= rendered.budget)
    }

    @Test
    fun `the board stays inside its budget with zero, one and twenty goals, and the goal section is bounded`() {
        val policy = HorizonPolicy()
        for (n in listOf(0, 1, 20, 200)) {
            val rendered = render((1..n).map { boardGoal(it, approved = it % 3 != 0, state = if (it % 5 == 0) WorkState.WAITING else WorkState.ACTIVE) }, policy)
            assertTrue(rendered.tokens <= policy.boardTokenBudget, "$n goals: ${rendered.tokens} tokens over ${policy.boardTokenBudget}")
            val goalLines = rendered.text.lines().count { it.startsWith("  ") && it.contains("Goal number") }
            assertTrue(goalLines <= policy.boardGoalLines, "$n goals: $goalLines lines")
            val section = rendered.text.lines().dropWhile { !it.startsWith("Goals:") }.takeWhile { it.startsWith("Goals:") || it.startsWith("  ") }.joinToString("\n")
            if (n > 0) assertTrue(HorizonBoards.tokens(section, policy) <= policy.boardGoalTokens, "$n goals: section is ${HorizonBoards.tokens(section, policy)} tokens")
            if (n > policy.boardGoalLines) assertTrue(rendered.text.contains("more goal"), "$n goals say how many are not shown")
        }
    }

    @Test
    fun `the board reads goals from the graph, keeps them out of the open count, and leaves out done and dropped ones`() {
        val running = goal("Running goal")
        runBlocking { structure.approveGoal(user, 2, running.uid, "yes"); structure.setState(user, 3, running.uid, WorkState.ACTIVE) }
        goal("Proposed goal")
        val finished = goal("Finished goal")
        runBlocking { structure.setState(user, 4, finished.uid, WorkState.DONE) }
        val task = runBlocking { structure.createItem(user, 1, ItemKind.TASK, "Send the invoice")!! }

        val data = runBlocking { ArcadeBoardSource(dbManager.getDatabase(), HorizonPolicy(), zone, System::currentTimeMillis, structure).read(user, "owner", 10) }!!
        assertEquals(setOf("Running goal", "Proposed goal"), data.goals.map { it.text }.toSet())
        assertEquals(listOf(task.uid), data.unfiled.map { it.uid }, "goals are not counted among the open items")
        assertTrue(data.goals.first { it.text == "Running goal" }.approved)
        assertFalse(data.goals.first { it.text == "Proposed goal" }.approved)
    }

    // ── Constraints ──────────────────────────────────────────────────────────

    @Test
    fun `with constraints on, a goal must be proposed or approved`() {
        val db = dbManager.getDatabase()
        val outcome = SchemaConstraints.apply(db)
        assertTrue(outcome.applied.contains("approval-vocabulary:Goal") || outcome.alreadyInPlace.contains("approval-vocabulary:Goal"), "$outcome")
        assertTrue(outcome.applied.contains("mandatory:Goal.approval") || outcome.alreadyInPlace.contains("mandatory:Goal.approval"), "$outcome")
        assertTrue(outcome.applied.contains("state-vocabulary:Goal") || outcome.alreadyInPlace.contains("state-vocabulary:Goal"), "$outcome")
        // a goal written the normal way still saves
        assertEquals("proposed", stored(goal().uid).approval)

        fun write(approval: String?): Boolean = try {
            db.transaction {
                db.newVertex("Goal").apply {
                    set("uid", UUID.randomUUID().toString()); set("text", "x"); set("createdAt", 1L); set("updatedAt", 1L)
                    if (approval != null) set("approval", approval)
                    save()
                }
            }
            true
        } catch (e: Exception) { false }
        assertFalse(write("maybe"), "an approval outside proposed and approved is refused")
        assertFalse(write(null), "a goal with no approval is refused")
        assertTrue(write("proposed"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun op(verb: String, vararg fields: Pair<String, String>): JsonObject = buildJsonObject {
        put("verb", verb)
        fields.forEach { (k, v) -> put(k, v) }
    }

    private fun ops(vararg items: JsonObject): JsonObject = buildJsonObject { put("ops", JsonArray(items.toList())) }
}
