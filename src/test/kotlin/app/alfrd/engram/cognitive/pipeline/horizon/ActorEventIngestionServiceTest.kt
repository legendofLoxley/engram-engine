package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Real ArcadeDB throughout — [ActorEventIngestionService] against real
 * [ArcadeHorizonGraphStore]/[ArcadeHorizonAssembler]/[ArcadeCycleSequencer]/[SalientTokenPropagator],
 * with fakes substituted only for [HorizonGraphStore]/[HorizonPropagator] in the specific tests that
 * need to force a write/propagation failure deterministically. No `CognitivePipeline`, no session, no
 * LLM client anywhere in this file — proving ingestion genuinely needs none of them.
 */
class ActorEventIngestionServiceTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var cycleSequencer: CycleSequencer
    private lateinit var store: HorizonGraphStore
    private lateinit var assembler: HorizonAssembler
    private lateinit var propagator: HorizonPropagator
    private lateinit var testDbPath: String

    @BeforeEach
    fun setUp() {
        testDbPath = "./data/test-actor-event-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        cycleSequencer = ArcadeCycleSequencer(dbManager.getDatabase())
        store = ArcadeHorizonGraphStore(dbManager.getDatabase())
        assembler = ArcadeHorizonAssembler(dbManager.getDatabase())
        propagator = SalientTokenPropagator(store, assembler)
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(testDbPath).deleteRecursively()
    }

    private fun service(
        cycleSequencer: CycleSequencer = this.cycleSequencer,
        store: HorizonGraphStore = this.store,
        propagator: HorizonPropagator = this.propagator,
    ) = ActorEventIngestionService(cycleSequencer, store, propagator)

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

    /** Seeds a plain OPEN intention directly (bypassing the service) — a pre-existing candidate for propagation to reactivate. Mirrors HorizonPropagatorTest's seedPhrase. */
    private fun seedOpenIntention(email: String, cycleSeq: Long, text: String): String {
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
            sourceVertex.newEdge("ASSERTS", phraseVertex, false).apply {
                set("context", "conversation")
                set("timestamp", now)
                set("scores", "[]")
                set("cycleSeq", cycleSeq)
                set("status", "open")
                set("statusHistory", "[]")
                set("statusCycleSeq", cycleSeq)
                save()
            }
        }
        return phraseUid
    }

    /**
     * Directly seeds an Actor-attributed `ASSERTS` edge with a given [propagationStatus], bypassing
     * [ActorEventIngestionService] entirely — simulates evidence that was already committed by some
     * prior delivery attempt whose propagation checkpoint is in a specific, known state (`null` =
     * never started, `"pending"` = started but interrupted before its outcome was recorded), so a
     * retry through the real service can be observed against a deterministic starting point rather
     * than racing a real crash.
     */
    private fun seedActorEventWithPropagationStatus(
        email: String, eventId: String, cycleSeq: Long, text: String, sourceName: String, propagationStatus: String?,
    ): String {
        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()
        val phraseUid = UUID.randomUUID().toString()
        val contentHash = actorEventFingerprint(ACTOR_OBSERVATION_SOURCE_TYPE, sourceName, text, null, null)
        db.transaction {
            val userVertex = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email))
                .use { rs -> rs.next().toElement().asVertex() }
            var sourceVertex = db.query("sql", "SELECT FROM Source WHERE name = :n AND type = :t", mapOf("n" to sourceName, "t" to ACTOR_OBSERVATION_SOURCE_TYPE))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null }
            if (sourceVertex == null) {
                sourceVertex = db.newVertex("Source").apply {
                    set("uid", UUID.randomUUID().toString())
                    set("name", sourceName)
                    set("type", ACTOR_OBSERVATION_SOURCE_TYPE)
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
                set("context", ACTOR_OBSERVATION_SOURCE_TYPE)
                set("timestamp", now)
                set("scores", "[]")
                set("cycleSeq", cycleSeq)
                set("eventId", eventId)
                set("contentHash", contentHash)
                set("occurredAt", now)
                set("kindMetadata", "{}")
                if (propagationStatus != null) set("propagationStatus", propagationStatus)
                save()
            }
        }
        return phraseUid
    }

    /** Delegates every [HorizonGraphStore] call to [delegate] except `recordActorEventPropagationStatus("pending", ...)`, which reports failure — simulates the checkpoint write itself failing (lock timeout, transient error) without touching evidence-write or any other status transition. */
    private fun pendingCheckpointFailingStore(delegate: HorizonGraphStore) = object : HorizonGraphStore by delegate {
        override suspend fun recordActorEventPropagationStatus(
            userEmail: String, eventId: String, status: String, incompletePropagationTargets: List<RelevanceEdgeSummary>,
        ): Boolean = if (status == "pending") false else delegate.recordActorEventPropagationStatus(userEmail, eventId, status, incompletePropagationTargets)
    }

    /** Counts real [propagate] invocations — a direct, unambiguous witness that propagation was or wasn't attempted, independent of what edges (if any) it would have produced. */
    private class CountingPropagator(private val delegate: HorizonPropagator) : HorizonPropagator {
        var callCount = 0
            private set
        override suspend fun propagate(userEmail: String, cycleSeq: Long, newPhrases: List<Pair<String, String>>): PropagationOutcome {
            callCount++
            return delegate.propagate(userEmail, cycleSeq, newPhrases)
        }
    }

    private fun assertsCountForEventId(eventId: String): Int =
        dbManager.getDatabase().query("sql", "SELECT count(*) as c FROM ASSERTS WHERE eventId = :id", mapOf("id" to eventId))
            .use { rs -> (rs.next().toMap()["c"] as Number).toInt() }

    private fun relatedToCount(email: String, relationType: String = "relevant_to"): Int =
        dbManager.getDatabase().query(
            "sql", "SELECT count(*) as c FROM RELATED_TO WHERE ownerEmail = :e AND relationType = :r",
            mapOf("e" to email, "r" to relationType),
        ).use { rs -> (rs.next().toMap()["c"] as Number).toInt() }

    // ── Idle ingestion, no conversational model ──────────────────────────────

    @Test
    fun `ingestion while chat is idle commits evidence without any pipeline, session, or LLM client`() = runBlocking {
        val email = "idle-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val outcome = service().ingest(email, eventId = "evt-1", kind = ActorEventKind.Observation("Weekly backup completed"), sourceName = "hermes")

        assertTrue(outcome is ActorEventIngestOutcome.Committed, "expected Committed, got $outcome")
        val committed = outcome as ActorEventIngestOutcome.Committed
        assertEquals(1, assertsCountForEventId("evt-1"))
        val text = store.phraseText(email, committed.phraseUid)
        assertEquals("Weekly backup completed", text)
    }

    // ── Duplicate / conflict / replay ─────────────────────────────────────────

    @Test
    fun `duplicate delivery does not duplicate evidence and re-attempts propagation idempotently`() = runBlocking {
        val email = "dup-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val first = service().ingest(email, "evt-dup", ActorEventKind.Observation("Nothing notable happened"), "hermes")
        val second = service().ingest(email, "evt-dup", ActorEventKind.Observation("Nothing notable happened"), "hermes")

        assertTrue(first is ActorEventIngestOutcome.Committed)
        assertTrue(second is ActorEventIngestOutcome.DuplicateDelivery, "expected DuplicateDelivery, got $second")
        val dup = second as ActorEventIngestOutcome.DuplicateDelivery
        assertEquals((first as ActorEventIngestOutcome.Committed).phraseUid, dup.existingPhraseUid)
        assertEquals(1, assertsCountForEventId("evt-dup"), "must never create a second Phrase for the same eventId")
        assertNull(dup.propagationOutcome, "original propagation had nothing to overlap with (no open candidates) — already recorded completed, so a duplicate must not re-attempt it")
    }

    @Test
    fun `conflicting reuse of an eventId with different content is rejected, never silently overwritten`() = runBlocking {
        val email = "conflict-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val first = service().ingest(email, "evt-conflict", ActorEventKind.Observation("Build succeeded"), "hermes")
        val second = service().ingest(email, "evt-conflict", ActorEventKind.Observation("Build FAILED"), "hermes")

        assertTrue(first is ActorEventIngestOutcome.Committed)
        assertTrue(second is ActorEventIngestOutcome.ConflictingReuse, "expected ConflictingReuse, got $second")
        assertEquals(1, assertsCountForEventId("evt-conflict"))
        assertEquals("Build succeeded", store.phraseText(email, (first as ActorEventIngestOutcome.Committed).phraseUid), "the original text must never be overwritten by a conflicting delivery")
    }

    @Test
    fun `replay after reopening storage is still recognized as a duplicate, durable not in-memory`() = runBlocking {
        val email = "reopen-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        service().ingest(email, "evt-reopen", ActorEventKind.Observation("Nothing notable happened"), "hermes")

        dbManager.close()
        dbManager = DatabaseManager(testDbPath)
        val reopenedStore = ArcadeHorizonGraphStore(dbManager.getDatabase())
        val reopenedAssembler = ArcadeHorizonAssembler(dbManager.getDatabase())
        val reopenedService = ActorEventIngestionService(
            ArcadeCycleSequencer(dbManager.getDatabase()), reopenedStore, SalientTokenPropagator(reopenedStore, reopenedAssembler),
        )

        val replay = reopenedService.ingest(email, "evt-reopen", ActorEventKind.Observation("Nothing notable happened"), "hermes")

        assertTrue(replay is ActorEventIngestOutcome.DuplicateDelivery, "expected DuplicateDelivery after reopening storage, got $replay")
        assertEquals(1, assertsCountForEventId("evt-reopen"))
    }

    @Test
    fun `replay after an intervening graph change retries only the originally-incomplete target, never a new one`() = runBlocking {
        val email = "replay-intervening-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val originalTarget = seedOpenIntention(email, cycleSeq = 1, text = "Arx priority is getting Alfrd running")

        // First delivery: force the markRelevant write to fail for the original target, using the
        // REAL SalientTokenPropagator (only the store call is faked) — proving the precise-retry
        // mechanism against a real incomplete-propagation record, not a hand-constructed one.
        val failingStore = object : HorizonGraphStore by store {
            override suspend fun markRelevant(userEmail: String, cycleSeq: Long, fromPhraseUid: String, toPhraseUid: String, strength: Double): Boolean =
                if (toPhraseUid == originalTarget) false else store.markRelevant(userEmail, cycleSeq, fromPhraseUid, toPhraseUid, strength)
        }
        val failingService = service(store = failingStore, propagator = SalientTokenPropagator(failingStore, assembler))
        val first = failingService.ingest(email, "evt-intervening", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")
        assertTrue(first is ActorEventIngestOutcome.Committed)
        val committed = first as ActorEventIngestOutcome.Committed
        assertTrue(committed.propagationOutcome is PropagationOutcome.Propagated)
        assertEquals(1, (committed.propagationOutcome as PropagationOutcome.Propagated).incompleteTargets.size, "the original attempt must record exactly the one failed target")
        assertEquals(0, relatedToCount(email), "the failed write must not have created any edge")

        // Intervening graph change: a NEW open intention that would ALSO overlap lexically ("arx")
        // if propagation blindly recomputed against today's candidates.
        val newIntention = seedOpenIntention(email, cycleSeq = 5, text = "Arx deployment checklist needs review")

        // Retry with the real (non-failing) store — same eventId, same content.
        val retry = service().ingest(email, "evt-intervening", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")

        assertTrue(retry is ActorEventIngestOutcome.DuplicateDelivery)
        val retryOutcome = (retry as ActorEventIngestOutcome.DuplicateDelivery).propagationOutcome
        assertNotNull(retryOutcome, "an incomplete prior attempt must be retried, not skipped")
        assertTrue(retryOutcome is PropagationOutcome.Propagated)
        val retryPropagated = retryOutcome as PropagationOutcome.Propagated
        assertEquals(1, retryPropagated.edgesCreated.size)
        assertEquals(originalTarget, retryPropagated.edgesCreated[0].toPhraseUid, "only the originally-recorded target is retried")
        assertTrue(retryPropagated.edgesCreated.none { it.toPhraseUid == newIntention }, "the intervening intention must never be touched by a precise retry")
        assertEquals(1, relatedToCount(email), "exactly one edge total — the completed retry, nothing against the new intention")

        // The completed edge's cycle must be the RETRY's own freshly-allocated cycle, never the
        // original event's cycle (cycleSeq 3, allocated for the failed first attempt).
        val edgeCycleSeq = dbManager.getDatabase().query(
            "sql", "SELECT cycleSeq FROM RELATED_TO WHERE ownerEmail = :e AND relationType = 'relevant_to' LIMIT 1",
            mapOf("e" to email),
        ).use { rs -> (rs.next().toMap()["cycleSeq"] as Number).toLong() }
        assertNotEquals(committed.cycleSeq, edgeCycleSeq, "a new effect discovered on retry must be dated at retry time, never backdated to the original event's cycle")
    }

    // ── Failure boundaries ─────────────────────────────────────────────────────

    @Test
    fun `allocation failure for an unknown user is rejected explicitly, nothing committed`() = runBlocking {
        val outcome = service().ingest("nobody-${UUID.randomUUID()}@test.alfrd.internal", "evt-unknown", ActorEventKind.Observation("hello"), "hermes")
        assertTrue(outcome is ActorEventIngestOutcome.AllocationFailed, "expected AllocationFailed, got $outcome")
    }

    @Test
    fun `blank required fields are rejected before any allocation or write is attempted`() = runBlocking {
        val email = "blank-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val outcome = service().ingest(email, eventId = "", kind = ActorEventKind.Observation("hello"), sourceName = "hermes")
        assertTrue(outcome is ActorEventIngestOutcome.Rejected, "expected Rejected, got $outcome")
    }

    @Test
    fun `a write failure commits nothing and is reported distinctly, never as success`() = runBlocking {
        val email = "write-fail-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val failingStore = object : HorizonGraphStore by store {
            override suspend fun ingestActorEvent(
                userEmail: String, cycleSeq: Long, sourceName: String, sourceType: String, text: String,
                eventId: String, contentHash: String, assignmentId: String?, occurredAt: Long?, kindMetadata: String,
            ): String? = null
        }

        val outcome = service(store = failingStore, propagator = SalientTokenPropagator(failingStore, assembler))
            .ingest(email, "evt-write-fail", ActorEventKind.Observation("hello"), "hermes")

        assertTrue(outcome is ActorEventIngestOutcome.WriteFailed, "expected WriteFailed, got $outcome")
        assertEquals(0, assertsCountForEventId("evt-write-fail"), "nothing must be committed on a write failure")
    }

    @Test
    fun `a propagation failure after a successful write is committed, distinct from full success`() = runBlocking {
        val email = "prop-fail-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val failingPropagator = object : HorizonPropagator {
            override suspend fun propagate(userEmail: String, cycleSeq: Long, newPhrases: List<Pair<String, String>>): PropagationOutcome =
                PropagationOutcome.Failed("simulated total propagation failure")
        }

        val outcome = service(propagator = failingPropagator).ingest(email, "evt-prop-fail", ActorEventKind.Observation("hello"), "hermes")

        assertTrue(outcome is ActorEventIngestOutcome.Committed, "evidence commitment must not be rolled back just because propagation failed")
        val committed = outcome as ActorEventIngestOutcome.Committed
        assertTrue(committed.propagationOutcome is PropagationOutcome.Failed, "propagation failure must be reported, never silently reported as full success")
        assertEquals(1, assertsCountForEventId("evt-prop-fail"), "the evidence write itself succeeded and must stand")
    }

    // ── Lookup failure vs. confirmed absence ────────────────────────────────────

    @Test
    fun `a transient lookup failure after a prior commit stops before allocating or writing, evidence remains singular`() = runBlocking {
        val email = "lookup-fail-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val first = service().ingest(email, "evt-lookup-fail", ActorEventKind.Observation("Weekly backup completed"), "hermes")
        assertTrue(first is ActorEventIngestOutcome.Committed, "expected Committed, got $first")

        val failingLookupStore = object : HorizonGraphStore by store {
            override suspend fun findActorEventByEventId(userEmail: String, eventId: String): HorizonGraphStore.ActorEventLookupResult =
                HorizonGraphStore.ActorEventLookupResult.LookupFailed("simulated transient query failure")
        }
        val retryDuringOutage = service(store = failingLookupStore, propagator = SalientTokenPropagator(failingLookupStore, assembler))
            .ingest(email, "evt-lookup-fail", ActorEventKind.Observation("Weekly backup completed"), "hermes")

        assertTrue(retryDuringOutage is ActorEventIngestOutcome.LookupFailed, "expected LookupFailed, got $retryDuringOutage")
        assertEquals(1, assertsCountForEventId("evt-lookup-fail"), "a lookup failure must never allocate or write — evidence must remain singular")

        val retryAfterRecovery = service().ingest(email, "evt-lookup-fail", ActorEventKind.Observation("Weekly backup completed"), "hermes")
        assertTrue(retryAfterRecovery is ActorEventIngestOutcome.DuplicateDelivery, "once lookup recovers, the same delivery must resolve to the original commit, got $retryAfterRecovery")
        assertEquals(1, assertsCountForEventId("evt-lookup-fail"), "still exactly one Phrase after the lookup recovers")
    }

    @Test
    fun `a lookup failure on a genuinely new eventId also stops before allocating or writing`() = runBlocking {
        val email = "lookup-fail-fresh-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val failingLookupStore = object : HorizonGraphStore by store {
            override suspend fun findActorEventByEventId(userEmail: String, eventId: String): HorizonGraphStore.ActorEventLookupResult =
                HorizonGraphStore.ActorEventLookupResult.LookupFailed("simulated transient query failure")
        }
        val outcome = service(store = failingLookupStore, propagator = SalientTokenPropagator(failingLookupStore, assembler))
            .ingest(email, "evt-fresh-lookup-fail", ActorEventKind.Observation("hello"), "hermes")
        assertTrue(outcome is ActorEventIngestOutcome.LookupFailed, "expected LookupFailed, got $outcome")
        assertEquals(0, assertsCountForEventId("evt-fresh-lookup-fail"), "no allocation or write may occur on a lookup failure, even for a never-before-seen eventId")
    }

    // ── Propagation checkpoint recovery ─────────────────────────────────────────

    @Test
    fun `propagation interrupted between effects and checkpoint persistence reports uncertain, never recomputes, and stays durable through reopened storage`() = runBlocking {
        val email = "prop-pending-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        // An open intention a full propagate() recompute WOULD connect to, if it ran — proves
        // PropagationUncertain genuinely takes no propagation action, not merely "happens to find
        // nothing to do".
        seedOpenIntention(email, cycleSeq = 1, text = "Arx priority is getting Alfrd running")
        val phraseUid = seedActorEventWithPropagationStatus(
            email, "evt-pending", cycleSeq = 2, text = "Arx developer build finished compiling", sourceName = "hermes", propagationStatus = "pending",
        )

        val retry = service().ingest(email, "evt-pending", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")

        assertTrue(retry is ActorEventIngestOutcome.PropagationUncertain, "expected PropagationUncertain, got $retry")
        val uncertain = retry as ActorEventIngestOutcome.PropagationUncertain
        assertEquals(phraseUid, uncertain.existingPhraseUid, "the already-committed evidence must still be identified — commitment and propagation are independent facts")
        assertEquals(0, relatedToCount(email), "no propagation action may be taken while the checkpoint is uncertain — never a silent full recompute against current candidates")
        assertEquals(1, assertsCountForEventId("evt-pending"), "no duplicate evidence")

        dbManager.close()
        dbManager = DatabaseManager(testDbPath)
        val reopenedStore = ArcadeHorizonGraphStore(dbManager.getDatabase())
        val reopenedAssembler = ArcadeHorizonAssembler(dbManager.getDatabase())
        val reopenedService = ActorEventIngestionService(
            ArcadeCycleSequencer(dbManager.getDatabase()), reopenedStore, SalientTokenPropagator(reopenedStore, reopenedAssembler),
        )
        val retryAfterReopen = reopenedService.ingest(email, "evt-pending", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")
        assertTrue(retryAfterReopen is ActorEventIngestOutcome.PropagationUncertain, "the uncertain checkpoint must be durable, not in-memory — expected PropagationUncertain again, got $retryAfterReopen")
        assertEquals(0, relatedToCount(email), "still no propagation action after reopening storage")
    }

    @Test
    fun `an eventId whose propagation never started is the one safe case that runs a full retry`() = runBlocking {
        val email = "prop-never-started-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val target = seedOpenIntention(email, cycleSeq = 1, text = "Arx priority is getting Alfrd running")
        val phraseUid = seedActorEventWithPropagationStatus(
            email, "evt-never-started", cycleSeq = 2, text = "Arx developer build finished compiling", sourceName = "hermes", propagationStatus = null,
        )

        val retry = service().ingest(email, "evt-never-started", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")

        assertTrue(retry is ActorEventIngestOutcome.DuplicateDelivery, "expected DuplicateDelivery, got $retry")
        val dup = retry as ActorEventIngestOutcome.DuplicateDelivery
        assertEquals(phraseUid, dup.existingPhraseUid)
        assertNotNull(dup.propagationOutcome, "propagation was never started for this event, so a duplicate delivery must actually run it — the one accepted full-recompute case")
        assertTrue(dup.propagationOutcome is PropagationOutcome.Propagated, "expected Propagated, got ${dup.propagationOutcome}")
        assertEquals(1, relatedToCount(email), "the never-started propagation should connect to the pre-existing open candidate now that it finally runs")
        assertTrue((dup.propagationOutcome as PropagationOutcome.Propagated).edgesCreated.any { it.toPhraseUid == target })
    }

    @Test
    fun `when the pending checkpoint write itself fails, propagate is never called and the status stays recoverable as never-started`() = runBlocking {
        val email = "checkpoint-write-fail-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        // An open intention a real propagate() run WOULD connect to — makes "propagate never ran"
        // an observable fact (zero relevance edges), not just an assumption.
        seedOpenIntention(email, cycleSeq = 1, text = "Arx priority is getting Alfrd running")

        val checkpointFailingStore = pendingCheckpointFailingStore(store)
        val countingPropagator = CountingPropagator(SalientTokenPropagator(checkpointFailingStore, assembler))

        val outcome = service(store = checkpointFailingStore, propagator = countingPropagator)
            .ingest(email, "evt-checkpoint-write-fail", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")

        assertTrue(outcome is ActorEventIngestOutcome.Committed, "evidence commitment must not be blocked by a checkpoint-write failure, got $outcome")
        val committed = outcome as ActorEventIngestOutcome.Committed
        assertTrue(committed.propagationOutcome is PropagationOutcome.Failed, "expected Failed, got ${committed.propagationOutcome}")
        assertEquals(0, countingPropagator.callCount, "propagate() must never run unless the pending checkpoint write is confirmed to have succeeded first")
        assertEquals(0, relatedToCount(email), "no relevance effects can exist if propagate() was never called")

        // The invariant a later retry depends on: propagationStatus was left untouched by the
        // failed checkpoint write (never promoted to "pending"), so a genuine retry through the
        // REAL store still sees "never started" and safely runs a full recompute exactly once —
        // never PropagationUncertain, which would mean a real effect might already exist unrecorded.
        val retry = service().ingest(email, "evt-checkpoint-write-fail", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")
        assertTrue(retry is ActorEventIngestOutcome.DuplicateDelivery, "expected DuplicateDelivery, got $retry")
        val dup = retry as ActorEventIngestOutcome.DuplicateDelivery
        assertNotNull(dup.propagationOutcome, "a never-started propagation must actually run on retry, not be skipped")
        assertTrue(dup.propagationOutcome is PropagationOutcome.Propagated, "expected Propagated, got ${dup.propagationOutcome}")
        assertEquals(1, relatedToCount(email), "the recovered retry should connect to the pre-existing open candidate")
    }

    @Test
    fun `a checkpoint write failure on the never-started duplicate-delivery retry path also never calls propagate`() = runBlocking {
        val email = "checkpoint-write-fail-retry-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        seedOpenIntention(email, cycleSeq = 1, text = "Arx priority is getting Alfrd running")
        seedActorEventWithPropagationStatus(
            email, "evt-never-started-checkpoint-fail", cycleSeq = 2, text = "Arx developer build finished compiling", sourceName = "hermes", propagationStatus = null,
        )

        val checkpointFailingStore = pendingCheckpointFailingStore(store)
        val countingPropagator = CountingPropagator(SalientTokenPropagator(checkpointFailingStore, assembler))

        val retry = service(store = checkpointFailingStore, propagator = countingPropagator)
            .ingest(email, "evt-never-started-checkpoint-fail", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")

        assertTrue(retry is ActorEventIngestOutcome.DuplicateDelivery, "expected DuplicateDelivery, got $retry")
        val dup = retry as ActorEventIngestOutcome.DuplicateDelivery
        assertTrue(dup.propagationOutcome is PropagationOutcome.Failed, "expected Failed, got ${dup.propagationOutcome}")
        assertEquals(0, countingPropagator.callCount, "propagate() must never run without a confirmed durable pending checkpoint, on this retry path either")
        assertEquals(0, relatedToCount(email))
    }

    @Test
    fun `PropagationUncertain never invokes propagate at all, confirmed by call count not just absence of edges`() = runBlocking {
        val email = "prop-uncertain-callcount-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        seedOpenIntention(email, cycleSeq = 1, text = "Arx priority is getting Alfrd running")
        seedActorEventWithPropagationStatus(
            email, "evt-uncertain-callcount", cycleSeq = 2, text = "Arx developer build finished compiling", sourceName = "hermes", propagationStatus = "pending",
        )

        val countingPropagator = CountingPropagator(SalientTokenPropagator(store, assembler))
        val outcome = service(propagator = countingPropagator)
            .ingest(email, "evt-uncertain-callcount", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")

        assertTrue(outcome is ActorEventIngestOutcome.PropagationUncertain, "expected PropagationUncertain, got $outcome")
        assertEquals(0, countingPropagator.callCount, "an uncertain checkpoint must never trigger a fresh recomputation — propagate() must not be called at all")
    }

    @Test
    fun `a duplicate delivery of an event whose original propagation returned Failed reports uncertain, never recomputes`() = runBlocking {
        // Regression test: PropagationOutcome.Failed is recorded as propagationStatus="incomplete"
        // with an EMPTY target list (see recordPropagationOutcome) — resolveDuplicateDelivery used
        // to fall through its incompletePropagationTargets.isNotEmpty() check straight into the
        // "never started" full-recompute branch, because nothing verified propagationStatus was
        // actually null first. A recorded (but empty) "incomplete" state is not "never started" —
        // it must be treated exactly like "pending": report uncertain, take no action.
        val email = "prop-failed-then-replay-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        // An open intention a full recompute WOULD connect to, if invoked — makes "never
        // recomputed" an observable fact, not an assumption.
        seedOpenIntention(email, cycleSeq = 1, text = "Arx priority is getting Alfrd running")

        val failingPropagator = object : HorizonPropagator {
            override suspend fun propagate(userEmail: String, cycleSeq: Long, newPhrases: List<Pair<String, String>>): PropagationOutcome =
                PropagationOutcome.Failed("simulated total propagation failure")
        }
        val countingPropagator = CountingPropagator(failingPropagator)

        val first = service(propagator = countingPropagator)
            .ingest(email, "evt-failed-then-replay", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")
        assertTrue(first is ActorEventIngestOutcome.Committed, "expected Committed, got $first")
        assertTrue((first as ActorEventIngestOutcome.Committed).propagationOutcome is PropagationOutcome.Failed)
        assertEquals(1, countingPropagator.callCount, "the original attempt must actually run propagate() once")

        val replay = service(propagator = countingPropagator)
            .ingest(email, "evt-failed-then-replay", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")

        assertTrue(
            replay is ActorEventIngestOutcome.PropagationUncertain,
            "a prior Failed outcome with no recorded target set must never authorize a full recompute — expected PropagationUncertain, got $replay",
        )
        assertEquals(1, countingPropagator.callCount, "the replay must add zero additional propagation calls")
        assertEquals(0, relatedToCount(email), "no relevance effects can exist from a replay that never ran propagate()")
    }

    // ── Isolation ────────────────────────────────────────────────────────────

    @Test
    fun `two different users reusing the identical eventId string never collide`() = runBlocking {
        val emailA = "userA-${UUID.randomUUID()}@test.alfrd.internal"
        val emailB = "userB-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(emailA)
        seedUser(emailB)

        val outcomeA = service().ingest(emailA, "shared-event-id", ActorEventKind.Observation("A's own event"), "hermes")
        val outcomeB = service().ingest(emailB, "shared-event-id", ActorEventKind.Observation("B's own event"), "hermes")

        assertTrue(outcomeA is ActorEventIngestOutcome.Committed, "user A must succeed independently, got $outcomeA")
        assertTrue(outcomeB is ActorEventIngestOutcome.Committed, "user B must succeed independently, not treated as a conflict with A's own use of the same id, got $outcomeB")
        assertNotEquals((outcomeA as ActorEventIngestOutcome.Committed).phraseUid, (outcomeB as ActorEventIngestOutcome.Committed).phraseUid)

        val aLookup = store.findActorEventByEventId(emailA, "shared-event-id")
        val bLookup = store.findActorEventByEventId(emailB, "shared-event-id")
        assertTrue(aLookup is HorizonGraphStore.ActorEventLookupResult.Found, "expected Found, got $aLookup")
        assertTrue(bLookup is HorizonGraphStore.ActorEventLookupResult.Found, "expected Found, got $bLookup")
        val aRecord = (aLookup as HorizonGraphStore.ActorEventLookupResult.Found).record
        val bRecord = (bLookup as HorizonGraphStore.ActorEventLookupResult.Found).record
        assertNotEquals(aRecord.phraseUid, bRecord.phraseUid)
        assertEquals("A's own event", store.phraseText(emailA, aRecord.phraseUid))
        assertEquals("B's own event", store.phraseText(emailB, bRecord.phraseUid))
    }

    // ── Concurrency ──────────────────────────────────────────────────────────

    @Test
    fun `concurrent identical deliveries through the real service resolve to exactly one commit`() = runBlocking {
        val email = "concurrent-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val s = service()

        val outcomes = coroutineScope {
            (1..5).map {
                async { s.ingest(email, "evt-concurrent", ActorEventKind.Observation("Concurrent delivery"), "hermes") }
            }.awaitAll()
        }

        val committedCount = outcomes.count { it is ActorEventIngestOutcome.Committed }
        val duplicateCount = outcomes.count { it is ActorEventIngestOutcome.DuplicateDelivery }
        assertEquals(1, committedCount, "exactly one delivery must win the commit — PerUserCycleLock serializes the rest, got outcomes: $outcomes")
        assertEquals(4, duplicateCount)
        assertEquals(1, assertsCountForEventId("evt-concurrent"), "only one Phrase must exist despite 5 concurrent identical deliveries")
    }

    // ── Actor evidence leaves user intent and permissions unchanged ────────────

    @Test
    fun `Actor evidence ingestion leaves an existing intention's status and the User's other fields untouched`() = runBlocking {
        val email = "unchanged-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val intentionUid = seedOpenIntention(email, cycleSeq = 1, text = "Getting Alfrd running on Arx is a priority for me")

        val db = dbManager.getDatabase()
        fun assertsSnapshot() = db.query("sql", "SELECT status, statusHistory, statusCycleSeq FROM ASSERTS WHERE @in.uid = :uid", mapOf("uid" to intentionUid))
            .use { rs -> rs.next().toMap() }
        fun userSnapshot() = db.query("sql", "SELECT username, tier, email FROM User WHERE email = :e", mapOf("e" to email))
            .use { rs -> rs.next().toMap() }
        val beforeAssert = assertsSnapshot()
        val beforeUser = userSnapshot()

        val outcome = service().ingest(email, "evt-unchanged", ActorEventKind.Observation("Arx developer build finished compiling"), "hermes")
        assertTrue(outcome is ActorEventIngestOutcome.Committed)

        val afterAssert = assertsSnapshot()
        val afterUser = userSnapshot()
        assertEquals(beforeAssert["status"], afterAssert["status"], "the pre-existing intention's status must be untouched by an unrelated Actor event")
        assertEquals(beforeAssert["statusHistory"], afterAssert["statusHistory"])
        assertEquals(beforeAssert["statusCycleSeq"], afterAssert["statusCycleSeq"])
        // lastCycleSeq bookkeeping on User is an expected, permitted side effect of allocating a
        // cycle — only username/tier/email (identity/authority/permission-relevant fields) are
        // asserted unchanged here.
        assertEquals(beforeUser["username"], afterUser["username"])
        assertEquals(beforeUser["tier"], afterUser["tier"])
        assertEquals(beforeUser["email"], afterUser["email"])
    }

    // ── Provenance is controlled by the ingestion contract, per ActorEventKind ──

    @Test
    fun `each ActorEventKind is committed with its own distinct, non-default provenance source type`() = runBlocking {
        val email = "kinds-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val obs = service().ingest(email, "evt-obs", ActorEventKind.Observation("saw X"), "hermes") as ActorEventIngestOutcome.Committed
        val interp = service().ingest(email, "evt-interp", ActorEventKind.Interpretation("concluded Y", basis = "from patterns in Z"), "hermes") as ActorEventIngestOutcome.Committed
        val tool = service().ingest(email, "evt-tool", ActorEventKind.ToolResult("did W", toolName = "deploy", toolSucceeded = true), "hermes") as ActorEventIngestOutcome.Committed

        fun sourceTypeOf(uid: String) = dbManager.getDatabase().query("sql", "SELECT context FROM ASSERTS WHERE @in.uid = :u", mapOf("u" to uid))
            .use { rs -> rs.next().toMap()["context"] as String }

        assertEquals(ACTOR_OBSERVATION_SOURCE_TYPE, sourceTypeOf(obs.phraseUid))
        assertEquals(ACTOR_INTERPRETATION_SOURCE_TYPE, sourceTypeOf(interp.phraseUid))
        assertEquals(ACTOR_TOOL_RESULT_SOURCE_TYPE, sourceTypeOf(tool.phraseUid))
        val distinctTypes = setOf(sourceTypeOf(obs.phraseUid), sourceTypeOf(interp.phraseUid), sourceTypeOf(tool.phraseUid))
        assertEquals(3, distinctTypes.size, "all three kinds must be distinguishable from each other")
    }

    @Test
    fun `identical text under different kinds is never treated as a conflicting or duplicate event`() = runBlocking {
        val email = "same-text-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val asObservation = service().ingest(email, "evt-same-text-1", ActorEventKind.Observation("Deployment finished"), "hermes")
        val asToolResult = service().ingest(email, "evt-same-text-2", ActorEventKind.ToolResult("Deployment finished", toolName = "deploy", toolSucceeded = true), "hermes")

        assertTrue(asObservation is ActorEventIngestOutcome.Committed)
        assertTrue(asToolResult is ActorEventIngestOutcome.Committed, "different eventId + different kind must never be rejected as a conflict just because the text matches")
    }

    // ── assignmentId is correlation only, never a dedup key ────────────────────

    @Test
    fun `assignmentId correlates events without participating in eventId-based dedup`() = runBlocking {
        val email = "assignment-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val first = service().ingest(email, "evt-a1", ActorEventKind.Observation("step one done"), "hermes", assignmentId = "assignment-123")
        val second = service().ingest(email, "evt-a2", ActorEventKind.Observation("step two done"), "hermes", assignmentId = "assignment-123")

        assertTrue(first is ActorEventIngestOutcome.Committed)
        assertTrue(second is ActorEventIngestOutcome.Committed, "two distinct eventIds sharing an assignmentId must both commit independently")
        assertNotEquals((first as ActorEventIngestOutcome.Committed).phraseUid, (second as ActorEventIngestOutcome.Committed).phraseUid)
    }

    @Test
    fun `retrying with occurredAt omitted both times is a duplicate, not a conflict, despite the clock advancing`() = runBlocking {
        val email = "occurred-at-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val first = service().ingest(email, "evt-occurred", ActorEventKind.Observation("periodic health check ok"), "hermes", occurredAt = null)
        Thread.sleep(5) // let wall-clock receipt time actually advance between the two calls
        val second = service().ingest(email, "evt-occurred", ActorEventKind.Observation("periodic health check ok"), "hermes", occurredAt = null)

        assertTrue(second is ActorEventIngestOutcome.DuplicateDelivery, "an identical retry with occurredAt omitted both times must never conflict merely because receipt time advanced — got $second")
    }
}
