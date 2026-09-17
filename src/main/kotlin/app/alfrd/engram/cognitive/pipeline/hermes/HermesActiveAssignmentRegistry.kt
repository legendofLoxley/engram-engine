package app.alfrd.engram.cognitive.pipeline.hermes

import java.util.concurrent.ConcurrentHashMap

/** What a cancellation request actually established — deliberately not a boolean: "requested"
 *  means the assignment was genuinely still open to it, "not active" covers both "no such
 *  assignment" and "it already finished" identically, matching this codebase's own convention
 *  (see [HermesAssignmentCompletionStore]) of never letting a caller distinguish "never existed"
 *  from "not yours"/"too late" by probing. Confirmed termination is never claimed here — only
 *  a later read of [HermesAssignmentCompletionStore] can say what actually happened. */
sealed interface HermesCancellationRequestOutcome {
    object Requested : HermesCancellationRequestOutcome
    object NotActive : HermesCancellationRequestOutcome
}

/**
 * Shared, cross-session registry of currently-dispatched-but-not-yet-finished Hermes
 * assignments, keyed by assignment id — the mirror-image lifecycle phase of
 * [HermesAssignmentCompletionStore] (which only ever sees an assignment *after* it finishes). A
 * single instance spans every session's own [HermesDelegationDispatcher], since a cancellation
 * request (which only knows an assignment id, from `PipelineTrace.hermesDelegation`) has no way
 * to know which session's pipeline instance originally dispatched it.
 *
 * Deliberately in-memory only, same lifetime convention as [HermesAssignmentCompletionStore]: an
 * assignment dispatched before a backend restart is not cancellable through this registry after
 * one (there is nothing to find), which is consistent with this whole delegation feature's
 * already-accepted restart limitations — not a new gap introduced here.
 */
class HermesActiveAssignmentRegistry {
    private data class Entry(val userEmail: String, val handle: HermesCancelHandle)

    private val active = ConcurrentHashMap<String, Entry>()

    fun register(assignmentId: String, userEmail: String, handle: HermesCancelHandle) {
        active[assignmentId] = Entry(userEmail, handle)
    }

    fun unregister(assignmentId: String) {
        active.remove(assignmentId)
    }

    /** [userEmail] must match the assignment's own owner — a mismatch is reported identically to
     *  an unknown assignment id, the same "cannot distinguish by probing" convention used
     *  throughout this feature. */
    fun requestCancellation(assignmentId: String, userEmail: String): HermesCancellationRequestOutcome {
        val entry = active[assignmentId] ?: return HermesCancellationRequestOutcome.NotActive
        if (entry.userEmail != userEmail) return HermesCancellationRequestOutcome.NotActive
        return if (entry.handle.requestCancel()) {
            HermesCancellationRequestOutcome.Requested
        } else {
            HermesCancellationRequestOutcome.NotActive
        }
    }
}
