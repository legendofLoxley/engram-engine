package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.AssertionStatus
import app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPropagator
import app.alfrd.engram.cognitive.pipeline.horizon.ProcessedRequestRecord
import app.alfrd.engram.cognitive.pipeline.horizon.PropagationOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.RequestLedger
import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import org.slf4j.LoggerFactory

/**
 * One graph write the coordinator confirmed or attempted this cycle, per operation — so a partial
 * failure (an intention rejected while a fact succeeds, say) can be reflected precisely in the
 * response's directive rather than collapsed into one blanket "something failed" flag.
 */
sealed interface MutationOutcome {
    data class Fact(val phraseUid: String, val applied: Boolean) : MutationOutcome
    data class Intention(val phraseUid: String?, val applied: Boolean, val quote: String) : MutationOutcome
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
 * Orchestrates one conversational cycle: allocate → interpret → write (ordinary facts, via
 * [EngramClient.ingest] unmodified, plus the interpreter's confirmed intention if any) → stamp
 * Horizon identity → propagate → assemble.
 *
 * The caller must already hold [app.alfrd.engram.cognitive.pipeline.horizon.PerUserCycleLock] for
 * [userEmail] before calling [runCycle] — this class does not acquire it itself, because it must
 * also cover [Script.run]'s own writes, which happen *before* this class is ever invoked (see
 * [CognitivePipeline.processInternal] for exactly what the lock wraps and why).
 *
 * Checkpointed via [RequestLedger] so a crash mid-cycle is resumable without duplicating work —
 * see that interface's doc for the full state machine. Interpretation never runs twice for the
 * same `requestId`: by the time any write has happened (the `facts_ingested` checkpoint),
 * interpretation has already fully completed, and its result is durably captured by *which
 * phrases exist and which one, if any, is the intention* (via [ProcessedRequestRecord.intentionPhraseUid])
 * — never re-derived by calling the interpreter a second time.
 */
open class HorizonCycleCoordinator(
    private val cycleSequencer: CycleSequencer,
    private val requestLedger: RequestLedger,
    private val interpreter: Interpreter,
    private val engramClient: EngramClient,
    private val horizonGraphStore: HorizonGraphStore,
    private val horizonPropagator: HorizonPropagator,
    private val horizonAssembler: HorizonAssembler,
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

    open suspend fun runCycle(userEmail: String, requestId: String, utterance: String): HorizonCycleResult {
        requestLedger.find(userEmail, requestId)?.let { existing ->
            when (existing.checkpoint) {
                "completed" -> return resultFromCompleted(userEmail, existing)
                "writes_committed" -> return resumeFromWritesCommitted(userEmail, requestId, existing)
                "facts_ingested" -> return resumeFromFactsIngested(userEmail, requestId, existing)
                else -> { /* cycle_allocated-only, failed, or unrecognized — proceed fresh below */ }
            }
        }

        val cycleSeq = cycleSequencer.allocateCycle(userEmail) ?: return HorizonCycleResult.allocationFailed()
        requestLedger.checkpoint(userEmail, requestId, cycleSeq, "cycle_allocated")

        testInterpretationBarrier?.invoke()
        val interpretStartMs = System.currentTimeMillis()
        val interpretOutcome = interpreter.interpret(utterance)
        val interpretLatencyMs = System.currentTimeMillis() - interpretStartMs

        val factCandidates = try {
            engramClient.decompose(utterance, emptyList())
        } catch (e: Exception) {
            logger.warn("runCycle: decompose failed for userEmail=$userEmail: ${e.message}")
            emptyList()
        }
        val intentionQuote = (interpretOutcome as? InterpretOutcome.ProposedAssertion)?.quote
        val allCandidates = if (intentionQuote != null) {
            // A distinct source tag from ordinary facts — never routed through the same
            // classification as onboarding-scaffold content; Horizon status (stamped next), not
            // PhraseCategory, is what actually distinguishes an intention downstream.
            factCandidates + PhraseCandidate(content = intentionQuote, source = "interpreter", category = PhraseCategory.CONTEXT)
        } else {
            factCandidates
        }
        val allUids = if (allCandidates.isEmpty()) {
            emptyList()
        } else {
            try {
                engramClient.ingest(allCandidates, userEmail)
            } catch (e: Exception) {
                logger.warn("runCycle: ingest failed for userEmail=$userEmail: ${e.message}")
                emptyList()
            }
        }
        // ingest() is all-or-nothing per its own contract — if the returned count doesn't match
        // what was asked, there's nothing to safely attribute per candidate, so nothing beyond
        // what's certain (nothing) is treated as written; never guess which uid is the intention.
        val factUids: List<String>
        val intentionUid: String?
        when {
            allUids.isEmpty() -> {
                factUids = emptyList()
                intentionUid = null
            }
            allUids.size == allCandidates.size -> {
                if (intentionQuote != null) {
                    factUids = allUids.dropLast(1)
                    intentionUid = allUids.last()
                } else {
                    factUids = allUids
                    intentionUid = null
                }
            }
            else -> {
                factUids = allUids
                intentionUid = null
            }
        }
        requestLedger.checkpoint(
            userEmail, requestId, cycleSeq, "facts_ingested",
            phraseUids = allUids, intentionPhraseUid = intentionUid,
        )

        val factTexts = factCandidates.map { it.content }
        val newPhrases = factUids.zip(factTexts) +
            listOfNotNull(if (intentionUid != null && intentionQuote != null) intentionUid to intentionQuote else null)

        return stampPropagateAssemble(
            userEmail, requestId, cycleSeq, interpretOutcome, interpretLatencyMs,
            factUids, intentionUid, intentionQuote, newPhrases,
        )
    }

    private suspend fun stampPropagateAssemble(
        userEmail: String,
        requestId: String,
        cycleSeq: Long,
        interpretOutcome: InterpretOutcome?,
        interpretLatencyMs: Long,
        factUids: List<String>,
        intentionUid: String?,
        intentionQuote: String?,
        newPhrases: List<Pair<String, String>>,
    ): HorizonCycleResult {
        val mutationOutcomes = mutableListOf<MutationOutcome>()
        for (uid in factUids) {
            val applied = horizonGraphStore.stampNewAssertion(userEmail, cycleSeq, uid, status = null)
            mutationOutcomes += MutationOutcome.Fact(uid, applied)
        }
        if (intentionQuote != null) {
            val applied = intentionUid?.let {
                horizonGraphStore.stampNewAssertion(userEmail, cycleSeq, it, status = AssertionStatus.OPEN)
            } ?: false
            mutationOutcomes += MutationOutcome.Intention(intentionUid, applied, intentionQuote)
        }
        requestLedger.checkpoint(
            userEmail, requestId, cycleSeq, "writes_committed",
            phraseUids = factUids + listOfNotNull(intentionUid), intentionPhraseUid = intentionUid,
        )

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

    /** (factUids, intentionUid, intentionText) recovered from a stored checkpoint's uids — never from re-running interpretation. */
    private suspend fun reconstructWrittenPhrases(
        userEmail: String,
        existing: ProcessedRequestRecord,
    ): Triple<List<String>, String?, String?> {
        val intentionUid = existing.intentionPhraseUid
        val factUids = existing.phraseUids.filter { it != intentionUid }
        val intentionText = intentionUid?.let { horizonGraphStore.phraseText(userEmail, it) }
        return Triple(factUids, intentionUid, intentionText)
    }

    private suspend fun resumeFromFactsIngested(
        userEmail: String,
        requestId: String,
        existing: ProcessedRequestRecord,
    ): HorizonCycleResult {
        val (factUids, intentionUid, intentionText) = reconstructWrittenPhrases(userEmail, existing)
        val interpretOutcome = intentionText?.let { InterpretOutcome.ProposedAssertion(it, 0) }
        val factTexts = factUids.map { horizonGraphStore.phraseText(userEmail, it) ?: "" }
        val newPhrases = factUids.zip(factTexts) +
            listOfNotNull(intentionUid?.let { it to (intentionText ?: "") })
        return stampPropagateAssemble(
            userEmail, requestId, existing.cycleSeq, interpretOutcome, 0,
            factUids, intentionUid, intentionText, newPhrases,
        )
    }

    private suspend fun resumeFromWritesCommitted(
        userEmail: String,
        requestId: String,
        existing: ProcessedRequestRecord,
    ): HorizonCycleResult {
        val (factUids, intentionUid, intentionText) = reconstructWrittenPhrases(userEmail, existing)
        val factTexts = factUids.map { horizonGraphStore.phraseText(userEmail, it) ?: "" }
        val newPhrases = factUids.zip(factTexts) +
            listOfNotNull(intentionUid?.let { it to (intentionText ?: "") })
        val mutationOutcomes = factUids.map { MutationOutcome.Fact(it, applied = true) } +
            listOfNotNull(intentionUid?.let { MutationOutcome.Intention(it, applied = true, quote = intentionText ?: "") })

        val propagateStartMs = System.currentTimeMillis()
        val propagationOutcome = horizonPropagator.propagate(userEmail, existing.cycleSeq, newPhrases)
        val propagateLatencyMs = System.currentTimeMillis() - propagateStartMs

        val assembleStartMs = System.currentTimeMillis()
        val assembleOutcome = horizonAssembler.assemble(userEmail, existing.cycleSeq)
        val assembleLatencyMs = System.currentTimeMillis() - assembleStartMs

        requestLedger.checkpoint(userEmail, requestId, existing.cycleSeq, "completed")

        return HorizonCycleResult(
            cycleSeq = existing.cycleSeq,
            interpretOutcome = intentionText?.let { InterpretOutcome.ProposedAssertion(it, 0) },
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
