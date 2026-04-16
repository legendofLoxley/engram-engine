package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.cloud.CloudLlmClient

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
    private val selectionService: ResponseSelectionService? = null,
) {

    private val attention     = Attention()
    private val comprehension = Comprehension(llmClient, selectTier2Model(llmClient))
    private val router        = Router(engramClient, llmClient, selectionService)
    private val expression    = Expression()

    private val stages: List<CognitiveStage> = listOf(attention, comprehension, expression)

    companion object {
        private fun selectTier2Model(llmClient: LlmClient?): LlmModel? {
            if (llmClient == null) return null
            if (llmClient is CloudLlmClient) return when {
                llmClient.hasGoogleKey    -> LlmModel.GEMINI_FLASH_2_0
                llmClient.hasAnthropicKey -> LlmModel.CLAUDE_HAIKU_3_5
                else                      -> null
            }
            // Non-cloud clients (e.g. TestLlmClient) — use Gemini as default
            return LlmModel.GEMINI_FLASH_2_0
        }
    }

    /** Call once before first use to allow stages to initialise resources. */
    suspend fun init() {
        stages.forEach { it.onInit() }
    }

    /** Result of a full pipeline cycle, enriched with routing metadata. */
    data class ChatResult(val responseText: String, val intent: IntentType, val comprehensionTier: Int)

    /** Extended result including the full pipeline trace for the debug endpoint. */
    data class DebugChatResult(val chat: ChatResult, val trace: PipelineTrace)

    /**
     * Process a single utterance end-to-end and return the final response text.
     */
    suspend fun process(utterance: String, sessionId: String, userId: String): String =
        processInternal(utterance, sessionId, userId, debug = false).first.responseText

    /**
     * Process a single utterance and return both the response text and the resolved intent.
     * Used by the HTTP chat surface to populate [ChatResult.intent] in the API response.
     */
    suspend fun processForChat(utterance: String, sessionId: String, userId: String): ChatResult =
        processInternal(utterance, sessionId, userId, debug = false).first

    /**
     * Process a single utterance with full instrumentation, returning both the
     * chat result and the pipeline trace for the debug endpoint.
     */
    suspend fun processForDebug(utterance: String, sessionId: String, userId: String): DebugChatResult {
        val (chatResult, trace) = processInternal(utterance, sessionId, userId, debug = true)
        return DebugChatResult(chatResult, trace!!)
    }

    private suspend fun processInternal(
        utterance: String, sessionId: String, userId: String, debug: Boolean,
    ): Pair<ChatResult, PipelineTrace?> {
        val trace = if (debug) PipelineTrace() else null
        val pipelineStartNs = if (debug) System.nanoTime() else 0L
        // Load scaffold state before Comprehension so Rule 0 fires correctly on subsequent turns.
        // An active scaffold question means the user is mid-onboarding and any utterance is an answer.
        val memoryStartNs = if (debug) System.nanoTime() else 0L
        val scaffoldState = try {
            val state = engramClient.getScaffoldState(userId)
            if (state.activeScaffoldQuestion != null) state else null
        } catch (_: Exception) {
            null
        }
        if (debug) {
            trace!!.latencyBreakdown.memoryMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - memoryStartNs)
        }

        val ctx = CognitiveContext(
            utterance     = utterance,
            sessionId     = sessionId,
            roomId        = "foyer",
            userId        = userId,
            timestamp     = java.time.Instant.now(),
            scaffoldState = scaffoldState,
            trace         = trace,
        )

        attention.evaluate(ctx)

        if (ctx.attentionAction != AttentionAction.PROCESS) {
            return Pair(ChatResult(ctx.responseText, ctx.intent, ctx.comprehensionTier), trace)
        }

        // ── Comprehension ────────────────────────────────────────────────────
        val comprehensionStartNs = if (debug) System.nanoTime() else 0L
        comprehension.evaluate(ctx)
        if (debug) {
            trace!!.latencyBreakdown.comprehensionMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - comprehensionStartNs)
            trace.model.comprehensionModel = if (trace.comprehension.tierTwoFired) tier2ModelName() else null
        }

        // ── Routing ──────────────────────────────────────────────────────────
        val routingStartNs = if (debug) System.nanoTime() else 0L
        val branch = router.route(ctx.intent)
        if (debug) {
            trace!!.latencyBreakdown.routingMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - routingStartNs)
            trace.routing.intentType = ctx.intent.name
            trace.routing.confidence = ctx.intentConfidence
            trace.routing.secondaryIntent = ctx.secondaryIntent?.name
            trace.routing.branchSelected = branch::class.simpleName ?: "Unknown"
            trace.routing.route = routeNameFor(ctx.intent)
        }

        // ── Reason (Branch execution) ────────────────────────────────────────
        val reasonStartNs = if (debug) System.nanoTime() else 0L
        branch.execute(ctx)
        if (debug) {
            trace!!.latencyBreakdown.reasonMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reasonStartNs)
            val (provider, model) = reasonModelInfo(branch)
            trace.model.reasonProvider = provider
            trace.model.reasonModel = model

            val selResult = ctx.selectionResult
            if (selResult != null) {
                val strategy = ctx.branchResult?.responseStrategy ?: ResponseStrategy.SIMPLE
                trace.responseSelection = ResponseSelectionTrace(
                    phraseId = selResult.phrase.uid,
                    phraseText = selResult.phrase.text,
                    interpolatedText = selResult.interpolated,
                    strategy = strategy,
                    compositeScore = selResult.compositeScore,
                    scores = mapOf(
                        "freshness"           to (selResult.scoreBreakdown["freshness"]           ?: 0.0),
                        "contextual_fit"      to (selResult.scoreBreakdown["contextualFit"]       ?: 0.0),
                        "communication_fit"   to (selResult.scoreBreakdown["communicationFit"]    ?: 0.0),
                        "phase_appropriateness" to (selResult.scoreBreakdown["phaseAppropriateness"] ?: 0.0),
                        "effectiveness"       to (selResult.scoreBreakdown["effectiveness"]       ?: 0.0),
                    ),
                    candidatesConsidered = ctx.selectionCandidatesConsidered,
                    selectionLatencyMs = ctx.selectionLatencyMs,
                )
            }
        }

        // ── Expression ───────────────────────────────────────────────────────
        val expressionStartNs = if (debug) System.nanoTime() else 0L
        expression.evaluate(ctx)
        if (debug) {
            trace!!.latencyBreakdown.expressionMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - expressionStartNs)
            trace.latencyBreakdown.totalPipelineMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pipelineStartNs)

            trace.session.scaffoldState = null // Serialising arbitrary objects is fragile; null for now
            trace.session.trustPhase = ctx.trustPhase?.toIntOrNull()
            trace.session.turnCount = ctx.priorUtterances.size + 1
            trace.session.sessionAgeMs = 0 // SessionManager doesn't expose creation time to pipeline
        }

        stages.forEach { it.onCycleEnd(ctx) }

        return Pair(ChatResult(ctx.responseText, ctx.intent, ctx.comprehensionTier), trace)
    }

    private fun tier2ModelName(): String? {
        val model = selectTier2Model(llmClient) ?: return null
        return model.name.lowercase().replace('_', '-')
    }

    private fun routeNameFor(intent: IntentType): String = when (intent) {
        IntentType.SOCIAL      -> "short_circuit_social"
        IntentType.ONBOARDING  -> "decompose_and_scaffold"
        IntentType.QUESTION    -> "graph_augmented_answer"
        IntentType.TASK        -> "task_accept"
        IntentType.CORRECTION  -> "correction_branch"
        IntentType.META        -> "meta_branch"
        IntentType.CLARIFICATION,
        IntentType.AMBIGUOUS   -> "clarification_branch"
    }

    private fun reasonModelInfo(branch: Branch): Pair<String?, String?> = when (branch) {
        is QuestionBranch    -> if (llmClient != null) ("anthropic" to "claude-3-7-sonnet") else (null to null)
        is OnboardingBranch  -> if (llmClient != null) ("anthropic" to "claude-3-7-sonnet") else (null to null)
        else                 -> null to null
    }
}
