package app.alfrd.engram.cognitive.pipeline.scaffold

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldPhaseTransition
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.model.OutcomeEdge
import java.time.Clock
import java.util.concurrent.TimeUnit

// ── Decision types ────────────────────────────────────────────────────────────

sealed class TransitionDecision {
    /** No transition criteria are met. */
    object NoChange : TransitionDecision()

    /** A transition should be (or has been) applied. */
    data class Transition(
        val from: Int,
        val to: Int,
        val evidence: String,
        val timestamp: Long,
    ) : TransitionDecision()
}

// ── Service ───────────────────────────────────────────────────────────────────

/**
 * Evaluates and applies trust-phase transitions per the Onboarding Scaffold Specification §4.
 *
 * **Evaluate** — pure logic; reads [ScaffoldState] and returns a [TransitionDecision].
 *   Does NOT write to storage; callers decide whether to apply.
 *
 * **Apply** — writes the updated [ScaffoldState.trustPhase] and appends a
 *   [ScaffoldPhaseTransition] record to [ScaffoldState.phaseTransitions].
 *   Idempotent: re-applying a decision whose `to` phase already matches the
 *   stored phase is a no-op (prevents duplicate history entries on concurrent writes).
 *
 * ### Advancement rules
 *
 * | From             | To               | Criteria                                                              |
 * |------------------|------------------|-----------------------------------------------------------------------|
 * | ORIENTATION (1)  | WORKING_RHYTHM (2)| ≥3 answered categories **and** IDENTITY or EXPERTISE answered         |
 * | WORKING_RHYTHM (2)| CONTEXT (3)      | ≥5 answered categories **and** sessionCount ≥ 3                       |
 * | CONTEXT (3)      | UNDERSTANDING (4) | ≥6 answered categories **and** sessionCount ≥ 10 **and** CORRECTED edge exists |
 *
 * ### Dormancy regression (§4 — narrow regression rules)
 * If `lastInteractionAt` is more than 90 days ago, the phase regresses by one level.
 * Regression is capped at WORKING_RHYTHM (2) — a user never falls back to ORIENTATION
 * from dormancy alone since scaffold data remains valid.
 */
class TrustPhaseTransitionService(
    private val engramClient: EngramClient,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Evaluates advancement criteria. Pass [outcomeEdges] if the OUTCOME edge writer is
     * deployed; pass an empty list (default) to gracefully degrade — UNDERSTANDING
     * advancement simply never fires without a CORRECTED edge.
     */
    fun evaluate(
        state: ScaffoldState,
        outcomeEdges: List<OutcomeEdge> = emptyList(),
    ): TransitionDecision {
        val nextPhase = computeNextPhase(state, outcomeEdges) ?: return TransitionDecision.NoChange
        return TransitionDecision.Transition(
            from      = state.trustPhase,
            to        = nextPhase,
            evidence  = buildEvidence(state, nextPhase),
            timestamp = clock.millis(),
        )
    }

    /**
     * Evaluates dormancy-based regression. Separate from [evaluate] to keep the
     * advancement and regression logic branches cleanly separated.
     *
     * Returns [TransitionDecision.NoChange] when:
     *   - [ScaffoldState.lastInteractionAt] is null (never seen before)
     *   - Fewer than 90 days since last interaction
     *   - Phase is already WORKING_RHYTHM or ORIENTATION (cap)
     */
    fun evaluateDormancyRegression(state: ScaffoldState): TransitionDecision {
        val lastInteraction = state.lastInteractionAt ?: return TransitionDecision.NoChange
        val daysSince = TimeUnit.MILLISECONDS.toDays(clock.millis() - lastInteraction)
        if (daysSince <= 90) return TransitionDecision.NoChange

        // Cap: never regress below WORKING_RHYTHM (2)
        if (state.trustPhase <= 2) return TransitionDecision.NoChange

        val nextPhase = state.trustPhase - 1
        return TransitionDecision.Transition(
            from      = state.trustPhase,
            to        = nextPhase,
            evidence  = "Dormancy regression: $daysSince days since last interaction.",
            timestamp = clock.millis(),
        )
    }

    /**
     * Writes the transition to storage. Reads the current state fresh before writing
     * to handle concurrent writes — if two coroutines race and one already applied the
     * transition, the second is a no-op rather than a duplicate entry.
     */
    suspend fun apply(userId: String, decision: TransitionDecision.Transition) {
        val current = engramClient.getScaffoldState(userId)
        // Idempotency: if the phase is already at the target, nothing to do
        if (current.trustPhase == decision.to) return

        val record = ScaffoldPhaseTransition(
            from      = phaseIntToString(decision.from),
            to        = phaseIntToString(decision.to),
            timestamp = decision.timestamp,
            evidence  = decision.evidence,
        )
        engramClient.updateScaffoldState(
            userId,
            current.copy(
                trustPhase       = decision.to,
                phaseTransitions = current.phaseTransitions + record,
            ),
        )
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun computeNextPhase(
        state: ScaffoldState,
        outcomeEdges: List<OutcomeEdge>,
    ): Int? = when (state.trustPhase) {
        1 -> { // ORIENTATION → WORKING_RHYTHM
            val hasFundamental = PhraseCategory.IDENTITY in state.answeredCategories ||
                PhraseCategory.EXPERTISE in state.answeredCategories
            if (state.answeredCategories.size >= 3 && hasFundamental) 2 else null
        }
        2 -> { // WORKING_RHYTHM → CONTEXT
            if (state.answeredCategories.size >= 5 && state.sessionCount >= 3) 3 else null
        }
        3 -> { // CONTEXT → UNDERSTANDING
            val hasCorrected = outcomeEdges.any { it.signal == "CORRECTED" }
            if (state.answeredCategories.size >= 6 &&
                state.sessionCount >= 10 &&
                hasCorrected
            ) 4 else null
        }
        else -> null // UNDERSTANDING (4) has no further advancement
    }

    private fun buildEvidence(state: ScaffoldState, nextPhase: Int): String = when (nextPhase) {
        2 -> "Answered ${state.answeredCategories.size} scaffold categories including foundational identity."
        3 -> "5+ categories answered across ${state.sessionCount} sessions — sustained engagement."
        4 -> "Full scaffold + ${state.sessionCount} sessions + user has corrected alfrd — collaborative refinement relationship."
        else -> "Phase advanced from ${state.trustPhase} to $nextPhase."
    }

    internal fun phaseIntToString(phase: Int): String = when (phase) {
        1 -> "ORIENTATION"
        2 -> "WORKING_RHYTHM"
        3 -> "CONTEXT"
        4 -> "UNDERSTANDING"
        else -> "UNKNOWN"
    }
}
