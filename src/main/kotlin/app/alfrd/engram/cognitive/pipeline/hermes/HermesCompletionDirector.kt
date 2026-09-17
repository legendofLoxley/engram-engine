package app.alfrd.engram.cognitive.pipeline.hermes

/**
 * The Director's own decision about one executed [HermesAssignment]: given the assignment's
 * original request context and the real [HermesAssignmentOutcome], exactly one of these is
 * chosen — never inferred later by a transport layer. [deliveryText] is always the complete,
 * final sentence a caller (the WebUI runner adapter) shows verbatim; nothing downstream may
 * wrap, rephrase, or otherwise recompose it.
 */
sealed interface HermesCompletionDecision {
    val deliveryText: String

    /**
     * Hermes's findings are trusted and delivered. For the one assignment kind this slice
     * supports (a single read-only fixture inspection, gated on [HermesAssignmentOutcome.Completed.toolSucceeded]),
     * "accept" means using Hermes's own reported wording untouched — an explicit policy choice,
     * not a fallback: per the governing contract, accepting wording directly needs no extra
     * model call. A future assignment kind could populate [deliveryText] with genuinely revised
     * wording without this type needing to change.
     */
    data class Accepted(override val deliveryText: String) : HermesCompletionDecision

    /**
     * Nothing is delivered as Hermes's own findings — either the assignment failed outright, or
     * it completed but didn't verifiably touch the permitted resource. [deliveryText] is always
     * an honest, Director-composed explanation; it never repeats raw, unvetted Hermes output.
     * [reason] is the internal diagnostic (logged, not shown to the user).
     */
    data class Withheld(override val deliveryText: String, val reason: String) : HermesCompletionDecision
}

/**
 * The Director-side response boundary this correction adds: previously, the WebUI runner
 * adapter (`_deliver_hermes_completion`) rendered whatever text Hermes reported, unconditionally,
 * with no check of [HermesAssignmentOutcome.Completed.toolSucceeded] at all — a self-reported
 * "the tool didn't actually succeed" outcome was delivered exactly like a trustworthy one. This
 * object is the single place that decision is made, so it can be reasoned about, tested, and
 * changed independently of the transport mechanics that eventually render its result.
 *
 * Deliberately a plain, synchronous, side-effect-free function — accepting Hermes's own wording
 * needs no LLM call (see [HermesCompletionDecision.Accepted]'s doc), so there is nothing here to
 * suspend on. [HermesDelegationDispatcher] calls this once execution finishes and stores the
 * result; the runner adapter downstream only ever sees the already-decided [HermesCompletionDecision.deliveryText].
 */
object HermesCompletionDirector {

    fun decide(assignment: HermesAssignment, outcome: HermesAssignmentOutcome): HermesCompletionDecision = when (outcome) {
        is HermesAssignmentOutcome.Completed -> if (outcome.toolSucceeded) {
            HermesCompletionDecision.Accepted(
                deliveryText = "Hermes finished checking that — it reported: ${outcome.findingsText}",
            )
        } else {
            HermesCompletionDecision.Withheld(
                deliveryText = "Hermes responded, but I can't confirm the result actually came from reading " +
                    "the right file, so I'm not passing along its specific content. You may want to ask again.",
                reason = "toolSucceeded=false toolName=${outcome.toolName} toolTargetPath=${outcome.toolTargetPath} " +
                    "assignmentId=${assignment.assignmentId}",
            )
        }
        is HermesAssignmentOutcome.Failed -> HermesCompletionDecision.Withheld(
            deliveryText = "Hermes wasn't able to complete that: ${outcome.reason}",
            reason = "execution failed: ${outcome.reason} assignmentId=${assignment.assignmentId}",
        )
    }
}
