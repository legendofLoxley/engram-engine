package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.ResponseCategory

/**
 * SocialBranch — the director for greetings, thanks, farewells, and modality checks.
 *
 * Produces conditioners only. The greeting case is gated on [CognitiveContext.turnIndex]:
 * a GREETING-category retrieval (and the "greet them" directive) is only ever produced on
 * turn 1 of a session — every subsequent unmatched SOCIAL utterance is treated as smalltalk,
 * never a repeat greeting.
 */
class SocialBranch : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        val lower = ctx.utterance.trim().lowercase()

        // expressionPhase matches the pre-split SocialBranch exactly: SIGN_OFF phrases are
        // seeded under SYNTHESIS, RECEIPT/GREETING under FIRST_RESPONSE.
        val (retrieval, directive) = when {
            isGoodbye(lower) -> phrasePool(ResponseCategory.SIGN_OFF, ExpressionPhase.SYNTHESIS) to
                "The user is signing off. Say a brief, warm goodbye."

            isThanks(lower) -> phrasePool(ResponseCategory.RECEIPT, ExpressionPhase.FIRST_RESPONSE) to
                "The user thanked you. Acknowledge briefly and warmly — no need to over-explain."

            isModalityCheck(lower) -> RetrievalIntent.None to
                "The user is checking whether you're there or paying attention. Confirm you're present " +
                "and ready, in a way appropriate to how you're communicating with them."

            ctx.turnIndex <= 1 -> phrasePool(ResponseCategory.GREETING, ExpressionPhase.FIRST_RESPONSE) to
                "This is the first turn of the session. Greet the user warmly, appropriate to the time of day."

            else -> RetrievalIntent.None to
                "The user made small talk (\"${ctx.utterance.trim()}\"). This is NOT the first turn of the " +
                "session — you already greeted them earlier. Do not greet them again. Respond naturally and briefly."
        }

        ctx.branchResult = BranchResult(
            responseStrategy = ResponseStrategy.SOCIAL,
            retrieval = retrieval,
            directive = directive,
        )
    }

    private fun phrasePool(category: ResponseCategory, expressionPhase: ExpressionPhase): RetrievalIntent =
        RetrievalIntent.PhrasePool(branch = BranchType.SOCIAL, category = category, expressionPhase = expressionPhase)

    private fun isGoodbye(lower: String) =
        listOf("bye", "goodbye", "see you", "until next time", "take care").any { lower.contains(it) }

    private fun isThanks(lower: String) =
        listOf("thanks", "thank you", "cheers").any { lower.contains(it) }

    private fun isModalityCheck(lower: String) =
        listOf(
            "can you hear me", "are you listening", "are you there",
            "is this working", "can you see me", "hello?",
            "is anyone there", "can you understand me",
        ).any { lower == it || lower.contains(it) }
}
