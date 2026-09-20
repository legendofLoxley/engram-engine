package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.AppliedOperation
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.CycleFollowUp
import app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperation
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPropagator
import app.alfrd.engram.cognitive.pipeline.horizon.HoldForChoice
import app.alfrd.engram.cognitive.pipeline.horizon.OperationPlanCodec
import app.alfrd.engram.cognitive.pipeline.horizon.ProcessedRequestRecord
import app.alfrd.engram.cognitive.pipeline.horizon.PropagationOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.RecordItem
import app.alfrd.engram.cognitive.pipeline.horizon.RequestLedger
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshot
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshotBuilder
import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One graph write the coordinator confirmed or attempted this cycle, per operation — so a partial
 * failure (one operation rejected while another lands, say) can be reflected precisely in the
 * response's directive rather than collapsed into one blanket "something failed" flag.
 */
sealed interface MutationOutcome {
    /**
     * The fallback path only (the interpretation model was unavailable and a plain claim was
     * captured instead). [phraseUid] is null when the candidate never became a Phrase at all —
     * [EngramClient.decompose] or [EngramClient.ingest] threw, or a partial [EngramClient.ingest]
     * result couldn't be safely attributed — as distinct from a Phrase that was created but whose
     * Horizon stamp failed ([applied] false with a non-null [phraseUid]). Either way [applied] is
     * false and the write must never be described to the user as having succeeded.
     */
    data class Fact(val phraseUid: String?, val applied: Boolean) : MutationOutcome

    /** One interpretation-model operation and what became of it. */
    data class Operation(val result: AppliedOperation) : MutationOutcome
}

/** What the previous turn left waiting on the user — handed back so the model can read this turn's message as the answer. */
data class PendingInterpretation(
    /** An item saved unfiled while the user was asked which project it belongs to. */
    val filingItemUid: String? = null,
    /** A statement held back while the user was asked whether they just want to talk or want a task. */
    val emotionQuote: String? = null,
) {
    companion object { val NONE = PendingInterpretation() }
}

/** Full result of one [HorizonCycleCoordinator.runCycle] call. */
data class HorizonCycleResult(
    val cycleSeq: Long?,
    val interpretOutcome: InterpretOutcome?,
    val mutationOutcomes: List<MutationOutcome>,
    val propagationOutcome: PropagationOutcome?,
    val assembleOutcome: AssembleOutcome?,
    val interpretLatencyMs: Long = 0,
    val propagateLatencyMs: Long = 0,
    val assembleLatencyMs: Long = 0,
    val allocationFailed: Boolean = false,
    /** Something the reply must ask the user because of what was just written — see [CycleFollowUp]. */
    val followUp: CycleFollowUp? = null,
    /** The user's live projects at the time — for a "which project?" question. Empty when the cycle was resumed. */
    val projectNames: List<String> = emptyList(),
) {
    companion object {
        fun allocationFailed(): HorizonCycleResult = HorizonCycleResult(
            cycleSeq = null,
            interpretOutcome = null,
            mutationOutcomes = emptyList(),
            propagationOutcome = null,
            assembleOutcome = null,
            allocationFailed = true,
        )
    }
}

/**
 * Orchestrates one conversational cycle: allocate → interpret → plan → apply → propagate → assemble.
 *
 * The [Interpreter] proposes operations against a [ViewSnapshot]; the validated plan is
 * checkpointed (`ops_planned`) *before* anything is written, then applied by
 * [HorizonOperationApplier], then propagated over and assembled. If the model can't be used
 * ([InterpretOutcome.LlmFailure]), the fallback is today's plain claim capture ([EngramClient.decompose]
 * then [EngramClient.ingest], stamped for the Horizon) so a model outage never means a memory outage —
 * the failure is reported, never hidden.
 *
 * The caller must already hold [app.alfrd.engram.cognitive.pipeline.horizon.PerUserCycleLock] for
 * [userEmail] before calling [runCycle] — this class does not acquire it itself, because it must
 * also cover [Script.run]'s own writes, which happen *before* this class is ever invoked (see
 * [CognitivePipeline.processInternal] for exactly what the lock wraps and why).
 *
 * Checkpointed via [RequestLedger] so a crash mid-cycle is resumable without duplicating work —
 * see that interface's doc for the full state machine. Interpretation never runs twice for the
 * same `requestId`: once `ops_planned` is recorded, a resume replays the stored plan, and every
 * operation is idempotent under replay (see [HorizonOperationApplier]).
 */
