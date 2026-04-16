package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory

/**
 * Task branch — graceful decline with optional memory capture.
 *
 * The user's intent is captured as a phrase so it's remembered even though
 * task execution is not yet implemented.
 *
 * When [memoryWriteService] is provided the write is async (fire-and-forget) so the
 * branch response is not delayed by ingestion. When it is null the branch falls back
 * to the synchronous [engramClient] call for backward compatibility.
 *
 * Memory writes are always best-effort — the response is returned regardless of
 * whether ingestion succeeds.
 */
class TaskBranch(
    private val engramClient: EngramClient?,
    private val memoryWriteService: MemoryWriteService? = null,
) : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        if (memoryWriteService != null) {
            // Async path — fire-and-forget
            memoryWriteService.captureUtterance(
                utterance        = ctx.utterance,
                userId           = ctx.userId,
                sessionId        = ctx.sessionId,
                turnIndex        = ctx.priorUtterances.size,
                scaffoldCategory = null,
                sourceTag        = "task_intent",
            )
        } else {
            // Sync path — backward-compatible for tests that construct the branch directly
            try {
                engramClient?.ingest(
                    listOf(
                        PhraseCandidate(
                            content  = ctx.utterance,
                            source   = "task_stub",
                            category = PhraseCategory.CONTEXT,
                        )
                    )
                )
            } catch (_: Exception) {}
        }

        ctx.branchResult = BranchResult(
            content          = "I've noted that — task execution is coming soon.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}
