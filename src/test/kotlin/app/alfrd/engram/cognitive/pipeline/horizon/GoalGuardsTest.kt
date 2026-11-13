package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import java.util.UUID

/**
 * The goal guards, attacked from every verb: nothing takes a goal to running before the owner approves it, nothing changes its done-when after, and
 * a correction cannot carry a goal round either. Operations are built in code and sent to the applier, past the parser, so what holds here holds for
 * any caller. The approval and the edits leave change records.
 */
class GoalGuardsTest {
    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var applier: HorizonOperationApplier
    private lateinit var path: String
    private lateinit var user: String
    private val zone = ZoneOffset.UTC
    private var cycle = 1L

    @BeforeEach
    fun setUp() {
        path = "./data/test-goal-guards-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        structure = ArcadeHorizonStructureStore(dbManager.getDatabase())
        applier = HorizonOperationApplier(structure, ArcadeHorizonGraphStore(dbManager.getDatabase()))
        user = "goal-guards-${UUID.randomUUID()}@example.test"
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

    private fun goal(text: String = "Review the open Williams items", doneWhen: String? = "Every open item has a proposal") =
        runBlocking { structure.createItem(user, 1, ItemKind.GOAL, text, extras = ItemExtras(doneWhen = doneWhen, stopWhen = "Ten cycles", budget = "10 cycles"))!! }

    private fun stored(uid: String) = runBlocking { structure.getItem(user, uid)!! }
    private fun send(vararg ops: HorizonOperation) = runBlocking { applier.apply(user, ++cycle, "op-${UUID.randomUUID()}", ops.toList(), zone) }
    private fun changes(uid: String) = ChangeLog(dbManager.getDatabase()).forTarget(user, uid)
    private fun goalCount(): Long = dbManager.getDatabase().query("sql", "SELECT count(*) AS n FROM Goal").use { it.next().getProperty<Number>("n").toLong() }

    /** Every operation that could touch a goal, built in code, aimed at [g] (and a task and a project to link and file with). */
    private fun everyOperationOn(g: ItemRecord, task: ItemRecord, project: ContainerRecord, other: ContainerRecord): List<HorizonOperation> = listOf(
        SetState(g.uid, WorkState.ACTIVE, "q"), SetState(g.uid, WorkState.WAITING, "q", waitingOn = "Sam"), SetState(g.uid, WorkState.OPEN, "q"),
        SetState(g.uid, WorkState.DONE, "q"), SetState(g.uid, WorkState.DROPPED, "q"),
        ResolveItem(g.uid, "q"), ReopenItem(g.uid, "q"), ConfirmItem(g.uid, "q"),
        FileItem(g.uid, project.uid), FileItem(g.uid, other.uid),
        LinkItems(g.uid, task.uid, "q"), LinkItems(task.uid, g.uid, "q"),
        SetAttention(g.uid, AttentionDirective.PINNED, "q"), SetAttention(g.uid, AttentionDirective.SUPPRESSED, "q"), SetAttention(g.uid, null, "q"),
        SetFocus(g.uid, "q"), ReleaseFocus("q"),
        CorrectItem(g.uid, "q", title = "A different goal", dueAt = 5_000_000_000_000L),
        RecordItem(ItemKind.GOAL, "Review the open Williams items", title = "Review the open Williams items", doneWhen = "Something easier"),
    )

    // ── Change records ───────────────────────────────────────────────────────

    @Test
    fun `editing a proposed goal and approving it leave change records, and a refused change to an approved one writes none`() {
        val g = goal(doneWhen = "All reviewed")
        assertEquals(GoalEdit.EDITED, runBlocking { structure.editGoal(user, 2, g.uid, doneWhen = "All have a proposal", stopWhen = "Five cycles", budget = "5 cycles", text = "Review all the Williams items") })
        assertEquals(GoalApproval.APPROVED, runBlocking { structure.approveGoal(user, 3, g.uid, "yes, go ahead") })
        val rows = changes(g.uid).filter { it.field in setOf("approval", "doneWhen", "stopWhen", "budget", "sentence") }.map { Triple(it.field, it.oldValue, it.newValue) }
        assertTrue(Triple("doneWhen", "All reviewed", "All have a proposal") in rows, rows.toString())
        assertTrue(Triple("approval", "proposed", "approved") in rows, rows.toString())
        assertTrue(Triple("stopWhen", "Ten cycles", "Five cycles") in rows && Triple("budget", "10 cycles", "5 cycles") in rows, rows.toString())
        assertTrue(Triple("sentence", "Review the open Williams items", "Review all the Williams items") in rows, rows.toString())

        // A refused change to the frozen goal writes nothing.
        val before = changes(g.uid).size
        assertEquals(GoalEdit.FROZEN, runBlocking { structure.editGoal(user, 5, g.uid, doneWhen = "Easier") })
        assertEquals(before, changes(g.uid).size)
    }

    // ── Running needs approval ───────────────────────────────────────────────

    @Test
    fun `a goal is written open and proposed whatever state is asked for`() {
        for (asked in listOf(WorkState.ACTIVE, WorkState.WAITING, WorkState.DONE, WorkState.DROPPED)) {
            val g = runBlocking { structure.createItem(user, 1, ItemKind.GOAL, "Goal asked as $asked", extras = ItemExtras(state = asked))!! }
            assertEquals(WorkState.OPEN, stored(g.uid).workState, "asked as $asked")
            assertEquals("proposed", stored(g.uid).approval)
        }
    }

    @Test
    fun `no operation takes a proposed goal to running or approves it, and the refusal says why`() {
        val g = goal()
        val task = runBlocking { structure.createItem(user, 1, ItemKind.TASK, "Send the invoice")!! }
        val p = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, "Williams")!! }
        val o = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, "Meridian")!! }
        for (op in everyOperationOn(g, task, p, o)) {
            send(op)
            val now = stored(g.uid)
            assertNotEquals(WorkState.ACTIVE, now.workState, "${op.verb} ${op}: the goal must not be running")
            assertEquals("proposed", now.approval, "${op.verb}: only approve_goal approves")
        }
        // (The sweep above ended with the goal done or stopped, which is final. A fresh proposal shows the approval refusal.)
        val fresh = goal("A fresh proposal")
        val refused = send(SetState(fresh.uid, WorkState.ACTIVE, "q")).single()
        assertFalse(refused.applied)
        assertTrue(refused.summary.contains("not approved") && refused.summary.contains("approve_goal"), refused.summary)

        // Once the owner approves it, it can run.
        assertTrue(send(ApproveGoal(fresh.uid, "yes, go ahead")).single().applied)
        assertTrue(send(SetState(fresh.uid, WorkState.ACTIVE, "q")).single().applied)
        assertEquals(WorkState.ACTIVE, stored(fresh.uid).workState)
    }

    // ── The bar does not move ────────────────────────────────────────────────

    @Test
    fun `after approval no operation changes the done-when, and an edit that tries is refused whole`() {
        val g = goal(doneWhen = "Every open item has a proposal")
        val task = runBlocking { structure.createItem(user, 1, ItemKind.TASK, "Send the invoice")!! }
        val p = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, "Williams")!! }
        val o = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, "Meridian")!! }
        assertTrue(send(ApproveGoal(g.uid, "yes, go ahead")).single().applied)

        for (op in everyOperationOn(g, task, p, o) + listOf(
            EditGoal(g.uid, "q", doneWhen = "Half of them"), EditGoal(g.uid, "q", doneWhen = "Half of them", stopWhen = "Never", budget = "Unlimited"),
        )) {
            send(op)
            val now = stored(g.uid)
            assertEquals("Every open item has a proposal", now.doneWhen, "${op.verb} $op must not move the bar")
            assertEquals("approved", now.approval, "${op.verb}: approval stays")
        }
        // Stopped and reopened, the bar is still the one that was approved.
        send(SetState(g.uid, WorkState.DROPPED, "q")); send(ReopenItem(g.uid, "q"))
        assertEquals("Every open item has a proposal", stored(g.uid).doneWhen)
        assertEquals(GoalEdit.FROZEN, runBlocking { structure.editGoal(user, ++cycle, g.uid, doneWhen = "Half of them") })

        // Nothing else moved either: a refused edit writes none of its fields, and a plain edit of the others is refused too.
        assertEquals("Ten cycles", stored(g.uid).stopWhen); assertEquals("10 cycles", stored(g.uid).budget)
        assertFalse(send(EditGoal(g.uid, "q", stopWhen = "Five cycles", budget = "5 cycles")).single().applied)
        assertEquals("Ten cycles", stored(g.uid).stopWhen)
    }

    // ── A correction is not a way round ──────────────────────────────────────

    @Test
    fun `correct_item on a goal is refused by the applier itself, proposed or approved, and writes nothing`() {
        val proposed = goal("A proposed goal")
        val approved = goal("An approved goal")
        runBlocking { structure.approveGoal(user, 2, approved.uid, "yes"); structure.setState(user, 3, approved.uid, WorkState.ACTIVE) }
        for (g in listOf(proposed, approved)) {
            val result = send(CorrectItem(g.uid, "q", title = "A different goal", dueAt = 5_000_000_000_000L)).single()
            assertFalse(result.applied, g.text)
            assertTrue(result.summary.contains("edit_goal"), result.summary)
        }
        assertEquals(2L, goalCount(), "no corrected goal was written")
        assertEquals(WorkState.ACTIVE, stored(approved.uid).workState, "and the running goal was not closed")
        assertEquals(WorkState.OPEN, stored(proposed.uid).workState)
    }

    @Test
    fun `a goal cannot be recorded from a source, whatever the chunk says`() {
        val before = goalCount()
        val result = send(RecordItem(ItemKind.GOAL, "Review the open Williams items", chunkUid = "any-chunk", claimant = "source: a page", writer = "claude_session")).single()
        assertFalse(result.applied)
        assertTrue(result.summary.contains("owner's own words"), result.summary)
        assertEquals(before, goalCount())
    }

    @Test
    fun `an engine that already enforces constraints gets the Goal type and its rules on its next start`() {
        val db = dbManager.getDatabase()
        // As a database made before goals existed: constraints on, and no Goal type.
        SchemaBootstrap.bootstrap(db, enforceConstraints = true)
        db.schema.dropType("Goal")
        assertFalse(db.schema.existsType("Goal"))
        // The next start, constraints still on: the type comes back with its rules, and goals are written and refused as they should be.
        SchemaBootstrap.bootstrap(db, enforceConstraints = true)
        assertTrue(db.schema.existsType("Goal"))
        val outcome = app.alfrd.engram.db.SchemaConstraints.apply(db)
        assertTrue(outcome.skipped.isEmpty(), "no rule is skipped: ${outcome.skipped}")
        assertEquals("proposed", stored(goal("A goal after the upgrade").uid).approval)
        val refused = try { db.transaction { db.newVertex("Goal").apply { set("uid", UUID.randomUUID().toString()); set("text", "x"); set("createdAt", 1L); set("updatedAt", 1L); set("approval", "maybe"); save() } }; false } catch (e: Exception) { true }
        assertTrue(refused, "an approval outside proposed and approved is refused on the upgraded database")
    }
}
