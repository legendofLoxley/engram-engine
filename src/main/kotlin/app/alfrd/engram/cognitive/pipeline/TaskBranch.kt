package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory

/**
 * Task branch — graceful decline with optional memory capture.
 *
 * The user's intent is ingested as a CONTEXT phrase so it's remembered even though
 * task execution is not yet implemented. If [engramClient] is unavailable the response
 * is still returned — the memory write is best-effort.
 */
class TaskBranch(
    private val engramClient: EngramClient?,
) : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        try {
            engramClient?.ingest(
                listOf(
                    PhraseCandidate(
                        content = ctx.utterance,
                        source = "task_stub",
                        category = PhraseCategory.CONTEXT,
                    )
                )
            )
        } catch (_: Exception) {}

        ctx.branchResult = BranchResult(
            content = "I've noted that — task execution is coming soon.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}
