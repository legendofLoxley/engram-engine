package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.cognitive.pipeline.memory.DatabaseEngramClient
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * The owner's decision: an approved goal is frozen. What a goal means is changed by stopping it and writing a new goal (linked to the old one), never by
 * editing it. While proposed it may be edited and re-filed; after approval only its state, its evidence and confirm_item may change. A goal that is
 * done or stopped is final; a paused one may resume.
 */
class GoalFreezeTest {
    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var applier: HorizonOperationApplier
    private lateinit var path: String
    private lateinit var user: String
    private val zone = ZoneOffset.UTC
    private var cycle = 1L

    @BeforeEach
    fun setUp() {
        path = "./data/test-goal-freeze-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        structure = ArcadeHorizonStructureStore(dbManager.getDatabase())
        applier = HorizonOperationApplier(structure, ArcadeHorizonGraphStore(dbManager.getDatabase()))
        user = "goal-freeze-${UUID.randomUUID()}@example.test"
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

    private fun project(name: String) = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, name)!! }
    private fun goal(text: String = "Review the open Williams items", under: ContainerRecord? = null) =
        runBlocking { structure.createItem(user, 1, ItemKind.GOAL, text, projectUid = under?.uid, extras = ItemExtras(doneWhen = "Every open item has a proposal", stopWhen = "Ten cycles", budget = "10 cycles"))!! }
    private fun approved(text: String = "Review the open Williams items", under: ContainerRecord? = null) =
        goal(text, under).also { runBlocking { structure.approveGoal(user, 2, it.uid, "yes, go ahead") } }

    private fun stored(uid: String) = runBlocking { structure.getItem(user, uid)!! }
    private fun send(vararg ops: HorizonOperation) = runBlocking { applier.apply(user, ++cycle, "op-${UUID.randomUUID()}", ops.toList(), zone) }
    private fun one(op: HorizonOperation) = send(op).single()
    private val rule = "stop it, then propose a new goal with record_item and replaces set to the old goal, which writes the link to it in the same step"

    // ── An approved goal is frozen ───────────────────────────────────────────

    @Test
    fun `an approved goal refuses every change to its sentence, done-when, stop-when and budget, and each refusal says what to do`() {
        val g = approved()
        for (edit in listOf(
            EditGoal(g.uid, "q", text = "Review everything about Williams"), EditGoal(g.uid, "q", doneWhen = "Half of them"),
            EditGoal(g.uid, "q", stopWhen = "Never"), EditGoal(g.uid, "q", budget = "Unlimited"),
            EditGoal(g.uid, "q", text = "Something else", doneWhen = "Easier", stopWhen = "Never", budget = "Unlimited"),
        )) {
            val result = one(edit)
            assertFalse(result.applied, edit.toString())
            assertTrue(result.summary.startsWith("Refused:") && result.summary.contains(rule), result.summary)
        }
        val now = stored(g.uid)
        assertEquals("Review the open Williams items", now.text)
        assertEquals("Every open item has a proposal", now.doneWhen); assertEquals("Ten cycles", now.stopWhen); assertEquals("10 cycles", now.budget)
        // Asking for what it already has is not a change.
        assertTrue(one(EditGoal(g.uid, "q", doneWhen = "Every open item has a proposal", text = "Review the open Williams items")).applied)
    }

    @Test
    fun `an approved goal is not re-filed, a proposed one may be, and filing it where it is changes nothing`() {
        val williams = project("Williams"); val meridian = project("Meridian")
        val proposed = goal("A proposed goal", williams)
        assertTrue(one(FileItem(proposed.uid, meridian.uid)).applied)
        assertEquals(meridian.uid, stored(proposed.uid).projectUid)

        val g = approved("An approved goal", williams)
        val refused = one(FileItem(g.uid, meridian.uid))
        assertFalse(refused.applied)
        assertTrue(refused.summary.startsWith("Refused:") && refused.summary.contains(rule), refused.summary)
        assertEquals(williams.uid, stored(g.uid).projectUid, "still under the project it was approved under")
        assertTrue(one(FileItem(g.uid, williams.uid)).applied, "filed where it already is: not a change")
    }

    @Test
    fun `an approved goal still starts, pauses, resumes, stops and finishes, and can be confirmed`() {
        val g = approved()
        assertTrue(one(SetState(g.uid, WorkState.ACTIVE, "q")).applied)                         // start
        assertTrue(one(SetState(g.uid, WorkState.WAITING, "q", waitingOn = "Sam")).applied)     // pause
        assertTrue(one(SetState(g.uid, WorkState.ACTIVE, "q")).applied)                         // resume
        val before = stored(g.uid).evidenceAt
        Thread.sleep(5)
        assertTrue(one(ConfirmItem(g.uid, "q")).applied)                                          // evidence
        assertTrue(stored(g.uid).evidenceAt > before)
        assertTrue(one(SetState(g.uid, WorkState.DONE, "q")).applied)                           // done
        val other = approved("Another goal")
        assertTrue(one(SetState(other.uid, WorkState.DROPPED, "q")).applied)                    // stop
        assertEquals(WorkState.DROPPED, stored(other.uid).workState)
    }

    // ── A proposed goal can still be edited ──────────────────────────────────

    @Test
    fun `a proposed goal's sentence, done-when, stop-when and budget can all change, and the change is recorded`() {
        val g = goal()
        val result = one(EditGoal(g.uid, "q", text = "Review every open Williams item", doneWhen = "Each has a proposal", stopWhen = "Five cycles", budget = "5 cycles"))
        assertTrue(result.applied, result.summary)
        val now = stored(g.uid)
        assertEquals("Review every open Williams item", now.text)
        assertEquals("Each has a proposal", now.doneWhen); assertEquals("Five cycles", now.stopWhen); assertEquals("5 cycles", now.budget)
        assertEquals("proposed", now.approval)
        val rows = ChangeLog(dbManager.getDatabase()).forTarget(user, g.uid).map { it.field }
        assertTrue(rows.containsAll(listOf("sentence", "doneWhen", "stopWhen", "budget")), rows.toString())
    }

    // ── Done and stopped are final; paused may resume ────────────────────────

    @Test
    fun `a done or stopped goal is final, and reopening it is refused with the same message`() {
        val done = approved("A finished goal"); val stopped = approved("A stopped goal"); val proposedStopped = goal("A proposal that was stopped")
        one(SetState(done.uid, WorkState.ACTIVE, "q")); one(SetState(done.uid, WorkState.DONE, "q"))
        one(SetState(stopped.uid, WorkState.DROPPED, "q")); one(SetState(proposedStopped.uid, WorkState.DROPPED, "q"))
        for (g in listOf(done, stopped, proposedStopped)) {
            val before = stored(g.uid).workState
            val reopen = one(ReopenItem(g.uid, "q"))
            assertFalse(reopen.applied, g.text)
            assertTrue(reopen.summary.startsWith("Refused:") && reopen.summary.contains(rule), reopen.summary)
            for (state in listOf(WorkState.OPEN, WorkState.ACTIVE, WorkState.WAITING)) {
                val moved = one(SetState(g.uid, state, "q"))
                assertFalse(moved.applied, "${g.text} to $state"); assertTrue(moved.summary.contains(rule), moved.summary)
            }
            assertEquals(before, stored(g.uid).workState, "${g.text} is as it was")
        }
        // done and stopped do not swap, but saying the same thing again is not a change
        assertFalse(one(SetState(done.uid, WorkState.DROPPED, "q")).applied)
        assertFalse(one(ResolveItem(stopped.uid, "q")).applied)
        assertTrue(one(ResolveItem(done.uid, "q")).applied)
        assertEquals(WorkState.DONE, stored(done.uid).workState); assertEquals(WorkState.DROPPED, stored(stopped.uid).workState)
    }

    @Test
    fun `a paused goal may resume, and a task is not affected by any of this`() {
        val g = approved()
        one(SetState(g.uid, WorkState.ACTIVE, "q")); one(SetState(g.uid, WorkState.WAITING, "q", waitingOn = "Sam"))
        assertTrue(one(SetState(g.uid, WorkState.ACTIVE, "q")).applied, "resume")
        one(SetState(g.uid, WorkState.WAITING, "q"))
        assertTrue(one(ReopenItem(g.uid, "q")).applied, "a paused goal may also be put back to open")

        val task = runBlocking { structure.createItem(user, 1, ItemKind.TASK, "Send the invoice")!! }
        one(SetState(task.uid, WorkState.DONE, "q"))
        assertTrue(one(ReopenItem(task.uid, "q")).applied, "a finished task still reopens")
    }

    // ── The sentence has no other way in ─────────────────────────────────────

    @Test
    fun `the conversational correction path leaves a goal's sentence alone, and still amends a plain fact`() {
        val client = DatabaseEngramClient(dbManager.getDatabase())
        val proposed = goal("A proposed goal"); val frozen = approved("An approved goal")
        val fact = runBlocking { structure.createItem(user, 1, ItemKind.FACT, "The shed is blue")!! }
        runBlocking { client.amendPhrase(proposed.uid, "Changed behind the owner's back"); client.amendPhrase(frozen.uid, "Changed behind the owner's back") }
        assertEquals("A proposed goal", stored(proposed.uid).text)
        assertEquals("An approved goal", stored(frozen.uid).text)
        runBlocking { client.amendPhrase(fact.uid, "The shed is green") }
        assertEquals("The shed is green", stored(fact.uid).text)
    }

    // ── The parser ───────────────────────────────────────────────────────────

    private fun op(verb: String, vararg fields: Pair<String, String>): JsonObject = buildJsonObject { put("verb", verb); fields.forEach { (k, v) -> put(k, v) } }
    private fun ops(item: JsonObject): JsonObject = buildJsonObject { put("ops", JsonArray(listOf(item))) }

    @Test
    fun `edit_goal takes a new sentence only from the owner's words`() {
        val g = goal()
        val snap = ViewSnapshot(emptyList(), emptyList(), listOf(SnapshotItem("i1", g.uid, ItemKind.GOAL, g.text, "open", null, null, emptyList(), null)), zone = zone)
        val said = "Change the goal to review all the Williams items."
        val ok = OperationParser.parse(ops(op("edit_goal", "item" to "i1", "title" to "Review all the Williams items", "quote" to "review all the Williams items")), said, snap, zone)
        assertEquals(listOf<HorizonOperation>(EditGoal(g.uid, "review all the Williams items", text = "Review all the Williams items")), ok.operations)
        val invented = OperationParser.parse(ops(op("edit_goal", "item" to "i1", "title" to "Rebuild the entire firewall infrastructure", "quote" to "review all the Williams items")), said, snap, zone)
        assertEquals("title_not_in_quote", invented.rejections.single().reason)
        val nothing = OperationParser.parse(ops(op("edit_goal", "item" to "i1", "quote" to "review all the Williams items")), said, snap, zone)
        assertEquals("nothing_to_change", nothing.rejections.single().reason)
        assertNotEquals("", g.text)
    }

    // ── The board ────────────────────────────────────────────────────────────

    private val day = 86_400_000L
    private fun boardGoal(i: Int, approved: Boolean, state: WorkState, evidenceDaysAgo: Int = 1) =
        BoardGoal("g$i", "Goal number $i with a sentence", approved, state, 100 * day - evidenceDaysAgo * day)
    private fun board(goals: List<BoardGoal>, changes: List<BoardChange> = emptyList()) = BoardComposer(HorizonPolicy(), zone)
        .plan(BoardData(100 * day, 99 * day, false, emptyList(), emptyList(), emptyList(), changes, 0, goals = goals)).renderWithHintedRefs().text

    @Test
    fun `on the board running means active only, and an approved goal that has not started reads as approved`() {
        val all = listOf(
            boardGoal(1, true, WorkState.OPEN),                       // approved, not started
            boardGoal(2, true, WorkState.ACTIVE),                     // running
            boardGoal(3, true, WorkState.ACTIVE, evidenceDaysAgo = 60), // running but quiet for too long: stalled
            boardGoal(4, true, WorkState.WAITING),                    // paused: stalled
            boardGoal(5, false, WorkState.OPEN),                      // proposed
            boardGoal(6, true, WorkState.OPEN, evidenceDaysAgo = 60), // approved, not started, however old: still just approved
        )
        // The counts line covers every goal; the section lists at most boardGoalLines of them, so the lines are read from smaller sets.
        assertTrue("Goals: 1 running, 2 approved, 1 waiting for approval, 2 stalled." in board(all), board(all))
        val text = board(all.take(4))
        val lines = text.lines()
        assertTrue(lines.single { "Goal number 1 " in it }.endsWith("approved, not started"), text)
        assertTrue(lines.single { "Goal number 2 " in it }.endsWith(": running"), text)
        assertTrue("stalled, no evidence since" in lines.single { "Goal number 3 " in it }, text)
        assertTrue("stalled, paused" in lines.single { "Goal number 4 " in it }, text)
        assertFalse(lines.single { "Goal number 1 " in it }.contains("running"), "not started is not running")
        val old = board(listOf(all[5]))
        assertTrue(old.lines().single { "Goal number 6 " in it }.endsWith("approved, not started"), old)
        assertFalse("stalled" in old, old)
    }

    @Test
    fun `since last time names a goal's fields in words, never the stored field names`() {
        fun change(target: String, field: String, old: String?, new: String?) = BoardChange(target, "Goal $target", target, null, field, old, new, 100 * day - 1000)
        val text = board(
            emptyList(),
            listOf(
                change("g1", "doneWhen", "All reviewed", "All have a proposal"), change("g1", "stopWhen", "Ten cycles", "Five cycles"),
                change("g2", "sentence", "Review items", "Review every item"), change("g2", "budget", "10 cycles", "5 cycles"),
                change("g3", "approval", "proposed", "approved"),
            ),
        )
        assertTrue("done-when All reviewed to All have a proposal" in text && "stop-when Ten cycles to Five cycles" in text, text)
        assertTrue("sentence Review items to Review every item" in text && "budget 10 cycles to 5 cycles" in text, text)
        assertTrue("approval proposed to approved" in text, text)
        assertFalse("doneWhen" in text || "stopWhen" in text, "no raw field names:\n$text")
    }
}
