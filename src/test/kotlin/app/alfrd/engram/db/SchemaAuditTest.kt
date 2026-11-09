package app.alfrd.engram.db

import com.arcadedb.database.Database
import com.arcadedb.graph.MutableVertex
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaAuditTest {

    private lateinit var manager: DatabaseManager
    private lateinit var db: Database
    private val owner = "audit-owner@example.test"
    private val other = "audit-other@example.test"

    private fun vertex(type: String, vararg props: Pair<String, Any?>): MutableVertex = db.newVertex(type).apply {
        props.forEach { (k, v) -> if (v != null) set(k, v) }
        save()
    }

    private fun item(type: String, text: String, ownerEmail: String?, state: String? = "open", uid: String = UUID.randomUUID().toString()) =
        vertex(type, "uid" to uid, "text" to text, "ownerEmail" to ownerEmail, "state" to state.takeIf { type != "Phrase" },
            "status" to "open".takeIf { type != "Phrase" }, "createdAt" to 1L, "visibility" to "private")

    @BeforeAll
    fun setUp() {
        manager = DatabaseManager("./data/test-schema-audit-${System.currentTimeMillis()}")
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        db.transaction {
            val u = vertex("User", "uid" to "u1", "email" to owner)
            val u2 = vertex("User", "uid" to "u2", "email" to other)
            val src = vertex("Source", "uid" to "s1", "name" to "conv", "type" to "conversation")
            val srcOther = vertex("Source", "uid" to "s2", "name" to "conv2", "type" to "conversation")
            u.newEdge("TRUSTS", src, false).save()
            u2.newEdge("TRUSTS", srcOther, false).save()
            // clean item
            src.newEdge("ASSERTS", item("Task", "clean task", owner, uid = "clean-task"), false).save()
            // task with no state (legacy) and one with an unknown state
            src.newEdge("ASSERTS", item("Task", "legacy", owner, state = null, uid = "legacy-task"), false).save()
            src.newEdge("ASSERTS", item("Task", "weird state", owner, state = "paused", uid = "weird-task"), false).save()
            // ownerless phrase asserted by the owner's source
            src.newEdge("ASSERTS", item("Phrase", "no owner stamp", null, uid = "ownerless"), false).save()
            // owner stamp disagrees with the chain: stamped other, asserted from owner's source
            src.newEdge("ASSERTS", item("Question", "wrong stamp", other, uid = "wrong-stamp"), false).save()
            // stamped owner, asserted by nobody
            item("Event", "orphan", owner, uid = "orphan-event")
            // a passage is never asserted, by design
            vertex("Phrase", "uid" to "passage-1", "text" to "p", "role" to "passage", "ownerEmail" to owner, "createdAt" to 1L)
            // duplicate uid inside the Phrase family (a Task and a plain Phrase)
            item("Phrase", "dup a", owner, uid = "dup-uid"); item("Task", "dup b", owner, uid = "dup-uid")
            // containers: one fine, one without owner, one project under a missing domain
            val dom = vertex("Domain", "uid" to "d1", "name" to "D", "ownerEmail" to owner, "createdAt" to 1L)
            val proj = vertex("Project", "uid" to "p1", "name" to "P", "ownerEmail" to owner, "createdAt" to 1L)
            vertex("Project", "uid" to "p-noowner", "name" to "P2")
            proj.newEdge("PART_OF", dom, true).save()
            val gone = vertex("Domain", "uid" to "d-gone", "name" to "Gone", "ownerEmail" to owner, "createdAt" to 1L)
            val lost = vertex("Project", "uid" to "p-lost", "name" to "Lost", "ownerEmail" to owner, "createdAt" to 1L)
            lost.newEdge("PART_OF", gone, false).save()
            val goneRid = gone.identity
            db.deleteRecord(db.lookupByRID(goneRid, true))
        }
    }

    @AfterAll
    fun tearDown() {
        manager.close()
        File("./data").listFiles()?.filter { it.name.startsWith("test-schema-audit-") }?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `reports each rule with counts and example uids`() {
        val r = SchemaAudit(db).run()
        assertEquals(setOf("ownerless"), r.finding("A3").examples.toSet() - r.finding("A3").examples.filter { it != "ownerless" }.toSet())
        assertEquals(1L, r.finding("A3").count)
        assertEquals(1L, r.finding("A4").count); assertEquals(listOf("wrong-stamp"), r.finding("A4").examples)
        // A5: the wrong-stamp item (asserted by another's source... here by the owner's source, stamped to other) and the orphan event
        assertTrue("orphan-event" in r.finding("A5").examples, r.finding("A5").toString())
        assertEquals(1L, r.finding("A5i").count, "a passage is never ASSERTed by design, so it is informational")
        assertEquals(setOf("legacy-task", "weird-task"), r.finding("A6").examples.toSet())
        assertEquals(1L, r.finding("A2").count); assertEquals(listOf("dup-uid"), r.finding("A2").examples)
        assertEquals(0L, r.finding("A7").count)
        assertEquals(1L, r.finding("A10").count, "a Project with no ownerEmail")
        assertTrue(r.finding("A8").count >= 1, "the PART_OF edge to the deleted domain is dangling")
        assertTrue(r.finding("A9b").count >= 1)
    }

    @Test
    fun `reads without writing anything`() {
        fun total() = db.schema.types.sumOf { t -> db.countType(t.name, false) }
        val before = total()
        SchemaAudit(db).run()
        assertEquals(before, total())
    }

    @Test
    fun `a duplicate uid is counted once per uid, not once per inherited copy`() {
        // dup-uid exists as one Phrase and one Task; a polymorphic query would show the Task twice. Distinct rows sharing the uid: one finding.
        assertEquals(1L, SchemaAudit(db).run().finding("A2").count)
    }
}
