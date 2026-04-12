package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.providers.LlmClient

/**
 * Top-level orchestrator for the cognitive processing cycle.
 *
 * Lifecycle per utterance:
 *   1. Attention.evaluate
 *   2. If not PROCESS → return empty response
 *   3. Load scaffold state → populate ctx.scaffoldState to activate Comprehension Rule 0
 *   4. Comprehension.evaluate
 *   5. Router.route → Branch
 *   6. Branch.execute
 *   7. Expression.evaluate
 *   8. onCycleEnd on all stages
 *
 * @param engramClient Memory backend. Defaults to [InMemoryEngramClient] so the pipeline
 *                     runs standalone without an external engram-engine instance.
 * @param llmClient    LLM backend. Null by default — branches degrade gracefully.
 */
class CognitivePipeline(
    private val engramClient: EngramClient = InMemoryEngramClient(),
    private val llmClient: LlmClient? = null,
) {

    private val attention     = Attention()
    private val comprehension = Comprehension()
    private val router        = Router(engramClient, llmClient)
    private val expression    = Expression()

    private val stages: List<CognitiveStage> = listOf(attention, comprehension, expression)

    /** Call once before first use to allow stages to initialise resources. */
    suspend fun init() {
        stages.forEach { it.onInit() }
    }

    /** Result of a full pipeline cycle, enriched with routing metadata. */
    data class ChatResult(val responseText: String, val intent: IntentType)

    /**
     * Process a single utterance end-to-end and return the final response text.
     */
    suspend fun process(utterance: String, sessionId: String, userId: String): String =
        processInternal(utterance, sessionId, userId).responseText

    /**
     * Process a single utterance and return both the response text and the resolved intent.
     * Used by the HTTP chat surface to populate [ChatResult.intent] in the API response.
     */
    suspend fun processForChat(utterance: String, sessionId: String, userId: String): ChatResult =
        processInternal(utterance, sessionId, userId)

    private suspend fun processInternal(utterance: String, sessionId: String, userId: String): ChatResult {
        // Load scaffold state before Comprehension so Rule 0 fires correctly on subsequent turns.
        // An active scaffold question means the user is mid-onboarding and any utterance is an answer.
        val scaffoldState = try {
            val state = engramClient.getScaffoldState(userId)
            if (state.activeScaffoldQuestion != null) state else null
        } catch (_: Exception) {
            null
        }

        val ctx = CognitiveContext(
            utterance     = utterance,
            sessionId     = sessionId,
            roomId        = "foyer",
            userId        = userId,
            timestamp     = java.time.Instant.now(),
            scaffoldState = scaffoldState,
        )

        attention.evaluate(ctx)

        if (ctx.attentionAction != AttentionAction.PROCESS) {
            return ChatResult(ctx.responseText, ctx.intent)
        }

        comprehension.evaluate(ctx)

        val branch = router.route(ctx.intent)
        branch.execute(ctx)

        expression.evaluate(ctx)

        stages.forEach { it.onCycleEnd(ctx) }

        return ChatResult(ctx.responseText, ctx.intent)
    }
}
