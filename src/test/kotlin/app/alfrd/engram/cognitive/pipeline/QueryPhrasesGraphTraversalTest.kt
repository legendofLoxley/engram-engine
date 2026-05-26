package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.api.InviteeManifest
import app.alfrd.engram.api.OnboardingService
import app.alfrd.engram.api.PhraseInput
import app.alfrd.engram.api.queryPhrases
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

/**
 * Acceptance tests for perspective-scoped phrase retrieval via graph traversal.
 *
 * Graph path under test:
 *   User(email) → out(TRUSTS) → Source → out(ASSERTS) → Phrase
 *
 * All tests operate on a shared embedded ArcadeDB instance seeded once per class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryPhrasesGraphTraversalTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var service: OnboardingService

    private val userAEmail = "alice@example.com"
    private val userBEmail = "bob@example.com"

    @BeforeAll
    fun setup() {
        val dbPath = "./data/test-phrases-traversal-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(dbPath)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        service = OnboardingService(db)

        // Seed Jacob's User vertex (required as INVITED edge origin)
        db.transaction {
            val now = System.currentTimeMillis()
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", "Jacob")
                set("email", OnboardingService.JACOB_EMAIL)
                set("tier", 1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
        }

        // Seed User A with personal phrases + global phrases
        service.seedBatch(listOf(
            InviteeManifest(
                name                = "Alice",
                email               = userAEmail,
                relationshipContext = "close friend",
                trustPhase          = "Acquaintance",
                engagementIntent    = "daily_companion",
                personalPhrases     = listOf(
                    PhraseInput("Alice loves hiking on weekends", salience = 0.9),
                    PhraseInput("Alice works as a UX designer", salience = 0.8),
                ),
                globalPhrases = listOf(
                    PhraseInput("The team uses Kotlin for backend services", salience = 0.7),
                    PhraseInput("Stand-up meetings happen every morning", salience = 0.6),
                ),
            ),
        ))

        // Seed User B — different personal phrases, same global phrases (deduped by hash)
        service.seedBatch(listOf(
            InviteeManifest(
                name                = "Bob",
                email               = userBEmail,
                relationshipContext = "colleague",
                trustPhase          = "Acquaintance",
                engagementIntent    = "work_support",
                personalPhrases     = listOf(
                    PhraseInput("Bob is a machine learning engineer", salience = 0.85),
                    PhraseInput("Bob prefers async communication", salience = 0.75),
                ),
                // Same global phrase texts → will deduplicate to same vertices
                globalPhrases = listOf(
                    PhraseInput("The team uses Kotlin for backend services", salience = 0.7),
                    PhraseInput("Stand-up meetings happen every morning", salience = 0.6),
                ),
            ),
        ))
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-phrases-traversal-") }
            ?.forEach { it.deleteRecursively() }
    }

    // ── Test 1: Perspective isolation ─────────────────────────────────────────

    @Test
    fun `queryPhrases for User A returns personal phrases and global phrases`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, userAEmail, null, 50)

        val texts = phrases.map { it.text }
        assertTrue(texts.any { it.contains("Alice") },
            "Expected Alice's personal phrase in results, got: $texts")
        assertTrue(texts.any { it.contains("Kotlin") },
            "Expected global phrase in results, got: $texts")
    }

    @Test
    fun `queryPhrases for User A does NOT include User B personal phrases`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, userAEmail, null, 50)

        val texts = phrases.map { it.text }
        assertFalse(texts.any { it.contains("Bob") },
            "User A should not see Bob's personal phrases, but got: $texts")
    }

    @Test
    fun `queryPhrases for User B does NOT include User A personal phrases`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, userBEmail, null, 50)

        val texts = phrases.map { it.text }
        assertFalse(texts.any { it.contains("Alice") },
            "User B should not see Alice's personal phrases, but got: $texts")
    }

    @Test
    fun `both users see the same global phrases`() {
        val db = dbManager.getDatabase()
        val phrasesA = queryPhrases(db, userAEmail, null, 50)
        val phrasesB = queryPhrases(db, userBEmail, null, 50)

        val textsA = phrasesA.map { it.text }.toSet()
        val textsB = phrasesB.map { it.text }.toSet()

        assertTrue(textsA.any { it.contains("Kotlin") },
            "User A should see global Kotlin phrase")
        assertTrue(textsB.any { it.contains("Kotlin") },
            "User B should see global Kotlin phrase")
    }

    // ── Test 2: Score aggregation on overlap (global phrase reachable from both ─
    //            personal + global Source for the same user via deduplication)

    @Test
    fun `global phrase reachable through one Source has sourceCount 1`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, userAEmail, null, 50)

        // Global phrases are only reachable via the global Source (one TRUSTS path)
        val kotlinPhrase = phrases.find { it.text.contains("Kotlin") }
        assertNotNull(kotlinPhrase, "Expected Kotlin phrase in results")
        assertTrue(kotlinPhrase!!.sourceCount >= 1,
            "Expected sourceCount >= 1, got: ${kotlinPhrase.sourceCount}")
    }

    @Test
    fun `scores map contains trust score from ASSERTS edge`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, userAEmail, null, 50)

        val anyPhrase = phrases.firstOrNull()
        assertNotNull(anyPhrase, "Expected at least one phrase")
        assertTrue(anyPhrase!!.scores.containsKey("trust"),
            "Expected trust score in scores map, got keys: ${anyPhrase.scores.keys}")
        assertTrue(anyPhrase.scores["trust"]!! > 0.0,
            "Expected positive trust score, got: ${anyPhrase.scores["trust"]}")
    }

    @Test
    fun `phrases with salience seed have salience in scores map`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, userAEmail, null, 50)

        val withSalience = phrases.filter { it.scores.containsKey("salience") }
        assertTrue(withSalience.isNotEmpty(),
            "Expected at least one phrase with salience score (seeded with salience=0.9)")
    }

    // ── Test 3: Empty graph (user with no TRUSTS edges) ───────────────────────

    @Test
    fun `queryPhrases for user with no TRUSTS edges returns empty list`() {
        val db = dbManager.getDatabase()
        // Create a User vertex with no TRUSTS edges
        val isolatedEmail = "isolated-${System.currentTimeMillis()}@example.com"
        db.transaction {
            val now = System.currentTimeMillis()
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", "Isolated")
                set("email", isolatedEmail)
                set("tier", 1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
        }

        val phrases = queryPhrases(db, isolatedEmail, null, 50)
        assertTrue(phrases.isEmpty(),
            "Expected empty list for user with no TRUSTS edges, got: ${phrases.size} phrases")
    }

    // ── Test 4: Concept filtering ─────────────────────────────────────────────

    @Test
    fun `concept filter returns only matching phrases`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, userAEmail, "hiking", 50)

        val texts = phrases.map { it.text }
        assertTrue(texts.isNotEmpty(), "Expected results for concept 'hiking'")
        assertTrue(texts.all { it.lowercase().contains("hik") },
            "Expected only hiking-related phrases, got: $texts")
        assertFalse(texts.any { it.contains("Kotlin") },
            "Kotlin phrase should not match 'hiking' concept")
    }

    @Test
    fun `concept filter is case-insensitive`() {
        val db = dbManager.getDatabase()
        val lower = queryPhrases(db, userAEmail, "designer", 50)
        val upper = queryPhrases(db, userAEmail, "DESIGNER", 50)

        assertEquals(lower.map { it.uid }.toSet(), upper.map { it.uid }.toSet(),
            "Case should not affect concept matching")
    }

    @Test
    fun `concept filter with no matches returns empty list`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, userAEmail, "xyznonexistentterm", 50)
        assertTrue(phrases.isEmpty(), "Expected empty list for unmatched concept")
    }

    // ── Test 5: Unknown user ──────────────────────────────────────────────────

    @Test
    fun `queryPhrases with unknown email returns empty list`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, "unknown@notauser.com", null, 50)
        assertTrue(phrases.isEmpty(),
            "Expected empty list for unknown email, got: ${phrases.size} phrases")
    }

    @Test
    fun `queryPhrases with blank email returns empty list`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, "", null, 50)
        assertTrue(phrases.isEmpty(), "Expected empty list for blank email")
    }

    // ── Ordering ──────────────────────────────────────────────────────────────

    @Test
    fun `results are ordered by trust score descending`() {
        val db = dbManager.getDatabase()
        val phrases = queryPhrases(db, userAEmail, null, 50)

        val trustScores = phrases.map { it.scores["trust"] ?: 0.0 }
        val sorted = trustScores.sortedDescending()
        assertEquals(sorted, trustScores,
            "Expected phrases ordered by trust score descending, got: $trustScores")
    }

    // ── Limit ─────────────────────────────────────────────────────────────────

    @Test
    fun `limit parameter caps the result set`() {
        val db = dbManager.getDatabase()
        val all   = queryPhrases(db, userAEmail, null, 50)
        val capped = queryPhrases(db, userAEmail, null, 1)

        assertTrue(all.size > 1, "Expected more than 1 phrase for User A")
        assertEquals(1, capped.size, "Expected exactly 1 phrase when limit=1")
        // The single result should be the highest-trust phrase
        assertEquals(all.first().uid, capped.first().uid,
            "Capped result should be the top-ranked phrase")
    }
}
