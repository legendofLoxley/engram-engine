package app.alfrd.engram.cognitive.pipeline

/** Routes ambiguous or under-confident utterances back to the user for clarification. */
class ClarificationBranch : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content = "Could you say more about what you mean?",
            responseStrategy = ResponseStrategy.SOCIAL,
        )
    }
}
