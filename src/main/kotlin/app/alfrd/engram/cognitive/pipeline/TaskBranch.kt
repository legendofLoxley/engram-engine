package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService

/**
 * Task branch — graceful decline stub.
 *
 * Memory ingestion is handled by the universal capture step in [CognitivePipeline.processInternal],
 * not here — every PROCESS turn is captured exactly once regardless of branch.
 */
class TaskBranch(
    @Suppress("UNUSED_PARAMETER") private val engramClient: EngramClient?,
    @Suppress("UNUSED_PARAMETER") private val memoryWriteService: MemoryWriteService? = null,
) : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content          = "I've noted that — task execution is coming soon.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}
