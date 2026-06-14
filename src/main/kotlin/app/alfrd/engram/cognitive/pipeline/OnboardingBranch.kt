package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState

/**
 * Onboarding branch — passive listener that silently ingests user utterances into the
 * memory graph without driving an interrogative question loop.
 *
 * Flow per turn:
 * 1. On the very first interaction ever (empty scaffold state, no active question): return the
 *    opening prompt and store it as activeScaffoldQuestion.
 * 2. Otherwise: capture the utterance for async memory ingestion via [memoryWriteService]
 *    (fire-and-forget), clear the active scaffold question so subsequent turns route normally,
 *    and return no branch result so the pipeline produces no assistant response for this turn.
 *
 * The interrogative category-cycling loop has been removed. The branch never generates or
 * stores a new scaffold question — its sole job is silent memory capture.
 */
class OnboardingBranch(
    private val engramClient: EngramClient,
    private val memoryWriteService: MemoryWriteService? = null,
) : Branch {

    companion object {
        val SCAFFOLD_PRIORITY = listOf(
            PhraseCategory.IDENTITY,
            PhraseCategory.EXPERTISE,
            PhraseCategory.PREFERENCE,
            PhraseCategory.ROUTINE,
            PhraseCategory.RELATIONSHIP,
            PhraseCategory.CONTEXT,
        )

        const val OPENER = "Good to meet you. I'd like to get oriented so I can be useful quickly. What are you working on right now?"
    }

    override suspend fun execute(ctx: CognitiveContext) {
        val state = try {
            engramClient.getScaffoldState(ctx.userId)
        } catch (e: Exception) {
            ScaffoldState()
        }

        // ── First ever interaction — send the opener ───────────────────────────
        if (state.answeredCategories.isEmpty() && state.activeScaffoldQuestion == null) {
            val newState = state.copy(activeScaffoldQuestion = OPENER)
            tryUpdateState(ctx.userId, newState)
            ctx.scaffoldState = newState
            ctx.branchResult = BranchResult(content = OPENER, responseStrategy = ResponseStrategy.SIMPLE)
            return
        }

        // ── Passively capture the utterance for memory ingestion ──────────────
        if (memoryWriteService != null) {
            memoryWriteService.captureUtterance(
                utterance        = ctx.utterance,
                userId           = ctx.userId,
                sessionId        = ctx.sessionId,
                turnIndex        = ctx.priorUtterances.size,
                scaffoldCategory = null,
                sourceTag        = "onboarding_conversation",
            )
        } else {
            val candidates = try {
                engramClient.decompose(ctx.utterance, ctx.priorUtterances)
            } catch (_: Exception) {
                emptyList()
            }
            try {
                if (candidates.isNotEmpty()) engramClient.ingest(candidates, ctx.userId)
            } catch (_: Exception) {}
        }

        // Clear the active question so subsequent utterances route via normal branches
        val newState = state.copy(activeScaffoldQuestion = null)
        tryUpdateState(ctx.userId, newState)
        ctx.scaffoldState = newState
        ctx.branchResult = null
    }

    private suspend fun tryUpdateState(userId: String, state: ScaffoldState) {
        try {
            engramClient.updateScaffoldState(userId, state)
        } catch (_: Exception) {}
    }
}
