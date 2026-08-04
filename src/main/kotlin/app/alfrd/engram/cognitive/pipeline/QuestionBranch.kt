package app.alfrd.engram.cognitive.pipeline

/**
 * Question branch — the director for graph-augmented answers.
 *
 * No dependencies: it never queries memory directly (that's [Script]'s job) and never calls
 * the LLM directly (that's [Actor]'s job). It only classifies the turn and describes what
 * grounding material the script stage should fetch.
 */
class QuestionBranch : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            responseStrategy = ResponseStrategy.SIMPLE,
            retrieval = RetrievalIntent.MemoryQuery(hint = ctx.memoryQueryHint ?: ctx.utterance),
            directive = "The user asked a question. If context about them is provided below, answer using it " +
                "and treat lower-confidence items as tentative. If no context is provided, answer generally and " +
                "helpfully from what you know. Be concise and warm — 1 to 3 sentences unless more depth is clearly needed.",
        )
    }
}
