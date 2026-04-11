package app.alfrd.engram.cognitive.pipeline

/** Implemented by every stage in the CognitivePipeline. */
interface CognitiveStage {
    /** Primary processing — mutates [ctx] in place. */
    suspend fun evaluate(ctx: CognitiveContext)

    /** Called once when the pipeline starts up. Default is a no-op. */
    suspend fun onInit() {}

    /** Called after Expression completes each cycle. Default is a no-op. */
    suspend fun onCycleEnd(ctx: CognitiveContext) {}
}
