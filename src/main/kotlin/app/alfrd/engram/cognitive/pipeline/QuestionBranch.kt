package app.alfrd.engram.cognitive.pipeline

/**
 * Question branch — the director for graph-augmented answers.
 *
 * No dependencies: it never queries memory (recall comes from the Context Horizon, which the
 * message's own words have already shaped by the time the reply is composed) and never calls the
 * LLM directly (that's [Actor]'s job). It only classifies the turn and says how to answer.
 */
class QuestionBranch : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            responseStrategy = ResponseStrategy.SIMPLE,
            retrieval = RetrievalIntent.None,
            directive = "The user asked a question. If it is about them, their plans or something they told you, " +
                "answer from the contextual awareness below and treat lower-confidence items as tentative; if what " +
                "they are asking about is not there, say plainly that you don't have it noted rather than guessing. " +
                "For general questions, answer helpfully from what you know. Be concise and warm — 1 to 3 sentences " +
                "unless more depth is clearly needed.",
        )
    }
}
