package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import app.alfrd.engram.cognitive.pipeline.posture.computePostureSignals
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionQuery
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.PostureMoveType

/**
 * Branch that resolves low-pressure utterance shapes (DISCLOSURE, FYI, CONTINUATION)
 * to a brief verbal acknowledgment without invoking an LLM or querying the memory graph.
 *
 * Move selection:
 *   - DISCLOSURE with elevated surface energy → HOLD
 *   - All other routed shapes              → RECEIPT
 *
 * When [selectionService] is available, selects from the FIRST_RESPONSE phrase pool by
 * move type.  Falls back to hardcoded phrases when the service is null or empty.
 */
class VerbalMoveBranch(
    private val selectionService: ResponseSelectionService? = null,
    private val turnShape: TurnShape,
) : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        val moveType = moveTypeFor(ctx)
        val text = selectPhrase(ctx, moveType) ?: fallback(moveType)
        ctx.branchResult = BranchResult(
            content = text,
            responseStrategy = ResponseStrategy.SOCIAL,
            source = "pool",
        )
    }

    // ── Move selection ────────────────────────────────────────────────────────

    private fun moveTypeFor(ctx: CognitiveContext): PostureMoveType {
        if (turnShape == TurnShape.Disclosure) {
            val signals = computePostureSignals(ctx)
            if (signals.surfaceEnergy > 0.3) return PostureMoveType.HOLD
        }
        return PostureMoveType.RECEIPT
    }

    private suspend fun selectPhrase(ctx: CognitiveContext, moveType: PostureMoveType): String? {
        val svc = selectionService ?: return null
        return try {
            val query = ResponseSelectionQuery(
                moveType = moveType,
                expressionPhase = ExpressionPhase.FIRST_RESPONSE,
                context = ctx,
                limit = 1,
            )
            val startMs = System.currentTimeMillis()
            val results = svc.select(query)
            ctx.selectionLatencyMs = System.currentTimeMillis() - startMs
            ctx.selectionResult = results.firstOrNull()
            results.firstOrNull()?.interpolated
        } catch (_: Exception) {
            null
        }
    }

    private fun fallback(moveType: PostureMoveType): String = when (moveType) {
        PostureMoveType.RECEIPT -> "Got it."
        PostureMoveType.ORIENT  -> "Right."
        PostureMoveType.HOLD    -> "I hear you."
        PostureMoveType.REPAIR  -> "Got it."
        PostureMoveType.PROBE   -> "Can you say more?"
        PostureMoveType.COMMIT  -> "On it."
        else                    -> "Got it."
    }
}
