package app.alfrd.engram.cognitive.pipeline

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
    val timestamp: Instant = Instant.now(),

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

    // ── Memory ────────────────────────────────────────────────────────────────
    var scaffoldState: Any? = null,
    var trustPhase: String? = null,
    var relevantPhrases: List<String>? = null,
    val priorUtterances: MutableList<String> = mutableListOf(),

    // ── Reason ────────────────────────────────────────────────────────────────
    var branchResult: BranchResult? = null,
    var responseIntent: IntentType? = null,
    var responsePhrases: List<String>? = null,
    var phaseTransitionEvidence: String? = null,

    // ── Expression ────────────────────────────────────────────────────────────
    var streamingPhases: List<String>? = null,
    var responseText: String = "",

    // ── Affect (static) ───────────────────────────────────────────────────────
    val affect: AffectConfig = AffectConfig(),

    // ── Debug trace (populated only for debug endpoint) ───────────────────────
    var trace: PipelineTrace? = null,
)
