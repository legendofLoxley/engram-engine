package app.alfrd.engram.cognitive.pipeline

/**
 * Task branch — the director for task requests. Task execution isn't implemented yet, so
 * this only produces a conditioner instructing the actor to acknowledge honestly.
 */
class TaskBranch : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            responseStrategy = ResponseStrategy.SIMPLE,
            retrieval = RetrievalIntent.None,
            directive = "The user made a task request. Task execution isn't available yet — acknowledge that " +
                "you've noted it, briefly and warmly, but do not claim to have performed or scheduled it.",
        )
    }
}
