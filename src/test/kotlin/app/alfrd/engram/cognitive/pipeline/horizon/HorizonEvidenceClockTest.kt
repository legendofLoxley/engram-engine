package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * The evidence clock means "something new was learned". A write that restates what the graph already holds (set_state open on an item whose effective
 * state is already open, with the stored state still null) is bookkeeping, not evidence; a real change and confirm_item still refresh it.
 */
class HorizonEvidenceClockTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var path: String
    private val day = 86_400_000L

    @BeforeEach
    fun setUp() {
        path = "./data/test-evidence-clock-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        structure = ArcadeHorizonStructureStore(dbManager.getDatabase())
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(path).deleteRecursively()
    }

    private fun newUser(): String {
        val email = "clock-${UUID.randomUUID()}@example.test"
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "c"); set("email", email)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        return email
    }

    private fun task(user: String, text: String) = runBlocking { structure.createItem(user, 1, ItemKind.TASK, text)!! }
    private fun get(user: String, uid: String) = runBlocking { structure.getItem(user, uid)!! }
    private fun sql(command: String, uid: String, extra: Map<String, Any?> = emptyMap()) {
        val db = dbManager.getDatabase()
        db.transaction { db.command("sql", command, mapOf("u" to uid) + extra) }
    }
    private fun changes(user: String, uid: String) = ChangeLog(dbManager.getDatabase()).forTarget(user, uid)

    /** An item as the backfill left it: open by its coarse status, no stored state, evidence a long time ago. */
    private fun legacyOpen(user: String, text: String, evidenceAt: Long, updatedAt: Long = evidenceAt): ItemRecord {
        val t = task(user, text)
        sql("UPDATE Task REMOVE state SET lastEvidenceAt = :e, updatedAt = :p WHERE uid = :u", t.uid, mapOf("e" to evidenceAt, "p" to updatedAt))
        return get(user, t.uid)
    }

    @Test
    fun `set_state open on an item that is already effectively open does not refresh evidence`() {
        val user = newUser()
        val old = System.currentTimeMillis() - 100 * day
        val t = legacyOpen(user, "Williams safety triggers", old)
        assertEquals(old, t.evidenceAt)

        assertTrue(runBlocking { structure.setState(user, 2, t.uid, WorkState.OPEN) }, "the write is accepted")

        val after = get(user, t.uid)
        assertEquals(old, after.evidenceAt, "evidence did not move")
        assertEquals(old, after.updatedAt, "nor did updatedAt")
        assertEquals(WorkState.OPEN, after.workState)
        assertEquals("open", dbManager.getDatabase().query("sql", "SELECT state FROM Task WHERE uid = :u", mapOf("u" to t.uid)).use { it.next().getProperty<String>("state") }, "the state is now stored")
        assertTrue(changes(user, t.uid).none { it.field == "state" }, "no change record for a state that did not change")
    }

    @Test
    fun `reopen through resolve false on an open item with no stored state is also not evidence`() {
        val user = newUser()
        val old = System.currentTimeMillis() - 60 * day
        val t = legacyOpen(user, "Re-check the alarm", old)
        runBlocking { structure.resolve(user, 2, t.uid, false) }
        assertEquals(old, get(user, t.uid).evidenceAt)
        assertTrue(changes(user, t.uid).isEmpty())
    }

    @Test
    fun `an item that never had an evidence stamp keeps reading its old updated time after a restated state`() {
        val user = newUser()
        val t = task(user, "Legacy")
        sql("UPDATE Task REMOVE state, lastEvidenceAt SET updatedAt = 5000 WHERE uid = :u", t.uid)
        assertNull(get(user, t.uid).lastEvidenceAt)
        runBlocking { structure.setState(user, 2, t.uid, WorkState.OPEN) }
        assertNull(get(user, t.uid).lastEvidenceAt, "no stamp is invented")
        assertEquals(5000L, get(user, t.uid).evidenceAt, "so it still reads as 5000, not as now")
    }

    @Test
    fun `restating a state that is already stored, or a waiting item's same nudge and waiting-on, is not evidence either`() {
        val user = newUser()
        val old = System.currentTimeMillis() - 50 * day
        val a = task(user, "Stored open")
        sql("UPDATE Task SET lastEvidenceAt = :e WHERE uid = :u", a.uid, mapOf("e" to old))
        runBlocking { structure.setState(user, 2, a.uid, WorkState.OPEN) }
        assertEquals(old, get(user, a.uid).evidenceAt)

        val w = task(user, "Waiting on Sam")
        val nudge = TimeValue(System.currentTimeMillis() + 5 * day)
        runBlocking { structure.setState(user, 3, w.uid, WorkState.WAITING, nudge, "Sam") }
        sql("UPDATE Task SET lastEvidenceAt = :e WHERE uid = :u", w.uid, mapOf("e" to old))
        val before = changes(user, w.uid).size
        runBlocking { structure.setState(user, 4, w.uid, WorkState.WAITING, nudge, "Sam") }
        assertEquals(old, get(user, w.uid).evidenceAt)
        assertEquals(before, changes(user, w.uid).size)
    }

    @Test
    fun `a real change of state, a new nudge, a new waiting-on and confirm_item still refresh evidence`() {
        val user = newUser()
        val old = System.currentTimeMillis() - 100 * day
        fun age(uid: String) = sql("UPDATE Task SET lastEvidenceAt = :e WHERE uid = :u", uid, mapOf("e" to old))
        val t = legacyOpen(user, "Moves", old)

        runBlocking { structure.setState(user, 2, t.uid, WorkState.ACTIVE) }
        assertTrue(get(user, t.uid).evidenceAt > old, "open to active is a change"); age(t.uid)

        runBlocking { structure.setState(user, 3, t.uid, WorkState.WAITING, TimeValue(System.currentTimeMillis() + 3 * day), "Sam") }
        assertTrue(get(user, t.uid).evidenceAt > old, "active to waiting is a change"); age(t.uid)

        runBlocking { structure.setState(user, 4, t.uid, WorkState.WAITING, TimeValue(System.currentTimeMillis() + 9 * day), "Sam") }
        assertTrue(get(user, t.uid).evidenceAt > old, "a new nudge date is a change"); age(t.uid)

        runBlocking { structure.setState(user, 5, t.uid, WorkState.WAITING, null, "Priya") }
        assertTrue(get(user, t.uid).evidenceAt > old, "a new waiting-on is a change"); age(t.uid)

        runBlocking { structure.touchEvidence(user, 6, t.uid) }
        assertTrue(get(user, t.uid).evidenceAt > old, "confirm_item is evidence"); age(t.uid)

        val legacy = legacyOpen(user, "Legacy confirm", old)
        runBlocking { structure.touchEvidence(user, 7, legacy.uid) }
        assertTrue(get(user, legacy.uid).evidenceAt > old, "confirm_item on a never-stored item still counts")
    }

    @Test
    fun `existing evidence dates are never rewritten by the fix`() {
        val user = newUser()
        val dates = listOf(12_345L, 1_700_000_000_000L, System.currentTimeMillis() - 7 * day)
        val items = dates.mapIndexed { i, d -> legacyOpen(user, "Old $i", d) }
        items.forEach { runBlocking { structure.setState(user, 2, it.uid, WorkState.OPEN) } }
        assertEquals(dates, items.map { get(user, it.uid).evidenceAt })
    }
}
