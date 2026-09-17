package app.alfrd.engram.cognitive.pipeline.hermes

import java.util.concurrent.ConcurrentHashMap

/**
 * One recorded completion. [outcome] and [decision] are two distinct, never-conflated facts —
 * matching this codebase's own convention ([app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestOutcome]'s
 * class doc): [outcome] is what Hermes actually did, [decision] is what the Director chose to do
 * about it (see [HermesCompletionDirector]). A caller that only wants to know what to *show* the
 * user needs [decision]`.deliveryText` alone — it is never derived by re-inspecting [outcome].
 * See this store's own doc for why [graphIngestOutcome] is carried alongside rather than derived.
 */
data class HermesAssignmentCompletion(
    val assignmentId: String,
    val userEmail: String,
    val outcome: HermesAssignmentOutcome,
    val decision: HermesCompletionDecision,
    val graphIngestOutcome: String,
    val recordedAt: Long,
)

/**
 * The explicit, independent delivery channel for a Hermes assignment's completion —
 * deliberately separate from [app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService]'s
 * graph commit. Per the design contract: "a committed result is not proof that the user
 * received it." A caller (the WebUI runner adapter) polls [get] for a specific
 * [HermesAssignment.assignmentId] to learn whether — and how — an outstanding
 * assignment finished, independently of whether/when that same result also became
 * eligible for [app.alfrd.engram.cognitive.pipeline.horizon.SurfacingReason.RecentActorEvidence].
 *
 * In-memory only, scoped to this process's lifetime — an outstanding assignment issued
 * before a backend restart has no completion recorded here even if Hermes itself later
 * finishes and commits to the graph (still recoverable through the existing
 * RecentActorEvidence Horizon path on a later turn, just not through this fast channel).
 * A durable queue is explicitly out of scope for this bounded increment.
 *
 * [get] requires the caller to name the exact [userEmail] the assignment was issued
 * for — an unknown or mismatched email returns null exactly like an unknown
 * [assignmentId], so a caller cannot enumerate or read another identity's completion
 * even by guessing/reusing an id (in practice not guessable either: assignmentId is a
 * random UUID).
 */
class HermesAssignmentCompletionStore(
    private val maxAgeMs: Long = 30 * 60 * 1000L,
) {
    private val completions = ConcurrentHashMap<String, HermesAssignmentCompletion>()

    fun record(
        assignmentId: String,
        userEmail: String,
        outcome: HermesAssignmentOutcome,
        decision: HermesCompletionDecision,
        graphIngestOutcome: String,
    ) {
        prune()
        completions[assignmentId] = HermesAssignmentCompletion(
            assignmentId = assignmentId,
            userEmail = userEmail,
            outcome = outcome,
            decision = decision,
            graphIngestOutcome = graphIngestOutcome,
            recordedAt = System.currentTimeMillis(),
        )
    }

    fun get(assignmentId: String, userEmail: String): HermesAssignmentCompletion? =
        completions[assignmentId]?.takeIf { it.userEmail == userEmail }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        completions.entries.removeIf { it.value.recordedAt < cutoff }
    }
}
