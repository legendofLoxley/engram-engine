package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.cognitive.pipeline.memory.DatabaseEngramClient
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SchemaConstraints
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import java.util.UUID

/**
 * Phase B of goals: the Director as its own source. The attack tests build every operation type in code, as the Director, and aim it at what the Director did not
 * write (the owner's items in and out of scope, containers, goals, another goal's Director items); the sweep ends by comparing the whole graph before and after.
 * One test removes the guard and shows the same attacks land, which is what makes the others mean something.
 */
class DirectorSourceTest {
    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var messages: ArcadeDirectorMessageStore
    private lateinit var applier: HorizonOperationApplier
    private lateinit var writer: DirectorWriter
    private lateinit var path: String
    private lateinit var user: String
    private val zone = ZoneOffset.UTC
    private var seq = 10L
    private var now = 1_800_000_000_000L

    private lateinit var williams: ContainerRecord
    private lateinit var meridian: ContainerRecord
    private lateinit var ownerTaskIn: ItemRecord
    private lateinit var ownerTaskOut: ItemRecord
    private lateinit var ownerNoteIn: ItemRecord
    private lateinit var theGoal: ItemRecord
    private lateinit var otherGoal: ItemRecord
    private lateinit var proposedGoal: ItemRecord

    @BeforeEach
    fun setUp() {
        path = "./data/test-director-source-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        structure = ArcadeHorizonStructureStore(db)
        messages = ArcadeDirectorMessageStore(db, clock = { now })
        applier = HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), directorMessages = messages)
        writer = DirectorWriter(structure, applier, messages = messages)
        user = "director-source-${UUID.randomUUID()}@example.test"
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "d"); set("email", user)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        runBlocking {
            williams = structure.createContainer(user, 1, ContainerKind.PROJECT, "Williams")!!
            meridian = structure.createContainer(user, 1, ContainerKind.PROJECT, "Meridian")!!
            ownerTaskIn = structure.createItem(user, 1, ItemKind.TASK, "Call Williams about the invoice", dueAt = 1_800_100_000_000L, projectUid = williams.uid, opKey = "owner-key-1")!!
            ownerTaskOut = structure.createItem(user, 1, ItemKind.TASK, "Renew the Meridian contract", projectUid = meridian.uid)!!
            ownerNoteIn = structure.createItem(user, 1, ItemKind.FACT, "Williams prefers email", projectUid = williams.uid)!!
        }
        theGoal = runningGoal("Review the open Williams items")
        otherGoal = runningGoal("Tag the imported Williams projects")
        proposedGoal = runBlocking { structure.createItem(user, 1, ItemKind.GOAL, "A goal nobody approved", projectUid = williams.uid)!! }
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(path).deleteRecursively()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun runningGoal(text: String, project: ContainerRecord? = williams): ItemRecord = runBlocking {
        val g = structure.createItem(user, 1, ItemKind.GOAL, text, projectUid = project?.uid, extras = ItemExtras(doneWhen = "Every item has a proposal"))!!
        assertEquals(GoalApproval.APPROVED, structure.approveGoal(user, 2, g.uid, "yes, go ahead"))
        assertTrue(structure.setState(user, 3, g.uid, WorkState.ACTIVE))
        structure.getItem(user, g.uid)!!
    }

    private fun director(vararg ops: HorizonOperation, goal: ItemRecord = theGoal, cycle: Int = 1): List<AppliedOperation> =
        runBlocking { writer.apply(user, goal.uid, cycle, ++seq, ops.toList(), zone) }

    private fun ownerSend(vararg ops: HorizonOperation): List<AppliedOperation> =
        runBlocking { applier.apply(user, ++seq, "owner-${UUID.randomUUID()}", ops.toList(), zone) }

    private fun stored(uid: String): ItemRecord = runBlocking { structure.getItem(user, uid)!! }
    private fun changes(uid: String) = ChangeLog(dbManager.getDatabase()).forTarget(user, uid)

    private fun count(type: String): Long = dbManager.getDatabase().query("sql", "SELECT count(*) AS n FROM $type").use { it.next().getProperty<Number>("n").toLong() }
    private fun countWhere(type: String, where: String, vararg params: Pair<String, Any>): Long =
        dbManager.getDatabase().query("sql", "SELECT count(*) AS n FROM $type WHERE $where", params.toMap()).use { it.next().getProperty<Number>("n").toLong() }

    private fun counts(): Map<String, Long> =
        listOf("Phrase", "Source", "ChangeRecord", "DirectorMessage", "RELATED_TO", "PART_OF", "ABOUT", "ASSERTS", "FOCUSES_ON", "HAS_CHANGE", "Concept", "Project", "Domain", "TRUSTS")
            .associateWith { count(it) }

    private data class Snap(val items: Map<String, ItemRecord?>, val containers: Map<String, ContainerRecord?>, val counts: Map<String, Long>)

    private fun snap(vararg itemUids: String): Snap = Snap(
        itemUids.toList().associateWith { runBlocking { structure.getItem(user, it) } },
        listOf(williams.uid, meridian.uid).associateWith { runBlocking { structure.getContainer(user, it) } },
        counts(),
    )

    private fun supersedes(newer: String, older: String): Long =
        countWhere("RELATED_TO", "relationType = 'supersedes' AND @out.uid = :a AND @in.uid = :b", "a" to newer, "b" to older)

    // ── Its own source, stamped on everything ────────────────────────────────

    @Test
    fun `what the Director writes is asserted by its own source and stamped with the goal and the cycle`() {
        val made = director(RecordItem(ItemKind.TASK, "Draft the review of the open items", title = "Draft the review"), cycle = 3).single()
        assertTrue(made.applied, made.summary)
        val item = stored(made.resultUid!!)
        assertEquals(theGoal.uid, item.directorGoalUid)
        assertEquals(3, item.directorCycle)
        assertEquals(williams.uid, item.projectUid, "filed under the goal's project, which the Director did not have to name")
        assertFalse(item.channelSourced)

        val row = dbManager.getDatabase().query("sql", "SELECT @out.type AS t, scores, directorGoalUid, directorCycle FROM ASSERTS WHERE @in.uid = :u", mapOf("u" to item.uid)).use { it.next().toMap() }
        assertEquals(DIRECTOR_SOURCE_TYPE, row["t"], "asserted by the Director's Source, not the owner's conversation")
        assertEquals("[]", row["scores"], "the Director's writing carries no trust from the owner; only their adoption would")
        assertEquals(theGoal.uid, row["directorGoalUid"])
        assertEquals(3, (row["directorCycle"] as Number).toInt())

        // An owner item is not stamped, and is asserted by the conversation source.
        assertNull(stored(ownerTaskIn.uid).directorGoalUid)
        val ownerRow = dbManager.getDatabase().query("sql", "SELECT @out.type AS t FROM ASSERTS WHERE @in.uid = :u", mapOf("u" to ownerTaskIn.uid)).use { it.next().toMap() }
        assertNotEquals(DIRECTOR_SOURCE_TYPE, ownerRow["t"])
    }

    @Test
    fun `change records the Director makes name the Director, the goal and the cycle as the actor`() {
        val uid = director(RecordItem(ItemKind.TASK, "Draft the review of the open items", title = "Draft the review"), cycle = 2).single().resultUid!!
        assertTrue(director(SetState(uid, WorkState.WAITING, "q", waitingOn = "Sam"), cycle = 2).single().applied)
        assertTrue(director(ResolveItem(uid, "q"), cycle = 2).single().applied)
        val actors = changes(uid).map { it.actor }.toSet()
        assertEquals(setOf("director:${theGoal.uid}:2"), actors)
        assertTrue(changes(uid).isNotEmpty())
    }

    @Test
    fun `a link the Director writes is stamped, and a replaced link target is not touched`() {
        val note = director(RecordItem(ItemKind.FACT, "Williams Ltd and Williams & Co are one client")).single().resultUid!!
        val linked = director(LinkItems(note, ownerTaskIn.uid, "q")).single()
        assertTrue(linked.applied, linked.summary)
        assertEquals(1L, countWhere("RELATED_TO", "relationType = 'relevant_to' AND directorGoalUid = :g AND directorCycle = 1", "g" to theGoal.uid))
        assertEquals(1L, countWhere("RELATED_TO", "@out.uid = :a AND @in.uid = :b", "a" to note, "b" to ownerTaskIn.uid))
    }

    @Test
    fun `the store keeps the Director's source and the Director's stamp together`() {
        // The Director's source without a stamp: refused, so no owner path can write as the Director.
        assertNull(runBlocking { structure.createItem(user, 1, ItemKind.TASK, "Forged", sourceType = DIRECTOR_SOURCE_TYPE) })
        // A stamp with another source: refused, so a stamp cannot dress up something else.
        val stamped = runBlocking { withContext(DirectorContext(DirectorStamp(theGoal.uid, 1))) { structure.createItem(user, 1, ItemKind.TASK, "Dressed up", sourceType = DatabaseEngramClient.SOURCE_TYPE) } }
        assertNull(stamped)
        assertEquals(0L, countWhere("Phrase", "text = 'Forged' OR text = 'Dressed up'"))
    }

    @Test
    fun `the owner's own paths do not stamp, and do not fold into the Director's items`() {
        val dirUid = director(RecordItem(ItemKind.TASK, "Draft the review of open items", title = "Draft the review")).single().resultUid!!
        // The owner saying the same thing is recorded as theirs, never as a duplicate of the Director's item.
        val said = ownerSend(RecordItem(ItemKind.TASK, "Draft the review of open items", title = "Draft the review")).single()
        assertTrue(said.applied, said.summary)
        assertNull(said.existingUid)
        assertNotEquals(dirUid, said.resultUid)
        assertNull(stored(said.resultUid!!).directorGoalUid)
        // The owner adopts, corrects or drops the Director's items with the usual verbs.
        assertTrue(ownerSend(ResolveItem(dirUid, "q")).single().applied)
        assertEquals(WorkState.DONE, stored(dirUid).workState)
        assertEquals(listOf(user), changes(dirUid).map { it.actor }.distinct(), "the owner's change is recorded as the owner's")
        val corrected = ownerSend(CorrectItem(dirUid, "q", title = "Draft the review, owner's version")).single()
        assertTrue(corrected.applied, corrected.summary)
        assertNull(stored(corrected.resultUid!!).directorGoalUid, "a correction by the owner is the owner's item")
    }

    // ── The write guard: every operation, as the Director ────────────────────

    @Test
    fun `every operation type is in the attack list`() {
        val source = File("src/main/kotlin/app/alfrd/engram/cognitive/pipeline/horizon/HorizonOperations.kt").readText()
        val declared = source.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            if (block.contains(": HorizonOperation {") || block.contains(": HorizonOperation\n")) Regex("data class (\\w+)").find(block)?.groupValues?.get(1) else null
        }.toSet()
        assertTrue(declared.size >= 15, "found the operation types: $declared")
        val attacked = attackOps(dirItem = "d", other = "o").map { it::class.simpleName!! }.toSet()
        assertEquals(declared - attacked, emptySet<String>(), "an operation type has no attack in the list")
    }

    /** Every operation type, aimed at the given targets. Built in code, past the parser. */
    private fun attackOps(dirItem: String, other: String): List<HorizonOperation> = buildList {
        val notOwn = listOf(ownerTaskIn.uid, ownerTaskOut.uid, ownerNoteIn.uid, other, theGoal.uid, proposedGoal.uid, williams.uid, meridian.uid)
        for (t in notOwn) {
            add(ResolveItem(t, "q")); add(ReopenItem(t, "q")); add(ConfirmItem(t, "q"))
            WorkState.entries.forEach { add(SetState(t, it, "q", waitingOn = "Sam")) }
            add(CorrectItem(t, "q", dueAt = 5_000_000_000_000L, title = "Rewritten"))
            add(FileItem(t, williams.uid)); add(FileItem(t, meridian.uid))
            listOf(AttentionDirective.PINNED, AttentionDirective.SUPPRESSED, null).forEach { add(SetAttention(t, it, "q")) }
            add(LinkItems(t, dirItem, "q"))
            add(SetFocus(t, "q"))
        }
        // From the Director's own item to what is outside the scope.
        add(LinkItems(dirItem, ownerTaskOut.uid, "q")); add(LinkItems(dirItem, meridian.uid, "q"))
        add(FileItem(dirItem, meridian.uid))
        add(SetAttention(dirItem, AttentionDirective.PINNED, "q"))
        // New writes outside the scope, or of the wrong kind.
        add(RecordItem(ItemKind.TASK, "Planted in Meridian", projectUid = meridian.uid))
        add(RecordItem(ItemKind.TASK, "Planted by name", projectName = "Meridian"))
        add(RecordItem(ItemKind.GOAL, "A goal of the Director's own", title = "A goal of the Director's own"))
        add(RecordItem(ItemKind.TASK, "From a chunk", chunkUid = "some-chunk"))
        add(CreateContainer(ContainerKind.PROJECT, "A new project", true, null, "q"))
        add(CreateContainer(ContainerKind.DOMAIN, "A new area", true, null, "q"))
        // Not the Director's to do at all.
        add(ReleaseFocus("q"))
        add(ApproveGoal(proposedGoal.uid, "q")); add(ApproveGoal(theGoal.uid, "q"))
        add(EditGoal(proposedGoal.uid, "q", doneWhen = "Something easier")); add(EditGoal(theGoal.uid, "q", text = "A different goal"))
        add(HoldForChoice("hold this one"))
        add(RetractCycle(theGoal.uid, 1, "q"))
    }

    @Test
    fun `every operation, aimed at what the Director did not write, is refused with a reason and changes nothing`() {
        val dirItem = director(RecordItem(ItemKind.TASK, "Draft the review", title = "Draft the review")).single().resultUid!!
        val otherDirItem = director(RecordItem(ItemKind.TASK, "Other goal's own work", title = "Other goal's own work"), goal = otherGoal).single().resultUid!!
        val watched = arrayOf(ownerTaskIn.uid, ownerTaskOut.uid, ownerNoteIn.uid, theGoal.uid, otherGoal.uid, proposedGoal.uid, dirItem, otherDirItem)
        val before = snap(*watched)

        val attacks = attackOps(dirItem, otherDirItem)
        assertTrue(attacks.size > 100, "the sweep is wide: ${attacks.size} operations")
        for (op in attacks) {
            val result = director(op).single()
            assertFalse(result.applied, "must be refused: $op")
            assertTrue(result.summary.startsWith("Refused: ") && result.summary.length > "Refused: ".length + 15, "says why: ${result.summary} ($op)")
        }
        assertEquals(before, snap(*watched), "nothing in the graph moved: no item, no container, no edge, no change record, no source")
    }

    @Test
    fun `the refusals say which rule`() {
        val dirItem = director(RecordItem(ItemKind.TASK, "Draft the review", title = "Draft the review")).single().resultUid!!
        val otherDirItem = director(RecordItem(ItemKind.TASK, "Other goal's own work", title = "Other goal's own work"), goal = otherGoal).single().resultUid!!
        fun why(op: HorizonOperation) = director(op).single().summary
        assertTrue(why(ResolveItem(ownerTaskIn.uid, "q")).contains("not written by the Director") && why(ResolveItem(ownerTaskIn.uid, "q")).contains("suggest the change in a note"))
        assertTrue(why(ResolveItem(otherDirItem, "q")).contains("another goal"))
        assertTrue(why(ResolveItem(williams.uid, "q")).contains("projects or areas"))
        assertTrue(why(RecordItem(ItemKind.TASK, "x", projectUid = meridian.uid)).contains("outside the goal's scope"))
        assertTrue(why(LinkItems(dirItem, ownerTaskOut.uid, "q")).contains("outside the goal's scope"))
        assertTrue(why(FileItem(dirItem, meridian.uid)).contains("outside the goal's scope"))
        assertTrue(why(RecordItem(ItemKind.GOAL, "x", title = "x")).contains("does not propose goals"))
        assertTrue(why(ApproveGoal(proposedGoal.uid, "q")).contains("only the owner approves"))
        assertTrue(why(RetractCycle(theGoal.uid, 1, "q")).contains("only the owner retracts"))
        assertTrue(why(SetFocus(williams.uid, "q")).contains("owner's instrument"))
    }

    @Test
    fun `the Director cannot fold a write into the owner's item by key or by similar words`() {
        val before = snap(ownerTaskIn.uid)
        // The owner's item key, and the owner's own words with another date: both would have been a write to the owner's item.
        val keyed = director(RecordItem(ItemKind.TASK, "Call Williams about the invoice", title = "Call Williams about the invoice", itemKey = "owner-key-1", dueAt = 1_900_000_000_000L)).single()
        assertTrue(keyed.applied, keyed.summary)
        assertNotEquals(ownerTaskIn.uid, keyed.resultUid)
        assertNull(keyed.existingUid)
        val stamped = stored(keyed.resultUid!!)
        assertEquals(theGoal.uid, stamped.directorGoalUid)
        assertEquals(before.items[ownerTaskIn.uid], stored(ownerTaskIn.uid), "the owner's item, its date and its evidence are exactly as they were")
        assertEquals(before.counts["ChangeRecord"], snap().counts["ChangeRecord"], "no change was recorded for the owner's item")
        // Saying the same thing again as the Director matches only the Director's own item.
        val again = director(RecordItem(ItemKind.TASK, "Call Williams about the invoice", title = "Call Williams about the invoice")).single()
        assertTrue(again.applied)
        assertEquals(stamped.uid, again.existingUid, "its own item again is the usual 'already on the list'")
    }

    @Test
    fun `without the guard the same attacks land, which is what the guard is for`() {
        val open = object : DirectorGuard(structure) {
            override suspend fun refusal(userEmail: String, scope: DirectorScope.Open, op: HorizonOperation): String? = null
        }
        val unguarded = DirectorWriter(structure, applier, open, messages)
        val results = runBlocking {
            unguarded.apply(user, theGoal.uid, 1, ++seq, listOf(ResolveItem(ownerTaskIn.uid, "q"), FileItem(ownerTaskOut.uid, williams.uid), ApproveGoal(proposedGoal.uid, "yes")), zone)
        }
        assertTrue(results.all { it.applied }, results.joinToString { it.summary })
        assertEquals(WorkState.DONE, stored(ownerTaskIn.uid).workState, "the owner's task was resolved by the Director")
        assertEquals(williams.uid, stored(ownerTaskOut.uid).projectUid, "the owner's task was re-filed by the Director")
        assertEquals("approved", stored(proposedGoal.uid).approval, "the Director approved a goal for the owner")
        // With the guard back, the same three are refused (the attack sweep above covers them for every target); nothing here is the guard's absence being invisible.
        val guarded = director(ResolveItem(ownerTaskOut.uid, "q"), FileItem(ownerTaskIn.uid, meridian.uid))
        assertTrue(guarded.none { it.applied })
    }

    // ── The Director's own items behave like any other item ──────────────────

    @Test
    fun `the Director's own items take every state change, a correction and a link, like any other item`() {
        val uid = director(RecordItem(ItemKind.TASK, "Draft the review of the open items", title = "Draft the review", dueAt = 1_800_500_000_000L)).single().resultUid!!
        assertEquals(WorkState.OPEN, stored(uid).workState)
        assertTrue(director(SetState(uid, WorkState.WAITING, "q", waitingOn = "Sam")).single().applied)
        assertEquals(WorkState.WAITING, stored(uid).workState)
        assertTrue(director(ResolveItem(uid, "q")).single().applied)
        assertEquals(WorkState.DONE, stored(uid).workState)
        assertTrue(director(ReopenItem(uid, "q")).single().applied)
        assertEquals(WorkState.OPEN, stored(uid).workState)
        assertTrue(director(ConfirmItem(uid, "q")).single().applied)
        assertTrue(director(FileItem(uid, williams.uid)).single().applied, "re-filed within the scope")
        assertTrue(director(SetAttention(uid, AttentionDirective.SUPPRESSED, "q")).single().applied)
        assertTrue(director(SetAttention(uid, null, "q")).single().applied)

        val corrected = director(CorrectItem(uid, "q", title = "Draft the review, second pass")).single()
        assertTrue(corrected.applied, corrected.summary)
        val newUid = corrected.resultUid!!
        assertEquals(theGoal.uid, stored(newUid).directorGoalUid, "the correction is the Director's too")
        assertEquals(WorkState.DONE, stored(uid).workState, "the old one is closed, as a correction always does")
        assertEquals(1L, supersedes(newUid, uid))

        val linked = director(LinkItems(newUid, ownerTaskIn.uid, "q"))
        assertTrue(linked.single().applied, "to an item filed in the scope")
        val own = director(LinkItems(newUid, uid, "q"))
        assertTrue(own.single().applied, "to one of its own")
    }

    @Test
    fun `a state change is suggested as a note and the item itself is not touched`() {
        val before = snap(ownerTaskIn.uid)
        val results = runBlocking { writer.suggestStateChange(user, theGoal.uid, 1, ++seq, ownerTaskIn.uid, WorkState.DONE, "the invoice was paid on 3 November", zone) }
        assertEquals(2, results.size)
        assertTrue(results.all { it.applied }, results.joinToString { it.summary })
        val note = stored(results[0].resultUid!!)
        assertEquals(ItemKind.FACT, note.kind)
        assertTrue(note.text.startsWith("Suggestion: mark \"Call Williams about the invoice\" done"), note.text)
        assertEquals(theGoal.uid, note.directorGoalUid)
        assertEquals(1L, countWhere("RELATED_TO", "@out.uid = :a AND @in.uid = :b", "a" to note.uid, "b" to ownerTaskIn.uid))
        assertEquals(before.items[ownerTaskIn.uid], stored(ownerTaskIn.uid), "the suggestion is a note; the task is as it was")

        // About something outside the scope: the note is still written (in the scope), the link to the outside is refused.
        val outside = runBlocking { writer.suggestStateChange(user, theGoal.uid, 1, ++seq, ownerTaskOut.uid, WorkState.DROPPED, "the contract was cancelled", zone) }
        assertTrue(outside[0].applied)
        assertFalse(outside[1].applied)
        assertEquals(WorkState.OPEN, stored(ownerTaskOut.uid).workState)
    }

    @Test
    fun `its notes, links and the ABOUT names it adds are its own, and show as the Director's in the Horizon`() {
        val made = director(RecordItem(ItemKind.TASK, "Compile the list of closed Williams invoices", title = "Compile the list of closed Williams invoices", about = listOf("Williams"))).single()
        assertTrue(made.applied, made.summary)
        val cycle = seq
        val horizon = runBlocking {
            (ArcadeHorizonAssembler(dbManager.getDatabase()).assemble(user, cycle, budget = HorizonBudget(12, 0, false)) as AssembleOutcome.Assembled).horizon
        }
        val shown = horizon.items.firstOrNull { it.text.text.contains("Compile the list of closed") }
        assertTrue(shown != null, "the Director's item is in view: ${horizon.items.map { it.text.text }}")
        assertEquals(ProvenanceKind.DIRECTOR, shown!!.provenance)
        assertEquals(DirectorNote(theGoal.uid, 1), shown.director)
        assertEquals(1L, countWhere("ABOUT", "directorGoalUid = :g", "g" to theGoal.uid))
    }

    // ── The goal gates the Director ──────────────────────────────────────────

    @Test
    fun `the Director writes only for a goal that is approved and running, in a live project`() {
        val op = RecordItem(ItemKind.TASK, "Some work", title = "Some work")
        fun refusedFor(goal: ItemRecord, expect: String) {
            val r = director(op, goal = goal).single()
            assertFalse(r.applied, goal.text)
            assertTrue(r.summary.contains(expect), "${goal.text}: ${r.summary}")
        }
        refusedFor(proposedGoal, "not approved")
        val approvedNotRunning = runBlocking { structure.createItem(user, 1, ItemKind.GOAL, "Approved, not started", projectUid = williams.uid)!! }
        runBlocking { structure.approveGoal(user, 2, approvedNotRunning.uid, "yes") }
        refusedFor(stored(approvedNotRunning.uid), "not running")
        val paused = runningGoal("Paused goal")
        runBlocking { structure.setState(user, 4, paused.uid, WorkState.WAITING) }
        refusedFor(stored(paused.uid), "paused")
        val stopped = runningGoal("Stopped goal")
        runBlocking { structure.setState(user, 4, stopped.uid, WorkState.DROPPED) }
        refusedFor(stored(stopped.uid), "done or stopped")
        val noProject = runningGoal("A goal with no project", project = null)
        refusedFor(noProject, "scope is empty")
        val doomed = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, "Finished client")!! }
        val inDoomed = runningGoal("Goal in a project that ends", project = doomed)
        runBlocking { structure.resolve(user, 5, doomed.uid, true) }
        refusedFor(inDoomed, "no longer active")
        // Not a goal, and not there.
        assertTrue(director(op, goal = ownerTaskIn).single().summary.contains("not a goal"))
        assertTrue(runBlocking { writer.apply(user, "no-such-goal", 1, ++seq, listOf(op), zone) }.single().summary.contains("does not exist"))
        assertEquals(0L, countWhere("Phrase", "text = 'Some work'"))
    }

    @Test
    fun `the scope is the project the goal was approved under, and it does not move afterwards`() {
        // A frozen goal is not re-filed (Phase A), so what the Director may write to cannot be moved by the owner's re-filing either.
        val refiled = ownerSend(FileItem(theGoal.uid, meridian.uid)).single()
        assertFalse(refiled.applied)
        val made = director(RecordItem(ItemKind.TASK, "Work inside the scope", title = "Work inside the scope")).single()
        assertEquals(williams.uid, stored(made.resultUid!!).projectUid)
    }

    // ── Replacement in one step ──────────────────────────────────────────────

    private fun replaceOp(old: ItemRecord, text: String = "Review every open Williams item and propose a state") =
        RecordItem(ItemKind.GOAL, text, title = text, doneWhen = "Every open item has a proposal", replacesUid = old.uid)

    @Test
    fun `a replacement for a stopped goal carries the link to it in the same step`() {
        val old = runningGoal("Review the open items, the first try")
        assertTrue(ownerSend(SetState(old.uid, WorkState.DROPPED, "stop it")).single().applied)
        val before = count("Goal")
        val result = ownerSend(replaceOp(stored(old.uid))).single()
        assertTrue(result.applied, result.summary)
        assertTrue(result.summary.contains("replaces the stopped goal"), result.summary)
        val fresh = stored(result.resultUid!!)
        assertEquals(before + 1, count("Goal"))
        assertEquals("proposed", fresh.approval, "a replacement is a new proposal; the owner approves it like any goal")
        assertEquals(williams.uid, fresh.projectUid, "it serves the same project unless another is named")
        assertEquals(1L, supersedes(fresh.uid, old.uid), "the link is there, written with the goal")
        assertEquals(WorkState.DROPPED, stored(old.uid).workState, "the old goal is as it was: stopped and final")
    }

    @Test
    fun `only a stopped goal that nothing has replaced can be replaced, and a refusal writes no goal`() {
        val running = runningGoal("Still running")
        val before = count("Goal")
        val edges = count("RELATED_TO")
        for (bad in listOf(running, stored(proposedGoal.uid), ownerTaskIn)) {
            val r = ownerSend(replaceOp(bad)).single()
            assertFalse(r.applied, bad.text)
            assertTrue(r.summary.contains("only a stopped goal can be replaced"), r.summary)
        }
        assertEquals(before, count("Goal"))
        assertEquals(edges, count("RELATED_TO"))

        val old = runningGoal("Replace me once")
        ownerSend(SetState(old.uid, WorkState.DROPPED, "stop it"))
        assertTrue(ownerSend(replaceOp(stored(old.uid))).single().applied)
        val second = ownerSend(replaceOp(stored(old.uid), "A second replacement of the same goal")).single()
        assertFalse(second.applied, "a goal already replaced is not replaced again")
        assertEquals(before + 1, count("Goal"))
    }

    @Test
    fun `the store itself refuses a replacement of anything but a stopped goal, so no caller can forget the link`() {
        val running = runningGoal("Still running too")
        val made = runBlocking { structure.createItem(user, 1, ItemKind.GOAL, "A replacement attempt", projectUid = williams.uid, extras = ItemExtras(replaces = running.uid)) }
        assertNull(made)
        assertEquals(0L, countWhere("Goal", "text = 'A replacement attempt'"))
    }

    private fun snapshotWithGoals(vararg goals: ItemRecord) = ViewSnapshot(
        focusLines = emptyList(), containers = emptyList(),
        items = goals.mapIndexed { i, g -> SnapshotItem(ref = "i${i + 1}", uid = g.uid, kind = ItemKind.GOAL, text = g.text, status = g.status, dueAt = null, projectRef = null, about = emptyList(), attention = null, state = g.workState) },
        zone = zone,
    )

    private fun parse(utterance: String, snapshot: ViewSnapshot, vararg ops: kotlinx.serialization.json.JsonObject) =
        OperationParser.parse(kotlinx.serialization.json.JsonObject(mapOf("ops" to kotlinx.serialization.json.JsonArray(ops.toList()))), utterance, snapshot, zone)

    private fun json(vararg fields: Pair<String, String>) = kotlinx.serialization.json.JsonObject(fields.associate { (k, v) ->
        k to when (v.toIntOrNull()) { null -> kotlinx.serialization.json.JsonPrimitive(v); else -> kotlinx.serialization.json.JsonPrimitive(v.toInt()) }
    })

    @Test
    fun `the parser takes replaces only on a goal, naming a stopped goal`() {
        val stopped = runningGoal("Old goal to stop").also { runBlocking { structure.setState(user, 4, it.uid, WorkState.DROPPED) } }
        val running = runningGoal("Running goal")
        val snapshot = snapshotWithGoals(stored(stopped.uid), stored(running.uid))
        val said = "Replace the old goal with this one: review all the Williams items."
        val quote = "review all the Williams items"
        fun record(kind: String, replaces: String) = json("verb" to "record_item", "kind" to kind, "quote" to quote, "title" to "Review all the Williams items", "replaces" to replaces)

        val ok = parse(said, snapshot, record("goal", "i1"))
        assertEquals(emptyList<OperationRejection>(), ok.rejections)
        assertEquals(stopped.uid, (ok.operations.single() as RecordItem).replacesUid)
        assertEquals("goal_not_stopped", parse(said, snapshot, record("goal", "i2")).rejections.single().reason)
        assertEquals("not_for_a_goal_field", parse(said, snapshot, record("task", "i1")).rejections.single().reason)
        assertEquals("unknown_ref", parse(said, snapshot, record("goal", "i9")).rejections.single().reason)
    }

    // ── Messages from the Director, as nodes ─────────────────────────────────

    private fun send(kind: DirectorMessageKind, text: String, at: Long, cycle: Int = 1, answers: String? = null, goal: ItemRecord = theGoal): DirectorMessage? {
        now = at
        return runBlocking { writer.send(user, goal.uid, cycle, kind, text, answers) }
    }

    @Test
    fun `a message is a node - who sent it, when, which goal and cycle, and what it answers, so a thread can be walked`() {
        val q = send(DirectorMessageKind.QUESTION, "Is Williams Ltd the same client as Williams & Co?", 1_000)!!
        assertEquals("director", q.sender)
        assertEquals(theGoal.uid, q.goalUid)
        assertEquals(1, q.cycle)
        assertEquals(1_000L, q.createdAt)
        val a = send(DirectorMessageKind.PROGRESS, "Treating them as one client, as the owner's note says.", 2_000, answers = q.uid)!!
        assertEquals(q.uid, a.answersUid)
        assertEquals(2L, countWhere("MESSAGE_FOR", "@in.uid = :g", "g" to theGoal.uid))
        assertEquals(1L, countWhere("ANSWERS", "@out.uid = :a AND @in.uid = :q", "a" to a.uid, "q" to q.uid))
        assertEquals(listOf(q.uid, a.uid), runBlocking { messages.thread(user, theGoal.uid) }.map { it.uid })
        // It answers an item as well as a message.
        assertTrue(send(DirectorMessageKind.FINISHED, "Done with the invoice call.", 3_000, answers = ownerTaskIn.uid) != null)
        assertEquals(1L, countWhere("ANSWERS", "@in.uid = :t", "t" to ownerTaskIn.uid))
    }

    @Test
    fun `a message needs an approved goal, text, and an answer target that exists`() {
        assertNull(send(DirectorMessageKind.QUESTION, "Asked for a goal nobody approved", 1, goal = proposedGoal))
        assertNull(send(DirectorMessageKind.QUESTION, "   ", 1))
        assertNull(send(DirectorMessageKind.QUESTION, "Answers nothing that exists", 1, answers = "no-such-node"))
        assertNull(runBlocking { writer.send(user, ownerTaskIn.uid, 1, DirectorMessageKind.QUESTION, "A task is not a goal") })
        assertEquals(0L, count("DirectorMessage"))
        // A stopped goal can still be spoken for: a finished or stopped goal is exactly when a message is wanted.
        val stopped = runningGoal("About to stop")
        runBlocking { structure.setState(user, 4, stopped.uid, WorkState.DROPPED) }
        assertTrue(send(DirectorMessageKind.FINISHED, "Stopped before it started.", 5, goal = stored(stopped.uid)) != null)
        // Text is bounded.
        assertEquals(ArcadeDirectorMessageStore.MAX_TEXT, send(DirectorMessageKind.FAILURE, "x".repeat(5_000), 6)!!.text.length)
    }

    @Test
    fun `questions, failures and finished goals are shown - routine progress is quiet by default`() {
        val day = 24L * 3_600_000L
        val t = 1_000_000L
        val q = send(DirectorMessageKind.QUESTION, "Which Williams projects are closed?", t)!!
        val f = send(DirectorMessageKind.FAILURE, "The model on 8083 did not answer.", t + 10)!!
        val p = send(DirectorMessageKind.PROGRESS, "Read 12 of 40 items.", t + 20)!!
        val d = send(DirectorMessageKind.FINISHED, "Every open item has a proposal.", t + 30)!!
        fun visible(since: Long, nowAt: Long, progress: Boolean = false) = runBlocking { messages.visible(user, since, nowAt, progress, 14, 100) }.map { it.uid }
        assertEquals(listOf(q.uid, f.uid, d.uid), visible(t - 1, t + 40))
        assertEquals(listOf(q.uid, f.uid, p.uid, d.uid), visible(t - 1, t + 40, progress = true))

        // Read once: only what is newer than the marker, plus a question nobody answered.
        assertEquals(listOf(q.uid), visible(t + 100, t + 200), "an unanswered question stays")
        // Answered: gone once the marker has passed it.
        send(DirectorMessageKind.PROGRESS, "Closed ones are listed in the note.", t + 300, answers = q.uid)
        assertEquals(emptyList<String>(), visible(t + 400, t + 500))
        // A question nobody answers stops being listed after the open-question window.
        val q2 = send(DirectorMessageKind.QUESTION, "Another question", t + 600)!!
        assertEquals(listOf(q2.uid), visible(t + 700, t + 10 * day))
        assertEquals(emptyList<String>(), visible(t + 700, t + 20 * day))
    }

    @Test
    fun `the channel reads a short From the Director section once, in order, and nothing when there is nothing`() {
        val t = 5_000_000L
        val policy = HorizonPolicy(directorMessageLines = 2)
        val digest = DirectorMessageDigest(messages, structure, policy, zone)
        assertNull(runBlocking { digest.section(user, "claude", t) }, "nothing to say renders nothing")

        send(DirectorMessageKind.FAILURE, "The first failure", t + 1)
        send(DirectorMessageKind.FINISHED, "The second thing, finished", t + 2)
        send(DirectorMessageKind.FAILURE, "The third failure", t + 3)
        send(DirectorMessageKind.PROGRESS, "Routine progress that stays quiet", t + 4)
        val first = runBlocking { digest.section(user, "claude", t + 10) }!!
        assertTrue(first.startsWith("From the Director"), first)
        assertTrue(first.contains("The first failure") && first.contains("The second thing, finished"), first)
        assertTrue(first.contains("on \"Review the open Williams items\"") && first.contains("cycle 1"), first)
        assertFalse(first.contains("The third failure") || first.contains("Routine progress"), first)
        assertTrue(first.contains("1 more waiting"), first)
        val second = runBlocking { digest.section(user, "claude", t + 11) }!!
        assertTrue(second.contains("The third failure") && !second.contains("The first failure"), second)
        assertNull(runBlocking { digest.section(user, "claude", t + 12) }, "read once, not repeated")
        // Another channel has its own marker.
        assertTrue(runBlocking { digest.section(user, "another-channel", t + 12) }!!.contains("The first failure"))
        // Progress shows when the policy says so.
        val loud = DirectorMessageDigest(messages, structure, HorizonPolicy(directorMessageLines = 10, directorShowProgress = true), zone)
        assertTrue(runBlocking { loud.section(user, "claude", t + 20) }!!.contains("Routine progress that stays quiet"))
    }

    @Test
    fun `a Director message is never an item`() {
        val phrases = count("Phrase")
        send(DirectorMessageKind.QUESTION, "Is this an item?", 7)
        assertEquals(phrases, count("Phrase"))
        assertEquals(1L, count("DirectorMessage"))
    }

    // ── Removal as a set ─────────────────────────────────────────────────────

    @Test
    fun `the owner retracts one goal cycle as a set - dropped with change records, nothing deleted`() {
        val t1 = director(RecordItem(ItemKind.TASK, "Cycle one task", title = "Cycle one task"), cycle = 1).single().resultUid!!
        val n1 = director(RecordItem(ItemKind.FACT, "Cycle one note about Williams"), cycle = 1).single().resultUid!!
        val q1 = director(RecordItem(ItemKind.QUESTION, "Which Williams items are closed?"), cycle = 1).single().resultUid!!
        assertTrue(director(LinkItems(n1, ownerTaskIn.uid, "q"), cycle = 1).single().applied)
        val t2 = director(RecordItem(ItemKind.TASK, "Cycle two task", title = "Cycle two task"), cycle = 2).single().resultUid!!
        val m1 = send(DirectorMessageKind.QUESTION, "Cycle one question for the channel", 10, cycle = 1)!!
        val m2 = send(DirectorMessageKind.PROGRESS, "Cycle one progress", 11, cycle = 1)!!
        val m3 = send(DirectorMessageKind.FINISHED, "Cycle two finished", 12, cycle = 2)!!
        val owner = arrayOf(ownerTaskIn.uid, ownerTaskOut.uid, ownerNoteIn.uid, theGoal.uid)
        val ownerBefore = snap(*owner)
        val phrases = count("Phrase"); val msgs = count("DirectorMessage"); val links = count("RELATED_TO"); val recordsBefore = count("ChangeRecord")

        val result = ownerSend(RetractCycle(theGoal.uid, 1, "take back what cycle one wrote")).single()
        assertTrue(result.applied, result.summary)
        assertTrue(result.summary.contains("3 item(s) dropped") && result.summary.contains("2 message(s) dropped") && result.summary.contains("Nothing was deleted"), result.summary)

        // Everything cycle 1 wrote is dropped; cycle 2 and the owner's things are as they were.
        for (uid in listOf(t1, n1, q1)) assertEquals(WorkState.DROPPED, stored(uid).workState, uid)
        assertEquals(WorkState.OPEN, stored(t2).workState)
        val messagesNow = runBlocking { messages.thread(user, theGoal.uid) }.associateBy { it.uid }
        assertTrue(messagesNow.getValue(m1.uid).retracted && messagesNow.getValue(m2.uid).retracted)
        assertFalse(messagesNow.getValue(m3.uid).retracted)
        assertEquals(ownerBefore.items, snap(*owner).items, "the owner's items are untouched")
        // Never deleted.
        assertEquals(phrases, count("Phrase")); assertEquals(msgs, count("DirectorMessage")); assertEquals(links, count("RELATED_TO"))
        // Each drop has a change record: cause retract_cycle, state to dropped, and the owner as the actor.
        assertTrue(count("ChangeRecord") > recordsBefore)
        for (uid in listOf(t1, n1, q1)) {
            val drop = changes(uid).last { it.field == "state" }
            assertEquals("dropped", drop.newValue, uid)
            assertEquals("retract_cycle", drop.cause)
            assertEquals(user, drop.actor)
        }
        // A retracted message is not shown; the unretracted one is.
        val shown = runBlocking { messages.visible(user, 0L, 100L, true, 14, 50) }.map { it.uid }
        assertEquals(listOf(m3.uid), shown)

        // Idempotent: a second retraction finds everything already dropped and writes nothing new.
        val recordsAfter = count("ChangeRecord")
        val again = ownerSend(RetractCycle(theGoal.uid, 1, "take back what cycle one wrote")).single()
        assertTrue(again.applied && again.summary.contains("3 already dropped"), again.summary)
        assertEquals(recordsAfter, count("ChangeRecord"))
    }

    @Test
    fun `a retracted task and a retracted note leave the Horizon`() {
        val results = director(
            RecordItem(ItemKind.TASK, "Compile the list of closed Williams invoices", title = "Compile the list of closed Williams invoices"),
            RecordItem(ItemKind.FACT, "Williams invoices closed since March are on the list"),
        )
        assertTrue(results.all { it.applied }, results.joinToString { it.summary })
        val cycle = seq
        fun shown(): List<String> = runBlocking {
            (ArcadeHorizonAssembler(dbManager.getDatabase()).assemble(user, cycle, budget = HorizonBudget(12, 0, false)) as AssembleOutcome.Assembled).horizon
        }.items.map { it.text.text }
        assertTrue(shown().any { it.startsWith("Compile the list") } && shown().any { it.startsWith("Williams invoices closed") }, shown().toString())
        assertTrue(ownerSend(RetractCycle(theGoal.uid, 1, "q")).single().applied)
        assertTrue(shown().none { it.startsWith("Compile the list") || it.startsWith("Williams invoices closed") }, shown().toString())
    }

    @Test
    fun `the board marks what the Director wrote`() {
        val soon = System.currentTimeMillis() - 1_000L
        val uid = director(RecordItem(ItemKind.TASK, "Send the Williams review", title = "Send the Williams review", dueAt = soon, dueKind = DueKind.HARD)).single().resultUid!!
        val data = runBlocking { ArcadeBoardSource(dbManager.getDatabase()).read(user, "claude", seq + 1) }!!
        val item = data.projects.flatMap { it.items }.first { it.uid == uid }
        assertTrue(item.byDirector)
        assertFalse(data.projects.flatMap { it.items }.first { it.uid == ownerTaskIn.uid }.byDirector)
        val text = BoardComposer().plan(data).render(emptyMap()).text
        assertTrue(text.contains("Send the Williams review\" [from the Director]") || text.contains("[from the Director]"), text)
    }

    @Test
    fun `retracting needs a goal and a cycle the Director wrote in`() {
        assertFalse(ownerSend(RetractCycle(ownerTaskIn.uid, 1, "q")).single().applied)
        val none = ownerSend(RetractCycle(theGoal.uid, 9, "q")).single()
        assertFalse(none.applied)
        assertTrue(none.summary.contains("Nothing to retract"), none.summary)
        // The Director cannot retract, not even its own cycle.
        director(RecordItem(ItemKind.TASK, "Its own work", title = "Its own work"))
        assertFalse(director(RetractCycle(theGoal.uid, 1, "q")).single().applied)
    }

    @Test
    fun `the parser takes retract_cycle only for a goal, with a cycle, as the owner's words`() {
        val snapshot = snapshotWithGoals(stored(theGoal.uid))
        val said = "Please take back everything cycle 2 wrote."
        val quote = "take back everything cycle 2 wrote"
        fun retract(vararg extra: Pair<String, String>) = json(*(listOf("verb" to "retract_cycle", "item" to "i1", "quote" to quote) + extra).toTypedArray())
        val ok = parse(said, snapshot, retract("cycle" to "2"))
        assertEquals(emptyList<OperationRejection>(), ok.rejections)
        assertEquals(RetractCycle(theGoal.uid, 2, quote), ok.operations.single())
        assertEquals("missing_cycle", parse(said, snapshot, retract()).rejections.single().reason)
        assertEquals("missing_cycle", parse(said, snapshot, retract("cycle" to "0")).rejections.single().reason)
        assertEquals("question_detected", parse("Should I take back everything cycle 2 wrote?", snapshot, retract("cycle" to "2")).rejections.single().reason)
        assertEquals("negation_marker_detected", parse("Do not take back everything cycle 2 wrote.", snapshot, retract("cycle" to "2")).rejections.single().reason)
        val taskSnapshot = ViewSnapshot(emptyList(), emptyList(), listOf(SnapshotItem("i1", ownerTaskIn.uid, ItemKind.TASK, ownerTaskIn.text, "open", null, null, emptyList(), null)), zone = zone)
        assertEquals("not_a_goal", parse(said, taskSnapshot, retract("cycle" to "2")).rejections.single().reason)
    }

    // ── The stamp cannot be moved ────────────────────────────────────────────

    @Test
    fun `with constraints on, the stamp on an item cannot change after it is written`() {
        val db = dbManager.getDatabase()
        val outcome = SchemaConstraints.apply(db)
        for (rule in listOf("readonly:Phrase.directorGoalUid", "readonly:Phrase.directorCycle")) {
            assertTrue(outcome.applied.contains(rule) || outcome.alreadyInPlace.contains(rule), "$rule: $outcome")
        }
        // Written with the stamp, as always, it saves.
        val uid = director(RecordItem(ItemKind.TASK, "Stamped under constraints", title = "Stamped under constraints"), cycle = 4).single().resultUid!!
        assertEquals(4, stored(uid).directorCycle)
        fun move(property: String, value: Any): Boolean = try {
            db.transaction {
                val v = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to uid)).use { it.next().toElement().asVertex() }
                v.modify().set(property, value).save()
            }
            true
        } catch (e: Exception) { false }
        assertFalse(move("directorGoalUid", otherGoal.uid), "an item cannot be moved to another goal")
        assertFalse(move("directorCycle", 9), "an item cannot be moved to another cycle")
        assertEquals(theGoal.uid, stored(uid).directorGoalUid)
        assertEquals(4, stored(uid).directorCycle)
        // The Director's items still behave: a state change under the constraints.
        assertTrue(director(ResolveItem(uid, "q"), cycle = 4).single().applied)
    }
}
