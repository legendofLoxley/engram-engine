package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.pipeline.scaffold.TransitionDecision
import app.alfrd.engram.cognitive.pipeline.scaffold.TrustPhaseTransitionService
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionQuery
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.cloud.CloudLlmClient
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.ResponseCategory

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
open class CognitivePipeline(
    private val engramClient: EngramClient = InMemoryEngramClient(),
    private val llmClient: LlmClient? = null,
    private val selectionService: ResponseSelectionService? = null,
    private val memoryWriteService: MemoryWriteService? = null,
    private val transitionService: TrustPhaseTransitionService? = null,
) {

    // Wrap with voice identity so every LLM call includes the base voice-modality prompt.
    // The original llmClient is kept separately for the CloudLlmClient type check in selectTier2Model.
    private val voiceLlmClient: LlmClient? = llmClient?.let { VoiceContextLlmClient(it) }

    private val attention     = Attention()
    private val comprehension = Comprehension(voiceLlmClient, selectTier2Model(llmClient))
    private val router        = Router(engramClient, voiceLlmClient, selectionService, memoryWriteService)
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
    data class ChatResult(val responseText: String, val intent: IntentType, val comprehensionTier: Int, val synthesisSource: String = "pool")

    /** Extended result including the full pipeline trace for the debug endpoint. */
    data class DebugChatResult(val chat: ChatResult, val trace: PipelineTrace)

    /** The synthesis text and its origin, for SSE streaming. */
    data class SynthesisResult(val text: String, val source: String)

    /** Result of an INIT signal — the selected greeting for a new session. */
    data class InitResponse(
        val greeting: String,
        val phraseId: String,
        val sessionId: String,
        /** Scaffold question to append for ORIENTATION users with < 3 answered categories. Null otherwise. */
        val scaffoldQuestion: String? = null,
    )

    /**
     * Process a single utterance end-to-end and return the final response text.
     */
    open suspend fun process(utterance: String, sessionId: String, userId: String): String =
        processInternal(utterance, sessionId, userId, debug = false).first.responseText

    /**
     * Process a single utterance end-to-end and return synthesis text with its source tag.
     * Used by [app.alfrd.engram.cognitive.pipeline.PhaseEventStreamer] to populate the
     * `source` field on every synthesis [app.alfrd.engram.model.PhaseEvent].
     *
     * Overridable so tests can inject controlled failures without touching [process].
     */
    open suspend fun processForStream(utterance: String, sessionId: String, userId: String): SynthesisResult {
        val (chatResult, _) = processInternal(utterance, sessionId, userId, debug = false)
        return SynthesisResult(chatResult.responseText, chatResult.synthesisSource)
    }

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

    /**
     * INIT signal — selects a scaffold-aware greeting phrase when a session starts.
     *
     * Loads the user's scaffold state so that:
     *   - Trust phase drives phase-appropriateness scoring
     *   - Session gap drives contextual-fit scoring (gap-aware phrases)
     *   - First-ever sessions receive a greeting + the opening scaffold question
     *
     * No LLM, no streaming, no pipeline trace — single-shot selection only.
     *
     * @param context  Optional client-side hints. Recognized keys:
     *                   - `"timezone"` — IANA time-zone ID (e.g. "America/Los_Angeles")
     * @param timestamp  Override the "now" instant used for time-of-day scoring.
     *                   Defaults to [java.time.Instant.now]. Useful in tests.
     */
    suspend fun initSession(
        sessionId: String,
        userId: String,
        context: Map<String, String>? = null,
        timestamp: java.time.Instant = java.time.Instant.now(),
    ): InitResponse {
        val zoneId = context?.get("timezone")?.let {
            try { java.time.ZoneId.of(it) } catch (_: Exception) { null }
        }

        val fallbackGreeting: () -> String = {
            val hour = java.time.LocalTime.now(zoneId ?: java.time.ZoneId.systemDefault()).hour
            when {
                hour < 12 -> "Good morning."
                hour < 17 -> "Good afternoon."
                else -> "Good evening."
            }
        }

        if (selectionService == null) {
            return InitResponse(
                greeting  = fallbackGreeting(),
                phraseId  = "fallback",
                sessionId = sessionId,
            )
        }

        // Load scaffold state for context-aware greeting selection.
        // Failure is non-fatal — selection falls back to phase-neutral scoring.
        var scaffoldState: ScaffoldState? = try {
            engramClient.getScaffoldState(userId)
        } catch (_: Exception) {
            null
        }

        // Evaluate dormancy regression before using state for greeting selection.
        // If the user has been away more than 90 days, regress their phase by one level
        // (capped at WORKING_RHYTHM — never back to ORIENTATION from dormancy alone).
        if (scaffoldState != null && transitionService != null) {
            val regression = transitionService.evaluateDormancyRegression(scaffoldState)
            if (regression is TransitionDecision.Transition) {
                try {
                    transitionService.apply(userId, regression)
                    scaffoldState = engramClient.getScaffoldState(userId)
                } catch (_: Exception) {
                    // Regression write failure is non-fatal — greet with stale phase
                }
            }
        }

        val trustPhaseString = when (scaffoldState?.trustPhase) {
            1 -> "ORIENTATION"
            2 -> "WORKING_RHYTHM"
            3 -> "CONTEXT"
            4 -> "UNDERSTANDING"
            else -> null
        }

        val ctx = CognitiveContext(
            utterance         = "",
            sessionId         = sessionId,
            userId            = userId,
            timestamp         = timestamp,
            zoneId            = zoneId,
            trustPhase        = trustPhaseString,
            sessionCount      = scaffoldState?.sessionCount ?: 0,
            lastInteractionAt = scaffoldState?.lastInteractionAt,
        )

        val query = ResponseSelectionQuery(
            branch          = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.ACKNOWLEDGE,
            category        = ResponseCategory.GREETING,
            context         = ctx,
            limit           = 1,
        )

        return try {
            val result = selectionService.select(query).firstOrNull()
            val greeting = result?.interpolated ?: fallbackGreeting()
            val phraseId = result?.phrase?.uid ?: "fallback"

            InitResponse(
                greeting         = greeting,
                phraseId         = phraseId,
                sessionId        = sessionId,
                scaffoldQuestion = resolveScaffoldQuestion(scaffoldState),
            )
        } catch (_: Exception) {
            InitResponse(
                greeting  = fallbackGreeting(),
                phraseId  = "fallback",
                sessionId = sessionId,
            )
        }
    }

    /**
     * Returns the scaffold question to pair with the INIT greeting, or null.
     *
     * Rules:
     *   - ORIENTATION phase only
     *   - Fewer than 3 answered categories
     *   - Uses the active question if one exists; otherwise derives from the next
     *     uncovered category (or the opener question for a brand-new user)
     */
    private fun resolveScaffoldQuestion(scaffoldState: ScaffoldState?): String? {
        if (scaffoldState == null) return null
        if (scaffoldState.trustPhase != 1) return null         // Not ORIENTATION
        if (scaffoldState.answeredCategories.size >= 3) return null

        // Return the question that was active when the user last left
        scaffoldState.activeScaffoldQuestion?.let { return it }

        // First-ever session — no question has been stored yet
        if (scaffoldState.answeredCategories.isEmpty()) {
            return "What are you working on right now?"
        }

        // Has some answered categories but no active question — derive the next one
        val next = OnboardingBranch.SCAFFOLD_PRIORITY.firstOrNull { it !in scaffoldState.answeredCategories }
        return next?.let { category ->
            when (category) {
                PhraseCategory.IDENTITY     -> "What are you working on right now?"
                PhraseCategory.EXPERTISE    -> "What tools or technologies do you work with most?"
                PhraseCategory.PREFERENCE   -> "Is there a particular way you prefer to work?"
                PhraseCategory.ROUTINE      -> "What does a typical day look like for you?"
                PhraseCategory.RELATIONSHIP -> "Do you work with a team, or mostly independently?"
                PhraseCategory.CONTEXT      -> "Is there anything important about your current situation I should know?"
            }
        }
    }

    private suspend fun processInternal(
        utterance: String, sessionId: String, userId: String, debug: Boolean,
    ): Pair<ChatResult, PipelineTrace?> {
        val trace = if (debug) PipelineTrace() else null
        // Per-stage nanosecond accumulators — summed into totalPipelineMs at the end so that
        // the breakdown always adds up correctly regardless of sub-millisecond rounding.
        var memoryNs = 0L
        var comprehensionNs = 0L
        var routingNs = 0L
        var reasonNs = 0L
        var expressionNs = 0L

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
            memoryNs = System.nanoTime() - memoryStartNs
            trace!!.latencyBreakdown.memoryMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(memoryNs)
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
            return Pair(ChatResult(ctx.responseText, ctx.intent, ctx.comprehensionTier, "pool"), trace)
        }

        // ── Comprehension ────────────────────────────────────────────────────
        val comprehensionStartNs = if (debug) System.nanoTime() else 0L
        comprehension.evaluate(ctx)
        if (debug) {
            comprehensionNs = System.nanoTime() - comprehensionStartNs
            trace!!.latencyBreakdown.comprehensionMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(comprehensionNs)
            trace.model.comprehensionModel = if (trace.comprehension.tierTwoFired) tier2ModelName() else null
        }

        // ── Routing ──────────────────────────────────────────────────────────
        val routingStartNs = if (debug) System.nanoTime() else 0L
        val branch = router.route(ctx.intent)
        if (debug) {
            routingNs = System.nanoTime() - routingStartNs
            trace!!.latencyBreakdown.routingMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(routingNs)
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
            reasonNs = System.nanoTime() - reasonStartNs
            trace!!.latencyBreakdown.reasonMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(reasonNs)
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
                        "freshness"              to (selResult.scoreBreakdown["freshness"]             ?: 0.0),
                        "contextualFit"          to (selResult.scoreBreakdown["contextualFit"]         ?: 0.0),
                        "communicationFit"       to (selResult.scoreBreakdown["communicationFit"]      ?: 0.0),
                        "phaseAppropriateness"   to (selResult.scoreBreakdown["phaseAppropriateness"]  ?: 0.0),
                        "effectiveness"          to (selResult.scoreBreakdown["effectiveness"]         ?: 0.0),
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
            expressionNs = System.nanoTime() - expressionStartNs
            trace!!.latencyBreakdown.expressionMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(expressionNs)
            trace.latencyBreakdown.totalPipelineMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    memoryNs + comprehensionNs + routingNs + reasonNs + expressionNs
                )

            trace.session.scaffoldState = null // Serialising arbitrary objects is fragile; null for now
            trace.session.trustPhase = ctx.trustPhase?.toIntOrNull()
            trace.session.turnCount = ctx.priorUtterances.size + 1
            trace.session.sessionAgeMs = 0 // SessionManager doesn't expose creation time to pipeline
        }

        stages.forEach { it.onCycleEnd(ctx) }

        return Pair(
            ChatResult(ctx.responseText, ctx.intent, ctx.comprehensionTier, ctx.branchResult?.source ?: "pool"),
            trace,
        )
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
