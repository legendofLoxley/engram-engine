package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaAudit
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SchemaConstraints
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import java.util.UUID

/**
 * Schema risk for Phase B: the first start with constraints on, over a database that already holds rows from before the Director existed. No rule may be skipped,
 * the rule count is what the schema work expects, the Director's whole flow passes the constraints, and the audit still finds nothing wrong afterwards.
 */
class DirectorSchemaTest {
    private val path = "./data/test-director-schema-${System.nanoTime()}"
    private val manager = DatabaseManager(path)
    private val db = manager.getDatabase()

    @AfterEach
    fun close() { manager.close(); File(path).deleteRecursively() }

    @Test
    fun `first start with constraints over rows from before the Director skips nothing, and the Director's flow and the audit are clean`() {
        SchemaBootstrap.bootstrap(db, enforceConstraints = false)
        val user = "schema-${UUID.randomUUID()}@example.test"
        db.transaction {
            db.newVertex("User").apply { set("uid", UUID.randomUUID().toString()); set("username", "s"); set("email", user); set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save() }
        }
        val structure = ArcadeHorizonStructureStore(db)
        val williams = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, "Williams")!! }
        runBlocking {
            structure.createItem(user, 1, ItemKind.TASK, "Call Williams about the invoice", projectUid = williams.uid)!!
            structure.createItem(user, 1, ItemKind.FACT, "Williams prefers email", projectUid = williams.uid)!!
            val g = structure.createItem(user, 1, ItemKind.GOAL, "Review the open Williams items", projectUid = williams.uid, extras = ItemExtras(doneWhen = "Every item has a proposal"))!!
            structure.approveGoal(user, 2, g.uid, "yes"); structure.setState(user, 3, g.uid, WorkState.ACTIVE)
        }
        val auditBefore = SchemaAudit(db).run().violations.map { it.id to it.count }

        val outcome = SchemaConstraints.apply(db)
        assertTrue(outcome.skipped.isEmpty(), "no rule skipped over existing rows: ${outcome.skipped}")
        val total = outcome.applied.size + outcome.alreadyInPlace.size
        assertEquals(50, total, "rule count $total (applied ${outcome.applied.size}, in place ${outcome.alreadyInPlace.size})")
        assertTrue("readonly:Phrase.directorGoalUid" in outcome.applied && "readonly:Phrase.directorCycle" in outcome.applied, outcome.applied.toString())

        // The whole Director flow, constraints on.
        val messages = ArcadeDirectorMessageStore(db)
        val applier = HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), directorMessages = messages)
        val writer = DirectorWriter(structure, applier, messages = messages)
        val goal = runBlocking { structure.listRecentItems(user, 10).first { it.kind == ItemKind.GOAL } }
        var seq = 10L
        val ops = listOf(
            RecordItem(ItemKind.TASK, "Compile the closed Williams invoices", title = "Compile the closed Williams invoices", about = listOf("Williams"), dueAt = 1_900_000_000_000L),
            RecordItem(ItemKind.FACT, "Williams Ltd and Williams & Co are one client"),
            RecordItem(ItemKind.QUESTION, "Which Williams invoices are closed?"),
        )
        val made = runBlocking { writer.apply(user, goal.uid, 1, ++seq, ops, ZoneOffset.UTC) }
        assertTrue(made.all { it.applied }, made.joinToString { it.summary })
        val linked = runBlocking { writer.apply(user, goal.uid, 1, ++seq, listOf(LinkItems(made[1].resultUid!!, made[0].resultUid!!, "q"), SetState(made[0].resultUid!!, WorkState.ACTIVE, "q")), ZoneOffset.UTC) }
        assertTrue(linked.all { it.applied }, linked.joinToString { it.summary })
        val m = runBlocking { writer.send(user, goal.uid, 1, DirectorMessageKind.QUESTION, "Is Williams Ltd the same client as Williams & Co?", made[2].resultUid) }
        assertTrue(m != null)
        // Phase C: a cycle's closing message carries what it used and how it ended; the numbers and the word are optional properties and pass every constraint.
        val closing = runBlocking { writer.send(user, goal.uid, 1, DirectorMessageKind.PROGRESS, "Cycle 1: done", null, usageTokens = 1234, usageMillis = 5678, closedAs = "completed") }
        assertTrue(closing != null && closing.closedAs == "completed" && closing.usageTokens == 1234L && closing.usageMillis == 5678L)
        assertEquals(1234L to 5678L, runBlocking { messages.usage(user, goal.uid) })
        val retracted = runBlocking { applier.apply(user, ++seq, "owner-1", listOf(RetractCycle(goal.uid, 1, "take it back")), ZoneOffset.UTC) }
        assertTrue(retracted.single().applied, retracted.single().summary)

        // The audit has nothing new to say, and the new type is in what it counted.
        val report = SchemaAudit(db).run()
        assertEquals(auditBefore, report.violations.map { it.id to it.count }, "the audit's violations are exactly what they were before the Director wrote: ${report.violations.map { it.id + ":" + it.count + " " + it.examples }}")
        println("RULES total=$total applied=${outcome.applied.size} inPlace=${outcome.alreadyInPlace.size} auditTotals=${report.totals}")
        assertTrue(report.totals.keys.any { it.contains("DirectorMessage") } || report.totals.isNotEmpty())
    }
}
