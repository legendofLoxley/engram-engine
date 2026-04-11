package app.alfrd.engram.cognitive.pipeline

/** Branch interface — each branch receives the context and populates [CognitiveContext.branchResult]. */
interface Branch {
    suspend fun execute(ctx: CognitiveContext)
}
