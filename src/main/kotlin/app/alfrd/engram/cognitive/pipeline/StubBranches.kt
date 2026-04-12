package app.alfrd.engram.cognitive.pipeline

/** Stub — correction handling is not yet available. */
class CorrectionBranch : Branch {
    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content = "Corrections aren't available yet.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}

/** Stub — memory queries are not yet available. */
class MetaBranch : Branch {
    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content = "Memory queries aren't available yet.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}
