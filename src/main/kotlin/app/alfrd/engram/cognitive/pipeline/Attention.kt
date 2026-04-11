package app.alfrd.engram.cognitive.pipeline

/**
 * Attention stage — PoC stub.
 * Always passes the utterance through for processing. Target < 5 ms.
 */
class Attention : CognitiveStage {
    override suspend fun evaluate(ctx: CognitiveContext) {
        ctx.attentionAction = AttentionAction.PROCESS
        ctx.attentionPriority = AttentionPriority.NORMAL
    }
}
