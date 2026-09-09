package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.api.queryPhrases
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.runBlocking
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HorizonGraphStoreTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var store: HorizonGraphStore

    @BeforeAll
    fun setUp() {
        val testDbPath = "./data/test-horizon-store-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        store = ArcadeHorizonGraphStore(dbManager.getDatabase())
    }

    @AfterAll
    fun tearDown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-horizon-store-") }
            ?.forEach { it.deleteRecursively() }
    }

    /** Seeds a User vertex + a conversational Source→ASSERTS→Phrase, mirroring DatabaseEngramClient.ingest(). */
    private fun seedConversationalPhrase(email: String, text: String): String {
        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()
        val phraseUid = UUID.randomUUID().toString()
        db.transaction {
            var userVertex = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
            if (userVertex == null) {
                userVertex = db.newVertex("User").apply {
                    set("uid", UUID.randomUUID().toString())
                    set("username", email.substringBefore("@"))
                    set("email", email)
                    set("tier", -1)
                    set("createdAt", now)
                    set("updatedAt", now)
                    save()
                }
            }
            val sourceName = "onboarding_conversation:$email"
            var sourceVertex = db.query("sql", "SELECT FROM Source WHERE name = :n", mapOf("n" to sourceName))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null }
            if (sourceVertex == null) {
                sourceVertex = db.newVertex("Source").apply {
                    set("uid", UUID.randomUUID().toString())
                    set("name", sourceName)
                    set("type", "onboarding_conversation")
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
            sourceVertex.newEdge("ASSERTS", phraseVertex, false).apply {
                set("context", "onboarding_conversation")
                set("timestamp", now)
                set("scores", """[{"type":"trust","perspective":"user","value":0.7},{"type":"salience","perspective":"author","value":0.9}]""")
                save()
            }
        }
        return phraseUid
    }

    /**
     * ASSERTS edges are created with `bidirectional=false` everywhere in this codebase, so
     * `Vertex.getEdges(IN, "ASSERTS")` object-graph traversal from the Phrase side is silently
     * empty (the same footgun the preceding increment documented for RELATED_TO) — this must query
     * via SQL `@in.uid` filtering instead, matching HorizonOwnership's approach.
     */
    private fun findAssertsEdgeForPhrase(phraseUid: String): com.arcadedb.graph.Edge {
        val db = dbManager.getDatabase()
        return db.query("sql", "SELECT FROM ASSERTS WHERE @in.uid = :u", mapOf("u" to phraseUid))
            .use { rs -> rs.next().toElement().asEdge() }
    }

    // ── Status lifecycle ─────────────────────────────────────────────────────

    @Test
    fun `status transitions append to statusHistory without mutating phrase text`() = runBlocking {
        val email = "horizon-store-status-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(email, "Getting Alfrd running on Arx is a priority for me.")

        assertTrue(store.markAssertionStatus(email, cycleSeq = 1, phraseUid = phraseUid, status = AssertionStatus.OPEN))
        assertTrue(store.markAssertionStatus(email, cycleSeq = 5, phraseUid = phraseUid, status = AssertionStatus.RESOLVED))
        assertTrue(store.markAssertionStatus(email, cycleSeq = 9, phraseUid = phraseUid, status = AssertionStatus.OPEN))

        val edge = findAssertsEdgeForPhrase(phraseUid)
        assertEquals("open", edge.get("status") as? String, "latest transition must win")
        assertEquals(9L, (edge.get("statusCycleSeq") as? Number)?.toLong(), "statusCycleSeq tracks the most recent status change")

        val history = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<StatusHistoryEntry>>(edge.get("statusHistory") as String)
        assertEquals(listOf("open", "resolved", "open"), history.map { it.state })

        val db = dbManager.getDatabase()
        val phraseText = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseUid))
            .use { rs -> rs.next().toElement().asVertex().get("text") as String }
        assertEquals("Getting Alfrd running on Arx is a priority for me.", phraseText, "status writes must never touch Phrase.text")
    }

    @Test
    fun `original assertion cycleSeq is never overwritten by a later status change`() = runBlocking {
        val email = "horizon-store-cycle-identity-${UUID.randomUUID()}@test.alfrd.internal"
        seedConversationalPhrase(email, "unrelated seed so the User vertex exists")
        // The environment-signal path is the only HorizonGraphStore write that stamps an original
        // cycleSeq at creation — conversational ingestion (DatabaseEngramClient, untouched this
        // increment) does not, so it can't demonstrate this distinction on its own.
        val phraseUid = store.ingestEnvironmentSignal(email, cycleSeq = 1, sourceName = "environment:x", text = "event")!!

        assertTrue(store.markAssertionStatus(email, cycleSeq = 5, phraseUid = phraseUid, status = AssertionStatus.OPEN))

        val edge = findAssertsEdgeForPhrase(phraseUid)
        assertEquals(1L, (edge.get("cycleSeq") as? Number)?.toLong(), "the original assertion cycle must survive a status change made in a later cycle")
        assertEquals(5L, (edge.get("statusCycleSeq") as? Number)?.toLong(), "the status change's own cycle is tracked separately")
    }

    @Test
    fun `markAssertionStatus rejects a cycleSeq that precedes the phrase's own original assertion`() = runBlocking {
        val email = "horizon-store-cycle-order-${UUID.randomUUID()}@test.alfrd.internal"
        seedConversationalPhrase(email, "unrelated seed so the User vertex exists")
        val phraseUid = store.ingestEnvironmentSignal(email, cycleSeq = 10, sourceName = "environment:x", text = "event")!!

        val result = store.markAssertionStatus(email, cycleSeq = 3, phraseUid = phraseUid, status = AssertionStatus.OPEN)
        assertFalse(result, "a status cannot be marked for a cycle before the phrase's own original assertion")

        val edge = findAssertsEdgeForPhrase(phraseUid)
        assertNull(edge.get("status") as? String, "a rejected out-of-order call must leave status untouched")
    }

    // ── Environment signal ingestion ─────────────────────────────────────────

    @Test
    fun `ingestEnvironmentSignal reuses an existing environment Source on a second call`() = runBlocking {
        val email = "horizon-store-env-${UUID.randomUUID()}@test.alfrd.internal"
        seedConversationalPhrase(email, "unrelated seed so the User vertex exists")
        val sourceName = "environment:arx-build-system"

        val uid1 = store.ingestEnvironmentSignal(email, cycleSeq = 2, sourceName = sourceName, text = "Build v0.9.1 finished.")
        val uid2 = store.ingestEnvironmentSignal(email, cycleSeq = 2, sourceName = sourceName, text = "Build v0.9.2 finished.")
        assertTrue(uid1 != null && uid2 != null && uid1 != uid2)

        val db = dbManager.getDatabase()
        // Scoped to this user's own TRUSTS edges, not a bare global name count: other test methods
        // in this PER_CLASS-shared database legitimately create their own, separate Source with the
        // same literal sourceName for a different user — that is exactly the per-user isolation
        // findOrCreateEnvironmentSource now provides (see its doc), not a bug. A global count would
        // conflate that with this user's own reuse, which is what this test actually checks.
        val trustedSourcesNamed = HorizonOwnership.trustedSourceUids(db, email).count { uid ->
            db.query("sql", "SELECT FROM Source WHERE uid = :u AND name = :n", mapOf("u" to uid, "n" to sourceName))
                .use { rs -> rs.hasNext() }
        }
        assertEquals(1, trustedSourcesNamed, "the same sourceName must reuse one Source vertex for this user, not create a second")

        val sourceVertex = db.query(
            "sql", "SELECT FROM Source WHERE name = :n AND uid IN :uids",
            mapOf("n" to sourceName, "uids" to HorizonOwnership.trustedSourceUids(db, email)),
        ).use { rs -> rs.next().toElement().asVertex() }
        assertEquals(ENVIRONMENT_SOURCE_TYPE, sourceVertex.get("type") as? String)
    }

    // ── relevant_to: incoming-side traversal regression guard ────────────────

    @Test
    fun `markRelevant creates an edge traversable from the target's incoming side`() = runBlocking {
        val email = "horizon-store-relevant-${UUID.randomUUID()}@test.alfrd.internal"
        val olderUid = seedConversationalPhrase(email, "Getting Alfrd running on Arx is a priority.")
        val newerUid = store.ingestEnvironmentSignal(email, cycleSeq = 2, sourceName = "environment:arx-build-system", text = "Build finished.")!!

        assertTrue(store.markRelevant(email, cycleSeq = 2, fromPhraseUid = newerUid, toPhraseUid = olderUid, strength = 1.0))

        val db = dbManager.getDatabase()
        val olderVertex = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to olderUid))
            .use { rs -> rs.next().toElement().asVertex() }
        // The exact traversal direction the preceding increment discovered must stay traversable:
        // querying INCOMING from the target, not outgoing from the trigger.
        val incoming = olderVertex.getEdges(Vertex.DIRECTION.IN, "RELATED_TO").toList()
        assertEquals(1, incoming.size, "regresses if createRelevantTo ever passes bidirectional=false")
        assertEquals("relevant_to", incoming.single().get("relationType") as? String)
        assertEquals(newerUid, incoming.single().getVertex(Vertex.DIRECTION.OUT)?.get("uid") as? String)
    }

    // ── supersedes: corrected direction + resolveCurrent ─────────────────────

    @Test
    fun `markSuperseded creates a traversable incoming edge and resolveCurrent finds the head of a 2-hop chain`() = runBlocking {
        val email = "horizon-store-supersedes-${UUID.randomUUID()}@test.alfrd.internal"
        val a = seedConversationalPhrase(email, "Delivery is Friday.")
        val b = seedConversationalPhrase(email, "Delivery is actually Monday.")
        val c = seedConversationalPhrase(email, "Delivery is now Wednesday.")

        assertTrue(store.markSuperseded(email, cycleSeq = 2, newerPhraseUid = b, olderPhraseUid = a))
        assertTrue(store.markSuperseded(email, cycleSeq = 3, newerPhraseUid = c, olderPhraseUid = b))

        val db = dbManager.getDatabase()
        val vertexA = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to a)).use { rs -> rs.next().toElement().asVertex() }
        val vertexB = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to b)).use { rs -> rs.next().toElement().asVertex() }
        val vertexC = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to c)).use { rs -> rs.next().toElement().asVertex() }

        // Incoming-side traversal must also work for supersedes — the corrected part of the design.
        assertEquals(1, vertexA.getEdges(Vertex.DIRECTION.IN, "RELATED_TO").count { it.get("relationType") == "supersedes" })
        assertEquals(1, vertexB.getEdges(Vertex.DIRECTION.IN, "RELATED_TO").count { it.get("relationType") == "supersedes" })

        val current = RelatedToEdges.resolveCurrent(listOf(vertexA, vertexB, vertexC))
        assertEquals(listOf(c), current.map { it.get("uid") as String })
    }

    // ── Adversarial cross-user writes ─────────────────────────────────────────

    @Test
    fun `markRelevant rejects a cross-user edge and creates nothing`() = runBlocking {
        val emailA = "horizon-store-xuser-a-${UUID.randomUUID()}@test.alfrd.internal"
        val emailB = "horizon-store-xuser-b-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseA = seedConversationalPhrase(emailA, "User A's private phrase.")
        val phraseB = seedConversationalPhrase(emailB, "User B's private phrase.")

        val result = store.markRelevant(emailA, cycleSeq = 1, fromPhraseUid = phraseA, toPhraseUid = phraseB)
        assertFalse(result, "a caller must not be able to link their own phrase to another user's")

        val db = dbManager.getDatabase()
        val phraseBVertex = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseB))
            .use { rs -> rs.next().toElement().asVertex() }
        assertTrue(phraseBVertex.getEdges(Vertex.DIRECTION.IN, "RELATED_TO").toList().isEmpty())
    }

    @Test
    fun `markSuperseded rejects a cross-user edge`() = runBlocking {
        val emailA = "horizon-store-xuser-sup-a-${UUID.randomUUID()}@test.alfrd.internal"
        val emailB = "horizon-store-xuser-sup-b-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseA = seedConversationalPhrase(emailA, "User A's fact.")
        val phraseB = seedConversationalPhrase(emailB, "User B's fact.")

        assertFalse(store.markSuperseded(emailA, cycleSeq = 1, newerPhraseUid = phraseA, olderPhraseUid = phraseB))
    }

    @Test
    fun `markAssertionStatus rejects a phrase that is not the caller's own`() = runBlocking {
        val emailA = "horizon-store-xuser-status-a-${UUID.randomUUID()}@test.alfrd.internal"
        val emailB = "horizon-store-xuser-status-b-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseB = seedConversationalPhrase(emailB, "User B's fact.")

        assertFalse(store.markAssertionStatus(emailA, cycleSeq = 1, phraseUid = phraseB, status = AssertionStatus.OPEN))
        val edge = findAssertsEdgeForPhrase(phraseB)
        assertNull(edge.get("status") as? String, "a rejected cross-user call must leave the phrase's status untouched")
    }

    // ── Regression: existing queryPhrases path must be unaffected by the new ASSERTS properties ──

    @Test
    fun `queryPhrases still returns correct scores for a phrase carrying a status`() = runBlocking {
        val email = "horizon-store-regression-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(email, "Getting Alfrd running on Arx is a priority for me.")
        assertTrue(store.markAssertionStatus(email, cycleSeq = 1, phraseUid = phraseUid, status = AssertionStatus.OPEN))

        val results = queryPhrases(dbManager.getDatabase(), email, concept = null, limit = 50)
        val scored = results.single { it.uid == phraseUid }
        assertEquals(0.7, scored.scores["trust"] ?: 0.0, 0.001, "adding a status must not zero out the existing trust score")
        assertEquals(0.9, scored.scores["salience"] ?: 0.0, 0.001, "adding a status must not zero out the existing salience score")
    }

    /**
     * Regression test: [ArcadeHorizonGraphStore.markAssertionStatus] used to `takeLast(20)` the
     * appended `statusHistory` list, deleting older transitions from durable storage — an audit
     * trail is supposed to be durable, and nothing in [HorizonAssembler] even reads this field back
     * (bounding what a *read* returns, if one ever surfaces it, is that read path's job, not the
     * writer's). Fixed by appending without truncation.
     */
    @Test
    fun `more than 20 status transitions are all preserved durably, never truncated on write`() = runBlocking {
        val email = "horizon-store-history-durability-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(email, "a phrase with a long status history")

        val transitionCount = 25
        val expectedStates = (1..transitionCount).map { i -> if (i % 2 == 1) "open" else "resolved" }
        expectedStates.forEachIndexed { index, _ ->
            val status = if (index % 2 == 0) AssertionStatus.OPEN else AssertionStatus.RESOLVED
            assertTrue(store.markAssertionStatus(email, cycleSeq = (index + 1).toLong(), phraseUid = phraseUid, status = status))
        }

        val edge = findAssertsEdgeForPhrase(phraseUid)
        val history = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<StatusHistoryEntry>>(edge.get("statusHistory") as String)
        assertEquals(transitionCount, history.size, "every one of the $transitionCount transitions must be preserved, not capped at 20")
        assertEquals(expectedStates, history.map { it.state })
    }

    // ── stampNewAssertion ────────────────────────────────────────────────────

    @Test
    fun `stampNewAssertion stamps cycleSeq and status atomically on a freshly created phrase`() = runBlocking {
        val email = "horizon-store-stamp-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(email, "Getting Alfrd running on Arx is a priority for me.")

        assertTrue(store.stampNewAssertion(email, cycleSeq = 4, phraseUid = phraseUid, status = AssertionStatus.OPEN))

        val edge = findAssertsEdgeForPhrase(phraseUid)
        assertEquals(4L, (edge.get("cycleSeq") as Number).toLong())
        assertEquals("open", edge.get("status"))
        assertEquals(4L, (edge.get("statusCycleSeq") as Number).toLong())
    }

    @Test
    fun `stampNewAssertion with status null stamps cycleSeq only`() = runBlocking {
        val email = "horizon-store-stamp-fact-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(email, "My dog's name is Newton.")

        assertTrue(store.stampNewAssertion(email, cycleSeq = 2, phraseUid = phraseUid, status = null))

        val edge = findAssertsEdgeForPhrase(phraseUid)
        assertEquals(2L, (edge.get("cycleSeq") as Number).toLong())
        assertNull(edge.get("status"), "a plain fact must never gain a status")
    }

    @Test
    fun `stampNewAssertion is idempotent for a resumed attempt with the same cycleSeq`() = runBlocking {
        val email = "horizon-store-stamp-resume-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(email, "Getting Alfrd running on Arx is a priority for me.")

        assertTrue(store.stampNewAssertion(email, cycleSeq = 4, phraseUid = phraseUid, status = AssertionStatus.OPEN))
        assertTrue(store.stampNewAssertion(email, cycleSeq = 4, phraseUid = phraseUid, status = AssertionStatus.OPEN))

        val edge = findAssertsEdgeForPhrase(phraseUid)
        val history = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<StatusHistoryEntry>>(edge.get("statusHistory") as String)
        assertEquals(1, history.size, "a resumed stamp with the same (status, cycleSeq) must not append a duplicate history entry")
    }

    @Test
    fun `stampNewAssertion rejects re-stamping an already-stamped phrase with a different cycleSeq`() = runBlocking {
        val email = "horizon-store-stamp-conflict-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(email, "Getting Alfrd running on Arx is a priority for me.")

        assertTrue(store.stampNewAssertion(email, cycleSeq = 4, phraseUid = phraseUid, status = AssertionStatus.OPEN))
        val secondAttempt = store.stampNewAssertion(email, cycleSeq = 99, phraseUid = phraseUid, status = AssertionStatus.OPEN)

        val edge = findAssertsEdgeForPhrase(phraseUid)
        assertFalse(secondAttempt, "a conflicting cycleSeq must be rejected, not silently overwrite the original")
        assertEquals(4L, (edge.get("cycleSeq") as Number).toLong(), "the original cycleSeq must never be overwritten")
    }

    @Test
    fun `stampNewAssertion returns false for a phrase not owned by the caller`() = runBlocking {
        val ownerEmail = "horizon-store-stamp-owner-${UUID.randomUUID()}@test.alfrd.internal"
        val otherEmail = "horizon-store-stamp-other-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(ownerEmail, "Getting Alfrd running on Arx is a priority for me.")

        assertFalse(store.stampNewAssertion(otherEmail, cycleSeq = 1, phraseUid = phraseUid, status = AssertionStatus.OPEN))
    }

    // ── phraseText ───────────────────────────────────────────────────────────

    @Test
    fun `phraseText returns the text of an owned phrase`() = runBlocking {
        val email = "horizon-store-text-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(email, "Getting Alfrd running on Arx is a priority for me.")

        assertEquals("Getting Alfrd running on Arx is a priority for me.", store.phraseText(email, phraseUid))
    }

    @Test
    fun `phraseText returns null for an unowned or unknown phrase`() = runBlocking {
        val ownerEmail = "horizon-store-text-owner-${UUID.randomUUID()}@test.alfrd.internal"
        val otherEmail = "horizon-store-text-other-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhrase(ownerEmail, "a private phrase")

        assertNull(store.phraseText(otherEmail, phraseUid))
        assertNull(store.phraseText(ownerEmail, "nonexistent-${UUID.randomUUID()}"))
    }
}
