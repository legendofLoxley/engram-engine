package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.FluxEvent
import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionResult
import app.alfrd.engram.cognitive.providers.TranscriptionResult
import app.alfrd.engram.model.ResponsePhrase
import java.time.Instant

/**
 * Mutable context object that flows through every stage of the CognitivePipeline
 * within a single utterance cycle.
 */
data class CognitiveContext(
    // ── Input ────────────────────────────────────────────────────────────────
    val utterance: String,
    val sessionId: String,
    val roomId: String = "foyer",
    val userId: String,
    val userEmail: String = "",
    val timestamp: Instant = Instant.now(),
    val zoneId: java.time.ZoneId? = null,
    /** Communication modality for this turn. Defaults to TEXT — an unset flag must never produce a voice identity. */
    val modality: Modality = Modality.TEXT,
    /** 1-indexed turn number within the session. Defaults to 1 so standalone/test construction behaves as turn 1. */
    val turnIndex: Int = 1,

    // ── STT / Flux events (pre-comprehension posture signals) ─────────────────
    /** STT transcription results for the current turn (used for speech_final detection). */
    val transcriptionResults: List<TranscriptionResult> = emptyList(),
    /** Deepgram Flux VAD event for the current turn; null when no Flux signal is available. */
    val fluxEvent: FluxEvent? = null,

    // ── Attention ─────────────────────────────────────────────────────────────
    var attentionAction: AttentionAction = AttentionAction.PROCESS,
    var attentionPriority: AttentionPriority = AttentionPriority.NORMAL,

    // ── Comprehension ─────────────────────────────────────────────────────────
    var intent: IntentType = IntentType.AMBIGUOUS,
    var intentConfidence: Double = 0.0,
    var comprehensionTier: Int = 1,
    var requiresMemory: Boolean = false,
    var memoryQueryHint: String? = null,
    var secondaryIntent: IntentType? = null,
    /** Text-path turn shape, null when no explicit signal was detected. */
    var turnShape: TurnShape? = null,

    // ── Memory ────────────────────────────────────────────────────────────────
    var scaffoldState: Any? = null,
    var trustPhase: String? = null,
    var sessionCount: Int = 0,
    var lastInteractionAt: Long? = null,
    var relevantPhrases: List<String>? = null,
    val priorUtterances: MutableList<String> = mutableListOf(),

    // ── Reason ────────────────────────────────────────────────────────────────
    var branchResult: BranchResult? = null,
    var responseIntent: IntentType? = null,
    var responsePhrases: List<ResponsePhrase>? = null,
    var phaseTransitionEvidence: String? = null,

    // ── Reason (Actor) ───────────────────────────────────────────────────────
    /** Set by the [Actor] stage — the sole writer of user-facing text. Null until it has run. */
    var actorResult: ActorResult? = null,

    // ── Expression ────────────────────────────────────────────────────────────
    var streamingPhases: List<String>? = null,
    var responseText: String = "",
    var streamingExpressionResult: StreamingExpressionResult? = null,

    // ── Affect (static) ───────────────────────────────────────────────────────
    val affect: AffectConfig = AffectConfig(),

    // ── Debug trace (populated only for debug endpoint) ───────────────────────
    var trace: PipelineTrace? = null,

    // ── Selection (populated by ResponseSelectionService during branch execution) ──
    var selectionResult: ResponseSelectionResult? = null,
    var selectionCandidatesConsidered: Int = 0,
    var selectionLatencyMs: Long = 0L,
    /** All scored candidates; only populated when trace != null (debug mode). */
    var selectionCandidates: List<ResponseSelectionResult>? = null,

    /** Per-turn retrieval quality readout — telemetry only. Set by [Script.run] on every turn. */
    var retrievalCoverage: RetrievalCoverage? = null,
)
