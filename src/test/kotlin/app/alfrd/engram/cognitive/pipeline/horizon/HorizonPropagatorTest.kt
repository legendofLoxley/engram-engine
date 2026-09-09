package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Against real `ArcadeHorizonGraphStore`/`ArcadeHorizonAssembler` — propagation's correctness
 * (idempotent [HorizonGraphStore.markRelevant], the real bounded candidate query) depends on
 * their actual behavior, not an assumption about their contracts.
 */
class HorizonPropagatorTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var store: HorizonGraphStore
    private lateinit var assembler: HorizonAssembler
    private lateinit var propagator: SalientTokenPropagator
    private lateinit var testDbPath: String

    @BeforeEach
    fun setUp() {
        testDbPath = "./data/test-propagator-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        store = ArcadeHorizonGraphStore(dbManager.getDatabase())
        assembler = ArcadeHorizonAssembler(dbManager.getDatabase())
        propagator = SalientTokenPropagator(store, assembler)
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(testDbPath).deleteRecursively()
    }

    private fun seedUser(email: String) {
        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", email.substringBefore("@"))
                set("email", email)
                set("tier", -1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
        }
    }

    /** Seeds a Phrase asserted by [email]'s own Source, optionally OPEN-status — mirrors the shape `stampNewAssertion` produces, built directly so this test targets the propagator, not the write path. */
    private fun seedPhrase(email: String, cycleSeq: Long, text: String, open: Boolean): String {
        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()
        val phraseUid = UUID.randomUUID().toString()
        db.transaction {
            val userVertex = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email))
                .use { rs -> rs.next().toElement().asVertex() }
            val sourceName = "conversation:$email"
            var sourceVertex = db.query("sql", "SELECT FROM Source WHERE name = :n", mapOf("n" to sourceName))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null }
            if (sourceVertex == null) {
                sourceVertex = db.newVertex("Source").apply {
                    set("uid", UUID.randomUUID().toString())
                    set("name", sourceName)
                    set("type", "conversation")
                    set("metadata", "{}")
                    save()
                }
                userVertex.modify().newEdge("TRUSTS", sourceVertex, false).apply { set("scores", "[]"); save() }
            }
            val phraseVertex = db.newVertex("Phrase").apply {
                set("uid", phraseUid)
                set("text", text)
                set("hash", phraseUid)
                set("visibility", "private")
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            val edge = sourceVertex.newEdge("ASSERTS", phraseVertex, false)
            edge.set("context", "conversation")
            edge.set("timestamp", now)
            edge.set("scores", "[]")
            edge.set("cycleSeq", cycleSeq)
            if (open) {
                edge.set("status", "open")
                edge.set("statusHistory", "[]")
                edge.set("statusCycleSeq", cycleSeq)
            }
            edge.save()
        }
        return phraseUid
    }

    @Test
    fun `shared salient token creates an edge with a positive recorded strength`() = runBlocking {
        val email = "prop-shared-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val openUid = seedPhrase(email, cycleSeq = 1, text = "Getting Alfrd running on Arx is a priority for me", open = true)
        val newUid = seedPhrase(email, cycleSeq = 3, text = "Arx developer build v0.9.2 finished compiling and is ready to flash", open = false)

        val outcome = propagator.propagate(email, cycleSeq = 3, newPhrases = listOf(newUid to "Arx developer build v0.9.2 finished compiling and is ready to flash"))

        assertTrue(outcome is PropagationOutcome.Propagated, "Expected Propagated, got $outcome")
        val propagated = outcome as PropagationOutcome.Propagated
        assertEquals(1, propagated.edgesCreated.size)
        assertEquals(newUid, propagated.edgesCreated[0].fromPhraseUid)
        assertEquals(openUid, propagated.edgesCreated[0].toPhraseUid)
        assertTrue(propagated.edgesCreated[0].strength > 0.0, "Strength must be a real positive value, not a placeholder")
    }

    @Test
    fun `zero lexical overlap creates no edge`() = runBlocking {
        val email = "prop-zero-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        seedPhrase(email, cycleSeq = 1, text = "Getting Alfrd running on Arx is a priority for me", open = true)
        val newUid = seedPhrase(email, cycleSeq = 3, text = "Weekly backup completed successfully overnight", open = false)

        val outcome = propagator.propagate(email, cycleSeq = 3, newPhrases = listOf(newUid to "Weekly backup completed successfully overnight"))

        assertTrue(outcome is PropagationOutcome.Propagated)
        assertEquals(emptyList<RelevanceEdgeSummary>(), (outcome as PropagationOutcome.Propagated).edgesCreated)
    }

    @Test
    fun `sharing only generic tier-2 vocabulary creates no edge`() = runBlocking {
        // Both mention "app" and "get"/"getting" — none of it distinctive; proves the expanded
        // stopword list, not mere token overlap, is what's actually deciding relevance.
        val email = "prop-generic-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        seedPhrase(email, cycleSeq = 1, text = "I really want to get the new app working for my team", open = true)
        val newUid = seedPhrase(email, cycleSeq = 3, text = "Just wanted to get the other app going too", open = false)

        val outcome = propagator.propagate(email, cycleSeq = 3, newPhrases = listOf(newUid to "Just wanted to get the other app going too"))

        assertTrue(outcome is PropagationOutcome.Propagated)
        assertEquals(emptyList<RelevanceEdgeSummary>(), (outcome as PropagationOutcome.Propagated).edgesCreated)
    }

    @Test
    fun `only status=open items are candidates, never resolved or plain facts`() = runBlocking {
        val email = "prop-open-only-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        // A plain, non-status fact sharing the same distinctive token — must never be targeted.
        seedPhrase(email, cycleSeq = 1, text = "Arx build configuration notes", open = false)
        val newUid = seedPhrase(email, cycleSeq = 3, text = "Arx developer build finished compiling", open = false)

        val outcome = propagator.propagate(email, cycleSeq = 3, newPhrases = listOf(newUid to "Arx developer build finished compiling"))

        assertTrue(outcome is PropagationOutcome.Propagated)
        assertEquals(emptyList<RelevanceEdgeSummary>(), (outcome as PropagationOutcome.Propagated).edgesCreated, "A non-open item must never be a propagation target")
    }

    @Test
    fun `a phrase never self-links against itself in the candidate pool`() = runBlocking {
        val email = "prop-self-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        // The "new" phrase is itself already open (e.g. a resumed cycle reprocessing the same uid).
        val uid = seedPhrase(email, cycleSeq = 1, text = "Getting Alfrd running on Arx is a priority for me", open = true)

        val outcome = propagator.propagate(email, cycleSeq = 1, newPhrases = listOf(uid to "Getting Alfrd running on Arx is a priority for me"))

        assertTrue(outcome is PropagationOutcome.Propagated)
        assertEquals(emptyList<RelevanceEdgeSummary>(), (outcome as PropagationOutcome.Propagated).edgesCreated, "A phrase must never be proposed as relevant to itself")
    }

    @Test
    fun `a repeated identical (from,to) pair does not duplicate the edge`() = runBlocking {
        val email = "prop-repeat-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        seedPhrase(email, cycleSeq = 1, text = "Getting Alfrd running on Arx is a priority for me", open = true)
        val newUid = seedPhrase(email, cycleSeq = 3, text = "Arx build finished compiling", open = false)

        val first = propagator.propagate(email, cycleSeq = 3, newPhrases = listOf(newUid to "Arx build finished compiling"))
        val second = propagator.propagate(email, cycleSeq = 3, newPhrases = listOf(newUid to "Arx build finished compiling"))

        assertTrue(first is PropagationOutcome.Propagated && second is PropagationOutcome.Propagated)
        assertEquals(1, (first as PropagationOutcome.Propagated).edgesCreated.size)
        assertEquals(1, (second as PropagationOutcome.Propagated).edgesCreated.size, "Idempotent re-propagation must report success, not silently fail, but must not create a second edge")

        val actualEdges = dbManager.getDatabase().query(
            "sql", "SELECT FROM RELATED_TO WHERE relationType = 'relevant_to' AND ownerEmail = :e",
            mapOf("e" to email),
        ).use { rs -> generateSequence { if (rs.hasNext()) rs.next() else null }.count() }
        assertEquals(1, actualEdges, "Exactly one edge must exist in the graph despite two propagate() calls")
    }

    @Test
    fun `a different from-uid reactivating the same target creates a genuinely new edge`() = runBlocking {
        val email = "prop-distinct-evidence-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        seedPhrase(email, cycleSeq = 1, text = "Getting Alfrd running on Arx is a priority for me", open = true)
        val firstEvidence = seedPhrase(email, cycleSeq = 3, text = "Arx build finished compiling", open = false)
        val secondEvidence = seedPhrase(email, cycleSeq = 5, text = "Arx flash completed without errors", open = false)

        propagator.propagate(email, cycleSeq = 3, newPhrases = listOf(firstEvidence to "Arx build finished compiling"))
        val second = propagator.propagate(email, cycleSeq = 5, newPhrases = listOf(secondEvidence to "Arx flash completed without errors"))

        assertTrue(second is PropagationOutcome.Propagated)
        assertEquals(1, (second as PropagationOutcome.Propagated).edgesCreated.size, "Different evidence reactivating the same target is a genuinely new edge, not deduplicated away")
    }

    @Test
    fun `NoOpHorizonPropagator always no-ops regardless of input`() = runBlocking {
        val propagator = NoOpHorizonPropagator()
        val outcome = propagator.propagate("anyone@test.alfrd.internal", 1, listOf("uid" to "Arx build finished compiling, a priority for me"))
        assertEquals(PropagationOutcome.Propagated(emptyList()), outcome)
    }
}
