package app.alfrd.engram.cognitive.pipeline.horizon

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * What kind of Actor-attributed claim this event is — the ONLY input that decides its
 * [ProvenanceKind]/`Source.type` (via [ActorEventIngestionService]'s private `sourceTypeFor`).
 * Deliberately a closed sealed type, not a caller-supplied free-text "kind" string: a request DTO
 * (e.g. a debug route) must map its own string discriminator through a fixed `when` into one of
 * these before calling [ActorEventIngestionService.ingest] — an unrecognized value is a rejection at
 * that boundary, never a passthrough into graph-visible provenance. [ToolResult.toolSucceeded] is
 * stored and surfaced as exactly what it is: a **self-reported claim** from whatever produced this
 * event. This service does not independently verify it — labeling a payload "tool" does not make its
 * claimed outcome verified evidence.
 */
sealed interface ActorEventKind {
    val text: String
    data class Observation(override val text: String) : ActorEventKind
    data class Interpretation(override val text: String, val basis: String) : ActorEventKind
    data class ToolResult(override val text: String, val toolName: String, val toolSucceeded: Boolean) : ActorEventKind
}

/**
 * Result of [ActorEventIngestionService.ingest]. Every variant is explicit — this codebase's house
 * style ([app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome],
 * [app.alfrd.engram.cognitive.pipeline.HorizonCycleResult]'s `MutationOutcome`) — because a sequence
 * number or a "no exception was thrown" alone proves nothing about what actually happened. Four
 * distinct facts, never conflated: **evidence committed** ([Committed]/[DuplicateDelivery] mean
 * yes, every other variant means no), **propagation completed** (each variant's own
 * `propagationOutcome`, entirely separate from whether evidence committed), **eligible** for a later
 * Horizon (a property of the committed graph state itself —
 * [SurfacingReason.RecentActorEvidence] — not something this outcome type asserts), and **actually
 * rendered** into a response prompt (decided per-turn by
 * [app.alfrd.engram.cognitive.pipeline.Actor]'s own budget ladder, entirely outside this service).
 * This service proves only the first two.
 */
sealed interface ActorEventIngestOutcome {
    /** Evidence committed (new Phrase + identity, atomically) and propagation attempted this delivery — [propagationOutcome] reports its own, separate result. */
    data class Committed(val eventId: String, val phraseUid: String, val cycleSeq: Long, val propagationOutcome: PropagationOutcome) : ActorEventIngestOutcome

    /**
     * The same [eventId] was already committed with identical content (matched by
     * [actorEventFingerprint]) — no new Phrase was created, satisfying "repeated delivery must not
     * duplicate evidence." [propagationOutcome] is `null` when the original delivery's propagation
     * was already recorded `"completed"` (nothing re-attempted — a successfully processed duplicate
     * must never create new relevance effects against intentions introduced since); non-null when
     * this delivery found genuinely incomplete prior propagation and retried it — see
     * [ActorEventIngestionService]'s class doc for exactly what that retry does and does not touch.
     */
    data class DuplicateDelivery(val eventId: String, val existingPhraseUid: String, val existingCycleSeq: Long, val propagationOutcome: PropagationOutcome?) : ActorEventIngestOutcome

    /** The same [eventId] was already committed with DIFFERENT content (fingerprint mismatch) — rejected explicitly, never silently overwritten, never treated as a duplicate. */
    data class ConflictingReuse(val eventId: String, val existingPhraseUid: String, val reason: String) : ActorEventIngestOutcome

    /** [CycleSequencer.allocateCycle] failed (unknown user, lock timeout) — nothing was committed. */
    data class AllocationFailed(val eventId: String) : ActorEventIngestOutcome

    /** [HorizonGraphStore.ingestActorEvent] failed — nothing was committed; the cycle number allocated for this attempt is simply unused (an accepted, documented gap — see [CycleSequencer]). */
    data class WriteFailed(val eventId: String, val reason: String) : ActorEventIngestOutcome

    /** Malformed input (blank required field) or an unauthorized/unknown scope — rejected before any allocation or write was attempted. */
    data class Rejected(val eventId: String, val reason: String) : ActorEventIngestOutcome
}

/**
 * Ingests one Actor-attributed event into the durable graph, independent of any Director turn —
 * "while chat is idle" per the governing contract. Reusable: this class has no dependency on
 * [app.alfrd.engram.cognitive.pipeline.CognitivePipeline], [app.alfrd.engram.cognitive.SessionManager],
 * or any HTTP concern — a debug route (`api/DebugActorEventRoutes.kt`) and, eventually, a real Hermes
 * adapter can both call [ingest] directly.
 *
 * **Concurrency and locking.** [ingest] acquires [PerUserCycleLock] for [ActorEventKind]'s owning
 * `userEmail` itself — callers never need to. This is what makes concurrent identical deliveries for
 * one user resolve deterministically to exactly one commit (see this class's tests): the mutex
 * serializes every call for that user, conversational and Actor-event alike, so the
 * check-for-existing-eventId-then-write sequence below can never race against itself.
 * **[PerUserCycleLock] is a single-process, in-memory `Mutex`** (see that object's own doc) — it
 * guarantees serialization within one running instance of this application, not across multiple
 * processes/replicas. A multi-process deployment sharing one database would need either an
 * ArcadeDB-enforced unique constraint on `eventId` (not configured today — only an index, which
 * speeds lookup but does not itself prevent two processes from both passing the "not found" check
 * before either commits) or a distributed lock; neither exists in this codebase. Today's actual
 * deployment is a single Ktor/Netty process, so this is a documented limitation, not a live gap.
 *
 * **Propagation retry is precise, not a blind recompute.** The first attempt always compares the new
 * evidence against every currently open candidate ([HorizonPropagator.propagate] — that is what
 * propagation means). But once that attempt's result is durably recorded (`ASSERTS.propagationStatus`
 * / `incompletePropagationTargets`, via [HorizonGraphStore.recordActorEventPropagationStatus]), a
 * later duplicate delivery of the *same* event behaves according to what was actually left undone,
 * never by re-running the comparison against today's state:
 * - `"completed"` → skipped entirely. A successfully processed duplicate must never create new
 *   relevance effects against intentions introduced later — the only way to guarantee that is to
 *   never look at "later" again once a delivery is known complete.
 * - `"incomplete"` with a specific recorded `(toPhraseUid, strength)` set → only those exact pairs
 *   are retried, via [HorizonGraphStore.markRelevant] directly (not [HorizonPropagator.propagate],
 *   which would recompute against today's full candidate pool) — so a retry can never connect the
 *   original evidence to something that did not exist at the time of the original attempt. Whatever
 *   *does* get created this time is stamped with a **freshly allocated cycle**, not the original
 *   event's — an effect discovered now is dated now, never backdated.
 * - No specific set recorded (the original `propagate()` call itself failed before producing one, or
 *   was never attempted) → the one narrow, accepted exception: a full [HorizonPropagator.propagate]
 *   retry against today's candidates, because there is no more precise information to retry against.
 */
open class ActorEventIngestionService(
    private val cycleSequencer: CycleSequencer,
    private val horizonGraphStore: HorizonGraphStore,
    private val horizonPropagator: HorizonPropagator,
) {
    private val logger = LoggerFactory.getLogger(ActorEventIngestionService::class.java)
    private val metadataJson = Json { encodeDefaults = false }

    open suspend fun ingest(
        userEmail: String,
        eventId: String,
        kind: ActorEventKind,
        sourceName: String,
        assignmentId: String? = null,
        occurredAt: Long? = null,
    ): ActorEventIngestOutcome = PerUserCycleLock.withLock(userEmail) {
        if (userEmail.isBlank() || eventId.isBlank() || sourceName.isBlank() || kind.text.isBlank()) {
            return@withLock ActorEventIngestOutcome.Rejected(eventId, "userEmail/eventId/sourceName/text must not be blank")
        }

        val sourceType = sourceTypeFor(kind)
        val fingerprint = fingerprintFor(kind, sourceName, assignmentId, occurredAt)

        val existing = horizonGraphStore.findActorEventByEventId(userEmail, eventId)
        if (existing != null) {
            if (existing.contentHash != fingerprint) {
                logger.warn("ingest: eventId=$eventId reused with different content for userEmail=$userEmail — rejecting as conflicting reuse")
                return@withLock ActorEventIngestOutcome.ConflictingReuse(eventId, existing.phraseUid, "eventId already committed with different content")
            }
            val propagationOutcome = retryPropagationIfIncomplete(userEmail, eventId, existing, kind.text)
            return@withLock ActorEventIngestOutcome.DuplicateDelivery(eventId, existing.phraseUid, existing.cycleSeq, propagationOutcome)
        }

        val cycleSeq = cycleSequencer.allocateCycle(userEmail)
            ?: return@withLock ActorEventIngestOutcome.AllocationFailed(eventId)

        val phraseUid = horizonGraphStore.ingestActorEvent(
            userEmail = userEmail,
            cycleSeq = cycleSeq,
            sourceName = sourceName,
            sourceType = sourceType,
            text = kind.text,
            eventId = eventId,
            contentHash = fingerprint,
            assignmentId = assignmentId,
            occurredAt = occurredAt,
            kindMetadata = kindMetadataFor(kind),
        ) ?: return@withLock ActorEventIngestOutcome.WriteFailed(eventId, "evidence write failed (unknown user or lock timeout)")

        val propagationOutcome = horizonPropagator.propagate(userEmail, cycleSeq, listOf(phraseUid to kind.text))
        recordPropagationOutcome(userEmail, eventId, propagationOutcome)

        ActorEventIngestOutcome.Committed(eventId, phraseUid, cycleSeq, propagationOutcome)
    }

    private suspend fun retryPropagationIfIncomplete(
        userEmail: String,
        eventId: String,
        existing: HorizonGraphStore.ActorEventRecord,
        text: String,
    ): PropagationOutcome? {
        if (existing.propagationStatus == "completed") return null

        return if (existing.incompletePropagationTargets.isNotEmpty()) {
            val retryCycle = cycleSequencer.allocateCycle(userEmail)
                ?: return PropagationOutcome.Failed("cycle allocation failed for precise propagation retry")
            val created = mutableListOf<RelevanceEdgeSummary>()
            val stillIncomplete = mutableListOf<RelevanceEdgeSummary>()
            for (target in existing.incompletePropagationTargets) {
                val applied = horizonGraphStore.markRelevant(userEmail, retryCycle, existing.phraseUid, target.toPhraseUid, target.strength)
                if (applied) created += target else stillIncomplete += target
            }
            val outcome = PropagationOutcome.Propagated(edgesCreated = created, incompleteTargets = stillIncomplete)
            recordPropagationOutcome(userEmail, eventId, outcome)
            outcome
        } else {
            // No specific incomplete set was ever recorded (the original propagate() call threw, or
            // this event's propagation was never attempted before a crash) — there is nothing precise
            // to retry, so this is the one accepted case that re-runs the full comparison.
            val retryCycle = cycleSequencer.allocateCycle(userEmail)
                ?: return PropagationOutcome.Failed("cycle allocation failed for full propagation retry")
            val outcome = horizonPropagator.propagate(userEmail, retryCycle, listOf(existing.phraseUid to text))
            recordPropagationOutcome(userEmail, eventId, outcome)
            outcome
        }
    }

    private suspend fun recordPropagationOutcome(userEmail: String, eventId: String, outcome: PropagationOutcome) {
        when (outcome) {
            is PropagationOutcome.Propagated -> {
                val status = if (outcome.incompleteTargets.isEmpty()) "completed" else "incomplete"
                horizonGraphStore.recordActorEventPropagationStatus(userEmail, eventId, status, outcome.incompleteTargets)
            }
            is PropagationOutcome.Failed -> {
                horizonGraphStore.recordActorEventPropagationStatus(userEmail, eventId, "incomplete", emptyList())
            }
        }
    }

    private fun sourceTypeFor(kind: ActorEventKind): String = when (kind) {
        is ActorEventKind.Observation -> ACTOR_OBSERVATION_SOURCE_TYPE
        is ActorEventKind.Interpretation -> ACTOR_INTERPRETATION_SOURCE_TYPE
        is ActorEventKind.ToolResult -> ACTOR_TOOL_RESULT_SOURCE_TYPE
    }

    private fun fingerprintFor(kind: ActorEventKind, sourceName: String, assignmentId: String?, occurredAt: Long?): String {
        val sourceType = sourceTypeFor(kind)
        return when (kind) {
            is ActorEventKind.Observation -> actorEventFingerprint(sourceType, sourceName, kind.text, assignmentId, occurredAt)
            is ActorEventKind.Interpretation -> actorEventFingerprint(sourceType, sourceName, kind.text, assignmentId, occurredAt, basis = kind.basis)
            is ActorEventKind.ToolResult -> actorEventFingerprint(
                sourceType, sourceName, kind.text, assignmentId, occurredAt, toolName = kind.toolName, toolSucceeded = kind.toolSucceeded,
            )
        }
    }

    @Serializable
    private data class KindMetadata(val basis: String? = null, val toolName: String? = null, val toolSucceeded: Boolean? = null)

    /** Event-specific fields, stored on the event's own edge (never only in the reused Source.metadata) — see [HorizonGraphStore.ingestActorEvent]. */
    private fun kindMetadataFor(kind: ActorEventKind): String = when (kind) {
        is ActorEventKind.Observation -> metadataJson.encodeToString(KindMetadata())
        is ActorEventKind.Interpretation -> metadataJson.encodeToString(KindMetadata(basis = kind.basis))
        is ActorEventKind.ToolResult -> metadataJson.encodeToString(KindMetadata(toolName = kind.toolName, toolSucceeded = kind.toolSucceeded))
    }
}
