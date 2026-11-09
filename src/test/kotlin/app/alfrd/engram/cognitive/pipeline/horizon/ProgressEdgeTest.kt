package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressEdgeTest {

    private lateinit var manager: DatabaseManager
    private lateinit var store: ArcadeHorizonStructureStore
    private val db get() = manager.getDatabase()

    @BeforeAll
    fun setUp() {
        manager = DatabaseManager("./data/test-progress-${System.currentTimeMillis()}")
        SchemaBootstrap.bootstrap(manager.getDatabase())
        store = ArcadeHorizonStructureStore(manager.getDatabase())
    }

    @AfterAll
    fun tearDown() {
        manager.close()
        File("./data").listFiles()?.filter { it.name.startsWith("test-progress-") }?.forEach { it.deleteRecursively() }
    }

    private fun newUser(): String {
        val email = "progress-${UUID.randomUUID()}@example.test"
        db.transaction { db.newVertex("User").set("uid", UUID.randomUUID().toString()).set("username", "u").set("email", email).set("tier", -1).set("createdAt", 1L).save() }
        return email
    }

    private fun item(email: String, text: String, kind: ItemKind = ItemKind.TASK) = runBlocking { store.createItem(email, 1, kind, text)!! }

    @Test
    fun `creates a progress link and lists it from both ends`() = runBlocking<Unit> {
        val me = newUser(); val goal = item(me, "Run a marathon", ItemKind.FACT); val step = item(me, "Run 5km")
        assertTrue(store.addProgress(me, 7, step.uid, goal.uid, "user"))
        val on = store.progressOn(me, goal.uid).single()
        assertEquals(step.uid, on.fromUid); assertEquals(goal.uid, on.toUid); assertEquals(7L, on.cycleSeq); assertEquals("user", on.writer); assertTrue(on.createdAt > 0)
        assertEquals(listOf(on), store.progressBy(me, step.uid))
        assertTrue(store.progressBy(me, goal.uid).isEmpty()); assertTrue(store.progressOn(me, step.uid).isEmpty())
    }

    @Test
    fun `adding the same link again changes nothing`() = runBlocking<Unit> {
        val me = newUser(); val a = item(me, "a"); val b = item(me, "b")
        assertTrue(store.addProgress(me, 1, a.uid, b.uid, "hermes")); assertTrue(store.addProgress(me, 2, a.uid, b.uid, "director"))
        val links = store.progressOn(me, b.uid)
        assertEquals(1, links.size); assertEquals("hermes", links.single().writer, "the first writer stands")
    }

    @Test
    fun `an item you do not own cannot be linked, from either end`() = runBlocking<Unit> {
        val a = newUser(); val b = newUser()
        val mine = item(a, "mine"); val theirs = item(b, "theirs"); val mine2 = item(a, "mine too")
        assertFalse(store.addProgress(a, 1, mine.uid, theirs.uid, "user"))
        assertFalse(store.addProgress(a, 1, theirs.uid, mine.uid, "user"))
        assertFalse(store.addProgress(b, 1, mine.uid, theirs.uid, "user"))
        assertFalse(store.addProgress(a, 1, mine.uid, mine.uid, "user"), "an item is not progress on itself")
        assertFalse(store.addProgress(a, 1, mine.uid, "no-such-uid", "user"))
        assertFalse(store.addProgress(a, 1, mine.uid, mine2.uid, " "), "a link says who wrote it")
        assertEquals(0, db.query("sql", "SELECT count(*) AS n FROM PROGRESS_OF WHERE ownerEmail IN [:a, :b]", mapOf("a" to a, "b" to b)).use { it.next().getProperty<Number>("n").toInt() }, "nothing was written by the refused calls")
        assertTrue(store.addProgress(a, 1, mine.uid, mine2.uid, "user"))
        assertTrue(store.progressOn(b, mine2.uid).isEmpty(), "another owner reads nothing, even knowing the uid")
        assertTrue(store.progressBy(b, mine.uid).isEmpty())
    }

    @Test
    fun `a link whose other end is no longer the owner's is left out of the listing`() = runBlocking<Unit> {
        val a = newUser(); val b = newUser()
        val mine = item(a, "mine"); val mine2 = item(a, "mine too"); val theirs = item(b, "theirs")
        assertTrue(store.addProgress(a, 1, mine.uid, mine2.uid, "user"))
        // a forged edge from someone else's item, stamped with a's ownerEmail
        db.transaction {
            val from = db.query("sql", "SELECT FROM Task WHERE uid = :u", mapOf("u" to theirs.uid)).use { it.next().toElement().asVertex() }
            val to = db.query("sql", "SELECT FROM Task WHERE uid = :u", mapOf("u" to mine2.uid)).use { it.next().toElement().asVertex() }
            from.modify().newEdge("PROGRESS_OF", to, false).set("ownerEmail", a).set("createdAt", 2L).set("writer", "forged").save()
        }
        assertEquals(listOf(mine.uid), store.progressOn(a, mine2.uid).map { it.fromUid })
    }

    @Test
    fun `the edge carries no score and no decay, and the Horizon does not know about it`() = runBlocking<Unit> {
        assertEquals(setOf("createdAt", "ownerEmail", "cycleSeq", "writer"), db.schema.getType("PROGRESS_OF").propertyNames)
        val me = newUser(); val goal = item(me, "Goal"); val step = item(me, "Step")
        val assembler = ArcadeHorizonAssembler(db)
        suspend fun view() = (assembler.assemble(me, 5) as AssembleOutcome.Assembled).horizon.let { h -> h.copy(asOf = 0L) }   // asOf is the wall clock
        val before = view()
        store.addProgress(me, 5, step.uid, goal.uid, "user")
        assertEquals(before, view(), "the Horizon is identical with and without the link")
    }
}
