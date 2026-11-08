package app.alfrd.engram.db

import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import com.arcadedb.database.Database
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class SchemaConstraintsTest {

    private lateinit var manager: DatabaseManager
    private lateinit var db: Database

    @BeforeEach
    fun open() {
        manager = DatabaseManager("./data/test-constraints-${UUID.randomUUID()}")
        db = manager.getDatabase()
    }

    @AfterEach
    fun close() {
        manager.close()
        File("./data").listFiles()?.filter { it.name.startsWith("test-constraints-") }?.forEach { it.deleteRecursively() }
    }

    private fun write(type: String, vararg props: Pair<String, Any?>) = db.transaction {
        val v = db.newVertex(type)
        props.forEach { (k, x) -> if (x != null) v.set(k, x) }
        v.save()
    }

    private fun rejected(body: () -> Unit): Boolean = try { body(); false } catch (_: Exception) { true }

    private fun task(uid: String, owner: String? = "o@x", createdAt: Long? = 1L, extra: Array<Pair<String, Any?>> = emptyArray()) =
        write("Task", "uid" to uid, "ownerEmail" to owner, "createdAt" to createdAt, "text" to "t", *extra)

    // ── the flag ────────────────────────────────────────────────────────────

    @Test
    fun `with the flag off the schema takes violating writes exactly as before`() {
        SchemaBootstrap.bootstrap(db, enforceConstraints = false)
        task("dup"); task("dup")                                       // duplicate uid across the hierarchy
        write("Phrase", "uid" to "dup", "text" to "p")
        write("Task", "ownerEmail" to "o@x", "createdAt" to 1L)         // no uid
        write("Domain", "uid" to "d1", "name" to "D")                  // no owner, no createdAt
        task("bad-state", extra = arrayOf("state" to "paused"))
        task("q", extra = arrayOf("sourceQuote" to "one"))
        db.transaction { db.query("sql", "SELECT FROM Task WHERE uid = 'q'").use { rs -> rs.next().toElement().asVertex().modify().set("sourceQuote", "two").save() } }
        assertTrue(db.schema.getType("Phrase").getProperty("uid").let { !it.isMandatory })
    }

    @Test
    fun `with the flag on each violating write is rejected`() {
        SchemaBootstrap.bootstrap(db, enforceConstraints = true)
        task("ok")
        assertTrue(rejected { write("Task", "ownerEmail" to "o@x", "createdAt" to 1L) }, "no uid")
        assertTrue(rejected { task("ok") }, "duplicate uid, same type")
        assertTrue(rejected { write("Phrase", "uid" to "ok", "createdAt" to 1L) }, "duplicate uid, supertype vs subtype")
        assertTrue(rejected { write("Event", "uid" to "ok", "createdAt" to 1L, "ownerEmail" to "o@x") }, "duplicate uid, sibling subtype")
        assertTrue(rejected { write("Domain", "uid" to "d1", "name" to "D", "createdAt" to 1L) }, "container without an owner")
        assertTrue(rejected { write("Project", "uid" to "p1", "name" to "P", "ownerEmail" to "o@x") }, "container without createdAt")
        assertTrue(rejected { task("no-created", createdAt = null) }, "item without createdAt (inherited from Phrase)")
        assertTrue(rejected { task("bad-state", extra = arrayOf("state" to "paused")) }, "state outside the vocabulary")
        task("good-state", extra = arrayOf("state" to "waiting"))
        task("no-state")
    }

    @Test
    fun `sourceQuote is read-only after the item is written, other fields still change`() {
        SchemaBootstrap.bootstrap(db, enforceConstraints = true)
        task("q", extra = arrayOf("sourceQuote" to "what I said"))
        fun edit(f: (com.arcadedb.graph.MutableVertex) -> Unit) = db.transaction {
            db.query("sql", "SELECT FROM Task WHERE uid = 'q'").use { rs -> rs.next().toElement().asVertex().modify().also(f).save() }
        }
        edit { it.set("status", "resolved") }
        edit { it.set("sourceQuote", "what I said") }  // setting the same value is not a change
        assertTrue(rejected { edit { it.set("sourceQuote", "something else") } })
        assertTrue(rejected { edit { it.remove("sourceQuote") } })
    }

    @Test
    fun `item text stays writable because amendPhrase rewrites it in place`() {
        SchemaBootstrap.bootstrap(db, enforceConstraints = true)
        task("t")
        db.transaction { db.query("sql", "SELECT FROM Phrase WHERE uid = 't'").use { rs -> rs.next().toElement().asVertex().modify().set("text", "amended").save() } }
        assertFalse(db.schema.getType("Phrase").getProperty("text").isReadonly, "text is deliberately not read-only: DatabaseEngramClient.amendPhrase overwrites it")
    }

    // ── inheritance ─────────────────────────────────────────────────────────

    @Test
    fun `a constraint on Phrase binds the subtypes, and a subtype cannot redeclare it`() {
        SchemaBootstrap.bootstrap(db, enforceConstraints = true)
        val phrase = db.schema.getType("Phrase")
        assertTrue(phrase.getProperty("uid").isMandatory)
        assertTrue(db.schema.getType("Task").getPolymorphicProperty("uid").isMandatory, "the subtype sees the inherited constraint")
        assertTrue(rejected { db.schema.getType("Task").createProperty("ownerEmail", com.arcadedb.schema.Type.STRING) }, "an inherited property cannot be redeclared on the subtype")
        assertTrue(phrase.getAllIndexes(false).any { it.isUnique && it.propertyNames == listOf("uid") })
        // ownerEmail is declared on Phrase, which has ownerless rows by design, so it is not made mandatory.
        assertFalse(phrase.getProperty("ownerEmail").isMandatory)
    }

    // ── safety: idempotent, never fails startup, never locks a legacy row out ──

    @Test
    fun `running the bootstrap again changes nothing and the flag can be switched off afterwards`() {
        SchemaBootstrap.bootstrap(db, enforceConstraints = true)
        val again = SchemaConstraints.apply(db)
        assertTrue(again.applied.isEmpty(), "second run applies nothing: ${again.applied}")
        assertTrue(again.alreadyInPlace.isNotEmpty())
        SchemaBootstrap.bootstrap(db, enforceConstraints = true)
        SchemaBootstrap.bootstrap(db, enforceConstraints = false)   // ensureIndex must tolerate the unique index that replaced the plain one
        assertTrue(rejected { task("x", owner = null, createdAt = null) }, "constraints stay once applied")
    }

    @Test
    fun `existing violations skip only the constraint they break and startup does not fail`() {
        SchemaBootstrap.bootstrap(db, enforceConstraints = false)
        task("dup"); task("dup")                                         // breaks uid-unique:Phrase
        write("Domain", "uid" to "d-legacy", "name" to "D", "createdAt" to 1L)   // breaks mandatory:Domain.ownerEmail
        write("Question", "uid" to "q-legacy", "ownerEmail" to "o@x")    // no createdAt: breaks mandatory:Phrase.createdAt
        val outcome = SchemaConstraints.apply(db)
        assertTrue("uid-unique:Phrase" in outcome.skipped, outcome.skipped.toString())
        assertTrue("mandatory:Domain.ownerEmail" in outcome.skipped)
        assertTrue("mandatory:Phrase.createdAt" in outcome.skipped)
        assertTrue("uid-mandatory:Phrase" in outcome.applied, "an unrelated clean rule is still applied")
        assertTrue("uid-unique:Concept" in outcome.applied)
        // the plain index on the skipped rule is still there, and the legacy rows can still be saved
        assertTrue(db.schema.getType("Phrase").getAllIndexes(false).any { !it.isUnique && it.propertyNames == listOf("uid") })
        db.transaction { db.query("sql", "SELECT FROM Domain WHERE uid = 'd-legacy'").use { rs -> rs.next().toElement().asVertex().modify().set("name", "Renamed").save() } }
        db.transaction { db.query("sql", "SELECT FROM Question WHERE uid = 'q-legacy'").use { rs -> rs.next().toElement().asVertex().modify().set("status", "open").save() } }
    }

    // ── the real writers still work with the flag on ────────────────────────

    @Test
    fun `the Horizon structure store writes pass every constraint`() {
        SchemaBootstrap.bootstrap(db, enforceConstraints = true)
        val email = "constraints-${UUID.randomUUID()}@example.test"
        write("User", "uid" to UUID.randomUUID().toString(), "email" to email, "username" to "u", "tier" to -1, "createdAt" to 1L)
        val store = ArcadeHorizonStructureStore(db)
        runBlocking {
            val domain = requireNotNull(store.createContainer(email, 1, ContainerKind.DOMAIN, "Home"))
            val project = requireNotNull(store.createContainer(email, 1, ContainerKind.PROJECT, "Garden", parentDomainUid = domain.uid))
            val item = store.createItem(email, 1, ItemKind.TASK, "water the beds", projectUid = project.uid, sourceQuote = "I should water the beds")
            if (item == null) fail<Unit>("createItem returned null under the constraints")
            assertTrue(store.setState(email, 2, item!!.uid, app.alfrd.engram.cognitive.pipeline.horizon.WorkState.WAITING))
        }
    }
}
