package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionQuery
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.ResponseCategory
import java.time.LocalTime

/**
 * SocialBranch — handles greetings, thanks, and farewells without LLM or memory.
 * Target < 50 ms.
 *
 * When [selectionService] is provided, responses are selected from the ResponsePhrase
 * pool with full scoring. Falls back to hardcoded strings when the service is null
 * or returns no candidates.
 */
class SocialBranch(
    private val selectionService: ResponseSelectionService? = null,
) : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        val lower = ctx.utterance.trim().lowercase()

        val response = when {
            isGoodbye(lower) -> selectOrFallback(
                ctx = ctx,
                category = ResponseCategory.SIGN_OFF,
                expressionPhase = ExpressionPhase.SYNTHESIS,
                fallback = "Until next time.",
            )
            isThanks(lower) -> selectOrFallback(
                ctx = ctx,
                category = ResponseCategory.ACKNOWLEDGMENT,
                expressionPhase = ExpressionPhase.ACKNOWLEDGE,
                fallback = "Of course.",
            )
            else -> selectOrFallback(
                ctx = ctx,
                category = ResponseCategory.GREETING,
                expressionPhase = ExpressionPhase.ACKNOWLEDGE,
                fallback = timeBasedGreeting(),
            )
        }

        ctx.branchResult = BranchResult(
            content = response,
            responseStrategy = ResponseStrategy.SOCIAL,
        )
    }

    private fun selectOrFallback(
        ctx: CognitiveContext,
        category: ResponseCategory,
        expressionPhase: ExpressionPhase,
        fallback: String,
    ): String {
        if (selectionService == null) return fallback

        return try {
            val query = ResponseSelectionQuery(
                branch = BranchType.SOCIAL,
                expressionPhase = expressionPhase,
                category = category,
                context = ctx,
                limit = 1,
            )
            val results = selectionService.select(query)
            results.firstOrNull()?.interpolated ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun timeBasedGreeting(): String {
        val hour = LocalTime.now().hour
        return when {
            hour < 12 -> "Good morning."
            hour < 17 -> "Good afternoon."
            else      -> "Good evening."
        }
    }

    private fun isGoodbye(lower: String) =
        listOf("bye", "goodbye", "see you", "until next time", "take care").any { lower.contains(it) }

    private fun isThanks(lower: String) =
        listOf("thanks", "thank you", "cheers").any { lower.contains(it) }
}
