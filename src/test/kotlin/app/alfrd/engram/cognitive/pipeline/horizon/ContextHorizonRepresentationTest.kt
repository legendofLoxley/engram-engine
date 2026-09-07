package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.graph.Vertex
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

/**
 * Proves exactly two things about the illustrative Context Horizon representation described in
 * the "Define conversational Context Horizon fixtures" Notion task — nothing about propagation:
 *
 * 1. REPRESENTATION — the existing schema (an environment-typed [Source], and a `relevant_to`
 *    [RELATED_TO] edge) can hold the structures the worked fixture describes, with zero
 *    production schema changes.
 * 2. CONSUMPTION — a read shaped like a future Horizon-assembly step can correctly pick out the
 *    reactivated item from that representation, and correctly ignores unrelated phrases.
 *
 * This test manually seeds the `relevant_to` edge exactly as a (not-yet-built) propagation step
 * would produce it. It does NOT exercise or validate propagation's own judgment — deciding that
 * the environment event is relevant, and deciding that restraint is the correct response, are
 * both bypassed here by construction. See the Notion task for that distinction in full.
 *
 * `EngramClient.ingest()` cannot represent the environment-sourced phrase as-is — it hardcodes
 * `Source.type = "onboarding_conversation"` (`DatabaseEngramClient.kt:47`) — so this test writes
 * directly via `db`, the same way [app.alfrd.engram.api.DebugConverseService] does for synthetic
 * users, rather than going through [app.alfrd.engram.cognitive.pipeline.memory.EngramClient].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContextHorizonRepresentationTest {

    private lateinit var dbManager: DatabaseManager
    private val email = "horizon-fixture-${UUID.randomUUID()}@test.alfrd.internal"

    @BeforeAll
    fun setUp() {
        val testDbPath = "./data/test-horizon-repr-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
    }

    @AfterAll
    fun tearDown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-horizon-repr-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `environment-sourced phrase and a relevant_to edge round-trip through the existing schema unmodified`() {
        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()

        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", "horizon-fixture")
                set("email", email)
                set("tier", -1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
        }
        val userVertex: Vertex = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email))
            .use { rs -> rs.next().toElement().asVertex() }

        // Turn 1's statement, via the existing conversational shape: User -TRUSTS-> Source -ASSERTS-> Phrase.
        val priorityPhraseUid = UUID.randomUUID().toString()
        db.transaction {
            val convoSource = db.newVertex("Source").apply {
                set("uid", UUID.randomUUID().toString())
                set("name", "onboarding_conversation:$email")
                set("type", "onboarding_conversation")
                set("metadata", "{}")
                save()
            }
            userVertex.modify().newEdge("TRUSTS", convoSource, false).apply {
                set("scores", "[]")
                save()
            }
            val priorityPhrase = db.newVertex("Phrase").apply {
                set("uid", priorityPhraseUid)
                set("text", "Getting Alfrd running on Arx is a priority for me — I keep meaning to get to it.")
                set("hash", priorityPhraseUid) // uniqueness is all this test needs, not real content hashing
                set("visibility", "private")
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            // Proposed extension of the existing ASSERTS.scores JSON array — not yet written by
            // any production code path. Illustrative here, not a schema change: `scores` already
            // accepts arbitrary {"type",...} entries (see OnboardingService's salience entries).
            convoSource.newEdge("ASSERTS", priorityPhrase, false).apply {
                set("context", "onboarding_conversation")
                set("timestamp", now)
                set("scores", """[{"type":"trust","perspective":"user","value":0.7},{"type":"status","value":"open"}]""")
                save()
            }
        }

        // The environment event, seeded directly — this is the part `ingest()` cannot do today.
        val eventPhraseUid = UUID.randomUUID().toString()
        db.transaction {
            val envSource = db.newVertex("Source").apply {
                set("uid", UUID.randomUUID().toString())
                set("name", "environment:arx-build-system")
                set("type", "environment_signal")
                set("metadata", "{}")
                save()
            }
            // Same TRUSTS/ASSERTS shape as a conversational fact, deliberately — so a future
            // assembly read can traverse User->TRUSTS->Source->ASSERTS->Phrase uniformly instead
            // of needing a second, environment-specific query shape.
            userVertex.modify().newEdge("TRUSTS", envSource, false).apply {
                set("scores", "[]")
                save()
            }
            val eventPhrase = db.newVertex("Phrase").apply {
                set("uid", eventPhraseUid)
                set("text", "Arx developer build v0.9.2 finished compiling and is ready to flash — no errors.")
                set("hash", eventPhraseUid)
                set("visibility", "private")
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            envSource.newEdge("ASSERTS", eventPhrase, false).apply {
                set("context", "environment_signal")
                set("timestamp", now)
                set("scores", "[]")
                save()
            }
        }

        // What a (not-yet-built) propagation step would produce: the newer item's relevance to an
        // older one is expressed as an edge FROM the newer phrase TO the one it concerns.
        db.transaction {
            val eventPhrase = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to eventPhraseUid))
                .use { rs -> rs.next().toElement().asVertex().modify() }
            val priorityPhrase = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to priorityPhraseUid))
                .use { rs -> rs.next().toElement().asVertex().modify() }
            // bidirectional=true (unlike every other edge type in this codebase, which all pass
            // false since they're only ever queried outward): consumption here deliberately reads
            // backward ("what's just been made relevant to X"), which requires the incoming side
            // to be indexed too — confirmed empirically, `false` left IN-direction traversal empty.
            eventPhrase.newEdge("RELATED_TO", priorityPhrase, true).apply {
                set("relationType", "relevant_to")
                set("strength", 1.0)
                save()
            }
        }

        // ── (1) Representation: both phrases exist, are queryable per-user, and are
        // distinguishable by their Source.type without any schema change. ──────────────────────
        // Re-fetch: `userVertex` above predates the TRUSTS edges added since, and ArcadeDB
        // vertex snapshots don't reflect edges added after they were read.
        val freshUserVertex = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email))
            .use { rs -> rs.next().toElement().asVertex() }
        val sourceTypesForUser = freshUserVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS")
            .mapNotNull { it.get("type") as? String }
            .toSet()
        assertEquals(
            setOf("onboarding_conversation", "environment_signal"),
            sourceTypesForUser,
            "Both the conversational and environment-signal sources must be reachable and distinguishable",
        )

        val eventPhraseVertex = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to eventPhraseUid))
            .use { rs -> rs.next().toElement().asVertex() }
        val relevantToEdges = eventPhraseVertex.getEdges(Vertex.DIRECTION.OUT, "RELATED_TO").toList()
        assertEquals(1, relevantToEdges.size, "Exactly one RELATED_TO edge must originate at the event phrase")
        val edge = relevantToEdges.single()
        assertEquals("relevant_to", edge.get("relationType") as? String)
        assertEquals(
            priorityPhraseUid,
            edge.getVertex(Vertex.DIRECTION.IN)?.get("uid") as? String,
            "Edge must point FROM the newer (event) phrase TO the item it makes relevant",
        )

        // ── (2) Consumption: a Horizon-assembly-shaped read finds the reactivated item, and only
        // that item — proving the representation is usable, not just storable. ──────────────────
        val reactivatedPhraseTexts = findReactivatedCandidates(freshUserVertex)
        assertEquals(
            setOf("Getting Alfrd running on Arx is a priority for me — I keep meaning to get to it."),
            reactivatedPhraseTexts,
            "Assembly read must surface exactly the phrase made relevant, not the event itself or anything unrelated",
        )
    }

    /**
     * Stand-in for a future Horizon-assembly read: phrases belonging to sources this user trusts
     * that are the *target* of an incoming `relevant_to` edge (i.e., something just made them
     * newly relevant). Test-only illustration, not production code — a real implementation is
     * the "cycle" task's job, not this one's.
     */
    private fun findReactivatedCandidates(userVertex: Vertex): Set<String> {
        val db = dbManager.getDatabase()
        val texts = mutableSetOf<String>()
        for (source in userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS")) {
            for (assertsEdge in source.getEdges(Vertex.DIRECTION.OUT, "ASSERTS")) {
                val phraseUid = assertsEdge.getVertex(Vertex.DIRECTION.IN)?.get("uid") as? String ?: continue
                // Re-query by uid rather than reusing the vertex handed back by traversal — see
                // the note above on `freshUserVertex`; the same staleness applies here.
                val phrase = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseUid))
                    .use { rs -> rs.next().toElement().asVertex() }
                val isReactivated = phrase.getEdges(Vertex.DIRECTION.IN, "RELATED_TO")
                    .any { it.get("relationType") as? String == "relevant_to" }
                if (isReactivated) {
                    (phrase.get("text") as? String)?.let { texts += it }
                }
            }
        }
        return texts
    }

    @Test
    fun `a phrase with no incoming relevant_to edge is not surfaced by the assembly read`() {
        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()
        val unrelatedEmail = "horizon-fixture-unrelated-${UUID.randomUUID()}@test.alfrd.internal"

        val userVertex: Vertex
        db.transaction {
            val user = db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", "horizon-fixture-unrelated")
                set("email", unrelatedEmail)
                set("tier", -1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            val source = db.newVertex("Source").apply {
                set("uid", UUID.randomUUID().toString())
                set("name", "onboarding_conversation:$unrelatedEmail")
                set("type", "onboarding_conversation")
                set("metadata", "{}")
                save()
            }
            user.newEdge("TRUSTS", source, false).apply { set("scores", "[]"); save() }
            val phrase = db.newVertex("Phrase").apply {
                set("uid", UUID.randomUUID().toString())
                set("text", "Anyway — what's a good way to structure the data model for my grocery list app?")
                set("hash", UUID.randomUUID().toString())
                set("visibility", "private")
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            source.newEdge("ASSERTS", phrase, false).apply {
                set("context", "onboarding_conversation")
                set("timestamp", now)
                set("scores", "[]")
                save()
            }
        }
        userVertex = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to unrelatedEmail))
            .use { rs -> rs.next().toElement().asVertex() }

        assertTrue(
            findReactivatedCandidates(userVertex).isEmpty(),
            "An ordinary phrase with no relevant_to edge pointing at it must not be surfaced as reactivated",
        )
    }
}
