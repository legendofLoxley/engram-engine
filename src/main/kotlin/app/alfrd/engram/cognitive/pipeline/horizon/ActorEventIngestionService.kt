package app.alfrd.engram.cognitive.pipeline.horizon

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

    /**
     * [HorizonGraphStore.findActorEventByEventId] could not determine whether this [eventId] was
     * already delivered — a transient query failure, not a confirmed absence. Nothing was allocated
     * or written this call: treating a lookup failure as "not found" would risk creating duplicate
     * evidence for an event that, for all this call actually established, may already exist. Safe
     * to retry the identical delivery once the underlying failure clears.
     */
    data class LookupFailed(val eventId: String, val reason: String) : ActorEventIngestOutcome

    /**
     * A prior delivery of this [eventId] committed evidence and started propagation
     * ([HorizonGraphStore.ActorEventRecord.propagationStatus] `"pending"`), but a crash or failure
     * between that start and its checkpoint means what it actually did to the graph — if
     * anything — was never recorded. This delivery deliberately took **no** propagation action:
     * re-running [HorizonPropagator.propagate] here would compare the original evidence against
     * whatever is open *now*, silently authorizing a full recomputation against intentions
     * introduced after the original attempt — exactly the outcome this type exists to prevent.
     * [existingPhraseUid]/[existingCycleSeq] identify the already-committed evidence (unaffected —
     * evidence commitment and propagation are independent facts); resolving the uncertainty is an
     * explicit follow-up action, not something this call performs on its own.
     */
    data class PropagationUncertain(val eventId: String, val existingPhraseUid: String, val existingCycleSeq: Long, val reason: String) : ActorEventIngestOutcome
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
 * **Propagation retry is precise, not a blind recompute — and never proceeds from an uncertain
 * checkpoint.** The first attempt always compares the new evidence against every currently open
 * candidate ([HorizonPropagator.propagate] — that is what propagation means). Immediately before
 * that call, this service durably records `propagationStatus = "pending"` — a checkpoint written
 * *before* the effect it describes, not after — via
 * [HorizonGraphStore.recordActorEventPropagationStatus]. Once the attempt returns, the real outcome
 * (`"completed"` / `"incomplete"` + the precise `(toPhraseUid, strength)` set still owed) overwrites
 * `"pending"`. A later duplicate delivery of the *same* event then behaves according to exactly what
 * checkpoint state it finds, never by re-running the comparison against today's state blind:
 * - `"completed"` → skipped entirely. A successfully processed duplicate must never create new
 *   relevance effects against intentions introduced later — the only way to guarantee that is to
 *   never look at "later" again once a delivery is known complete.
 * - `"pending"` → the attempt started but its outcome was never recorded (a crash or failure between
 *   effects and checkpoint persistence). This is genuinely missing recovery information, not a safe
 *   "nothing happened yet" — reported as [ActorEventIngestOutcome.PropagationUncertain] with **no**
 *   propagation action taken. Silently falling through to a full recompute here is exactly the bug
 *   this checkpoint exists to prevent: it would compare the original evidence against whatever is
 *   open *now*, which may include intentions that did not exist at the time of the original attempt.
 * - `"incomplete"` with a specific recorded `(toPhraseUid, strength)` set → only those exact pairs
 *   are retried, via [HorizonGraphStore.markRelevant] directly (not [HorizonPropagator.propagate],
 *   which would recompute against today's full candidate pool) — so a retry can never connect the
 *   original evidence to something that did not exist at the time of the original attempt. This is
 *   safe to interrupt and repeat: the recorded target set is never narrowed mid-retry, so a crash
 *   partway through simply means the next retry repeats the same bounded set, and
 *   [HorizonGraphStore.markRelevant] is itself idempotent per `(from, to)` pair. Whatever *does* get
 *   created this time is stamped with a **freshly allocated cycle**, not the original event's — an
 *   effect discovered now is dated now, never backdated.
 * - `null`/absent (the original `propagate()` call was never reached — e.g. a crash between evidence
 *   commitment and the `"pending"` checkpoint) → the one narrow, accepted exception: a full
 *   [HorizonPropagator.propagate] retry against today's candidates. This is safe specifically
 *   *because* nothing was ever started — there is no prior partial effect to reconcile against, so
 *   this genuinely *is* the first attempt, not a recomputation of one already in flight.
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

        when (val lookup = horizonGraphStore.findActorEventByEventId(userEmail, eventId)) {
            is HorizonGraphStore.ActorEventLookupResult.LookupFailed -> {
                // Stop here — no allocation, no write. A caught query failure is not evidence of
                // absence; treating it as one would risk creating duplicate evidence on retry.
                logger.warn("ingest: lookup failed for eventId=$eventId userEmail=$userEmail: ${lookup.reason} — stopping without allocating or writing")
                return@withLock ActorEventIngestOutcome.LookupFailed(eventId, lookup.reason)
            }
            is HorizonGraphStore.ActorEventLookupResult.Found -> {
                val existing = lookup.record
                if (existing.contentHash != fingerprint) {
                    logger.warn("ingest: eventId=$eventId reused with different content for userEmail=$userEmail — rejecting as conflicting reuse")
                    return@withLock ActorEventIngestOutcome.ConflictingReuse(eventId, existing.phraseUid, "eventId already committed with different content")
                }
                return@withLock resolveDuplicateDelivery(userEmail, eventId, existing, kind.text)
            }
            HorizonGraphStore.ActorEventLookupResult.ConfirmedAbsent -> {
                // Genuine "never delivered" — proceed to a fresh commit below.
            }
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

        val propagationOutcome = runAndRecordPropagation(userEmail, eventId, phraseUid, cycleSeq, kind.text)

        ActorEventIngestOutcome.Committed(eventId, phraseUid, cycleSeq, propagationOutcome)
    }

    /**
     * Resolves a duplicate delivery of an already-committed [eventId] purely from its recorded
     * checkpoint state — see this class's doc for the full state table. Never inspects "today's"
     * candidate pool except in the one narrow case ([HorizonGraphStore.ActorEventRecord.propagationStatus]
     * `null`) where doing so is provably a first attempt, not a recomputation.
     */
    private suspend fun resolveDuplicateDelivery(
        userEmail: String,
        eventId: String,
        existing: HorizonGraphStore.ActorEventRecord,
        text: String,
    ): ActorEventIngestOutcome {
        when (existing.propagationStatus) {
            "completed" -> return ActorEventIngestOutcome.DuplicateDelivery(eventId, existing.phraseUid, existing.cycleSeq, propagationOutcome = null)
            "pending" -> {
                logger.warn(
                    "resolveDuplicateDelivery: eventId=$eventId propagationStatus=pending (interrupted between effects and " +
                        "checkpoint persistence) for userEmail=$userEmail — reporting uncertain, taking no propagation action",
                )
                return ActorEventIngestOutcome.PropagationUncertain(
                    eventId,
                    existing.phraseUid,
                    existing.cycleSeq,
                    "propagation was started but its outcome was never recorded — refusing to recompute against current state",
                )
            }
            else -> Unit // "incomplete" with a recorded target set, or null/never-started — handled below.
        }

        if (existing.incompletePropagationTargets.isNotEmpty()) {
            val retryCycle = cycleSequencer.allocateCycle(userEmail)
                ?: return ActorEventIngestOutcome.DuplicateDelivery(
                    eventId, existing.phraseUid, existing.cycleSeq,
                    PropagationOutcome.Failed("cycle allocation failed for precise propagation retry"),
                )
            val created = mutableListOf<RelevanceEdgeSummary>()
            val stillIncomplete = mutableListOf<RelevanceEdgeSummary>()
            for (target in existing.incompletePropagationTargets) {
                val applied = horizonGraphStore.markRelevant(userEmail, retryCycle, existing.phraseUid, target.toPhraseUid, target.strength)
                if (applied) created += target else stillIncomplete += target
            }
            val outcome = PropagationOutcome.Propagated(edgesCreated = created, incompleteTargets = stillIncomplete)
            recordPropagationOutcome(userEmail, eventId, outcome)
            return ActorEventIngestOutcome.DuplicateDelivery(eventId, existing.phraseUid, existing.cycleSeq, outcome)
        }

        if (existing.propagationStatus != null) {
            // Reachable when propagationStatus is "incomplete" with an EMPTY target list — e.g.
            // PropagationOutcome.Failed, which SalientTokenPropagator can return without ever
            // capturing which markRelevant calls, if any, it attempted before failing (see that
            // class's catch block). The attempt's outcome WAS durably recorded here (unlike
            // "pending"), but it recorded no specific set to retry precisely — falling through to a
            // full recompute would carry the exact same "recompute against later intentions" risk
            // as "pending", so it gets the same treatment: report uncertain, take no action.
            logger.warn(
                "resolveDuplicateDelivery: eventId=$eventId propagationStatus=${existing.propagationStatus} with no recorded " +
                    "incomplete target set for userEmail=$userEmail — reporting uncertain, taking no propagation action",
            )
            return ActorEventIngestOutcome.PropagationUncertain(
                eventId,
                existing.phraseUid,
                existing.cycleSeq,
                "a prior propagation attempt recorded no specific retry target set — refusing to recompute against current state",
            )
        }

        // propagationStatus == null: never started. The one safe full-recompute case — there is no
        // prior partial effect to reconcile against, so this genuinely is a first attempt.
        val retryCycle = cycleSequencer.allocateCycle(userEmail)
            ?: return ActorEventIngestOutcome.DuplicateDelivery(
                eventId, existing.phraseUid, existing.cycleSeq,
                PropagationOutcome.Failed("cycle allocation failed for full propagation retry"),
            )
        val outcome = runAndRecordPropagation(userEmail, eventId, existing.phraseUid, retryCycle, text)
        return ActorEventIngestOutcome.DuplicateDelivery(eventId, existing.phraseUid, existing.cycleSeq, outcome)
    }

    /**
     * Runs one full [HorizonPropagator.propagate] attempt against today's candidate pool, with the
     * `"pending"` checkpoint written *before* the attempt — never after — so a crash during
     * [HorizonPropagator.propagate] itself (between effects and checkpoint persistence) leaves a
     * durable, explicit trace ([ActorEventIngestOutcome.PropagationUncertain] on the next lookup)
     * rather than silent `null`, which [resolveDuplicateDelivery] would otherwise treat as
     * "never started" and blindly recompute.
     *
     * **The checkpoint write's own success is verified, not assumed, before [propagate] ever
     * runs.** A checkpoint written *after* the effect it describes is worthless — but so is one
     * merely *attempted* beforehand: if [HorizonGraphStore.recordActorEventPropagationStatus]
     * itself fails (write-lock timeout, unknown user), `propagationStatus` stays exactly as it was
     * before this call — most likely still `null` — while [propagate] is about to run regardless.
     * A subsequent crash during that unguarded run would then leave real, uncertain effects behind
     * a `null` checkpoint, which [resolveDuplicateDelivery] treats as "never started" and safe to
     * fully recompute — silently reintroducing the exact uncontrolled recomputation this whole
     * mechanism exists to prevent. So [propagate] is never called unless the "pending" write is
     * confirmed durable first; on failure this returns [PropagationOutcome.Failed] immediately,
     * leaving `propagationStatus` untouched — a later retry's "`null` means safe to retry fully"
     * reasoning stays valid because nothing was actually attempted.
     */
    private suspend fun runAndRecordPropagation(userEmail: String, eventId: String, phraseUid: String, cycleSeq: Long, text: String): PropagationOutcome {
        val checkpointSaved = horizonGraphStore.recordActorEventPropagationStatus(userEmail, eventId, "pending", emptyList())
        if (!checkpointSaved) {
            logger.warn(
                "runAndRecordPropagation: could not persist the pending propagation checkpoint for eventId=$eventId " +
                    "userEmail=$userEmail — refusing to run propagate() without a confirmed durable checkpoint",
            )
            return PropagationOutcome.Failed("could not persist the pending propagation checkpoint before starting — propagation not attempted")
        }
        val outcome = horizonPropagator.propagate(userEmail, cycleSeq, listOf(phraseUid to text))
        recordPropagationOutcome(userEmail, eventId, outcome)
        return outcome
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

    /**
     * Event-specific fields, stored on the event's own edge (never only in the reused
     * Source.metadata) — see [HorizonGraphStore.ingestActorEvent]. Encoded via the shared
     * [ActorEventMetadata] shape so [HorizonAssembler]'s decode side can never drift out of
     * agreement with what was actually written here.
     */
    private fun kindMetadataFor(kind: ActorEventKind): String = when (kind) {
        is ActorEventKind.Observation -> metadataJson.encodeToString(ActorEventMetadata())
        is ActorEventKind.Interpretation -> metadataJson.encodeToString(ActorEventMetadata(basis = kind.basis))
        is ActorEventKind.ToolResult -> metadataJson.encodeToString(ActorEventMetadata(toolName = kind.toolName, toolSucceeded = kind.toolSucceeded))
    }
}
