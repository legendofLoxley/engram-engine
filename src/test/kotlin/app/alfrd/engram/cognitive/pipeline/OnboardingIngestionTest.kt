package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.api.InviteeManifest
import app.alfrd.engram.api.OnboardingService
import app.alfrd.engram.cognitive.UserGraphService
import app.alfrd.engram.cognitive.pipeline.memory.DatabaseEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

/**
 * Acceptance tests for engram-engine spec §5.1 — onboarding phrase ingestion.
 *
 * Verifies that user statements made during onboarding are stored as Phrase vertices
 * attributed to the user's Source, and are retrievable via [DatabaseEngramClient.queryPhrases].
 *
 * Graph path under test:
 *   User(email) → TRUSTS → Source(onboarding_conversation) → ASSERTS → Phrase
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnboardingIngestionTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var engramClient: DatabaseEngramClient

    private val userEmail = "ingestion-test-${System.currentTimeMillis()}@example.com"

    @BeforeAll
    fun setup() {
        val dbPath = "./data/test-onboarding-ingestion-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(dbPath)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)

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

        // Create the test user via onboard/seed so their User vertex + initial Source exist
        OnboardingService(db).seedBatch(listOf(
            InviteeManifest(
                name                = "Test User",
                email               = userEmail,
                relationshipContext = "test subject",
                trustPhase          = "Acquaintance",
                engagementIntent    = "acceptance_test",
                personalPhrases     = emptyList(),
                globalPhrases       = emptyList(),
            )
        ))

        engramClient = DatabaseEngramClient(db)
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-onboarding-ingestion-") }
            ?.forEach { it.deleteRecursively() }
    }

    // ── §5.1 acceptance: phrase created and attributed to user ────────────────

    @Test
    fun `user statement during onboarding is stored as a phrase attributed to their Source`() = runTest {
        val candidates = engramClient.decompose("my dog's name is Newton", emptyList())
        engramClient.ingest(candidates, userEmail)

        val phrases = engramClient.queryPhrases(userEmail, null, 50)
        val texts = phrases.map { it.text }

        assertTrue(
            texts.any { it.lowercase().contains("newton") },
            "Expected 'Newton' phrase in results after onboarding ingestion, got: $texts",
        )
    }

    @Test
    fun `ingested phrase is retrievable by concept query`() = runTest {
        val candidates = engramClient.decompose("I prefer working late at night", emptyList())
        engramClient.ingest(candidates, userEmail)

        val phrases = engramClient.queryPhrases(userEmail, "night", 50)
        val texts = phrases.map { it.text }

        assertTrue(
            texts.any { it.lowercase().contains("night") },
            "Expected concept-filtered phrase in results, got: $texts",
        )
    }

    @Test
    fun `ingested phrases are NOT visible to other users`() = runTest {
        val otherEmail = "other-${System.currentTimeMillis()}@example.com"

        val candidates = engramClient.decompose("private thought for ingestion test", emptyList())
        engramClient.ingest(candidates, userEmail)

        // Other user has no User vertex — should get empty results
        val phrases = engramClient.queryPhrases(otherEmail, null, 50)
        assertTrue(
            phrases.none { it.text.contains("private thought") },
            "Expected other user not to see ingested phrase, got: ${phrases.map { it.text }}",
        )
    }

    @Test
    fun `ingest is a no-op when userEmail is blank`() = runTest {
        val before = engramClient.queryPhrases(userEmail, null, 50).size
        engramClient.ingest(
            listOf(PhraseCandidate("should not be stored", "user", PhraseCategory.CONTEXT)),
            userEmail = "",
        )
        val after = engramClient.queryPhrases(userEmail, null, 50).size
        assertEquals(before, after, "Blank userEmail must not write any phrases")
    }

    @Test
    fun `ingested phrase has a trust score on its ASSERTS edge`() = runTest {
        val candidates = engramClient.decompose("I use Kotlin for everything", emptyList())
        engramClient.ingest(candidates, userEmail)

        val phrases = engramClient.queryPhrases(userEmail, "kotlin", 50)
        assertTrue(phrases.isNotEmpty(), "Expected at least one phrase matching 'kotlin'")
        val trustScore = phrases.first().scores["trust"]
        assertNotNull(trustScore, "Expected trust score to be present")
        assertTrue(trustScore!! > 0.0, "Expected positive trust score, got: $trustScore")
    }

    // ── §5.2 acceptance: Supabase-direct signup (no /onboard/seed, no INVITED edge) ─

    @Test
    fun `Supabase-direct signup gets User+TRUSTS+Source wired and queryPhrases returns results via full traversal`() = runTest {
        val selfEmail = "self-signup-${System.currentTimeMillis()}@example.com"
        val db = dbManager.getDatabase()
        val ugs = UserGraphService(db)

        // Simulate initSession() creating the User vertex for a brand-new Supabase-direct user
        val created = ugs.findOrCreateUser(selfEmail)
        assertNotNull(created, "findOrCreateUser must return a UserRecord for a new email")
        val storedTier = db.query(
            "sql", "SELECT FROM User WHERE email = :email", mapOf("email" to selfEmail),
        ).use { rs ->
            if (rs.hasNext()) (rs.next().toElement().asVertex().get("tier") as? Number)?.toInt() else null
        }
        assertEquals(UserGraphService.SELF_SIGNUP_TIER, storedTier,
            "Self-signup user must have SELF_SIGNUP_TIER, not the founder-invited tier",
        )

        // Calling findOrCreateUser again must be idempotent
        val second = ugs.findOrCreateUser(selfEmail)
        assertNotNull(second)
        assertEquals(created!!.uid, second!!.uid, "Repeated findOrCreateUser must return the same User vertex")

        // Simulate OnboardingBranch ingesting the user's first utterance
        val candidates = engramClient.decompose("I work on distributed systems", emptyList())
        assertTrue(candidates.isNotEmpty(), "Decomposition must produce at least one candidate")
        engramClient.ingest(candidates, selfEmail)

        // Verify ingest() wired User → TRUSTS → Source(onboarding_conversation) — not just Source + ASSERTS
        val userVertex = db.query(
            "sql", "SELECT FROM User WHERE email = :email", mapOf("email" to selfEmail),
        ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
        assertNotNull(userVertex, "User vertex must exist after findOrCreateUser")
        val trustsEdges = userVertex!!.getEdges(Vertex.DIRECTION.OUT, "TRUSTS").toList()
        assertTrue(trustsEdges.isNotEmpty(),
            "ingest() must create a TRUSTS edge from User to the onboarding_conversation Source")

        // Verify queryPhrases returns results via the full User → TRUSTS → Source → ASSERTS → Phrase traversal
        val phrases = engramClient.queryPhrases(selfEmail, null, 50)
        assertTrue(phrases.isNotEmpty(),
            "queryPhrases must return results for a Supabase-direct signup via graph traversal, got: $phrases")
    }
}
