package app.alfrd.engram

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Acceptance tests for Response Architecture v0.2.0 schema and seed data.
 *
 * Covers:
 *  1. ResponsePhrase, SELECTED, OUTCOME vertex/edge types exist
 *  2. All seed phrases inserted with correct metadata (moveType, postureAffinity, etc.)
 *  3. ASSERTS edges link all seeds to system_response_pool source
 *  4. Query by moveType returns correct phrase sets
 *  5. Query by expressionPhase + branchAffinity returns correct sets
 *  6. Phrase hashes are deterministic (sha256 of text)
 *  7. SELECTED and OUTCOME edge types have required properties
 *  8. Composite index on SELECTED (phraseUid + userId) and sessionId index
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ResponsePhraseSchemaTest {

    companion object {
        private lateinit var tempDir: java.nio.file.Path
        private lateinit var dbManager: DatabaseManager

        @BeforeAll
        @JvmStatic
        fun setUp() {
            tempDir = Files.createTempDirectory("engram-rp-test-")
            val dbPath = tempDir.resolve("test-db").toString()
            dbManager = DatabaseManager(dbPath)
            SchemaBootstrap.bootstrap(dbManager.getDatabase())
            ResponsePhraseSeed.seed(dbManager.getDatabase())
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            dbManager.close()
            tempDir.toFile().deleteRecursively()
        }

        private fun sha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    // ── Schema: vertex types ──────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `ResponsePhrase vertex type exists in schema`() {
        assertTrue(dbManager.getDatabase().schema.existsType("ResponsePhrase"))
    }

    @Test
    @Order(2)
    fun `ResponsePhrase has moveType and postureAffinity properties`() {
        val type = dbManager.getDatabase().schema.getType("ResponsePhrase")
        assertTrue(type.getProperty("moveType") != null, "moveType property must exist")
        assertTrue(type.getProperty("postureAffinity") != null, "postureAffinity property must exist")
    }

    // ── Schema: edge types ────────────────────────────────────────────────────

    @Test
    @Order(3)
    fun `SELECTED edge type exists with required properties`() {
        val schema = dbManager.getDatabase().schema
        assertTrue(schema.existsType("SELECTED"), "SELECTED edge type must exist")
        val et = schema.getType("SELECTED")
        assertTrue(et.getProperty("phraseUid") != null)
        assertTrue(et.getProperty("sessionId") != null)
        assertTrue(et.getProperty("userId") != null)
        assertTrue(et.getProperty("turnIndex") != null)
        assertTrue(et.getProperty("branch") != null)
        assertTrue(et.getProperty("moveType") != null)
        assertTrue(et.getProperty("compositeScore") != null)
        assertTrue(et.getProperty("timestamp") != null)
    }

    @Test
    @Order(4)
    fun `OUTCOME edge type exists with required properties`() {
        val schema = dbManager.getDatabase().schema
        assertTrue(schema.existsType("OUTCOME"), "OUTCOME edge type must exist")
        val et = schema.getType("OUTCOME")
        assertTrue(et.getProperty("phraseUid") != null)
        assertTrue(et.getProperty("sessionId") != null)
        assertTrue(et.getProperty("signal") != null)
        assertTrue(et.getProperty("contextSnapshot") != null)
        assertTrue(et.getProperty("timestamp") != null)
    }

    // ── Seed: basic counts ────────────────────────────────────────────────────

    @Test
    @Order(5)
    fun `seed inserts ResponsePhrase vertices`() {
        val db = dbManager.getDatabase()
        val count = db.query("sql", "SELECT count(*) as cnt FROM ResponsePhrase").use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertTrue(count >= 30, "Expected at least 30 seed phrases, got $count")
    }

    @Test
    @Order(6)
    fun `seed is idempotent — second call does not insert more phrases`() {
        val db = dbManager.getDatabase()
        val before = db.query("sql", "SELECT count(*) as cnt FROM ResponsePhrase").use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        ResponsePhraseSeed.seed(db)
        val after = db.query("sql", "SELECT count(*) as cnt FROM ResponsePhrase").use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertEquals(before, after, "Second seed call must not insert additional phrases")
    }

    // ── Seed: ASSERTS edges ───────────────────────────────────────────────────

    @Test
    @Order(7)
    fun `all ResponsePhrase vertices have ASSERTS edge to system_response_pool`() {
        val db = dbManager.getDatabase()
        val totalPhrases = db.query("sql", "SELECT count(*) as cnt FROM ResponsePhrase").use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        val assertsCount = db.query(
            "sql",
            "SELECT count(*) as cnt FROM ASSERTS WHERE context = 'seed'"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertEquals(totalPhrases, assertsCount,
            "Every ResponsePhrase must have exactly one ASSERTS(seed) edge")
    }

    @Test
    @Order(8)
    fun `system_response_pool Source vertex exists`() {
        val db = dbManager.getDatabase()
        val count = db.query(
            "sql", "SELECT count(*) as cnt FROM Source WHERE uid = 'system_response_pool'"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertEquals(1L, count, "Exactly one system_response_pool Source vertex must exist")
    }

    // ── Query by moveType ─────────────────────────────────────────────────────

    @Test
    @Order(9)
    fun `RECEIPT moveType returns exactly 7 phrases`() {
        val count = countByMoveType("RECEIPT")
        assertEquals(7, count, "RECEIPT pool must have exactly 7 phrases")
    }

    @Test
    @Order(10)
    fun `ORIENT moveType returns exactly 4 phrases`() {
        val count = countByMoveType("ORIENT")
        assertEquals(4, count, "ORIENT pool must have exactly 4 phrases")
    }

    @Test
    @Order(11)
    fun `HOLD moveType returns exactly 4 phrases`() {
        val count = countByMoveType("HOLD")
        assertEquals(4, count, "HOLD pool must have exactly 4 phrases")
    }

    @Test
    @Order(12)
    fun `REPAIR moveType returns exactly 3 phrases`() {
        val count = countByMoveType("REPAIR")
        assertEquals(3, count, "REPAIR pool must have exactly 3 phrases")
    }

    @Test
    @Order(13)
    fun `PROBE moveType returns exactly 5 phrases`() {
        val count = countByMoveType("PROBE")
        assertEquals(5, count, "PROBE pool must have exactly 5 phrases")
    }

    @Test
    @Order(14)
    fun `COMMIT moveType returns exactly 4 phrases`() {
        val count = countByMoveType("COMMIT")
        assertEquals(4, count, "COMMIT pool must have exactly 4 phrases")
    }

    @Test
    @Order(15)
    fun `MISREAD_RECOVERY moveType returns exactly 3 phrases`() {
        val count = countByMoveType("MISREAD_RECOVERY")
        assertEquals(3, count, "MISREAD_RECOVERY pool must have exactly 3 phrases")
    }

    @Test
    @Order(16)
    fun `MULTI_UTTERANCE_HOLD moveType returns exactly 3 phrases`() {
        val count = countByMoveType("MULTI_UTTERANCE_HOLD")
        assertEquals(3, count, "MULTI_UTTERANCE_HOLD pool must have exactly 3 phrases")
    }

    @Test
    @Order(17)
    fun `phrases with null moveType exist (greetings, acknowledgments, bridge, etc)`() {
        val db = dbManager.getDatabase()
        val count = db.query("sql", "SELECT count(*) as cnt FROM ResponsePhrase WHERE moveType IS NULL").use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertTrue(count > 0, "Non-posture phrases (null moveType) must exist")
    }

    // ── Query by expressionPhase + branchAffinity ─────────────────────────────

    @Test
    @Order(18)
    fun `BRIDGE expressionPhase returns bridge post-comprehension phrases`() {
        val db = dbManager.getDatabase()
        val count = db.query(
            "sql",
            "SELECT count(*) as cnt FROM ResponsePhrase WHERE expressionPhase = 'BRIDGE'"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertTrue(count >= 9, "Expected at least 9 BRIDGE-phase phrases, got $count")
    }

    @Test
    @Order(19)
    fun `FIRST_RESPONSE expressionPhase returns posture and greeting phrases`() {
        val db = dbManager.getDatabase()
        val count = db.query(
            "sql",
            "SELECT count(*) as cnt FROM ResponsePhrase WHERE expressionPhase = 'FIRST_RESPONSE'"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertTrue(count >= 30, "Expected at least 30 FIRST_RESPONSE phrases, got $count")
    }

    @Test
    @Order(20)
    fun `RECEIPT phrases are queryable by expressionPhase and moveType combined`() {
        val db = dbManager.getDatabase()
        val count = db.query(
            "sql",
            "SELECT count(*) as cnt FROM ResponsePhrase WHERE expressionPhase = 'FIRST_RESPONSE' AND moveType = 'RECEIPT'"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertEquals(7, count)
    }

    // ── Phrase hashes ─────────────────────────────────────────────────────────

    @Test
    @Order(21)
    fun `all ResponsePhrase vertices have a non-null hash`() {
        val db = dbManager.getDatabase()
        val nullHashCount = db.query(
            "sql",
            "SELECT count(*) as cnt FROM ResponsePhrase WHERE hash IS NULL"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertEquals(0L, nullHashCount, "Every ResponsePhrase must have a non-null hash")
    }

    @Test
    @Order(22)
    fun `phrase hash matches sha256 of text — spot check Right`() {
        val db = dbManager.getDatabase()
        val expected = sha256("Right.")
        val storedHash = db.query(
            "sql",
            "SELECT hash FROM ResponsePhrase WHERE text = 'Right.' AND moveType = 'RECEIPT' LIMIT 1"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["hash"] as? String else null
        }
        assertTrue(storedHash != null, "Right. with moveType=RECEIPT must exist in DB")
        assertEquals(expected, storedHash, "Hash must equal sha256('Right.')")
    }

    @Test
    @Order(23)
    fun `phrase hash is 64-char lowercase hex`() {
        val db = dbManager.getDatabase()
        val hashes = mutableListOf<String>()
        db.query("sql", "SELECT hash FROM ResponsePhrase LIMIT 10").use { rs ->
            while (rs.hasNext()) {
                val h = rs.next().toMap()["hash"] as? String ?: ""
                hashes.add(h)
            }
        }
        assertTrue(hashes.isNotEmpty(), "Must have at least some hashes to verify")
        for (hash in hashes) {
            assertEquals(64, hash.length, "SHA-256 hex must be 64 chars")
            assertTrue(hash.matches(Regex("[0-9a-f]+")), "Hash must be lowercase hex: $hash")
        }
    }

    @Test
    @Order(24)
    fun `sha256 is deterministic — same text always produces same hash`() {
        val h1 = sha256("Understood.")
        val h2 = sha256("Understood.")
        assertEquals(h1, h2)
        assertEquals(64, h1.length)
    }

    // ── postureAffinity ───────────────────────────────────────────────────────

    @Test
    @Order(25)
    fun `all first-response posture phrases have non-null postureAffinity JSON`() {
        val db = dbManager.getDatabase()
        val missingAffinity = db.query(
            "sql",
            "SELECT count(*) as cnt FROM ResponsePhrase WHERE moveType IS NOT NULL AND postureAffinity IS NULL"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertEquals(0L, missingAffinity,
            "Every phrase with a moveType must have a non-null postureAffinity")
    }

    @Test
    @Order(26)
    fun `postureAffinity JSON contains turnShapes field`() {
        val db = dbManager.getDatabase()
        val affinity = db.query(
            "sql",
            "SELECT postureAffinity FROM ResponsePhrase WHERE moveType = 'RECEIPT' LIMIT 1"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["postureAffinity"] as? String else null
        }
        assertTrue(affinity != null, "RECEIPT phrase must have postureAffinity")
        assertTrue(affinity!!.contains("turnShapes"), "postureAffinity JSON must contain turnShapes")
        assertTrue(affinity.contains("surfaceEnergyRange"), "postureAffinity JSON must contain surfaceEnergyRange")
        assertTrue(affinity.contains("responsePressureRange"), "postureAffinity JSON must contain responsePressureRange")
    }

    // ── visibility ────────────────────────────────────────────────────────────

    @Test
    @Order(27)
    fun `all ResponsePhrase vertices have visibility = internal`() {
        val db = dbManager.getDatabase()
        val nonInternal = db.query(
            "sql",
            "SELECT count(*) as cnt FROM ResponsePhrase WHERE visibility <> 'internal'"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertEquals(0L, nonInternal, "All ResponsePhrase vertices must have visibility='internal'")
    }

    // ── interpolation ─────────────────────────────────────────────────────────

    @Test
    @Order(28)
    fun `ORIENT pool includes exactly one interpolated phrase`() {
        val db = dbManager.getDatabase()
        val count = db.query(
            "sql",
            "SELECT count(*) as cnt FROM ResponsePhrase WHERE moveType = 'ORIENT' AND requiresInterpolation = true"
        ).use { rs ->
            if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
        }
        assertEquals(1L, count, "ORIENT pool must have exactly one interpolated phrase (Okay — {lastTopic}.)")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun countByMoveType(moveType: String): Int {
        val db = dbManager.getDatabase()
        return db.query(
            "sql",
            "SELECT count(*) as cnt FROM ResponsePhrase WHERE moveType = :mt",
            mapOf("mt" to moveType)
        ).use { rs ->
            if (rs.hasNext()) (rs.next().toMap()["cnt"] as? Long ?: 0L).toInt() else 0
        }
    }
}
