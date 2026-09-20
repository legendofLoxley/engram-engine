package app.alfrd.engram.db

import com.arcadedb.schema.Type
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Verifies the fix to [SchemaBootstrap.ensureProperty] (`DocumentType.getProperty` throws rather
 * than returning null for a genuinely missing property) against the exact scenario every prior
 * call site avoided by coincidence: a type that already exists on disk WITHOUT a property
 * [SchemaBootstrap] wants to add. Every property [SchemaBootstrap] previously called
 * `ensureProperty` for was also already defined inline in that type's own `ensureVertex`/
 * `ensureEdge` block, so a freshly-bootstrapped test database never actually exercised the
 * missing-property path this class checks.
 */
class SchemaBootstrapMigrationTest {

    private lateinit var dbManager: DatabaseManager
    private val dirPrefix = "test-schema-migration-${System.nanoTime()}"

    @BeforeEach
    fun setUp() {
        dbManager = DatabaseManager("./data/$dirPrefix")
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File("./data").listFiles()?.filter { it.name.startsWith(dirPrefix) }?.forEach { it.deleteRecursively() }
    }

    /** Reproduces this repo's ASSERTS/RELATED_TO edge types exactly as they existed before the Context Horizon properties were added. */
    private fun createPreMigrationSchema() {
        val db = dbManager.getDatabase()
        db.transaction {
            val schema = db.schema
            val asserts = schema.createEdgeType("ASSERTS")
            asserts.createProperty("context", Type.STRING)
            asserts.createProperty("timestamp", Type.LONG)
            asserts.createProperty("scores", Type.STRING)

            val relatedTo = schema.createEdgeType("RELATED_TO")
            relatedTo.createProperty("relationType", Type.STRING)
            relatedTo.createProperty("strength", Type.DOUBLE)
        }
    }

    @Test
    fun `bootstrap adds the new properties to an existing ASSERTS and RELATED_TO type that predate them`() {
        createPreMigrationSchema()
        val schema = dbManager.getDatabase().schema
        // Sanity check on the simulated pre-migration state itself: the new properties genuinely
        // don't exist yet — getProperty throws for a missing property (the bug ensureProperty had).
        assertTrue(kotlin.runCatching { schema.getType("ASSERTS").getProperty("status") }.isFailure)

        assertDoesNotThrow { SchemaBootstrap.bootstrap(dbManager.getDatabase()) }

        val assertsType = schema.getType("ASSERTS")
        assertEquals(Type.STRING, assertsType.getProperty("status").type)
        assertEquals(Type.STRING, assertsType.getProperty("statusHistory").type)
        assertEquals(Type.LONG, assertsType.getProperty("cycleSeq").type)
        assertEquals(Type.LONG, assertsType.getProperty("statusCycleSeq").type)
        // Pre-existing properties must survive the migration untouched.
        assertEquals(Type.STRING, assertsType.getProperty("context").type)

        val relatedToType = schema.getType("RELATED_TO")
        assertEquals(Type.LONG, relatedToType.getProperty("createdAt").type)
        assertEquals(Type.LONG, relatedToType.getProperty("cycleSeq").type)
        assertEquals(Type.STRING, relatedToType.getProperty("relationType").type)
    }

    @Test
    fun `bootstrap is a safe no-op the second time, on both a freshly-created and a migrated database`() {
        // Fresh database — bootstrap creates everything, then a second call must not throw or
        // duplicate anything.
        assertDoesNotThrow { SchemaBootstrap.bootstrap(dbManager.getDatabase()) }
        assertDoesNotThrow { SchemaBootstrap.bootstrap(dbManager.getDatabase()) }
        val assertsType = dbManager.getDatabase().schema.getType("ASSERTS")
        assertEquals(Type.STRING, assertsType.getProperty("status").type)
    }

