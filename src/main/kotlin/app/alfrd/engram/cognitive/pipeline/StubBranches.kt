package app.alfrd.engram.cognitive.pipeline

/** Stub — memory queries are not yet available. */
class MetaBranch : Branch {
    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content = "Memory queries aren't available yet.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}
