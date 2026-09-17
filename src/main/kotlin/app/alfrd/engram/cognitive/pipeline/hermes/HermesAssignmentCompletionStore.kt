package app.alfrd.engram.cognitive.pipeline.hermes

import java.util.concurrent.ConcurrentHashMap

/** One recorded completion — see [HermesAssignmentCompletionStore]'s doc for why [graphIngestOutcome] is carried alongside [outcome] rather than derived from it. */
data class HermesAssignmentCompletion(
    val assignmentId: String,
    val userEmail: String,
    val outcome: HermesAssignmentOutcome,
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

    fun record(assignmentId: String, userEmail: String, outcome: HermesAssignmentOutcome, graphIngestOutcome: String) {
        prune()
        completions[assignmentId] = HermesAssignmentCompletion(
            assignmentId = assignmentId,
            userEmail = userEmail,
            outcome = outcome,
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