    @Test
    fun `bootstrap is a safe no-op after migrating a pre-existing database`() {
        createPreMigrationSchema()
        SchemaBootstrap.bootstrap(dbManager.getDatabase()) // first run performs the migration
        assertDoesNotThrow { SchemaBootstrap.bootstrap(dbManager.getDatabase()) } // second run must be a no-op, not a re-throw

        val assertsType = dbManager.getDatabase().schema.getType("ASSERTS")
        assertEquals(Type.STRING, assertsType.getProperty("status").type)
        assertEquals(Type.LONG, assertsType.getProperty("statusCycleSeq").type)
    }

    @Test
    fun `a migrated ASSERTS edge is actually writable and readable with the new properties, not just schema-present`() {
        createPreMigrationSchema()
        SchemaBootstrap.bootstrap(dbManager.getDatabase())

        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()
        val phraseUid = UUID.randomUUID().toString()
        db.transaction {
            val source = db.newVertex("Source").apply {
                set("uid", UUID.randomUUID().toString())
                set("name", "migration-test-source")
                set("type", "onboarding_conversation")
                set("metadata", "{}")
                save()
            }
            val phrase = db.newVertex("Phrase").apply {
                set("uid", phraseUid)
                set("text", "post-migration write")
                set("hash", phraseUid)
                set("visibility", "private")
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            source.newEdge("ASSERTS", phrase, false).apply {
                set("context", "onboarding_conversation")
                set("timestamp", now)
                set("scores", "[]")
                set("status", "open")
                set("statusHistory", """[{"state":"open","at":$now}]""")
                set("cycleSeq", 1L)
                save()
            }
        }

        val edge = db.query("sql", "SELECT FROM ASSERTS WHERE @in.uid = :u", mapOf("u" to phraseUid)).use { it.next().toElement().asEdge() }
        assertEquals("open", edge.get("status"))
        assertEquals(1L, (edge.get("cycleSeq") as Number).toLong())
    }

    // ── Context Horizon structure: containers, typed items, focus ────────────

    @Test
    fun `bootstrap creates the container types, the item subtypes and the structure edges`() {
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        val schema = dbManager.getDatabase().schema

        for (name in listOf("Domain", "Project", "Task", "Event", "Question")) assertTrue(schema.existsType(name), "$name must exist")
        for (name in listOf("PART_OF", "ABOUT", "FOCUSES_ON")) assertTrue(schema.existsType(name), "$name must exist")

        // Item subtypes extend Phrase, so Phrase queries and Phrase-typed code keep working.
        for (name in listOf("Task", "Event", "Question")) {
            assertTrue(schema.getType(name).instanceOf("Phrase"), "$name must extend Phrase")
            assertTrue(schema.getType(name).getPolymorphicProperty("text") != null, "$name must inherit Phrase.text")
            assertTrue(schema.getType(name).getPolymorphicProperty("opKey") != null, "$name must inherit Phrase.opKey")
            assertTrue(schema.getType(name).getPolymorphicProperty("sourceQuote") != null, "$name must inherit Phrase.sourceQuote")
        }
        assertTrue(!schema.getType("Domain").instanceOf("Phrase") && !schema.getType("Project").instanceOf("Phrase"), "containers are not Phrases")

        assertEquals(Type.LONG, schema.getType("Task").getProperty("dueAt").type)
        assertEquals(Type.LONG, schema.getType("Event").getProperty("occursAt").type)
        assertEquals(Type.STRING, schema.getType("Question").getProperty("askedBy").type)
        assertEquals(Type.LONG, schema.getType("Project").getProperty("dueAt").type)
        assertEquals(Type.STRING, schema.getType("Project").getProperty("ownerEmail").type)
        assertEquals(Type.STRING, schema.getType("FOCUSES_ON").getProperty("mode").type)
        assertEquals(Type.STRING, schema.getType("FOCUSES_ON").getProperty("zoom").type)
        assertEquals(Type.LONG, schema.getType("User").getProperty("lastActiveAt").type)
    }

    @Test
    fun `a Task is found through the Phrase uid index, on a fresh database`() {
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        assertItemSubtypesAreInThePhraseIndex()
    }

    /** A database that already holds Phrase data, indexed exactly as [SchemaBootstrap] created it before the structure types existed. */
    private fun createPrePhase1PhraseSchema(): String {
        val db = dbManager.getDatabase()
        val existingUid = UUID.randomUUID().toString()
        db.transaction {
            val phrase = db.schema.createVertexType("Phrase")
            phrase.createProperty("uid", Type.STRING)
            phrase.createProperty("text", Type.STRING)
            phrase.createProperty("hash", Type.STRING)
            phrase.createProperty("createdAt", Type.LONG)
            phrase.createProperty("updatedAt", Type.LONG)
            phrase.createProperty("visibility", Type.STRING)
            phrase.createProperty("userId", Type.STRING)
            phrase.createProperty("source", Type.STRING)
            phrase.createProperty("trustPhase", Type.INTEGER)
            phrase.createProperty("score", Type.DOUBLE)
            for (indexed in listOf("uid", "hash", "userId")) {
                phrase.getOrCreateTypeIndex(com.arcadedb.schema.Schema.INDEX_TYPE.LSM_TREE, false, indexed)
            }
            val user = db.schema.createVertexType("User")
            user.createProperty("uid", Type.STRING)
            user.createProperty("username", Type.STRING)
            user.createProperty("email", Type.STRING)
            user.createProperty("tier", Type.INTEGER)
            user.createProperty("createdAt", Type.LONG)
            user.createProperty("updatedAt", Type.LONG)
            db.newVertex("Phrase").apply {
                set("uid", existingUid); set("text", "pre-existing claim"); set("hash", existingUid)
                set("visibility", "private"); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
        return existingUid
    }

    @Test
    fun `bootstrap migrates a database with existing indexed Phrase data, keeping it and indexing the new subtypes`() {
        val existingUid = createPrePhase1PhraseSchema()
        val db = dbManager.getDatabase()
        assertTrue(!db.schema.existsType("Task"))

        assertDoesNotThrow { SchemaBootstrap.bootstrap(db) }

        assertTrue(db.schema.getType("Task").instanceOf("Phrase"))
        assertEquals(Type.STRING, db.schema.getType("Phrase").getProperty("opKey").type)
        assertEquals(Type.LONG, db.schema.getType("User").getProperty("lastActiveAt").type)
        assertEquals(1L, db.query("sql", "SELECT count(*) as n FROM Phrase WHERE uid = :u", mapOf("u" to existingUid))
            .use { (it.next().toMap()["n"] as Number).toLong() }, "the pre-existing claim survives the migration")
        assertItemSubtypesAreInThePhraseIndex()

        assertDoesNotThrow { SchemaBootstrap.bootstrap(db) }
        assertEquals(1L, db.query("sql", "SELECT count(*) as n FROM Phrase WHERE uid = :u", mapOf("u" to existingUid))
            .use { (it.next().toMap()["n"] as Number).toLong() })
    }

    /** Creates one record of each item subtype and proves each is reachable by key through the Phrase index, not just by scan. */
    private fun assertItemSubtypesAreInThePhraseIndex() {
        val db = dbManager.getDatabase()
        val uids = mutableMapOf<String, String>()
        db.transaction {
            for (type in listOf("Task", "Event", "Question")) {
                val uid = UUID.randomUUID().toString()
                uids[type] = uid
                db.newVertex(type).apply {
                    set("uid", uid); set("text", "a $type"); set("hash", uid); set("visibility", "private")
                    set("createdAt", 1L); set("updatedAt", 1L); set("opKey", "k|$uid"); save()
                }
            }
        }
        for ((type, uid) in uids) {
            val cursor = db.lookupByKey("Phrase", "uid", uid)
            assertTrue(cursor.hasNext(), "the Phrase.uid index must contain the $type record")
            assertEquals(type, cursor.next().record.asVertex().typeName)
            assertEquals(1L, db.query("sql", "SELECT count(*) as n FROM Phrase WHERE opKey = :k", mapOf("k" to "k|$uid"))
                .use { (it.next().toMap()["n"] as Number).toLong() }, "$type must be found by Phrase.opKey")
        }
    }
}
