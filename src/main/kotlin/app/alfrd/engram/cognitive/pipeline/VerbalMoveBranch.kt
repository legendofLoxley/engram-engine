package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import app.alfrd.engram.cognitive.pipeline.posture.computePostureSignals
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.PostureMoveType

/**
 * Branch that resolves low-pressure utterance shapes (DISCLOSURE, FYI, CONTINUATION) to a
 * brief verbal-acknowledgment conditioner — no LLM call and no memory query happen here.
 *
 * Move selection (unchanged from before the director/actor split):
 *   - DISCLOSURE with elevated surface energy → HOLD
 *   - All other routed shapes              → RECEIPT
 *
 * The computed [PostureMoveType] becomes a [RetrievalIntent.PhrasePool] query for [Script] to
 * fetch a style-reference phrase from the FIRST_RESPONSE pool; the [Actor] composes the final
 * acknowledgment.
 */
class VerbalMoveBranch(private val turnShape: TurnShape) : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        val moveType = moveTypeFor(ctx)
        ctx.branchResult = BranchResult(
            responseStrategy = ResponseStrategy.SOCIAL,
            retrieval = RetrievalIntent.PhrasePool(moveType = moveType, expressionPhase = ExpressionPhase.FIRST_RESPONSE),
            directive = "The user made a brief, low-pressure remark (not a question or request). Give a brief, " +
                "natural acknowledgment — a few words, nothing more. Do not ask a question back unless truly needed. " +
                trustPhaseCalibration(ctx.trustPhase),
        )
    }

    private fun moveTypeFor(ctx: CognitiveContext): PostureMoveType {
        if (turnShape == TurnShape.Disclosure) {
            val signals = computePostureSignals(ctx)
            if (signals.surfaceEnergy > 0.3) return PostureMoveType.HOLD
        }
        return PostureMoveType.RECEIPT
    }
}
