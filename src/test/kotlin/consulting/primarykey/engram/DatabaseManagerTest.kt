package consulting.primarykey.engram

import consulting.primarykey.engram.db.DatabaseManager
import consulting.primarykey.engram.db.SchemaBootstrap
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DatabaseManagerTest {

    companion object {
        private lateinit var tempDir: java.nio.file.Path
        private lateinit var dbManager: DatabaseManager

        @BeforeAll
        @JvmStatic
        fun setUp() {
            tempDir = Files.createTempDirectory("engram-test-")
            val dbPath = tempDir.resolve("test-db").toString()
            dbManager = DatabaseManager(dbPath)
            SchemaBootstrap.bootstrap(dbManager.getDatabase())
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            dbManager.close()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @Order(1)
    fun `database opens successfully`() {
        val db = dbManager.getDatabase()
        assertTrue(db.isOpen, "Database should be open")
    }

    @Test
    @Order(2)
    fun `schema bootstrap creates vertex types`() {
        val schema = dbManager.getDatabase().schema
        assertTrue(schema.existsType("Phrase"), "Phrase vertex type should exist")
        assertTrue(schema.existsType("Concept"), "Concept vertex type should exist")
        assertTrue(schema.existsType("Source"), "Source vertex type should exist")
        assertTrue(schema.existsType("User"), "User vertex type should exist")
        assertTrue(schema.existsType("ScoreType"), "ScoreType vertex type should exist")
        assertTrue(schema.existsType("Scope"), "Scope vertex type should exist")
    }

    @Test
    @Order(3)
    fun `schema bootstrap creates edge types`() {
        val schema = dbManager.getDatabase().schema
        assertTrue(schema.existsType("FOLLOWS"), "FOLLOWS edge type should exist")
        assertTrue(schema.existsType("CONTAINS"), "CONTAINS edge type should exist")
        assertTrue(schema.existsType("ASSERTS"), "ASSERTS edge type should exist")
        assertTrue(schema.existsType("RELATED_TO"), "RELATED_TO edge type should exist")
        assertTrue(schema.existsType("TRUSTS"), "TRUSTS edge type should exist")
        assertTrue(schema.existsType("INVITED"), "INVITED edge type should exist")
        assertTrue(schema.existsType("QUOTES"), "QUOTES edge type should exist")
    }

    @Test
    @Order(4)
    fun `schema bootstrap is idempotent`() {
        // Running bootstrap again should not throw
        assertDoesNotThrow {
            SchemaBootstrap.bootstrap(dbManager.getDatabase())
        }
    }

    @Test
    @Order(5)
    fun `can create a Phrase vertex`() {
        val db = dbManager.getDatabase()
        var rid: com.arcadedb.graph.MutableVertex? = null

        db.transaction {
            val phrase = db.newVertex("Phrase")
            phrase.set("uid", "phrase-001")
            phrase.set("text", "Hello, Engram!")
            phrase.set("hash", "abc123")
            phrase.set("visibility", "public")
            phrase.set("createdAt", System.currentTimeMillis())
            phrase.set("updatedAt", System.currentTimeMillis())
            phrase.save()
            rid = phrase
        }

        assertNotNull(rid) { "Saved vertex should not be null" }
    }

    @Test
    @Order(6)
    fun `can create two Phrases linked by a FOLLOWS edge`() {
        val db = dbManager.getDatabase()

        db.transaction {
            val p1 = db.newVertex("Phrase")
            p1.set("uid", "phrase-A")
            p1.set("text", "First phrase")
            p1.set("hash", "hash-A")
            p1.set("visibility", "public")
            p1.set("createdAt", 1000L)
            p1.set("updatedAt", 1000L)
            p1.save()

            val p2 = db.newVertex("Phrase")
            p2.set("uid", "phrase-B")
            p2.set("text", "Second phrase")
            p2.set("hash", "hash-B")
            p2.set("visibility", "public")
            p2.set("createdAt", 2000L)
            p2.set("updatedAt", 2000L)
            p2.save()

            val edge = p1.newEdge("FOLLOWS", p2, true)
            edge.set("attributions", "[]")
            edge.set("scores", "[]")
            edge.save()
        }

        // Verify through a query
        val count = db.query("sql", "SELECT count(*) as c FROM FOLLOWS").next().getProperty<Long>("c")
        assertTrue(count >= 1, "At least one FOLLOWS edge should exist")
    }

    @Test
    @Order(7)
    fun `can traverse FOLLOWS edge between Phrases`() {
        val db = dbManager.getDatabase()

        db.transaction {
            val source = db.newVertex("Phrase")
            source.set("uid", "phrase-src")
            source.set("text", "Source phrase")
            source.set("hash", "hash-src")
            source.set("visibility", "public")
            source.set("createdAt", 3000L)
            source.set("updatedAt", 3000L)
            source.save()

            val dest = db.newVertex("Phrase")
            dest.set("uid", "phrase-dst")
            dest.set("text", "Destination phrase")
            dest.set("hash", "hash-dst")
            dest.set("visibility", "public")
            dest.set("createdAt", 4000L)
            dest.set("updatedAt", 4000L)
            dest.save()

            source.newEdge("FOLLOWS", dest, true).save()
        }

        val results = mutableListOf<String>()
        db.transaction {
            val cursor = db.query(
                "sql",
                "SELECT out('FOLLOWS').uid as targetUid FROM Phrase WHERE uid = 'phrase-src'"
            )
            while (cursor.hasNext()) {
                val row = cursor.next()
                val targets = row.getProperty<Any>("targetUid")
                if (targets != null) results.add(targets.toString())
            }
        }

        assertTrue(results.isNotEmpty(), "Traversal should return at least one result")
        assertTrue(results.any { it.contains("phrase-dst") }, "Should find the destination phrase uid")
    }
}
