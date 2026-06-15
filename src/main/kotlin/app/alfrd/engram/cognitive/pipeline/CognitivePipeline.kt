package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.FluxEvent
import app.alfrd.engram.cognitive.pipeline.posture.PostureSignals
import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import app.alfrd.engram.cognitive.pipeline.posture.computePostureSignals
import app.alfrd.engram.cognitive.pipeline.posture.selectMoveType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.pipeline.scaffold.TransitionDecision
import app.alfrd.engram.cognitive.pipeline.scaffold.TrustPhaseTransitionService
import app.alfrd.engram.cognitive.pipeline.selection.OutcomeSignalClassifier
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionQuery
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.providers.TranscriptionResult
import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.cloud.CloudLlmClient
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.PostureMoveType
import app.alfrd.engram.model.OutcomeSignal
import app.alfrd.engram.model.ResponseCategory
import org.slf4j.LoggerFactory

/**
 * Top-level orchestrator for the cognitive processing cycle.
 *
 * Lifecycle per utterance:
 *   1. Attention.evaluate
 *   2. If not PROCESS → return empty response
 *   3. Comprehension.evaluate
 *   4. Router.route → Branch
 *   5. Branch.execute
 *   6. Universal memory ingestion (fire-and-forget via MemoryWriteService)
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
    private val firstSessionHandler: FirstSessionHandler? = null,
) {

    private val logger = LoggerFactory.getLogger(CognitivePipeline::class.java)

    // Wrap with voice identity so every LLM call includes the base voice-modality prompt.
    // The original llmClient is kept separately for the CloudLlmClient type check in selectTier2Model.
    private val voiceLlmClient: LlmClient? = llmClient?.let { VoiceContextLlmClient(it) }

    private val attention     = Attention()
    private val comprehension = Comprehension(voiceLlmClient, selectTier2Model(llmClient))
    private val router        = Router(engramClient, voiceLlmClient, selectionService, memoryWriteService)
    private val expression    = Expression()

    private val stages: List<CognitiveStage> = listOf(attention, comprehension, expression)

    /**
     * Outcome state from the most recently completed turn.
     * Set after phrase selection; consumed and cleared at the start of the next turn.
     * Volatile so [SessionManager] can read it from the eviction thread safely.
     */
    @Volatile var pendingOutcome: PendingOutcome? = null
        internal set

    /**
     * First-session identity verification state.
     * Set during [initSession] when both detection checks pass; updated through Turn 2.
     * Null when first-session handling is disabled or not triggered.
     */
    @Volatile var firstSessionState: FirstSessionState? = null
        internal set

    @Volatile private var sessionZoneId: java.time.ZoneId? = null

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

    /** Result of the first-response posture -> selection pipeline for SSE streaming. */
    data class FirstResponseResult(
        val moveType: PostureMoveType,
        val text: String,
        val postureSignals: PostureSignals,
        val phraseId: String? = null,
    )

    /** Result of an INIT signal — the selected greeting for a new session. */
    data class InitResponse(
        val greeting: String,
        val phraseId: String,
        val sessionId: String,
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
     * Fast first-response path for turn-finalization streaming:
     * AssemblyAI/Flux turn event -> posture signals -> move type -> scored first-response phrase.
     *
     * No LLM calls. Selection writes SELECTED edges asynchronously via [ResponseSelectionService].
     */
    open suspend fun processFirstResponseForStream(
        utterance: String,
        sessionId: String,
        userId: String,
        transcriptionResults: List<TranscriptionResult> = emptyList(),
        fluxEvent: FluxEvent? = null,
        priorMoveType: PostureMoveType? = null,
        timestamp: java.time.Instant = java.time.Instant.now(),
    ): FirstResponseResult {
        val scaffoldState = try {
            engramClient.getScaffoldState(userId)
        } catch (_: Exception) {
            null
        }

        val trustPhaseString = when (scaffoldState?.trustPhase) {
            1 -> "ORIENTATION"
            2 -> "WORKING_RHYTHM"
            3 -> "CONTEXT"
            4 -> "UNDERSTANDING"
            else -> null
        }

        val ctx = CognitiveContext(
            utterance = utterance,
            sessionId = sessionId,
            userId = userId,
            timestamp = timestamp,
            zoneId = sessionZoneId,
            transcriptionResults = transcriptionResults,
            fluxEvent = fluxEvent,
            trustPhase = trustPhaseString,
            sessionCount = scaffoldState?.sessionCount ?: 0,
            lastInteractionAt = scaffoldState?.lastInteractionAt,
        )

        val postureSignals = computePostureSignals(ctx)
        val moveType = selectMoveType(postureSignals, priorMoveType)

        if (selectionService == null) {
            return FirstResponseResult(
                moveType = moveType,
                text = fallbackFirstResponseText(moveType),
                postureSignals = postureSignals,
                phraseId = "fallback",
            )
        }

        val query = ResponseSelectionQuery(
            branch = null,
            moveType = moveType,
            expressionPhase = ExpressionPhase.FIRST_RESPONSE,
            context = ctx,
            limit = 1,
        )

        val selected = try {
            selectionService.select(query).firstOrNull()
        } catch (_: Exception) {
            null
        }

        return FirstResponseResult(
            moveType = moveType,
            text = selected?.interpolated ?: fallbackFirstResponseText(moveType),
            postureSignals = postureSignals,
            phraseId = selected?.phrase?.uid,
        )
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
        userEmail: String = "",
    ): InitResponse {
        // Ensure a User vertex exists for Supabase-direct signups (no-op for seeded users).
        if (userEmail.isNotBlank()) {
            firstSessionHandler?.userGraphService?.let { ugs ->
                withContext(Dispatchers.IO) { ugs.findOrCreateUser(userEmail) }
            }
        }

        if (userEmail == "yardkup@gmail.com") {
            return InitResponse(
                greeting  = "[debug] engram-engine ok · session=$sessionId",
                phraseId  = "debug",
                sessionId = sessionId,
            )
        }

        val zoneId = context?.get("timezone")?.let {
            try { java.time.ZoneId.of(it) } catch (_: Exception) { null }
        }
        sessionZoneId = zoneId

        val fallbackGreeting: () -> String = {
            val hour = java.time.LocalTime.now(zoneId ?: java.time.ZoneId.systemDefault()).hour
            when {
                hour < 12 -> "Good morning."
                hour < 17 -> "Good afternoon."
                else -> "Good evening."
            }
        }

        // ── First-session detection ──────────────────────────────────────────
        // For returning verified users (not first session), capture the INVITED edge so
        // we can produce a warm intro from opening_context below.
        var warmIntroText: String? = null
        if (firstSessionHandler != null && userEmail.isNotBlank()) {
            val detection = firstSessionHandler.detectFirstSession(userId, userEmail)
            if (detection.isFirstSession) {
                val turn1 = firstSessionHandler.handleTurn1(detection)
                if (!turn1.rejected) {
                    // Valid invitee — store state and return Turn 1 greeting.
                    val edge = turn1.invitedEdge!!
                    firstSessionState = FirstSessionState(
                        isFirstSession               = true,
                        awaitingIdentityVerification = true,
                        trustPhase                   = edge.trustPhase,
                        engagementIntent             = edge.engagementIntent,
                        relationshipContext          = edge.relationshipContext,
                    )
                    logger.info(
                        "initSession userId=$userId email=$userEmail path=first-session-turn1 " +
                        "trustPhase=${edge.trustPhase}"
                    )
                    return InitResponse(
                        greeting  = turn1.response,
                        phraseId  = "first-session-turn1",
                        sessionId = sessionId,
                    )
                }
                // No INVITED edge — user reached this endpoint authenticated, so the
                // beta gate is already cleared. Fall through to normal greeting selection.
                logger.info("initSession userId=$userId email=$userEmail path=closed-beta-rejection")
            } else {
                // Not first session — capture opening_context for warm intro if present.
                warmIntroText = detection.invitedEdge?.openingContext?.takeIf { it.isNotBlank() }
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

        // ── Warm intro for returning invited user ────────────────────────────
        if (warmIntroText != null) {
            logger.info(
                "initSession userId=$userId email=$userEmail path=warm-intro " +
                "scaffoldTrustPhase=${scaffoldState?.trustPhase} firstSessionState=$firstSessionState"
            )
            return InitResponse(
                greeting  = warmIntroText,
                phraseId  = "invited-warm-intro",
                sessionId = sessionId,
            )
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
            expressionPhase = ExpressionPhase.FIRST_RESPONSE,
            category        = ResponseCategory.GREETING,
            context         = ctx,
            limit           = 1,
        )

        return try {
            val result = selectionService.select(query).firstOrNull()
            val greeting = result?.interpolated ?: fallbackGreeting()
            val phraseId = result?.phrase?.uid ?: "fallback"

            logger.info(
                "initSession userId=$userId email=$userEmail path=selection " +
                "phraseId=$phraseId scaffoldTrustPhase=${scaffoldState?.trustPhase} " +
                "firstSessionState=$firstSessionState"
            )
            InitResponse(
                greeting  = greeting,
                phraseId  = phraseId,
                sessionId = sessionId,
            )
        } catch (_: Exception) {
            InitResponse(
                greeting  = fallbackGreeting(),
                phraseId  = "fallback",
                sessionId = sessionId,
            )
        }
    }

    private suspend fun processInternal(
        utterance: String, sessionId: String, userId: String, debug: Boolean,
    ): Pair<ChatResult, PipelineTrace?> {

        // ── First-session Turn 2 interception ────────────────────────────────
        // When the user is mid-verification, bypass the normal cognitive pipeline
        // and route directly to the identity verification handler.
        val fss = firstSessionState
        if (fss != null && fss.awaitingIdentityVerification && firstSessionHandler != null) {
            val turn2 = firstSessionHandler.handleTurn2(userId, utterance, fss)
            firstSessionState = turn2.newState
            // On successful verification, seed the scaffold state with the trust phase from
            // the INVITED edge so subsequent sessions start at the correct onboarding phase.
            if (turn2.newState.identityVerified) {
                val trustPhaseInt = when (turn2.newState.trustPhase?.trim()?.lowercase()) {
                    "colleague"  -> 2
                    "confidant"  -> 3
                    else         -> 1  // Acquaintance / default → ORIENTATION
                }
                try {
                    val current = engramClient.getScaffoldState(userId)
                    engramClient.updateScaffoldState(
                        userId,
                        current.copy(trustPhase = trustPhaseInt),
                    )
                } catch (_: Exception) { /* non-fatal */ }
            }
            return Pair(ChatResult(turn2.response, IntentType.SOCIAL, 1, "first-session"), null)
        }

        val trace = if (debug) PipelineTrace() else null
        // Per-stage nanosecond accumulators — summed into totalPipelineMs at the end so that
        // the breakdown always adds up correctly regardless of sub-millisecond rounding.
        var memoryNs = 0L
        var comprehensionNs = 0L
        var routingNs = 0L
        var reasonNs = 0L
        var expressionNs = 0L

        // Classify + write the OUTCOME for the previous turn before processing the new one.
        // The new utterance is the user's reaction to the phrase selected last turn.
        val priorPending = pendingOutcome
        if (priorPending != null) {
            pendingOutcome = null
            val classification = OutcomeSignalClassifier.classify(utterance, priorPending.priorContext)
            selectionService?.recordOutcome(
                phraseUid       = priorPending.phraseUid,
                sessionId       = priorPending.sessionId,
                userId          = priorPending.userId,
                turnIndex       = priorPending.turnIndex,
                signal          = classification.signal,
                contextSnapshot = buildContextSnapshot(utterance, classification.confidence, null),
            )
        }

        val ctx = CognitiveContext(
            utterance = utterance,
            sessionId = sessionId,
            roomId    = "foyer",
            userId    = userId,
            userEmail = userId,
            timestamp = java.time.Instant.now(),
            zoneId    = sessionZoneId,
            trace     = trace,
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
        val branch = router.route(ctx.intent, ctx.turnShape)
        if (debug) {
            routingNs = System.nanoTime() - routingStartNs
            trace!!.latencyBreakdown.routingMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(routingNs)
            trace.routing.intentType = ctx.intent.name
            trace.routing.confidence = ctx.intentConfidence
            trace.routing.secondaryIntent = ctx.secondaryIntent?.name
            trace.routing.branchSelected = branch::class.simpleName ?: "Unknown"
            trace.routing.route = routeNameFor(ctx.intent, ctx.turnShape)
        }

        // ── Reason (Branch execution) ────────────────────────────────────────
        val reasonStartNs = if (debug) System.nanoTime() else 0L
        branch.execute(ctx)

        // ── Universal memory ingestion ────────────────────────────────────────
        // Every PROCESS turn is silently decomposed and ingested exactly once,
        // independent of which branch handled it. Fire-and-forget — never blocks.
        memoryWriteService?.captureUtterance(
            utterance = utterance,
            userId    = userId,
            sessionId = sessionId,
            turnIndex = ctx.priorUtterances.size,
            sourceTag = "conversation",
        )

        // Record pending outcome for the *next* turn's classification.
        // Only set when a phrase was actually selected — pure-reason branches leave this null.
        ctx.selectionResult?.let { result ->
            pendingOutcome = PendingOutcome(
                phraseUid    = result.phrase.uid,
                sessionId    = sessionId,
                userId       = userId,
                turnIndex    = ctx.priorUtterances.size + 1,
                priorContext = OutcomeSignalClassifier.PriorTurnContext(
                    utterance  = utterance,
                    phraseText = result.phrase.text,
                ),
            )
        }
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

        logger.info(
            "turn sessionId=$sessionId userId=$userId intent=${ctx.intent} " +
            "branch=${branch::class.simpleName} source=${ctx.branchResult?.source ?: "pool"}"
        )
        // TODO: log comprehensionTier and selectionResult.phraseId at DEBUG level for phrase-level tracing

        return Pair(
            ChatResult(ctx.responseText, ctx.intent, ctx.comprehensionTier, ctx.branchResult?.source ?: "pool"),
            trace,
        )
    }

    private fun tier2ModelName(): String? {
        val model = selectTier2Model(llmClient) ?: return null
        return model.name.lowercase().replace('_', '-')
    }

    private fun fallbackFirstResponseText(moveType: PostureMoveType): String = when (moveType) {
        PostureMoveType.RECEIPT -> "Got it."
        PostureMoveType.ORIENT -> "Okay, let's orient."
        PostureMoveType.HOLD -> "I hear you."
        PostureMoveType.REPAIR -> "Let me repair that."
        PostureMoveType.PROBE -> "Can you say a bit more?"
        PostureMoveType.COMMIT -> "On it."
        PostureMoveType.WAIT -> ""
        PostureMoveType.MISREAD_RECOVERY -> "You're right, I misread that."
        PostureMoveType.YIELD -> ""
        PostureMoveType.MULTI_UTTERANCE_HOLD -> "I'm with you."
    }

    private fun routeNameFor(intent: IntentType, turnShape: TurnShape? = null): String {
        when (turnShape) {
            TurnShape.Disclosure,
            TurnShape.FYI,
            TurnShape.Continuation -> return "verbal_move"
            TurnShape.TaskRequest  -> return "task_accept"
            TurnShape.Correction   -> return "correction_branch"
            else -> { /* fall through to intent-based name */ }
        }
        return when (intent) {
            IntentType.SOCIAL                  -> "short_circuit_social"
            IntentType.QUESTION                -> "graph_augmented_answer"
            IntentType.TASK                    -> "task_accept"
            IntentType.CORRECTION              -> "correction_branch"
            IntentType.META                    -> "meta_branch"
            IntentType.CLARIFICATION,
            IntentType.AMBIGUOUS               -> "clarification_branch"
        }
    }

    private fun reasonModelInfo(branch: Branch): Pair<String?, String?> = when (branch) {
        is QuestionBranch -> if (llmClient != null) ("anthropic" to "claude-3-7-sonnet") else (null to null)
        else              -> null to null
    }

    /**
     * Called by [app.alfrd.engram.cognitive.SessionManager] when a session expires.
     * If a phrase selection is pending outcome classification, writes a DISENGAGED edge
     * and clears the pending state. Fire-and-forget — never throws.
     */
    fun recordDisengagedOutcome() {
        val pending = pendingOutcome ?: return
        pendingOutcome = null
        selectionService?.recordOutcome(
            phraseUid       = pending.phraseUid,
            sessionId       = pending.sessionId,
            userId          = pending.userId,
            turnIndex       = pending.turnIndex,
            signal          = OutcomeSignal.DISENGAGED,
            contextSnapshot = buildContextSnapshot(pending.priorContext.utterance, 0.0, "session_timeout"),
        )
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun buildContextSnapshot(utterance: String, confidence: Double, reason: String?): String {
        val escaped = utterance.replace("\\", "\\\\").replace("\"", "\\\"")
        return if (reason != null) {
            """{"utterance":"$escaped","reason":"$reason"}"""
        } else {
            """{"utterance":"$escaped","confidence":$confidence}"""
        }
    }

    /**
     * Holds the outcome context from the last phrase-selected turn so the *next*
     * utterance can classify the signal and write the OUTCOME edge.
     */
    data class PendingOutcome(
        val phraseUid: String,
        val sessionId: String,
        val userId: String,
        val turnIndex: Int,
        val priorContext: OutcomeSignalClassifier.PriorTurnContext,
    )
}
