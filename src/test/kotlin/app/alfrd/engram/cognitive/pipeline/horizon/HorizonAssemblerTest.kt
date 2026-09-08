package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.database.Database
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Exercises the worked Arx fixture from the "Define conversational Context Horizon fixtures"
 * Notion task against real [HorizonGraphStore]/[HorizonAssembler] production code — every
 * `relevant_to`/`supersedes` edge is seeded directly by the test, standing in for a future
 * propagation step, never a demonstration of propagation itself.
 */
class HorizonAssemblerTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var store: HorizonGraphStore
    private lateinit var assembler: HorizonAssembler
    private val tempDirPrefixes = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        val prefix = "test-horizon-asm-${System.nanoTime()}"
        tempDirPrefixes += prefix
        dbManager = DatabaseManager("./data/$prefix")
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        store = ArcadeHorizonGraphStore(dbManager.getDatabase())
        assembler = ArcadeHorizonAssembler(dbManager.getDatabase())
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        tempDirPrefixes.forEach { prefix ->
            File("./data").listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.deleteRecursively() }
        }
    }

    /** Seeds a User + conversational Source→ASSERTS→Phrase with an explicit cycleSeq stamped on the ASSERTS edge. */
    private fun seedConversationalPhraseWithCycle(db: Database, email: String, text: String, cycleSeq: Long): String {
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
            // Ordinary conversational ingestion (DatabaseEngramClient.ingest(), untouched this
            // increment) does not stamp cycleSeq — this test stands in for that future ingest-path
            // update, the same way the preceding increment wrote environment-sourced phrases
            // directly because EngramClient.ingest() couldn't represent them either.
            sourceVertex.newEdge("ASSERTS", phraseVertex, false).apply {
                set("context", "onboarding_conversation")
                set("timestamp", now)
                set("scores", """[{"type":"trust","perspective":"user","value":0.7}]""")
                set("cycleSeq", cycleSeq)
                save()
            }
        }
        return phraseUid
    }

    private fun seedConversationalPhraseWithCycle(email: String, text: String, cycleSeq: Long): String =
        seedConversationalPhraseWithCycle(dbManager.getDatabase(), email, text, cycleSeq)

    /** Unwraps a successful assembly, failing loudly (not silently) on an unexpected ConsistencyFailure. */
    private fun AssembleOutcome.expectSuccess(): ContextHorizon {
        check(this is AssembleOutcome.Assembled) { "expected a successful assembly, got: $this" }
        return horizon
    }

    private fun phraseExists(db: Database, phraseUid: String): Boolean =
        db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseUid)).use { it.hasNext() }

    // ── Worked Arx fixture: full classification table, driven by cycleSeq not timestamps ────────

    @Test
    fun `worked Arx fixture classifies every item correctly across closely-spaced turns`() = runBlocking {
        val email = "horizon-fixture-${UUID.randomUUID()}@test.alfrd.internal"

        // Turn 1 (cycleSeq=1): the priority statement — an open intention, executed with zero delay
        // before the assertions below run, proving classification is cycle-identity based, not
        // wall-clock-gap based.
        val arxUid = seedConversationalPhraseWithCycle(
            email, "Getting Alfrd running on Arx is a priority for me — I keep meaning to get to it.", cycleSeq = 1,
        )
        assertTrue(store.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN))

        val afterTurn1 = assembler.assemble(email, currentCycleSeq = 1).expectSuccess()
        val arxAtTurn1 = afterTurn1.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertEquals(HorizonItemCategory.INTENTION, arxAtTurn1.category)
        assertEquals(AssertionStatus.OPEN, arxAtTurn1.status)
        assertEquals(ProvenanceKind.EXPLICIT_USER_STATEMENT, arxAtTurn1.provenance)
        assertEquals(SurfacingReason.JustAsserted(1), arxAtTurn1.surfacing)

        // Turn 2 (cycleSeq=2) — an unrelated subject change. No status is ever attached to it.
        val groceryUid = seedConversationalPhraseWithCycle(
            email, "Anyway — what's a good way to structure the data model for my grocery list app?", cycleSeq = 2,
        )

        val afterTurn2 = assembler.assemble(email, currentCycleSeq = 2).expectSuccess()
        val groceryItem = afterTurn2.items.single { it.sourceRefs.first().phraseUid == groceryUid }
        assertEquals(HorizonItemCategory.FACT, groceryItem.category)
        assertNull(groceryItem.status)
        assertEquals(SurfacingReason.JustAsserted(2), groceryItem.surfacing)

        val arxAtTurn2 = afterTurn2.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertEquals(
            SurfacingReason.DormantOpen(1), arxAtTurn2.surfacing,
            "ordinary topic change — no explicit pin instruction — must recede to dormant, not vanish",
        )

        // Environment event, ingested in the same cycle as turn 2, BEFORE any relevant_to edge exists.
        val eventUid = store.ingestEnvironmentSignal(
            email, cycleSeq = 2, sourceName = "environment:arx-build-system",
            text = "Arx developer build v0.9.2 finished compiling and is ready to flash — no errors.",
        )!!

        val beforePropagation = assembler.assemble(email, currentCycleSeq = 2).expectSuccess()
        val eventItem = beforePropagation.items.single { it.sourceRefs.first().phraseUid == eventUid }
        assertEquals(HorizonItemCategory.ENVIRONMENT_EVENT, eventItem.category)
        assertEquals(ProvenanceKind.ENVIRONMENT_SIGNAL, eventItem.provenance)
        assertEquals(SurfacingReason.JustAsserted(2), eventItem.surfacing)
        val arxBeforePropagation = beforePropagation.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertEquals(
            SurfacingReason.DormantOpen(1), arxBeforePropagation.surfacing,
            "nothing connects the event to the priority item yet — propagation has not run",
        )

        // Test setup standing in for a future propagation decision — NOT a demonstration of
        // propagation. Seeded directly, exactly as the preceding increment's fixture test did.
        assertTrue(store.markRelevant(email, cycleSeq = 2, fromPhraseUid = eventUid, toPhraseUid = arxUid, strength = 1.0))

        // Turn 3 (cycleSeq=3), immediately after — the user continues the unrelated task.
        val turn3Uid = seedConversationalPhraseWithCycle(
            email, "Okay, and how should I handle the shopping history — separate table or just a flag on each item?",
            cycleSeq = 3,
        )

        val afterTurn3 = assembler.assemble(email, currentCycleSeq = 3).expectSuccess()
        val arxReactivated = afterTurn3.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertTrue(arxReactivated.surfacing is SurfacingReason.ActiveReactivation)
        val info = (arxReactivated.surfacing as SurfacingReason.ActiveReactivation).info
        assertEquals(eventUid, info.triggeringPhraseUid)
        assertEquals(2L, info.edgeCycleSeq)
        assertEquals(HorizonItemCategory.INTENTION, arxReactivated.category, "reactivation must not change what kind of content this is")
        assertEquals(AssertionStatus.OPEN, arxReactivated.status)

        val turn3Item = afterTurn3.items.single { it.sourceRefs.first().phraseUid == turn3Uid }
        assertEquals(SurfacingReason.JustAsserted(3), turn3Item.surfacing)

        assertTrue(
            afterTurn3.items.none { it.sourceRefs.first().phraseUid == groceryUid },
            "turn 2's grocery phrase has no status and is no longer JustAsserted — it must not linger as a Horizon candidate",
        )
    }

    // ── Reactivation lifecycle: decays by cycle count, never proven via a real sleep ─────────────

    @Test
    fun `reactivation decays by cycle count, not wall-clock time`() = runBlocking {
        val email = "horizon-lifecycle-${UUID.randomUUID()}@test.alfrd.internal"
        val arxUid = seedConversationalPhraseWithCycle(email, "Arx priority.", cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN)
        val eventUid = store.ingestEnvironmentSignal(email, cycleSeq = 1, sourceName = "environment:x", text = "event")!!
        store.markRelevant(email, cycleSeq = 1, fromPhraseUid = eventUid, toPhraseUid = arxUid)

        val within = assembler.assemble(email, currentCycleSeq = 3, activeRelevanceCycles = 3).expectSuccess()
        assertTrue(within.items.single { it.sourceRefs.first().phraseUid == arxUid }.surfacing is SurfacingReason.ActiveReactivation)

        val past = assembler.assemble(email, currentCycleSeq = 10, activeRelevanceCycles = 3).expectSuccess()
        assertEquals(
            SurfacingReason.DormantOpen(1),
            past.items.single { it.sourceRefs.first().phraseUid == arxUid }.surfacing,
            "reactivation must decay once the cycle window has passed, not persist forever",
        )
    }

    // ── Cycle identity: original assertion distinct from later status changes ────────────────────

    @Test
    fun `a status change in a later cycle does not make an old phrase read as JustAsserted`() = runBlocking {
        val email = "horizon-cycle-identity-${UUID.randomUUID()}@test.alfrd.internal"
        val arxUid = seedConversationalPhraseWithCycle(email, "Arx priority.", cycleSeq = 1)
        // Status is set FOUR cycles after the phrase's own original assertion.
        store.markAssertionStatus(email, cycleSeq = 5, phraseUid = arxUid, status = AssertionStatus.OPEN)

        val horizon = assembler.assemble(email, currentCycleSeq = 5).expectSuccess()
        val item = horizon.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertTrue(
            item.surfacing !is SurfacingReason.JustAsserted,
            "the CONTENT was asserted in cycle 1, not cycle 5 — a status edit must not read as a fresh statement",
        )
        assertEquals(
            SurfacingReason.DormantOpen(5), item.surfacing,
            "surfacing should reflect the status's own recency (cycle 5), not claim the content is fresh",
        )
    }

    @Test
    fun `future-cycle evidence is excluded from the Horizon, not misclassified`() = runBlocking {
        val email = "horizon-future-exclusion-${UUID.randomUUID()}@test.alfrd.internal"
        // Simulates a caller sequencing bug: content whose own assertion cycle is ahead of the
        // cycle assemble() is being asked about.
        val futureUid = seedConversationalPhraseWithCycle(email, "This claims to be from the future.", cycleSeq = 100)
        val horizon = assembler.assemble(email, currentCycleSeq = 5).expectSuccess()
        assertTrue(
            horizon.items.none { it.sourceRefs.first().phraseUid == futureUid },
            "evidence whose cycleSeq is ahead of currentCycleSeq must be excluded, never surfaced as JustAsserted/DormantOpen",
        )
    }

    @Test
    fun `an open item whose status was marked in a future cycle is excluded until that cycle arrives`() = runBlocking {
        val email = "horizon-future-status-${UUID.randomUUID()}@test.alfrd.internal"
        val openUid = seedConversationalPhraseWithCycle(email, "Old open item.", cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = openUid, status = AssertionStatus.OPEN)
        // Simulate an out-of-order write bypassing the store's own guard (e.g. a bad migration):
        // push statusCycleSeq into the future directly.
        val db = dbManager.getDatabase()
        db.transaction {
            db.query("sql", "SELECT FROM ASSERTS WHERE @in.uid = :u", mapOf("u" to openUid)).use { rs ->
                rs.next().toElement().asEdge().modify().apply { set("statusCycleSeq", 999L); save() }
            }
        }

        val horizon = assembler.assemble(email, currentCycleSeq = 5).expectSuccess()
        assertTrue(
            horizon.items.none { it.sourceRefs.first().phraseUid == openUid },
            "an item whose status was (incorrectly) marked in a future cycle must not be surfaced yet",
        )
    }

    // ── Consistent, committed read ────────────────────────────────────────────────────────────

    @Test
    fun `an awaited write is always visible to the very next assemble call`() = runBlocking {
        val email = "horizon-consistency-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhraseWithCycle(email, "some fact", cycleSeq = 1)
        assertTrue(store.markAssertionStatus(email, cycleSeq = 1, phraseUid = phraseUid, status = AssertionStatus.OPEN))

        val horizon = assembler.assemble(email, currentCycleSeq = 1).expectSuccess()
        assertTrue(horizon.items.any { it.sourceRefs.first().phraseUid == phraseUid && it.status == AssertionStatus.OPEN })
    }

    /**
     * A sequential await-then-read (the test above) only proves ordering between two calls made
     * one after another by the SAME caller — it says nothing about what a concurrent writer, from
     * a different thread, racing an in-progress `assemble()` call, can do to the result. This test
     * uses `ArcadeHorizonAssembler.testMidAssemblySync` to deterministically start a real,
     * independently-committed write on another thread strictly between `assemble()`'s
     * candidate-pool fetch and its reactivation fetch, then verifies mutual exclusion actually
     * holds — not merely that some plausible-looking outcome came back.
     *
     * **What was tried and rejected before this mechanism, with evidence (see
     * [HorizonConsistencyLock] for the full account):**
     * - Sequential await-then-read alone (insufficient by construction — this test exists because
     *   that one cannot detect a torn cross-query read).
     * - `Database.setTransactionIsolationLevel(REPEATABLE_READ)` — tested against this exact
     *   scenario; the concurrent write was still observed by the later query. No change.
     * - `Database.executeInReadLock`/`executeInWriteLock` — ArcadeDB's own native locks.
     *   Reproduced a real deadlock: these are thread-affine, and this codebase's writers are
     *   `suspend fun`s that hop threads via `withContext(Dispatchers.IO)`; a writer thread that
     *   entered `executeInWriteLock` and then called such a suspend function hung forever, and
     *   `Database.close()` — which independently needs that same internal lock — hung too.
     *   Confirmed via `jstack`: one thread parked in `ReentrantReadWriteLock$WriteLock.lock` inside
     *   `LocalDatabase.executeInWriteLock` called from `LocalDatabase.close`, contending with
     *   another thread still inside `executeInWriteLock`'s own callable.
     *
     * The mechanism that survived: a plain JVM `ReentrantReadWriteLock`, one per `Database`
     * instance, acquired and released entirely within one synchronous `db.transaction { }` call
     * inside the same `withContext(Dispatchers.IO)` block that runs it — never held across a
     * suspension point, so lock and unlock always happen on the same thread.
     */
    @Test
    fun `a concurrent write is excluded until assemble releases its read lock, then proceeds and is not reflected`() = runBlocking {
        val email = "horizon-concurrency-${UUID.randomUUID()}@test.alfrd.internal"
        // Arx is asserted at cycle 1; assemble() below runs at cycle 3, so absent the concurrent
        // write it reads as DormantOpen(1) — distinct from JustAsserted, so the assertion below
        // actually distinguishes "excluded" from "not excluded" rather than being trivially true.
        val arxUid = seedConversationalPhraseWithCycle(email, "Arx priority.", cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN)
        val eventUid = store.ingestEnvironmentSignal(email, cycleSeq = 1, sourceName = "environment:concurrency", text = "event")!!
        // No relevant_to edge yet — the concurrent writer below creates it mid-assembly.

        val arcadeAssembler = assembler as ArcadeHorizonAssembler
        var writerCommittedAtMillis: Long? = null
        val writer = Thread {
            // A plain call — the write lock this acquires is entirely internal to markRelevant()
            // itself (see ArcadeHorizonGraphStore); nothing external needs to wrap it.
            val committed = runBlocking { store.markRelevant(email, cycleSeq = 3, fromPhraseUid = eventUid, toPhraseUid = arxUid) }
            if (committed) writerCommittedAtMillis = System.currentTimeMillis()
        }
        // Fire-and-forget: assemble() must NOT wait for the writer — with correct mutual exclusion
        // in place, the writer cannot finish until assemble() releases its read lock, so blocking
        // here would deadlock the test against its own correctness guarantee.
        arcadeAssembler.testMidAssemblySync = { writer.start() }

        val outcome = try {
            assembler.assemble(email, currentCycleSeq = 3, activeRelevanceCycles = 3)
        } finally {
            arcadeAssembler.testMidAssemblySync = null
        }
        val assembleEnd = System.currentTimeMillis()
        val horizon = outcome.expectSuccess()

        writer.join(10_000)
        assertTrue(writerCommittedAtMillis != null, "the concurrent write must eventually succeed once the read lock is released")
        assertTrue(
            writerCommittedAtMillis!! >= assembleEnd,
            "the write must not be able to commit until assemble() has fully released its read lock — " +
                "committed at $writerCommittedAtMillis, assemble() ended at $assembleEnd",
        )

        // Excluded entirely: the snapshot reflects the coherent "before" state, not a torn mix.
        val item = horizon.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertEquals(
            SurfacingReason.DormantOpen(1), item.surfacing,
            "the concurrent write was excluded from this read entirely — the snapshot must reflect the state as it stood before the write, not a partial view of it",
        )
    }

    /**
     * The other half of "coherent before-or-after, or an explicit failure": if the lock genuinely
     * cannot be acquired in time, `assemble()` must say so, not guess. Uses a short
     * [ArcadeHorizonAssembler] `lockTimeoutMs` so the failure path is exercised deterministically
     * and quickly, rather than waiting out the 3-second production default.
     */
    @Test
    fun `assemble returns an explicit ConsistencyFailure when the lock cannot be acquired in time, never a mixed snapshot`() = runBlocking {
        val email = "horizon-lock-timeout-${UUID.randomUUID()}@test.alfrd.internal"
        seedConversationalPhraseWithCycle(email, "some fact", cycleSeq = 1)

        val shortTimeoutAssembler = ArcadeHorizonAssembler(dbManager.getDatabase(), lockTimeoutMs = 150)
        val writeLock = HorizonConsistencyLock.forDatabase(dbManager.getDatabase()).writeLock()
        writeLock.lock() // simulates a writer that is busy for longer than the assembler's timeout
        try {
            val outcome = shortTimeoutAssembler.assemble(email, currentCycleSeq = 1)
            assertTrue(
                outcome is AssembleOutcome.ConsistencyFailure,
                "expected an explicit ConsistencyFailure while the write lock is held elsewhere, got: $outcome",
            )
        } finally {
            writeLock.unlock()
        }

        // Once released, an ordinary call succeeds normally — the failure was specific to the
        // contended window, not a permanent break.
        val recovered = shortTimeoutAssembler.assemble(email, currentCycleSeq = 1)
        assertTrue(recovered is AssembleOutcome.Assembled, "expected assembly to succeed once the lock is free, got: $recovered")
    }

    // ── Adversarial cross-user read: defense in depth even if a bad edge already exists ─────────

    @Test
    fun `assemble never surfaces another user's phrase even via a store-bypassing raw edge`() = runBlocking {
        val emailA = "horizon-xread-a-${UUID.randomUUID()}@test.alfrd.internal"
        val emailB = "horizon-xread-b-${UUID.randomUUID()}@test.alfrd.internal"
        val arxUidA = seedConversationalPhraseWithCycle(emailA, "User A's private open intention.", cycleSeq = 1)
        store.markAssertionStatus(emailA, cycleSeq = 1, phraseUid = arxUidA, status = AssertionStatus.OPEN)
        val attackerPhraseB = seedConversationalPhraseWithCycle(emailB, "User B's private attack-payload phrase.", cycleSeq = 1)

        val db = dbManager.getDatabase()
        db.transaction {
            val from = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to attackerPhraseB))
                .use { it.next().toElement().asVertex().modify() }
            val to = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to arxUidA))
                .use { it.next().toElement().asVertex().modify() }
            RelatedToEdges.createRelevantTo(from, to, strength = 1.0, cycleSeq = 1, createdAt = System.currentTimeMillis())
        }

        val horizon = assembler.assemble(emailA, currentCycleSeq = 1).expectSuccess()
        val arxItem = horizon.items.single { it.sourceRefs.first().phraseUid == arxUidA }
        assertTrue(
            arxItem.surfacing !is SurfacingReason.ActiveReactivation,
            "a cross-user edge injected outside HorizonGraphStore must not be trusted as an active reactivation",
        )
        val allUids = horizon.items.flatMap { it.sourceRefs.map { ref -> ref.phraseUid } } +
            horizon.items.mapNotNull { (it.surfacing as? SurfacingReason.ActiveReactivation)?.info?.triggeringPhraseUid }
        assertFalse(attackerPhraseB in allUids, "user B's phrase uid must never appear anywhere in user A's snapshot")
        val allText = horizon.items.map { it.text.text } +
            horizon.items.mapNotNull { (it.surfacing as? SurfacingReason.ActiveReactivation)?.info?.triggeringPhraseText?.text }
        assertTrue(allText.none { it.contains("attack-payload") }, "user B's phrase text must never appear anywhere in user A's snapshot")
    }

    // ── Cross-user Source isolation: same sourceName, different users ────────────────────────────

    /**
     * Regression test for a real cross-user leak: [ArcadeHorizonGraphStore.findOrCreateEnvironmentSource]
     * used to look up a reusable Source by [sourceName] alone, globally — so two different users
     * calling [HorizonGraphStore.ingestEnvironmentSignal] with the same [sourceName] would share one
     * Source vertex. The second user's phrase would then be asserted from a Source only the FIRST
     * user trusts (the early-return path skipped creating the second user's own `TRUSTS` edge),
     * making the second user's "private" event invisible to themselves and visible in the first
     * user's [HorizonAssembler.assemble] snapshot. Fixed by scoping reuse to a Source the specific
     * calling user already trusts, matched on name AND type.
     */
    @Test
    fun `two users ingesting different private events under the same source name get separate, non-leaking Sources`() = runBlocking {
        val emailA = "horizon-source-iso-a-${UUID.randomUUID()}@test.alfrd.internal"
        val emailB = "horizon-source-iso-b-${UUID.randomUUID()}@test.alfrd.internal"
        val sharedSourceName = "environment:shared-build-system"
        // ingestEnvironmentSignal requires an existing User vertex — it does not create one.
        seedConversationalPhraseWithCycle(emailA, "unrelated seed so A's User vertex exists", cycleSeq = 0)
        seedConversationalPhraseWithCycle(emailB, "unrelated seed so B's User vertex exists", cycleSeq = 0)

        val uidA = store.ingestEnvironmentSignal(emailA, cycleSeq = 1, sourceName = sharedSourceName, text = "A's private build event")!!
        val uidB = store.ingestEnvironmentSignal(emailB, cycleSeq = 1, sourceName = sharedSourceName, text = "B's private build event")!!
        assertTrue(uidA != uidB)

        val db = dbManager.getDatabase()
        val sourceUidsA = HorizonOwnership.trustedSourceUids(db, emailA)
        val sourceUidsB = HorizonOwnership.trustedSourceUids(db, emailB)
        assertTrue(sourceUidsA.none { it in sourceUidsB }, "each user must trust their own separate Source vertex, never a shared one")

        val horizonA = assembler.assemble(emailA, currentCycleSeq = 1).expectSuccess()
        val horizonB = assembler.assemble(emailB, currentCycleSeq = 1).expectSuccess()
        assertTrue(horizonA.items.any { it.sourceRefs.first().phraseUid == uidA }, "user A must see their own event")
        assertTrue(horizonB.items.any { it.sourceRefs.first().phraseUid == uidB }, "user B must see their own event")
        assertTrue(horizonA.items.none { it.sourceRefs.first().phraseUid == uidB }, "user A must never see user B's event")
        assertTrue(horizonB.items.none { it.sourceRefs.first().phraseUid == uidA }, "user B must never see user A's event")
        assertTrue(horizonA.items.none { it.text.text.contains("B's private") }, "user A's snapshot must never contain user B's text")
        assertTrue(horizonB.items.none { it.text.text.contains("A's private") }, "user B's snapshot must never contain user A's text")
    }

    // ── Independently bounded active-relevance candidate path ────────────────────────────────────

    /**
     * Regression test: [ArcadeHorizonAssembler]'s reactivation lookup used to be restricted to
     * phrases already selected by the open/fresh candidate pools — so a `relevant_to` edge targeting
     * a phrase ranked below those pools' own bounded `LIMIT` (e.g. a very old open item) was silently
     * never reactivated. Fixed by [ArcadeHorizonAssembler.queryActiveReactivations] querying
     * `relevant_to` edges directly, independent of the open/fresh pools, and admitting any newly
     * found target as a full candidate via [ArcadeHorizonAssembler.queryAssertsForPhrase].
     */
    @Test
    fun `an old item ranked below the open pool's own limit is still found and reactivated via the independent path`() = runBlocking {
        val email = "horizon-independent-reactivation-${UUID.randomUUID()}@test.alfrd.internal"
        // maxItems=1 -> poolLimit = 1 * CANDIDATE_POOL_OVERFETCH(3) = 3. Seed 4 open items so the
        // oldest-by-statusCycleSeq one (the reactivation target) ranks 4th and is excluded from the
        // open pool's top-3 (ORDER BY statusCycleSeq DESC LIMIT 3).
        val budget = HorizonBudget(maxItems = 1, itemCount = 0, truncated = false)
        val targetUid = seedConversationalPhraseWithCycle(email, "old target item", cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = targetUid, status = AssertionStatus.OPEN)
        (2..4).forEach { i ->
            val uid = seedConversationalPhraseWithCycle(email, "filler open item $i", cycleSeq = i.toLong())
            store.markAssertionStatus(email, cycleSeq = i.toLong(), phraseUid = uid, status = AssertionStatus.OPEN)
        }

        // Test setup sanity check: confirm the target is genuinely excluded from the bounded pool
        // absent any reactivation — otherwise this test would prove nothing about the independent path.
        val withoutReactivation = assembler.assemble(email, currentCycleSeq = 4, budget = budget).expectSuccess()
        assertTrue(
            withoutReactivation.items.none { it.sourceRefs.first().phraseUid == targetUid },
            "test setup: the target must be excluded from the open pool before reactivation",
        )

        val triggerUid = seedConversationalPhraseWithCycle(email, "fresh trigger", cycleSeq = 5)
        assertTrue(store.markRelevant(email, cycleSeq = 5, fromPhraseUid = triggerUid, toPhraseUid = targetUid))

        val horizon = assembler.assemble(email, currentCycleSeq = 5, activeRelevanceCycles = 3, budget = budget).expectSuccess()
        assertEquals(1, horizon.items.size, "ActiveReactivation outranks everything else, so the reactivated target must be the sole kept item")
        val item = horizon.items.single()
        assertEquals(targetUid, item.sourceRefs.first().phraseUid)
        assertTrue(item.surfacing is SurfacingReason.ActiveReactivation, "expected ActiveReactivation, got: ${item.surfacing}")
        assertEquals(triggerUid, (item.surfacing as SurfacingReason.ActiveReactivation).info.triggeringPhraseUid)
    }

    // ── Distinct failure modes: consistency, query error, and byte budget are never conflated ─────

    @Test
    fun `assemble returns an explicit QueryFailure on a genuine database error, distinguishable from a legitimately empty graph`() = runBlocking {
        val email = "horizon-query-failure-${UUID.randomUUID()}@test.alfrd.internal"
        seedConversationalPhraseWithCycle(email, "some phrase", cycleSeq = 1)
        dbManager.getDatabase().close() // forces every subsequent query/transaction on this handle to throw

        val outcome = assembler.assemble(email, currentCycleSeq = 1)
        assertTrue(outcome is AssembleOutcome.QueryFailure, "expected QueryFailure on a real database error, got: $outcome")
    }

    @Test
    fun `assemble returns Assembled with an empty item list for a legitimately empty graph, never conflated with a query error`() = runBlocking {
        val email = "horizon-legitimately-empty-${UUID.randomUUID()}@test.alfrd.internal"
        // No data seeded at all for this user — not even a User vertex.
        val outcome = assembler.assemble(email, currentCycleSeq = 1)
        assertTrue(outcome is AssembleOutcome.Assembled, "a user with no data yet must be a successful, empty Assembled result, got: $outcome")
        assertTrue((outcome as AssembleOutcome.Assembled).horizon.items.isEmpty())
    }

    // ── Serialized-output byte budget is enforced, not merely documented ─────────────────────────

    @Test
    fun `assemble drops lowest-priority items to fit a real byte budget, preserving evidence for what was dropped`() = runBlocking {
        val email = "horizon-byte-enforce-${UUID.randomUUID()}@test.alfrd.internal"
        val budget = HorizonBudget(maxItems = 5, itemCount = 0, truncated = false)
        val uids = (1..5).map { i ->
            val uid = seedConversationalPhraseWithCycle(email, "moderately long open item text number $i", cycleSeq = i.toLong())
            store.markAssertionStatus(email, cycleSeq = i.toLong(), phraseUid = uid, status = AssertionStatus.OPEN)
            uid
        }

        // First measure what an unconstrained assembly actually encodes to, so the override budget
        // below is derived from a real measurement (enough for ~2 items, not all 5) rather than a
        // guessed magic number.
        val assemblerImpl = assembler as ArcadeHorizonAssembler
        val unconstrained = assemblerImpl.assemble(email, currentCycleSeq = 5, budget = budget).expectSuccess()
        assertEquals(5, unconstrained.items.size, "test setup: all 5 items must fit under the default byte budget")
        val json = Json { encodeDefaults = true }
        val fullBytes = json.encodeToString(unconstrained).toByteArray(Charsets.UTF_8).size
        val twoItemsBytes = json.encodeToString(unconstrained.copy(items = unconstrained.items.take(2))).toByteArray(Charsets.UTF_8).size
        val tightBudget = (fullBytes + twoItemsBytes) / 2 // strictly between "2 items" and "all 5 items" in size

        assemblerImpl.testByteBudgetOverride = tightBudget
        try {
            val constrained = assemblerImpl.assemble(email, currentCycleSeq = 5, budget = budget).expectSuccess()
            assertTrue(constrained.items.size < 5, "the byte budget must actually reduce the item count below what the item-count budget alone would keep")
            assertTrue(constrained.budget.truncated)
            val encodedSize = json.encodeToString(constrained).toByteArray(Charsets.UTF_8).size
            assertTrue(encodedSize <= tightBudget, "the enforced result ($encodedSize bytes) must actually fit the override budget ($tightBudget bytes)")

            // Evidence preserved: every item dropped for the byte budget is still traceable via
            // omittedSample/omittedAtLeast, resolving to a real seeded phrase — never silently lost.
            assertTrue(constrained.omittedAtLeast > 0)
            constrained.omittedSample.forEach { omitted ->
                assertTrue(omitted.sourceRef.phraseUid in uids, "every byte-budget-dropped item must resolve to a real seeded phrase")
            }
        } finally {
            assemblerImpl.testByteBudgetOverride = null
        }
    }

    @Test
    fun `assemble returns an explicit BudgetExceeded when even zero items fits the byte budget, never an oversized snapshot`() = runBlocking {
        val email = "horizon-byte-exceeded-${UUID.randomUUID()}@test.alfrd.internal"
        val uid = seedConversationalPhraseWithCycle(email, "an item", cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = uid, status = AssertionStatus.OPEN)

        val assemblerImpl = assembler as ArcadeHorizonAssembler
        assemblerImpl.testByteBudgetOverride = 10 // even an empty ContextHorizon's JSON metadata exceeds this
        try {
            val outcome = assemblerImpl.assemble(email, currentCycleSeq = 1)
            assertTrue(outcome is AssembleOutcome.BudgetExceeded, "expected BudgetExceeded, got: $outcome")
        } finally {
            assemblerImpl.testByteBudgetOverride = null
        }

        // Once the override is cleared, an ordinary call succeeds normally.
        val recovered = assemblerImpl.assemble(email, currentCycleSeq = 1)
        assertTrue(recovered is AssembleOutcome.Assembled, "expected assembly to succeed once the byte budget is realistic again, got: $recovered")
    }

    // ── Bounded output: honest lower-bound accounting, never an unbounded list ───────────────────

    @Test
    fun `overflow beyond budget is bounded, honest, and every omitted entry is traceable`() = runBlocking {
        val email = "horizon-bounded-${UUID.randomUUID()}@test.alfrd.internal"
        // Distinct cycleSeq per item avoids ORDER BY ties, keeping which items get kept vs.
        // omitted deterministic.
        (1..10).forEach { i ->
            val uid = seedConversationalPhraseWithCycle(email, "Open item number $i", cycleSeq = i.toLong())
            store.markAssertionStatus(email, cycleSeq = i.toLong(), phraseUid = uid, status = AssertionStatus.OPEN)
        }

        val budget = HorizonBudget(maxItems = 3, itemCount = 0, truncated = false)
        val horizon = assembler.assemble(email, currentCycleSeq = 100, budget = budget).expectSuccess()

        assertEquals(3, horizon.items.size)
        assertTrue(horizon.budget.truncated)
        // poolLimit = 3 * CANDIDATE_POOL_OVERFETCH(3) = 9 of the 10 seeded items are even fetched;
        // 3 are kept, so 6 are omitted from the fetched pool — a true, deterministic lower bound.
        assertEquals(6, horizon.omittedAtLeast)
        assertTrue(horizon.omittedSample.size <= HorizonLimits.OMITTED_SAMPLE_CAP)
        assertTrue(horizon.moreCandidatesAvailable, "the pool itself hit its cap — the 10th item was never even fetched")

        val db = dbManager.getDatabase()
        horizon.omittedSample.forEach { omitted ->
            assertTrue(phraseExists(db, omitted.sourceRef.phraseUid), "every omitted entry must still resolve to a live graph vertex")
        }
    }

    // ── Serialized-output budget: truncation marked, full text always recoverable ────────────────

    @Test
    fun `long text is truncated and marked, full text stays recoverable by uid`() = runBlocking {
        val email = "horizon-truncation-${UUID.randomUUID()}@test.alfrd.internal"
        val longText = "x".repeat(HorizonLimits.MAX_ITEM_TEXT_LENGTH + 50)
        val phraseUid = seedConversationalPhraseWithCycle(email, longText, cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = phraseUid, status = AssertionStatus.OPEN)

        val horizon = assembler.assemble(email, currentCycleSeq = 1).expectSuccess()
        val item = horizon.items.single { it.sourceRefs.first().phraseUid == phraseUid }
        assertTrue(item.text.truncated)
        assertTrue(item.text.text.length <= HorizonLimits.MAX_ITEM_TEXT_LENGTH + 1)

        val fullText = dbManager.getDatabase().query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseUid))
            .use { it.next().toElement().asVertex().get("text") as String }
        assertEquals(longText, fullText, "the full text must always be recoverable via the phrase uid, regardless of truncation")
    }

    @Test
    fun `a long triggering phrase's text is also truncated, not inlined unbounded`() = runBlocking {
        val email = "horizon-truncation-trigger-${UUID.randomUUID()}@test.alfrd.internal"
        val arxUid = seedConversationalPhraseWithCycle(email, "Arx priority.", cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN)
        val longEventText = "y".repeat(HorizonLimits.MAX_ITEM_TEXT_LENGTH + 50)
        val eventUid = store.ingestEnvironmentSignal(email, cycleSeq = 1, sourceName = "environment:x", text = longEventText)!!
        store.markRelevant(email, cycleSeq = 1, fromPhraseUid = eventUid, toPhraseUid = arxUid)

        val horizon = assembler.assemble(email, currentCycleSeq = 1).expectSuccess()
        val info = (horizon.items.single { it.sourceRefs.first().phraseUid == arxUid }.surfacing as SurfacingReason.ActiveReactivation).info
        assertTrue(info.triggeringPhraseText.truncated)
        assertTrue(info.triggeringPhraseText.text.length <= HorizonLimits.MAX_ITEM_TEXT_LENGTH + 1)
    }

    /**
     * Individual per-field caps (item text, triggering text, item/omitted counts) were each tested
     * in isolation above, but never the COMPLETE structure at once, encoded in the format Alfrd
     * actually uses for outbound payloads elsewhere in this codebase (`kotlinx.serialization` JSON),
     * and measured as real bytes rather than summed `String.length`. A raw character sum cannot
     * stand in for the real encoded size: JSON escaping expands characters that need it (quotes,
     * backslashes, newlines — each becomes two ASCII bytes instead of one), and a UTF-16 character
     * count says nothing about UTF-8 byte size for non-ASCII text (many BMP characters, e.g. CJK,
     * take 3 bytes each; surrogate-pair characters, e.g. emoji, take 4 bytes for 2 UTF-16 units).
     * This test forces every bounding mechanism to its worst case, seeds content requiring both
     * escaping and multibyte encoding, serializes with the real [Json] encoder, and measures the
     * actual `ByteArray` size against [HorizonLimits.MAX_SERIALIZED_HORIZON_BYTES] — not a
     * hand-derived character ceiling.
     */
    @Test
    fun `the complete serialized Horizon stays within its defined byte budget at worst-case population, including escaping and multibyte content`() = runBlocking {
        val email = "horizon-budget-${UUID.randomUUID()}@test.alfrd.internal"
        // Content requiring JSON escaping (quote, backslash, newline, tab) AND multibyte UTF-8
        // encoding (CJK — 3 bytes/char, non-surrogate; emoji — 4 bytes/char, surrogate pair),
        // followed by enough filler to push well past the truncation cap.
        val stress = "\"quoted\" \\backslash\\ \nnewline\t tab 漢字漢字ひらがな 😀🎉🚀"
        val longText = stress + "x".repeat(HorizonLimits.MAX_ITEM_TEXT_LENGTH + 500)

        // Force every bounding mechanism at once: more open items than the budget allows (forces
        // omittedSample to its cap), every item's text over the truncation cap, and a reactivation
        // whose own triggering text is also over the cap.
        val overflowCount = HorizonBudget.DEFAULT.maxItems + HorizonLimits.OMITTED_SAMPLE_CAP + 5
        val uids = (1..overflowCount).map { i ->
            val uid = seedConversationalPhraseWithCycle(email, "$longText-item-$i", cycleSeq = i.toLong())
            store.markAssertionStatus(email, cycleSeq = i.toLong(), phraseUid = uid, status = AssertionStatus.OPEN)
            uid
        }
        val eventUid = store.ingestEnvironmentSignal(
            email, cycleSeq = overflowCount.toLong(), sourceName = "environment:budget-test", text = "$longText-event",
        )!!
        store.markRelevant(email, cycleSeq = overflowCount.toLong(), fromPhraseUid = eventUid, toPhraseUid = uids.last())

        val horizon = assembler.assemble(email, currentCycleSeq = overflowCount.toLong()).expectSuccess()

        // Per-field caps, individually, at worst-case population:
        assertTrue(horizon.items.size <= HorizonBudget.DEFAULT.maxItems)
        assertTrue(horizon.omittedSample.size <= HorizonLimits.OMITTED_SAMPLE_CAP)
        horizon.items.forEach { item ->
            assertTrue(item.text.text.length <= HorizonLimits.MAX_ITEM_TEXT_LENGTH + 1)
            assertTrue(item.sourceRefs.size <= HorizonLimits.MAX_SOURCE_REFS_PER_ITEM)
            (item.surfacing as? SurfacingReason.ActiveReactivation)?.info?.triggeringPhraseText?.let {
                assertTrue(it.text.length <= HorizonLimits.MAX_ITEM_TEXT_LENGTH + 1)
            }
        }
        assertTrue(horizon.items.any { it.text.truncated }, "test setup: at least one item must actually be truncated")
        val reactivated = horizon.items.mapNotNull { (it.surfacing as? SurfacingReason.ActiveReactivation)?.info }
        assertTrue(reactivated.any { it.triggeringPhraseText.truncated }, "test setup: the reactivation's triggering text must actually be truncated")

        // Real serialization, in the format this codebase actually uses elsewhere for outbound
        // payloads — not a hand-rolled size estimate.
        val json = Json { encodeDefaults = true }
        val serialized = json.encodeToString(horizon)
        val encodedBytes = serialized.toByteArray(Charsets.UTF_8)

        // The escaping/multibyte content must actually have landed in the serialized output —
        // otherwise this test would prove nothing about them. A raw newline/quote is illegal inside
        // a JSON string; their presence here can only mean they were escaped, and the multibyte
        // characters must survive UTF-8 round-tripping intact.
        assertTrue(serialized.contains("\\n"), "newline must be JSON-escaped in the serialized payload")
        assertTrue(serialized.contains("\\\""), "quote must be JSON-escaped in the serialized payload")
        assertTrue(serialized.contains("漢字"), "multibyte CJK text must survive serialization intact")
        assertTrue(serialized.contains("😀"), "surrogate-pair emoji must survive serialization intact")

        assertTrue(
            encodedBytes.size <= HorizonLimits.MAX_SERIALIZED_HORIZON_BYTES,
            "encoded=${encodedBytes.size} bytes, budget=${HorizonLimits.MAX_SERIALIZED_HORIZON_BYTES} bytes — " +
                "the complete Horizon exceeded its defined serialized-output byte budget",
        )

        // Resolvable evidence references and truncation indicators must survive a real decode, not
        // just remain present in the in-memory object graph before serialization.
        val decoded = json.decodeFromString<ContextHorizon>(serialized)
        assertEquals(horizon, decoded, "round-tripping through the real JSON encoder must be lossless")
        decoded.items.forEach { item ->
            item.sourceRefs.forEach { ref ->
                assertTrue(uids.contains(ref.phraseUid) || ref.phraseUid == eventUid, "every decoded sourceRef.phraseUid must resolve to a real seeded phrase")
            }
        }
        assertTrue(decoded.items.any { it.text.truncated }, "truncation indicator must survive the JSON round trip")
        val decodedReactivation = decoded.items.mapNotNull { (it.surfacing as? SurfacingReason.ActiveReactivation)?.info }
        assertTrue(decodedReactivation.any { it.triggeringPhraseText.truncated }, "reactivation truncation indicator must survive the JSON round trip")
        assertTrue(decodedReactivation.any { it.triggeringPhraseUid == eventUid }, "the reactivation's evidence reference (triggeringPhraseUid) must survive and resolve to the real seeded event")
    }

    // ── Scale/index verification: not merely assumed bounded ─────────────────────────────────────

    /**
     * A timing comparison alone can't distinguish "uses the index" from "just happens to be fast
     * enough at this N" — this asserts on the actual [com.arcadedb.query.sql.executor.ExecutionPlan]
     * ArcadeDB produces for the real open-item query, via
     * [ArcadeHorizonAssembler.debugOpenPoolExecutionSteps], which runs the exact same SQL/params
     * [ArcadeHorizonAssembler] itself uses.
     */
    @Test
    fun `the open-item query's execution plan shows an index-based fetch, not a full type scan`() = runBlocking {
        val email = "horizon-plan-${UUID.randomUUID()}@test.alfrd.internal"
        seedManyOpenItemsInOneTransaction(dbManager.getDatabase(), email, count = 200)

        val sourceUids = HorizonOwnership.trustedSourceUids(dbManager.getDatabase(), email)
        assertTrue(sourceUids.isNotEmpty(), "test setup: the seeded user must have a trusted source")

        val steps = (assembler as ArcadeHorizonAssembler)
            .debugOpenPoolExecutionSteps(sourceUids, currentCycleSeq = 1_000_000, limit = 36)
        val planDescription = steps.joinToString(" | ")

        // Confirmed by direct inspection: ArcadeDB's plan for this query is
        // "FetchFromIndexStep: + FETCH FROM INDEX ASSERTS[status] status = :status" followed by
        // GetValueFromIndexEntryStep/FilterStep/FilterByTypeStep/OrderByStep/LimitExecutionStep —
        // the remaining conditions (@out.uid IN ..., statusCycleSeq <= ...) are applied as a filter
        // over the index-selected rows, not a scan of the whole ASSERTS type. Checking for the
        // specific index name (not just "any index step") rules out a false positive from some
        // unrelated index appearing incidentally in the plan.
        assertTrue(
            steps.any { it.contains("FetchFromIndexStep") && it.contains("ASSERTS[status]") },
            "expected a FetchFromIndexStep naming the ASSERTS[status] index; got: $planDescription",
        )
        assertFalse(
            steps.any { it.contains("FetchFromTypeExecutionStep") },
            "plan includes a full type-scan step (FetchFromTypeExecutionStep) — the status index is not actually being used: $planDescription",
        )
    }

    @Test
    fun `open-item query does not regress into an unindexed full scan at 5000+ edges`() = runBlocking {
        val baselineEmail = "horizon-scale-baseline-${UUID.randomUUID()}@test.alfrd.internal"
        seedManyOpenItemsInOneTransaction(dbManager.getDatabase(), baselineEmail, count = 20)
        val baselineMs = measureTimeMillis { assembler.assemble(baselineEmail, currentCycleSeq = 1_000_000).expectSuccess() }

        val scaleEmail = "horizon-scale-large-${UUID.randomUUID()}@test.alfrd.internal"
        seedManyOpenItemsInOneTransaction(dbManager.getDatabase(), scaleEmail, count = 5_000)
        val scaleMs = measureTimeMillis { assembler.assemble(scaleEmail, currentCycleSeq = 1_000_000).expectSuccess() }

        assertTrue(
            scaleMs < baselineMs * 50 + 2_000,
            "open-item query took ${scaleMs}ms at 5000 edges vs ${baselineMs}ms at 20 — looks like an unindexed full scan",
        )
    }

    /** Bulk-seeds [count] open-status phrases on one Source in a single transaction — test setup, not what's being measured. */
    private fun seedManyOpenItemsInOneTransaction(db: Database, email: String, count: Int) {
        val now = System.currentTimeMillis()
        db.transaction {
            val userVertex = db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", email.substringBefore("@"))
                set("email", email)
                set("tier", -1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            val sourceVertex = db.newVertex("Source").apply {
                set("uid", UUID.randomUUID().toString())
                set("name", "onboarding_conversation:$email")
                set("type", "onboarding_conversation")
                set("metadata", "{}")
                save()
            }
            userVertex.newEdge("TRUSTS", sourceVertex, false).apply { set("scores", "[]"); save() }
            repeat(count) { i ->
                val uid = UUID.randomUUID().toString()
                val phraseVertex = db.newVertex("Phrase").apply {
                    set("uid", uid)
                    set("text", "Open item number $i")
                    set("hash", uid)
                    set("visibility", "private")
                    set("createdAt", now)
                    set("updatedAt", now)
                    save()
                }
                sourceVertex.newEdge("ASSERTS", phraseVertex, false).apply {
                    set("context", "onboarding_conversation")
                    set("timestamp", now)
                    set("scores", "[]")
                    set("status", "open")
                    set("statusHistory", """[{"state":"open","at":$now}]""")
                    set("cycleSeq", i.toLong())
                    save()
                }
            }
        }
    }

    // ── listCandidates: full, explicitly paginated enumeration ────────────────────────────────

    @Test
    fun `listCandidates pages through the full open-item set with no gaps or duplicates`() = runBlocking {
        val email = "horizon-paging-${UUID.randomUUID()}@test.alfrd.internal"
        val seededUids = (1..25).map { i ->
            val uid = seedConversationalPhraseWithCycle(email, "Open item $i", cycleSeq = i.toLong())
            store.markAssertionStatus(email, cycleSeq = i.toLong(), phraseUid = uid, status = AssertionStatus.OPEN)
            uid
        }.toSet()

        val collected = mutableSetOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = assembler.listCandidates(email, status = AssertionStatus.OPEN, limit = 7, cursor = cursor)
            collected += page.items.map { it.phraseUid }
            cursor = page.nextCursor
            pages++
            assertTrue(pages < 100, "pagination must terminate")
        } while (cursor != null)

        assertEquals(seededUids, collected)
    }

    /**
     * Regression test: continuation used to be decided by the CATEGORY-FILTERED row count
     * (`rows.size == limit`), not the raw pre-filter page size. A raw page whose rows all happened
     * to fail the category filter came back with `rows.size == 0`, which `!= limit`, so
     * `nextCursor` was set to `null` — silently terminating pagination even though later raw pages
     * held real matches. Fixed by basing continuation on the raw row count instead.
     */
    @Test
    fun `listCandidates continues past a raw page whose rows all fail the category filter, reaching later matching records`() = runBlocking {
        val email = "horizon-paging-category-${UUID.randomUUID()}@test.alfrd.internal"
        seedConversationalPhraseWithCycle(email, "unrelated seed so the User vertex exists", cycleSeq = 0)
        // 5 ENVIRONMENT_EVENT items at the most recent cycles sort first (ORDER BY cycleSeq DESC)
        // and form the entire first raw page at limit=5 — none of them match the INTENTION filter
        // used below.
        (10..14).forEach { i ->
            assertTrue(store.ingestEnvironmentSignal(email, cycleSeq = i.toLong(), sourceName = "environment:noise-$i", text = "environment noise $i") != null)
        }
        // 5 INTENTION (open) items at older cycles land on a LATER raw page.
        val intentionUids = (1..5).map { i ->
            val uid = seedConversationalPhraseWithCycle(email, "intention $i", cycleSeq = i.toLong())
            store.markAssertionStatus(email, cycleSeq = i.toLong(), phraseUid = uid, status = AssertionStatus.OPEN)
            uid
        }.toSet()

        val collected = mutableSetOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = assembler.listCandidates(email, category = HorizonItemCategory.INTENTION, limit = 5, cursor = cursor)
            collected += page.items.map { it.phraseUid }
            cursor = page.nextCursor
            pages++
            assertTrue(pages < 100, "pagination must terminate")
        } while (cursor != null)

        assertEquals(intentionUids, collected, "every INTENTION item must still be reached even though the first raw page was entirely non-matching")
    }

    /** Without a deterministic tiebreaker, SKIP/LIMIT paging one row at a time over cycleSeq-tied rows is not guaranteed to visit each row exactly once. */
    @Test
    fun `listCandidates pagination is stable across a cycleSeq tie via the phraseUid tiebreaker`() = runBlocking {
        val email = "horizon-paging-tie-${UUID.randomUUID()}@test.alfrd.internal"
        val uids = (1..2).map {
            val uid = seedConversationalPhraseWithCycle(email, "tied item $it", cycleSeq = 1)
            store.markAssertionStatus(email, cycleSeq = 1, phraseUid = uid, status = AssertionStatus.OPEN)
            uid
        }.toSet()

        val collected = mutableSetOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = assembler.listCandidates(email, status = AssertionStatus.OPEN, limit = 1, cursor = cursor)
            collected += page.items.map { it.phraseUid }
            cursor = page.nextCursor
            pages++
            assertTrue(pages < 100, "pagination must terminate")
        } while (cursor != null)

        assertEquals(uids, collected, "both cycleSeq-tied items must be visited exactly once across pages")
    }

    // ── Restart/rebuild equivalence ────────────────────────────────────────────────────────────

    @Test
    fun `restart recovers an equivalent Horizon from the same graph state`() = runBlocking {
        val path = "./data/test-horizon-asm-restart-${System.nanoTime()}"
        tempDirPrefixes += "test-horizon-asm-restart"
        var manager = DatabaseManager(path)
        SchemaBootstrap.bootstrap(manager.getDatabase())
        var localStore: HorizonGraphStore = ArcadeHorizonGraphStore(manager.getDatabase())
        var localAssembler: HorizonAssembler = ArcadeHorizonAssembler(manager.getDatabase())

        val email = "horizon-restart-${UUID.randomUUID()}@test.alfrd.internal"
        val arxUid = seedConversationalPhraseWithCycle(manager.getDatabase(), email, "Arx priority.", cycleSeq = 1)
        localStore.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN)
        val eventUid = localStore.ingestEnvironmentSignal(email, cycleSeq = 2, sourceName = "environment:x", text = "event")!!
        localStore.markRelevant(email, cycleSeq = 2, fromPhraseUid = eventUid, toPhraseUid = arxUid)

        val before = localAssembler.assemble(email, currentCycleSeq = 2).expectSuccess()

        manager.close()
        manager = DatabaseManager(path) // reopen the same on-disk path — no re-bootstrap needed
        localAssembler = ArcadeHorizonAssembler(manager.getDatabase())

        val after = localAssembler.assemble(email, currentCycleSeq = 2).expectSuccess()

        assertEquals(signature(before), signature(after))
        manager.close()
    }

    /** The equivalence definition from the design doc: (phraseUid, category, status, cycleSeq) tuples. */
    private fun signature(horizon: ContextHorizon): Set<List<Any?>> = horizon.items.map { item ->
        val cycleSeq = when (val s = item.surfacing) {
            is SurfacingReason.ActiveReactivation -> s.info.edgeCycleSeq
            is SurfacingReason.JustAsserted -> s.cycleSeq
            is SurfacingReason.DormantOpen -> s.lastStatusChangeCycleSeq
        }
        listOf(item.sourceRefs.first().phraseUid, item.category, item.status, cycleSeq)
    }.toSet()

    // ── Provenance: explicit user statements carry no inferred reason ────────────────────────────

    @Test
    fun `an explicit user statement carries verbatim text and explicit provenance, no inferred reason`() = runBlocking {
        val email = "horizon-provenance-${UUID.randomUUID()}@test.alfrd.internal"
        val text = "Getting Alfrd running on Arx is a priority for me."
        val arxUid = seedConversationalPhraseWithCycle(email, text, cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN)

        val horizon = assembler.assemble(email, currentCycleSeq = 1).expectSuccess()
        val item = horizon.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertEquals(ProvenanceKind.EXPLICIT_USER_STATEMENT, item.provenance)
        assertEquals(text, item.text.text, "must carry the user's own words verbatim, not a paraphrase or inferred reason")
    }
}
