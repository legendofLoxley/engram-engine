package app.alfrd.engram.cognitive.pipeline

/** Routes ambiguous or under-confident utterances back to the user for clarification. */
class ClarificationBranch : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            responseStrategy = ResponseStrategy.SOCIAL,
            retrieval = RetrievalIntent.None,
            directive = "The user's intent is unclear. Ask a brief, warm clarifying question about what they mean.",
        )
    }
}
