package app.alfrd.engram.cognitive.pipeline

import java.time.Instant

/**
 * Top-level orchestrator for the cognitive processing cycle.
 *
 * Lifecycle per utterance:
 *   1. Attention.evaluate
 *   2. If not PROCESS → return empty response
 *   3. Comprehension.evaluate
 *   4. Router.route → Branch
 *   5. Branch.execute
 *   6. Expression.evaluate
 *   7. onCycleEnd on all stages
 */
class CognitivePipeline {

    private val attention     = Attention()
    private val comprehension = Comprehension()
    private val router        = Router()
    private val expression    = Expression()

    private val stages: List<CognitiveStage> = listOf(attention, comprehension, expression)

    /** Call once before first use to allow stages to initialise resources. */
    suspend fun init() {
        stages.forEach { it.onInit() }
    }

    /**
     * Process a single utterance end-to-end and return the final response text.
     */
    suspend fun process(utterance: String, sessionId: String, userId: String): String {
        val ctx = CognitiveContext(
            utterance = utterance,
            sessionId = sessionId,
            roomId    = "foyer",
            userId    = userId,
            timestamp = Instant.now(),
        )

        attention.evaluate(ctx)

        if (ctx.attentionAction != AttentionAction.PROCESS) {
            return ctx.responseText
        }

        comprehension.evaluate(ctx)

        val branch = router.route(ctx.intent)
        branch.execute(ctx)

        expression.evaluate(ctx)

        stages.forEach { it.onCycleEnd(ctx) }

        return ctx.responseText
    }
}
