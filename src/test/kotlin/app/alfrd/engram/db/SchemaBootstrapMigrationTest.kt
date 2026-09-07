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
}
