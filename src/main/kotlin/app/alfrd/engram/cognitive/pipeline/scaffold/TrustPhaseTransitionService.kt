package app.alfrd.engram.cognitive.pipeline.scaffold

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldPhaseTransition
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
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
 * Applies dormancy-based regression to the relationship-wide scaffold trust phase
 * ([ScaffoldState.trustPhase]) per the Onboarding Scaffold Specification §4's narrow regression
 * rule. This is a distinct, older mechanism from the per-topic confidence model in
 * [app.alfrd.engram.cognitive.pipeline.confidence.TopicConfidenceService] — `ctx.trustPhase`
 * still feeds [app.alfrd.engram.cognitive.pipeline.selection.SelectionScorer] for phrase-pool
 * phase-appropriateness scoring, which is out of scope for the per-topic redesign (flagged as
 * not-yet-topic-aware by the Response Architecture design doc itself).
 *
 * The session/category-counting *advancement* rule that used to live here has been removed —
 * it was never invoked on the live turn path, and is superseded entirely by per-topic
 * confidence, driven by demonstrated competence and explicit user feedback rather than counting.
 *
 * **Apply** — writes the updated [ScaffoldState.trustPhase] and appends a
 *   [ScaffoldPhaseTransition] record to [ScaffoldState.phaseTransitions].
 *   Idempotent: re-applying a decision whose `to` phase already matches the
 *   stored phase is a no-op (prevents duplicate history entries on concurrent writes).
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
     * Evaluates dormancy-based regression.
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

    internal fun phaseIntToString(phase: Int): String = when (phase) {
        1 -> "ORIENTATION"
        2 -> "WORKING_RHYTHM"
        3 -> "CONTEXT"
        4 -> "UNDERSTANDING"
        else -> "UNKNOWN"
    }
}
