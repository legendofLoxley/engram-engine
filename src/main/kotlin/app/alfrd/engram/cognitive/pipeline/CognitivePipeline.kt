package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.affect.Mood
import app.alfrd.engram.cognitive.pipeline.affect.MoodDrift
import app.alfrd.engram.cognitive.pipeline.affect.MoodOverrideDetector
import app.alfrd.engram.cognitive.pipeline.affect.MoodState
import app.alfrd.engram.cognitive.pipeline.affect.moodDirective
import app.alfrd.engram.cognitive.pipeline.confidence.AffirmationClassifier
import app.alfrd.engram.cognitive.pipeline.confidence.TopicConfidenceService
import app.alfrd.engram.cognitive.pipeline.confidence.TopicResolver
import app.alfrd.engram.cognitive.pipeline.confidence.topicConfidenceDirective
import app.alfrd.engram.cognitive.pipeline.posture.FluxEvent
import app.alfrd.engram.cognitive.pipeline.posture.PostureSignals
import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import app.alfrd.engram.cognitive.pipeline.posture.attunementDirective
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
    private val personaSource: PersonaSource = DefaultPersonaSource(),
    private val confidenceService: TopicConfidenceService? = null,
) {

    private val logger = LoggerFactory.getLogger(CognitivePipeline::class.java)

    // Comprehension is a classifier, not a mouth — it never needed identity injection; giving
    // it the raw client (instead of a voice-wrapped one) drops an irrelevant "you are a voice
    // assistant, you can hear them" system instruction from tier-2 classification calls.
    // Isolated in its own commit: unverified whether this shifts real classification output,
    // since no test here exercises a real model and TestLlmClient fakes don't react to
    // systemPrompt content either way.
    private val attention     = Attention()
    private val comprehension = Comprehension(llmClient, selectTier2Model(llmClient))
    private val router        = Router()
    private val script        = Script(engramClient, selectionService, personaSource, confidenceService)
    private val actor         = Actor(llmClient)
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
     * Session-level Mood state (Affect's v2 layer) — slow-moving, overridable by explicit
     * instruction, and deliberately never read from or written to by [confidenceService].
     * Same session-scoped-instance-field pattern as [pendingOutcome]: one [CognitivePipeline]
     * per session, reused for its lifetime, per [app.alfrd.engram.cognitive.SessionManager].
     */
    @Volatile private var moodState: MoodState = MoodState()

    /** Small rolling window of already-computed [OutcomeSignal] history, feeding [MoodDrift] only. */
    @Volatile private var recentOutcomeSignals: List<OutcomeSignal> = emptyList()

    /**
     * First-session state recorded when an invited user is greeted with the warm provenance
     * intro. Set during [initSession]; null when first-session handling is disabled or not triggered.
     */
    @Volatile var firstSessionState: FirstSessionState? = null
        internal set

    @Volatile private var sessionZoneId: java.time.ZoneId? = null

    /**
     * Per-session turn counters, keyed by sessionId. Every public method here already takes
     * [String] sessionId per call, so — unlike, say, [pendingOutcome], which assumes one
     * instance per session — this has to hold up even when a single [CognitivePipeline] is
     * driven across multiple sessions (as several tests deliberately do). This is the reliable
     * source of "what turn number is this" — [CognitiveContext.priorUtterances] is never
     * populated in production and cannot be used for this. Drives the turn-1-only greeting
     * gate in [SocialBranch].
     */
    private val turnCounters = java.util.concurrent.ConcurrentHashMap<String, Int>()

    companion object {
        /** Human-readable name of the model [Actor] uses, for debug-trace reporting. Keep in sync with [Actor]. */
        private const val ACTOR_MODEL_NAME = "claude-sonnet-4-5"

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
    open suspend fun process(
        utterance: String, sessionId: String, userId: String, modality: Modality = Modality.TEXT,
    ): String =
        processInternal(utterance, sessionId, userId, debug = false, modality = modality).first.responseText

    /**
     * Process a single utterance end-to-end and return synthesis text with its source tag.
     * Used by [app.alfrd.engram.cognitive.pipeline.PhaseEventStreamer] to populate the
     * `source` field on every synthesis [app.alfrd.engram.model.PhaseEvent].
     *
     * Overridable so tests can inject controlled failures without touching [process].
     */
    open suspend fun processForStream(
        utterance: String, sessionId: String, userId: String, modality: Modality = Modality.TEXT,
    ): SynthesisResult {
        val (chatResult, _) = processInternal(utterance, sessionId, userId, debug = false, modality = modality)
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
        val scaffoldState = loadScaffoldState(userId)

        val ctx = CognitiveContext(
            utterance = utterance,
            sessionId = sessionId,
            userId = userId,
            timestamp = timestamp,
            zoneId = sessionZoneId,
            transcriptionResults = transcriptionResults,
            fluxEvent = fluxEvent,
            trustPhase = trustPhaseIntToString(scaffoldState?.trustPhase),
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
    suspend fun processForChat(
        utterance: String, sessionId: String, userId: String, modality: Modality = Modality.TEXT,
    ): ChatResult =
        processInternal(utterance, sessionId, userId, debug = false, modality = modality).first

    /**
     * Process a single utterance with full instrumentation, returning both the
     * chat result and the pipeline trace for the debug endpoint.
     */
    suspend fun processForDebug(
        utterance: String, sessionId: String, userId: String, modality: Modality = Modality.TEXT,
    ): DebugChatResult {
        val (chatResult, trace) = processInternal(utterance, sessionId, userId, debug = true, modality = modality)
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
                when {
                    turn1.seedingError -> {
                        // INVITED edge present but openingContext missing — surface the error visibly.
                        logger.error(
                            "initSession: seeding error for userId=$userId email=$userEmail — " +
                            "openingContext is blank on INVITED edge"
                        )
                        return InitResponse(
                            greeting  = turn1.response,
                            phraseId  = "first-session-seeding-error",
                            sessionId = sessionId,
                        )
                    }
                    !turn1.rejected -> {
                        // Valid invitee — write VERIFIED edge, seed scaffold, return warm intro.
                        val edge = turn1.invitedEdge!!
                        withContext(Dispatchers.IO) {
                            try {
                                firstSessionHandler.userGraphService.writeVerifiedEdge(
                                    userId, System.currentTimeMillis(),
                                )
                            } catch (_: Exception) { /* non-fatal */ }
                        }
                        val trustPhaseInt = when (edge.trustPhase.trim().lowercase()) {
                            "colleague"  -> 2
                            "confidant"  -> 3
                            else         -> 1  // Acquaintance / default → ORIENTATION
                        }
                        try {
                            val current = engramClient.getScaffoldState(userId)
                            engramClient.updateScaffoldState(userId, current.copy(trustPhase = trustPhaseInt))
                        } catch (_: Exception) { /* non-fatal */ }
                        firstSessionState = FirstSessionState(
                            isFirstSession   = true,
                            trustPhase       = edge.trustPhase,
                            engagementIntent = edge.engagementIntent,
                        )
                        logger.info(
                            "initSession userId=$userId email=$userEmail path=first-session-warm-intro " +
                            "trustPhase=${edge.trustPhase}"
                        )
                        return InitResponse(
                            greeting  = turn1.response,
                            phraseId  = "first-session-turn1",
                            sessionId = sessionId,
                        )
                    }
                    else -> {
                        // No INVITED edge — fall through to normal greeting selection.
                        logger.info("initSession userId=$userId email=$userEmail path=closed-beta-rejection")
                    }
                }
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
        var scaffoldState: ScaffoldState? = loadScaffoldState(userId)

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

        val trustPhaseString = trustPhaseIntToString(scaffoldState?.trustPhase)

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
        utterance: String, sessionId: String, userId: String, debug: Boolean, modality: Modality = Modality.TEXT,
    ): Pair<ChatResult, PipelineTrace?> {

        val turnIndex = turnCounters.merge(sessionId, 1, Int::plus)!!
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
            if (debug) {
                trace!!.graphMutations.outcomeEdge = OutcomeEdgeMutationTrace(
                    phraseUid = priorPending.phraseUid,
                    userId    = priorPending.userId,
                    sessionId = priorPending.sessionId,
                    signal    = classification.signal.name,
                    turnIndex = priorPending.turnIndex,
                )
            }

            // Per-topic confidence evidence — resolved from the *user's prior utterance*
            // (priorContext.utterance), not the selected response phrase text, since short
            // posture phrases ("Got it.") carry no topic. Deliberately reuses this
            // already-computed OutcomeSignal for "demonstrated competence" rather than a
            // separate classification — distinct concern (phrase effectiveness) from the
            // first-class explicit-feedback signal below, per the confidence model's spec.
            val priorTopic = TopicResolver.resolve(priorPending.priorContext.utterance)
            confidenceService?.recordDemonstratedCompetence(userId, priorTopic, classification.signal)
            if (AffirmationClassifier.isAffirmation(utterance)) {
                confidenceService?.recordExplicitAffirmation(userId, priorTopic)
            }

            // Mood drift (Affect, not Confidence) — reuses the same already-computed
            // OutcomeSignal history, capped to a small rolling window.
            recentOutcomeSignals = (recentOutcomeSignals + classification.signal).takeLast(5)
        }

        // Mood override — a direct instruction ("stop being so formal") wins over drift and is
        // sticky for the rest of the session until changed again.
        moodState = MoodOverrideDetector.detect(utterance)?.let { MoodState(mood = it, overrideActive = true) }
            ?: if (moodState.overrideActive) moodState else moodState.copy(mood = MoodDrift.next(moodState.mood, recentOutcomeSignals))

        val ctx = CognitiveContext(
            utterance = utterance,
            sessionId = sessionId,
            roomId    = "foyer",
            userId    = userId,
            userEmail = userId,
            timestamp = java.time.Instant.now(),
            zoneId    = sessionZoneId,
            trace     = trace,
            modality  = modality,
            turnIndex = turnIndex,
            affect    = AffectConfig(mood = moodState.mood),
        )

        // Scaffold/trust state, loaded on every turn (previously only loaded by initSession
        // and the voice first-response fast path) so trust phase can condition the response
        // and phase-appropriate phrase filtering activates on the main turn path too.
        val scaffoldState = loadScaffoldState(userId)
        ctx.scaffoldState = scaffoldState
        ctx.trustPhase = trustPhaseIntToString(scaffoldState?.trustPhase)
        ctx.sessionCount = scaffoldState?.sessionCount ?: 0
        ctx.lastInteractionAt = scaffoldState?.lastInteractionAt

        attention.evaluate(ctx)

        if (ctx.attentionAction != AttentionAction.PROCESS) {
            return Pair(ChatResult(ctx.responseText, ctx.intent, ctx.comprehensionTier, "none"), trace)
        }

        // ── Comprehension ────────────────────────────────────────────────────
        val comprehensionStartNs = if (debug) System.nanoTime() else 0L
        comprehension.evaluate(ctx)
        // Full posture read for this turn (turn shape already set by Comprehension above via
        // classifyTextPathTurnShape; this adds surfaceEnergy/responsePressure). Computed for
        // every turn, independent of which branch/director ends up firing — see attunement below.
        ctx.postureSignals = computePostureSignals(ctx)
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

        // ── Reason (Branch execution — director) ─────────────────────────────
        val reasonStartNs = if (debug) System.nanoTime() else 0L
        branch.execute(ctx)

        // ── Script (retrieval) + Actor (composition) ─────────────────────────
        // The only two components allowed to touch EngramClient/LlmClient for this turn.
        val retrievedScript = script.run(ctx, ctx.branchResult?.retrieval ?: RetrievalIntent.None)
        val persona = script.persona(ctx.modality)
        // Posture read as a natural-language directive — computed independent of which branch
        // fired (ctx.postureSignals is set for every turn above), so it still reaches the actor
        // even when routing/comprehension picks the wrong branch for the turn's actual content.
        // Uses ctx.turnShape (Comprehension's text-path classifier), not
        // ctx.postureSignals.turnShape (a separate, voice-tuned classifier that defaults to FYI
        // rather than null and can disagree on the text path).
        val attunement = attunementDirective(ctx.turnShape, ctx.postureSignals?.surfaceEnergy ?: 0.0)
        // Per-topic confidence conditioner — resolved from *this turn's* utterance (a distinct,
        // forward-looking use from the backward-looking evidence resolution above). Epistemic
        // confidence only, never tone — see the class doc on Conditioners.
        val currentTopic = TopicResolver.resolve(ctx.utterance)
        val currentTopicPhase = currentTopic?.let {
            try { engramClient.getTopicConfidence(ctx.userEmail, it).phase } catch (_: Exception) { null }
        }
        val conditioners = Conditioners(
            modality         = ctx.modality,
            responseStrategy = ctx.branchResult?.responseStrategy ?: ResponseStrategy.SIMPLE,
            directive        = ctx.branchResult?.directive ?: "Respond naturally and briefly.",
            attunement       = attunement,
            persona          = persona.persona,
            selfDescription  = persona.selfDescription,
            topicConfidence  = topicConfidenceDirective(currentTopicPhase),
            mood             = moodDirective(moodState.mood),
        )
        val coverage = ctx.retrievalCoverage ?: RetrievalCoverage.NONE_NEEDED
        logger.info(
            "retrieval-coverage sessionId=$sessionId userId=$userId turnIndex=$turnIndex " +
            "coverage=${"%.2f".format(coverage.coverage)} activationMass=${"%.2f".format(coverage.activationMass)} " +
            "playFired=${coverage.playFired} conceptResolutionRatio=${"%.2f".format(coverage.conceptResolutionRatio)} " +
            "gaps=${if (coverage.gaps.isEmpty()) "none" else coverage.gaps.joinToString("|")} " +
            "conditioners=$conditioners"
        )
        ctx.actorResult = actor.compose(ctx.utterance, retrievedScript, conditioners)

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
            val (provider, model) = if (ctx.actorResult?.source == "llm") "anthropic" to ACTOR_MODEL_NAME else null to null
            trace.model.reasonProvider = provider
            trace.model.reasonModel = model

            trace.retrievalCoverage = RetrievalCoverageTrace(
                coverage = coverage.coverage,
                activationMass = coverage.activationMass,
                playFired = coverage.playFired,
                conceptResolutionRatio = coverage.conceptResolutionRatio,
                gaps = coverage.gaps,
            )

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

            // Graph mutations: SELECTED edge written this turn
            ctx.selectionResult?.let { selResult ->
                val isFirstResponse = selResult.phrase.moveType != null
                trace.graphMutations.selectedEdge = SelectedEdgeMutationTrace(
                    phraseUid      = selResult.phrase.uid,
                    userId         = userId,
                    sessionId      = sessionId,
                    turnIndex      = ctx.priorUtterances.size + 1,
                    branch         = if (!isFirstResponse) ctx.branchResult?.responseStrategy?.name else null,
                    compositeScore = selResult.compositeScore,
                )
            }

            // All scored candidates (populated by ResponseSelectionService when trace != null)
            ctx.selectionCandidates?.let { candidates ->
                val selectedId = ctx.selectionResult?.phrase?.uid
                trace.candidatePhrases = candidates.map { candidate ->
                    CandidatePhraseTrace(
                        phraseId       = candidate.phrase.uid,
                        phraseText     = candidate.phrase.text,
                        compositeScore = candidate.compositeScore,
                        scores         = candidate.scoreBreakdown,
                        selected       = candidate.phrase.uid == selectedId,
                    )
                }
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
            // ctx.trustPhase is the scaffold enum name ("ORIENTATION"...); trace.session.trustPhase
            // wants the underlying 1-4 int, so map it back rather than parsing the name as a number.
            trace.session.trustPhase = trustPhaseStringToInt(ctx.trustPhase)
            trace.session.turnCount = ctx.priorUtterances.size + 1
            trace.session.sessionAgeMs = 0 // SessionManager doesn't expose creation time to pipeline
        }

        stages.forEach { it.onCycleEnd(ctx) }

        logger.info(
            "turn sessionId=$sessionId userId=$userId intent=${ctx.intent} " +
            "branch=${branch::class.simpleName} source=${ctx.actorResult?.source ?: "degraded"} " +
            "utterance=\"${ctx.utterance}\" response=\"${ctx.responseText}\""
        )
        // TODO: log comprehensionTier and selectionResult.phraseId at DEBUG level for phrase-level tracing

        return Pair(
            ChatResult(ctx.responseText, ctx.intent, ctx.comprehensionTier, ctx.actorResult?.source ?: "degraded"),
            trace,
        )
    }

    private fun tier2ModelName(): String? {
        val model = selectTier2Model(llmClient) ?: return null
        return model.name.lowercase().replace('_', '-')
    }

    /** Loads scaffold state for [userId]. Failure is non-fatal — callers fall back to phase-neutral behavior. */
    private suspend fun loadScaffoldState(userId: String): ScaffoldState? = try {
        engramClient.getScaffoldState(userId)
    } catch (_: Exception) {
        null
    }

    /** Maps the persisted trust-phase int (1-4) to its scaffold enum name, or null when absent/unknown. */
    private fun trustPhaseIntToString(phase: Int?): String? = when (phase) {
        1 -> "ORIENTATION"
        2 -> "WORKING_RHYTHM"
        3 -> "CONTEXT"
        4 -> "UNDERSTANDING"
        else -> null
    }

    /** Inverse of [trustPhaseIntToString] — for debug-trace reporting. */
    private fun trustPhaseStringToInt(phase: String?): Int? = when (phase) {
        "ORIENTATION" -> 1
        "WORKING_RHYTHM" -> 2
        "CONTEXT" -> 3
        "UNDERSTANDING" -> 4
        else -> null
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
