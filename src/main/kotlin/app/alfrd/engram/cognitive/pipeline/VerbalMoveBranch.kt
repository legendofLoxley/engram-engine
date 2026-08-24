package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.TurnShape

/**
 * Branch that resolves low-pressure utterance shapes (DISCLOSURE, FYI, CONTINUATION) to a
 * brief verbal-acknowledgment conditioner — no LLM call and no memory query happen here.
 *
 * No retrieval: the posture read for this turn (including any emotional weight in a
 * Disclosure-shaped utterance) reaches the actor as the pipeline-wide `attunement` conditioner
 * ([app.alfrd.engram.cognitive.pipeline.posture.attunementDirective], computed once in
 * [CognitivePipeline] from [CognitiveContext.turnShape] and [CognitiveContext.postureSignals])
 * rather than through a canned phrase this branch would otherwise pick.
 */
class VerbalMoveBranch(private val turnShape: TurnShape) : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            responseStrategy = ResponseStrategy.SOCIAL,
            retrieval = RetrievalIntent.None,
            directive = "The user made a brief, low-pressure remark (not a question or request). Give a brief, " +
                "natural acknowledgment — a few words, nothing more. Do not ask a question back unless truly needed.",
        )
    }
}