open class HorizonCycleCoordinator(
    private val cycleSequencer: CycleSequencer,
    private val requestLedger: RequestLedger,
    private val interpreter: Interpreter,
    private val engramClient: EngramClient,
    private val horizonGraphStore: HorizonGraphStore,
    private val horizonPropagator: HorizonPropagator,
    private val horizonAssembler: HorizonAssembler,
    private val viewSnapshotBuilder: ViewSnapshotBuilder,
    private val operationApplier: HorizonOperationApplier,
) {

    private val logger = LoggerFactory.getLogger(HorizonCycleCoordinator::class.java)

    /**
     * Test-only synchronization point, invoked immediately before the interpretation LLM call.
     * No-op (null) in production. Mirrors [app.alfrd.engram.cognitive.pipeline.horizon.HorizonAssembler]'s
     * `testMidAssemblySync` — lets a test deterministically park one cycle mid-flight (while it
     * holds [app.alfrd.engram.cognitive.pipeline.horizon.PerUserCycleLock] for this user) and
     * observe, rather than assume, that a second cycle for the same user genuinely waits for it.
     */
    internal var testInterpretationBarrier: (suspend () -> Unit)? = null

    open suspend fun runCycle(
        userEmail: String,
        requestId: String,
        utterance: String,
        pending: PendingInterpretation = PendingInterpretation.NONE,
        zone: ZoneId = ZoneId.systemDefault(),
    ): HorizonCycleResult {
        requestLedger.find(userEmail, requestId)?.let { existing ->
            when (existing.checkpoint) {
                "completed" -> return resultFromCompleted(userEmail, existing)
                "writes_committed" -> return resumeFromWritesCommitted(userEmail, requestId, existing)
                "ops_planned" -> resumeFromOpsPlanned(userEmail, requestId, existing)?.let { return it }
                "facts_ingested" -> return resumeFromFactsIngested(userEmail, requestId, existing)
                else -> { /* cycle_allocated-only, failed, or unrecognized — proceed fresh below */ }
            }
        }

        val cycleSeq = cycleSequencer.allocateCycle(userEmail) ?: return HorizonCycleResult.allocationFailed()
        requestLedger.checkpoint(userEmail, requestId, cycleSeq, "cycle_allocated")

        testInterpretationBarrier?.invoke()
        val snapshot = try {
            viewSnapshotBuilder.build(userEmail, zone, pending.filingItemUid, pending.emotionQuote)
        } catch (e: Exception) {
            // A snapshot the model can't be given isn't a reason to lose the turn: it just can't refer to existing things.
            logger.warn("runCycle: view snapshot failed for userEmail=$userEmail: ${e.message}")
            ViewSnapshot(emptyList(), emptyList(), emptyList(), pending.emotionQuote, null, zone)
        }
        val interpretStartMs = System.currentTimeMillis()
        val interpretOutcome = interpreter.interpret(utterance, snapshot, ZonedDateTime.now(zone))
        val interpretLatencyMs = System.currentTimeMillis() - interpretStartMs

        if (interpretOutcome is InterpretOutcome.LlmFailure) {
            return fallbackCaptureFacts(userEmail, requestId, cycleSeq, utterance, interpretOutcome, interpretLatencyMs)
        }

        val plan = (interpretOutcome as? InterpretOutcome.Operations)?.operations ?: emptyList()
        requestLedger.checkpoint(userEmail, requestId, cycleSeq, "ops_planned", opsPlan = OperationPlanCodec.encode(plan))
        return applyPropagateAssemble(
            userEmail, requestId, cycleSeq, plan, interpretOutcome, interpretLatencyMs,
            projectNames = snapshot.containers.filter { it.kind == app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind.PROJECT }.map { it.name },
        )
    }

    private suspend fun applyPropagateAssemble(
        userEmail: String,
        requestId: String,
        cycleSeq: Long,
        plan: List<HorizonOperation>,
        interpretOutcome: InterpretOutcome,
        interpretLatencyMs: Long,
        projectNames: List<String>,
    ): HorizonCycleResult {
        val applied = operationApplier.apply(userEmail, cycleSeq, requestId, plan)
        val created = applied.filter { it.applied && it.resultUid != null }
        // `writes_committed` means every operation landed. If one failed the row stays at `ops_planned`,
        // so a retry of this same request re-applies the stored plan (idempotently) instead of losing it.
        if (applied.all { it.applied }) {
            requestLedger.checkpoint(userEmail, requestId, cycleSeq, "writes_committed", phraseUids = created.mapNotNull { it.resultUid })
        }

        val newPhrases = created.map { it.resultUid!! to (it.resultText ?: "") }
        val propagateStartMs = System.currentTimeMillis()
        val propagationOutcome = horizonPropagator.propagate(userEmail, cycleSeq, newPhrases)
        val propagateLatencyMs = System.currentTimeMillis() - propagateStartMs

        val assembleStartMs = System.currentTimeMillis()
        val assembleOutcome = horizonAssembler.assemble(userEmail, cycleSeq)
        val assembleLatencyMs = System.currentTimeMillis() - assembleStartMs

        if (applied.all { it.applied }) requestLedger.checkpoint(userEmail, requestId, cycleSeq, "completed")

        return HorizonCycleResult(
            cycleSeq = cycleSeq,
            interpretOutcome = interpretOutcome,
            mutationOutcomes = applied.map { MutationOutcome.Operation(it) },
            propagationOutcome = propagationOutcome,
            assembleOutcome = assembleOutcome,
            interpretLatencyMs = interpretLatencyMs,
            propagateLatencyMs = propagateLatencyMs,
            assembleLatencyMs = assembleLatencyMs,
            followUp = followUpFor(applied, projectNames),
            projectNames = projectNames,
        )
    }

    private fun followUpFor(applied: List<AppliedOperation>, projectNames: List<String>): CycleFollowUp? {
        applied.firstOrNull { it.applied && it.operation is HoldForChoice }
            ?.let { return CycleFollowUp.AskEmotionChoice(it.operation.quote) }
        applied.firstOrNull { it.applied && (it.operation as? RecordItem)?.askWhichProject == true && it.resultUid != null }
            ?.let { return CycleFollowUp.AskWhichProject(it.resultUid!!, it.resultText ?: it.operation.quote, projectNames) }
        return null
    }

    /** The model couldn't be used: capture what claims the deterministic decomposer finds, exactly as before the interpreter existed. Reported, never hidden. */
    private suspend fun fallbackCaptureFacts(
        userEmail: String,
        requestId: String,
        cycleSeq: Long,
        utterance: String,
        interpretOutcome: InterpretOutcome,
        interpretLatencyMs: Long,
    ): HorizonCycleResult {
        var decomposeFailed = false
        val factCandidates = try {
            engramClient.decompose(utterance, emptyList())
        } catch (e: Exception) {
            logger.warn("runCycle: decompose failed for userEmail=$userEmail: ${e.message}")
            decomposeFailed = true
            emptyList()
        }
        val allUids = if (factCandidates.isEmpty()) {
            emptyList()
        } else {
            try {
                engramClient.ingest(factCandidates, userEmail)
            } catch (e: Exception) {
                logger.warn("runCycle: ingest failed for userEmail=$userEmail: ${e.message}")
                emptyList()
            }
        }
        // ingest() is all-or-nothing per its own contract — if the returned count doesn't match
        // what was asked, there's nothing to safely attribute per candidate, so nothing beyond
        // what's certain (nothing) is treated as written.
        val factUids = if (allUids.size == factCandidates.size) allUids else emptyList()
        requestLedger.checkpoint(userEmail, requestId, cycleSeq, "facts_ingested", phraseUids = factUids)

        val newPhrases = factUids.zip(factCandidates.map { it.content })
        // A candidate that never became a Phrase at all is otherwise invisible: it has no uid to
        // attach a MutationOutcome to, so without this it would silently vanish from mutationOutcomes.
        val lostFactCount = if (decomposeFailed) 1 else (factCandidates.size - factUids.size).coerceAtLeast(0)
        val lostFactOutcomes = List(lostFactCount) { MutationOutcome.Fact(phraseUid = null, applied = false) }
        return stampPropagateAssemble(userEmail, requestId, cycleSeq, interpretOutcome, interpretLatencyMs, factUids, newPhrases, lostFactOutcomes)
    }

    private suspend fun stampPropagateAssemble(
        userEmail: String,
        requestId: String,
        cycleSeq: Long,
        interpretOutcome: InterpretOutcome?,
        interpretLatencyMs: Long,
        factUids: List<String>,
        newPhrases: List<Pair<String, String>>,
        initialMutationOutcomes: List<MutationOutcome> = emptyList(),
    ): HorizonCycleResult {
        val mutationOutcomes = initialMutationOutcomes.toMutableList()
        for (uid in factUids) {
            val applied = horizonGraphStore.stampNewAssertion(userEmail, cycleSeq, uid, status = null)
            mutationOutcomes += MutationOutcome.Fact(uid, applied)
        }
        requestLedger.checkpoint(userEmail, requestId, cycleSeq, "writes_committed", phraseUids = factUids)

        val propagateStartMs = System.currentTimeMillis()
        val propagationOutcome = horizonPropagator.propagate(userEmail, cycleSeq, newPhrases)
        val propagateLatencyMs = System.currentTimeMillis() - propagateStartMs

        val assembleStartMs = System.currentTimeMillis()
        val assembleOutcome = horizonAssembler.assemble(userEmail, cycleSeq)
        val assembleLatencyMs = System.currentTimeMillis() - assembleStartMs

        requestLedger.checkpoint(userEmail, requestId, cycleSeq, "completed")

        return HorizonCycleResult(
            cycleSeq = cycleSeq,
            interpretOutcome = interpretOutcome,
            mutationOutcomes = mutationOutcomes,
            propagationOutcome = propagationOutcome,
            assembleOutcome = assembleOutcome,
            interpretLatencyMs = interpretLatencyMs,
            propagateLatencyMs = propagateLatencyMs,
            assembleLatencyMs = assembleLatencyMs,
        )
    }

    /** The stored plan replayed — never a second model call. Null if the stored plan is missing or unreadable, so the caller proceeds as a fresh cycle. */
    private suspend fun resumeFromOpsPlanned(userEmail: String, requestId: String, existing: ProcessedRequestRecord): HorizonCycleResult? {
        val plan = OperationPlanCodec.decode(existing.opsPlan) ?: run {
            logger.warn("runCycle: ops_planned row for requestId=$requestId has no readable plan — treating as a fresh cycle")
            return null
        }
        return applyPropagateAssemble(
            userEmail, requestId, existing.cycleSeq, plan,
            InterpretOutcome.Operations(plan, emptyList(), 0), 0, projectNames = emptyList(),
        )
    }

    private suspend fun resumeFromFactsIngested(
        userEmail: String,
        requestId: String,
        existing: ProcessedRequestRecord,
    ): HorizonCycleResult {
        val factUids = existing.phraseUids
        val factTexts = factUids.map { horizonGraphStore.phraseText(userEmail, it) ?: "" }
        return stampPropagateAssemble(userEmail, requestId, existing.cycleSeq, null, 0, factUids, factUids.zip(factTexts))
    }

    private suspend fun resumeFromWritesCommitted(
        userEmail: String,
        requestId: String,
        existing: ProcessedRequestRecord,
    ): HorizonCycleResult {
        val uids = existing.phraseUids
        val newPhrases = uids.zip(uids.map { horizonGraphStore.phraseText(userEmail, it) ?: "" })
        val plan = OperationPlanCodec.decode(existing.opsPlan)
        // The plan says what was applied (writes_committed means all of it was); with no plan this was
        // the fallback path, whose only writes are the plain claims.
        val mutationOutcomes = plan?.map { MutationOutcome.Operation(AppliedOperation(it, applied = true, summary = "Applied ${it.verb}")) }
            ?: uids.map { MutationOutcome.Fact(it, applied = true) }

        val propagateStartMs = System.currentTimeMillis()
        val propagationOutcome = horizonPropagator.propagate(userEmail, existing.cycleSeq, newPhrases)
        val propagateLatencyMs = System.currentTimeMillis() - propagateStartMs

        val assembleStartMs = System.currentTimeMillis()
        val assembleOutcome = horizonAssembler.assemble(userEmail, existing.cycleSeq)
        val assembleLatencyMs = System.currentTimeMillis() - assembleStartMs

        requestLedger.checkpoint(userEmail, requestId, existing.cycleSeq, "completed")

        return HorizonCycleResult(
            cycleSeq = existing.cycleSeq,
            interpretOutcome = plan?.let { InterpretOutcome.Operations(it, emptyList(), 0) },
            mutationOutcomes = mutationOutcomes,
            propagationOutcome = propagationOutcome,
            assembleOutcome = assembleOutcome,
            propagateLatencyMs = propagateLatencyMs,
            assembleLatencyMs = assembleLatencyMs,
        )
    }

    /** Duplicate-after-commit: no interpretation, no writes, no propagation re-run — just a fresh read at the already-committed cycle. */
    private suspend fun resultFromCompleted(userEmail: String, existing: ProcessedRequestRecord): HorizonCycleResult {
        val assembleStartMs = System.currentTimeMillis()
        val assembleOutcome = horizonAssembler.assemble(userEmail, existing.cycleSeq)
        val assembleLatencyMs = System.currentTimeMillis() - assembleStartMs
        return HorizonCycleResult(
            cycleSeq = existing.cycleSeq,
            interpretOutcome = null,
            mutationOutcomes = emptyList(),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = assembleOutcome,
            assembleLatencyMs = assembleLatencyMs,
        )
    }
}
