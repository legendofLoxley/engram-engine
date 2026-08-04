package app.alfrd.engram.cognitive.pipeline

/** Stub — memory queries are not yet available. Currently unreachable via [Router] (META routes to [QuestionBranch]). */
class MetaBranch : Branch {
    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            responseStrategy = ResponseStrategy.SIMPLE,
            retrieval = RetrievalIntent.None,
            directive = "The user asked about your capabilities. Memory-query capabilities aren't available yet — say so honestly and briefly.",
        )
    }
}
