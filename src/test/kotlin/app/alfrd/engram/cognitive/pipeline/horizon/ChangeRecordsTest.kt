package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChangeRecordsTest {

    private lateinit var manager: DatabaseManager
    private lateinit var store: ArcadeHorizonStructureStore
    private val db get() = manager.getDatabase()

    @BeforeAll
    fun setUp() {
        manager = DatabaseManager("./data/test-change-records-${System.currentTimeMillis()}")
        SchemaBootstrap.bootstrap(manager.getDatabase())
        store = ArcadeHorizonStructureStore(manager.getDatabase())
    }

    @AfterAll
    fun tearDown() {
        manager.close()
        File("./data").listFiles()?.filter { it.name.startsWith("test-change-records-") }?.forEach { it.deleteRecursively() }
    }

    private fun newUser(): String {
        val email = "changes-${UUID.randomUUID()}@example.test"
        db.transaction { db.newVertex("User").set("uid", UUID.randomUUID().toString()).set("username", "u").set("email", email).set("tier", -1).set("createdAt", 1L).save() }
        return email
    }

    private fun task(email: String, text: String = "Order the soil", projectUid: String? = null, dueAt: Long? = null) =
        runBlocking { store.createItem(email, 1, ItemKind.TASK, text, dueAt = dueAt, projectUid = projectUid)!! }

    private fun changes(email: String, uid: String) = runBlocking { store.changesFor(email, uid) }
    private fun total() = db.countType("ChangeRecord", false)

    // ── each tracked field produces exactly one record with the right old and new ──

    @Test
    fun `a state change records one change with the old and new state`() = runBlocking<Unit> {
        val me = newUser(); val t = task(me)
        assertTrue(store.setState(me, 2, t.uid, WorkState.ACTIVE))
        val c = changes(me, t.uid)
        assertEquals(1, c.size, c.toString())
        with(c.single()) {
            assertEquals("state", field); assertEquals("open", oldValue); assertEquals("active", newValue)
            assertEquals(t.uid, targetUid); assertEquals("Task", targetType); assertEquals(me, ownerEmail); assertEquals(me, actor)
            assertEquals("set_state", cause); assertEquals("change", family); assertNull(reason); assertTrue(changedAt > 0)
        }
    }

    @Test
    fun `waiting records state, check-in date and who it waits on, each once`() = runBlocking<Unit> {
        val me = newUser(); val t = task(me)
        store.setState(me, 2, t.uid, WorkState.WAITING, TimeValue(5_000L), "the landlord")
        val byField = changes(me, t.uid).associateBy { it.field }
        assertEquals(setOf("state", "nudgeAt", "waitingOn"), byField.keys)
        assertEquals("open" to "waiting", byField.getValue("state").let { it.oldValue to it.newValue })
        assertEquals(null to "5000", byField.getValue("nudgeAt").let { it.oldValue to it.newValue })
        assertEquals(null to "the landlord", byField.getValue("waitingOn").let { it.oldValue to it.newValue })
        // leaving waiting clears both, and that is recorded as a change too
        store.setState(me, 3, t.uid, WorkState.DONE)
        val after = changes(me, t.uid)
        assertEquals(6, after.size, after.toString())
        assertEquals("5000" to null, after.last { it.field == "nudgeAt" }.let { it.oldValue to it.newValue })
        assertEquals("the landlord" to null, after.last { it.field == "waitingOn" }.let { it.oldValue to it.newValue })
        assertEquals("waiting" to "done", after.last { it.field == "state" }.let { it.oldValue to it.newValue })
    }

    @Test
    fun `due date, hard or soft, check-in and attention each record their own change`() = runBlocking<Unit> {
        val me = newUser(); val t = task(me, dueAt = 1_000L)
        store.setDue(me, 2, t.uid, 2_000L)
        store.setTiming(me, 2, t.uid, dueKind = DueKind.HARD, nudge = TimeValue(3_000L))
        store.setAttention(me, 2, t.uid, AttentionDirective.PINNED)
        store.setAttention(me, 3, t.uid, null)
        val all = changes(me, t.uid).map { Triple(it.field, it.oldValue, it.newValue) }
        assertEquals(
            listOf(Triple("dueAt", "1000", "2000"), Triple("dueKind", null, "hard"), Triple("nudgeAt", null, "3000"), Triple("attention", null, "pinned"), Triple("attention", "pinned", null)).sortedBy { it.first + it.third },
            all.sortedBy { it.first + it.third }, all.toString(),
        )
        // clearing the date clears its hard mark too, and both are recorded
        store.setDue(me, 4, t.uid, null)
        val cleared = changes(me, t.uid).drop(all.size).map { Triple(it.field, it.oldValue, it.newValue) }.toSet()
        assertEquals(setOf(Triple("dueAt", "2000", null), Triple("dueKind", "hard", null)), cleared)
    }

    @Test
    fun `an event's time is recorded as dueAt`() = runBlocking<Unit> {
        val me = newUser()
        val e = store.createItem(me, 1, ItemKind.EVENT, "Dentist", dueAt = 10L)!!
        store.setDue(me, 2, e.uid, 20L)
        assertEquals(listOf(Triple("dueAt", "10", "20")), changes(me, e.uid).map { Triple(it.field, it.oldValue, it.newValue) })
    }

    @Test
    fun `re-filing records the old and new parent, and filing an unfiled item records it from nothing`() = runBlocking<Unit> {
        val me = newUser()
        val a = store.createContainer(me, 1, ContainerKind.PROJECT, "A")!!; val b = store.createContainer(me, 1, ContainerKind.PROJECT, "B")!!
        val t = task(me, projectUid = a.uid)
        assertTrue(changes(me, t.uid).isEmpty(), "creating the item filed under A is not a change")
        store.file(me, 2, t.uid, b.uid)
        assertEquals(listOf(Triple("PART_OF", a.uid, b.uid)), changes(me, t.uid).map { Triple(it.field, it.oldValue, it.newValue) })
        assertEquals(listOf(t.uid), store.itemsIn(me, b.uid).map { it.uid }, "the new filing is intact")
        assertTrue(store.itemsIn(me, a.uid).isEmpty())
        val loose = task(me, "Loose end")
        store.file(me, 3, loose.uid, a.uid)
        assertEquals(listOf(Triple("PART_OF", null, a.uid)), changes(me, loose.uid).map { Triple(it.field, it.oldValue, it.newValue) })
    }

    @Test
    fun `releasing a focus records it before the edge is deleted`() = runBlocking<Unit> {
        val me = newUser(); val t = task(me); val u = task(me, "Other")
        store.setFocus(me, 2, t.uid, FocusMode.PINNED)
        assertEquals(1, store.releaseFocus(me))
        assertTrue(store.getFocus(me).isEmpty())
        val c = changes(me, t.uid).single()
        assertEquals(Triple("FOCUSES_ON", "pinned/telescope", null), Triple(c.field, c.oldValue, c.newValue)); assertEquals("release_focus", c.cause)
        // replacing a pinned focus releases the old one, which is recorded against the old target
        store.setFocus(me, 3, t.uid, FocusMode.PINNED); store.setFocus(me, 4, u.uid, FocusMode.PINNED)
        val replaced = changes(me, t.uid).last()
        assertEquals("FOCUSES_ON", replaced.field); assertEquals("set_focus", replaced.cause)
        assertTrue(changes(me, u.uid).isEmpty(), "pinning the new target is not recorded; only the release is")
    }

    // ── what is not a change ────────────────────────────────────────────────

    @Test
    fun `an unchanged value, evidence and bookkeeping produce no record`() = runBlocking<Unit> {
        val me = newUser(); val a = store.createContainer(me, 1, ContainerKind.PROJECT, "A")!!
        val t = task(me, projectUid = a.uid, dueAt = 1_000L)
        store.setState(me, 2, t.uid, WorkState.ACTIVE)
        val before = total()
        store.setState(me, 3, t.uid, WorkState.ACTIVE)                     // same state
        store.setDue(me, 3, t.uid, 1_000L)                                  // same date
        store.setTiming(me, 3, t.uid, dueKind = DueKind.SOFT)               // soft is what an unmarked item already is
        store.setAttention(me, 3, t.uid, null)                              // already normal
        store.file(me, 3, t.uid, a.uid)                                     // already there
        store.touchEvidence(me, 3, t.uid)                                   // confirmation is not a change
        store.linkAbout(me, 3, t.uid, "soil")
        store.releaseFocus(me)                                              // nothing focused
        assertEquals(before, total())
        assertEquals(1, changes(me, t.uid).size)
        store.setState(me, 4, t.uid, WorkState.OPEN)                        // reopening from active is a real change
        assertEquals(2, changes(me, t.uid).size)
    }

    @Test
    fun `an item that never had a state reads its status, so setting open is no change`() = runBlocking<Unit> {
        val me = newUser(); val t = task(me)
        db.transaction { db.query("sql", "SELECT FROM Task WHERE uid = :u", mapOf("u" to t.uid)).use { rs -> val m = rs.next().toElement().asVertex().modify(); m.remove("state"); m.save() } }
        store.setState(me, 2, t.uid, WorkState.OPEN)
        assertTrue(changes(me, t.uid).isEmpty(), "legacy item with no state already read as open")
    }

    @Test
    fun `only the opted-in fields can be recorded`() {
        assertEquals(setOf("state", "dueAt", "dueKind", "waitingOn", "nudgeAt", "attention"), ChangeTracking.FIELDS)
        assertEquals(setOf("PART_OF", "FOCUSES_ON"), ChangeTracking.EDGES)
        val log = ChangeLog(db)
        val v = db.query("sql", "SELECT FROM User LIMIT 1").use { it.next().toElement().asVertex() }
        for (field in listOf("updatedAt", "lastEvidenceAt", "hash", "opKey", "runId", "text")) {
            val e = runCatching { db.transaction { log.append("x", v, field, "a", "b", "c", "d") } }
            assertTrue(e.isFailure, "$field must not be recordable")
        }
    }

    // ── cause, actor, supersession ──────────────────────────────────────────

    @Test
    fun `the verb being applied is the cause, and the actor can be named`() = runBlocking<Unit> {
        val me = newUser(); val t = task(me)
        withContext(ChangeContext("correct_item", actor = "director")) { store.resolve(me, 2, t.uid, true) }
        val c = changes(me, t.uid).single()
        assertEquals("correct_item", c.cause); assertEquals("director", c.actor); assertEquals("done", c.newValue)
    }

    @Test
    fun `records survive a supersession`() = runBlocking<Unit> {
        val me = newUser(); val old = task(me, "Call Sam on Monday")
        store.setState(me, 2, old.uid, WorkState.ACTIVE)
        val corrected = task(me, "Call Sam on Tuesday")
        assertTrue(ArcadeHorizonGraphStore(db).markSuperseded(me, 3, corrected.uid, old.uid))
        withContext(ChangeContext("correct_item")) { store.resolve(me, 3, old.uid, true) }
        val c = changes(me, old.uid)
        assertEquals(listOf("active" to "done"), c.takeLast(1).map { it.oldValue to it.newValue })
        assertEquals(listOf("set_state", "correct_item"), c.map { it.cause })
        assertTrue(changes(me, corrected.uid).isEmpty(), "the new item starts with a clean history")
    }

    // ── ownership ───────────────────────────────────────────────────────────

    @Test
    fun `records are scoped by owner`() = runBlocking<Unit> {
        val a = newUser(); val b = newUser()
        val t = task(a); store.setState(a, 2, t.uid, WorkState.ACTIVE)
        assertEquals(1, changes(a, t.uid).size)
        assertTrue(changes(b, t.uid).isEmpty(), "another owner cannot read a's history, even knowing the uid")
        assertFalse(store.setState(b, 2, t.uid, WorkState.DONE), "and cannot change it, so nothing is recorded for b")
        assertEquals(1, db.query("sql", "SELECT count(*) AS n FROM ChangeRecord WHERE targetUid = :u", mapOf("u" to t.uid)).use { it.next().getProperty<Number>("n").toInt() })
        assertTrue(db.query("sql", "SELECT FROM ChangeRecord WHERE targetUid = :u", mapOf("u" to t.uid)).use { rs -> generateSequence { if (rs.hasNext()) rs.next() else null }.all { it.getProperty<String>("ownerEmail") == a } })
    }

    // ── append-only, and linked ─────────────────────────────────────────────

    @Test
    fun `a written record cannot be changed or deleted, and the log exposes no way to`() = runBlocking<Unit> {
        val me = newUser(); val t = task(me)
        store.setState(me, 2, t.uid, WorkState.ACTIVE)
        fun rejected(sql: String) = runCatching { db.transaction { db.command("sql", sql, mapOf("u" to t.uid)).close() } }.isFailure
        assertTrue(rejected("UPDATE ChangeRecord SET newValue = 'done' WHERE targetUid = :u"), "update")
        assertTrue(rejected("UPDATE ChangeRecord SET field = 'state', cause = 'x' WHERE targetUid = :u"), "update other fields")
        runCatching { db.transaction { db.command("sql", "DELETE FROM ChangeRecord WHERE targetUid = :u", mapOf("u" to t.uid)).close() } }   // vetoed: refused or skipped, never done
        assertEquals("active", changes(me, t.uid).single().newValue, "the record is still there, unchanged")
        val publicMethods = ChangeLog::class.java.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()
        // Reads are allowed to grow (forTarget, and since for the zoomed-out board's "changed since last read"); there is still no update or delete.
        assertEquals(setOf("append", "forTarget", "since"), publicMethods)
        assertTrue(ArcadeHorizonStructureStore::class.java.methods.none { it.name.contains("deleteChange", true) || it.name.contains("updateChange", true) })
    }

    @Test
    fun `a record is linked from its target by HAS_CHANGE, and the target's other edges survive`() = runBlocking<Unit> {
        val me = newUser(); val a = store.createContainer(me, 1, ContainerKind.PROJECT, "A")!!; val b = store.createContainer(me, 1, ContainerKind.PROJECT, "B")!!
        val t = task(me, projectUid = a.uid)
        store.setState(me, 2, t.uid, WorkState.ACTIVE); store.file(me, 3, t.uid, b.uid); store.setAttention(me, 4, t.uid, AttentionDirective.PINNED)
        store.setDue(me, 5, t.uid, 7L)
        val vertex = db.query("sql", "SELECT FROM Task WHERE uid = :u", mapOf("u" to t.uid)).use { it.next().toElement().asVertex() }
        val linked = vertex.getVertices(Vertex.DIRECTION.OUT, "HAS_CHANGE").map { it.get("uid") }.toSet()
        assertEquals(changes(me, t.uid).map { it.uid }.toSet(), linked)
        assertEquals(4, linked.size)
        assertEquals(listOf(b.uid), vertex.getVertices(Vertex.DIRECTION.OUT, "PART_OF").map { it.get("uid") })
        assertEquals(1, db.query("sql", "SELECT count(*) AS n FROM ASSERTS WHERE @in.uid = :u", mapOf("u" to t.uid)).use { it.next().getProperty<Number>("n").toInt() }, "its assertion is intact")
        assertEquals(b.uid, store.getItem(me, t.uid)!!.projectUid)
    }

    @Test
    fun `a change record is not a Phrase and not an item, so no Horizon scan can see one`() {
        val type = db.schema.getType("ChangeRecord")
        assertFalse(type.isSubTypeOf("Phrase"))
        assertTrue(db.schema.existsType("HAS_CHANGE"))
        assertFalse("Event" == "ChangeRecord", "named so as not to collide with the world-event item type")
    }

    // ── effectiveAt: optional, written only when given ──

    private fun recordProps(uid: String): Map<String, Any?> =
        db.query("sql", "SELECT FROM ChangeRecord WHERE uid = :u", mapOf("u" to uid)).use { it.next().toElement().asVertex().propertyNames.associateWith { n -> null } }

    private fun appendDirect(me: String, t: ItemRecord, effectiveAt: Long?): String {
        var uid = ""
        db.transaction {
            val v = db.query("sql", "SELECT FROM Task WHERE uid = :u", mapOf("u" to t.uid)).use { it.next().toElement().asVertex() }
            ChangeLog(db).append(me, v, "state", "open", "done", cause = "test", actor = me, effectiveAt = effectiveAt)
        }
        uid = db.query("sql", "SELECT FROM ChangeRecord WHERE targetUid = :u ORDER BY changedAt DESC LIMIT 1", mapOf("u" to t.uid)).use { it.next().getProperty<String>("uid") }
        return uid
    }

    @Test
    fun `a record written without effectiveAt has no such property`() {
        val me = newUser(); val t = task(me)
        val uid = appendDirect(me, t, null)
        assertFalse("effectiveAt" in recordProps(uid).keys)
        assertNull(changes(me, t.uid).single().effectiveAt)
    }

    @Test
    fun `a record written with effectiveAt keeps it and changedAt is separate`() {
        val me = newUser(); val t = task(me)
        val uid = appendDirect(me, t, 1_700_000_000_000L)
        assertTrue("effectiveAt" in recordProps(uid).keys)
        val view = changes(me, t.uid).single()
        assertEquals(1_700_000_000_000L, view.effectiveAt)
        assertTrue(view.changedAt > view.effectiveAt!!)
    }

    @Test
    fun `an ordinary verb never sets effectiveAt`() = runBlocking<Unit> {
        val me = newUser(); val t = task(me)
        store.setState(me, 2, t.uid, WorkState.ACTIVE)
        assertNull(changes(me, t.uid).single().effectiveAt)
    }

    @Test
    fun `bootstrap adds effectiveAt to a database that already holds ChangeRecord rows and leaves them alone`() = runBlocking<Unit> {
        // A database as deployed before effectiveAt existed: the type has rows and no such property.
        val path = "./data/test-change-records-old-${System.currentTimeMillis()}"
        val m = DatabaseManager(path)
        try {
            val d = m.getDatabase()
            SchemaBootstrap.bootstrap(d)
            d.schema.getType("ChangeRecord").dropProperty("effectiveAt")
            val s = ArcadeHorizonStructureStore(d)
            val me = "old-${UUID.randomUUID()}@example.test"
            d.transaction { d.newVertex("User").set("uid", UUID.randomUUID().toString()).set("username", "u").set("email", me).set("tier", -1).set("createdAt", 1L).save() }
            val t = s.createItem(me, 1, ItemKind.TASK, "Old row")!!
            s.setState(me, 2, t.uid, WorkState.ACTIVE)
            fun snapshot() = d.query("sql", "SELECT FROM ChangeRecord").use { rs -> buildList { while (rs.hasNext()) add(rs.next().toElement().asVertex().toJSON().toString()) } }
            val before = snapshot()
            assertEquals(1, before.size)
            assertFalse(d.schema.getType("ChangeRecord").existsProperty("effectiveAt"))

            SchemaBootstrap.bootstrap(d)
            SchemaBootstrap.bootstrap(d)   // idempotent

            val prop = d.schema.getType("ChangeRecord").getProperty("effectiveAt")
            assertTrue(prop != null && prop.type == com.arcadedb.schema.Type.LONG && prop.isReadonly && !prop.isMandatory)
            assertEquals(before, snapshot(), "existing rows are untouched")
            // and new records can carry it
            val v = d.query("sql", "SELECT FROM Task WHERE uid = :u", mapOf("u" to t.uid)).use { it.next().toElement().asVertex() }
            d.transaction { ChangeLog(d).append(me, v, "state", "active", "done", cause = "t", actor = me, effectiveAt = 5L) }
            assertEquals(5L, s.changesFor(me, t.uid).last().effectiveAt)
        } finally {
            m.close(); File(path).deleteRecursively()
        }
    }
}
