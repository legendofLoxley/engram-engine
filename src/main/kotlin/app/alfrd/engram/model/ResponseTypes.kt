package app.alfrd.engram.model

import kotlinx.serialization.Serializable

enum class BranchType { SOCIAL, ONBOARDING, QUESTION, TASK, CORRECTION, META, CLARIFICATION }

enum class TrustPhase { ORIENTATION, WORKING_RHYTHM, CONTEXT, UNDERSTANDING }

enum class ExpressionPhase { FIRST_RESPONSE, BRIDGE, PARTIAL, INTERIM, SYNTHESIS }

enum class TurnShape {
    QUESTION, CORRECTION, TOPIC_OPENER, DISCLOSURE, FYI, CONTINUATION,
    FRAGMENTED, BARGE_IN, TASK_REQUEST, COLLABORATIVE
}

/** Move-type for first-response pool phrases (RECEIPT … MULTI_UTTERANCE_HOLD). Null for all other pools. */
enum class PostureMoveType {
    RECEIPT, ORIENT, HOLD, REPAIR, PROBE, COMMIT, WAIT, MISREAD_RECOVERY, YIELD, MULTI_UTTERANCE_HOLD
}

/** 18 response categories — first-response posture pool (10) + session/post-comprehension pools (8). */
enum class ResponseCategory {
    // First-response posture pool
    RECEIPT, ORIENT, HOLD, REPAIR, PROBE, COMMIT, WAIT, MISREAD_RECOVERY, YIELD, MULTI_UTTERANCE_HOLD,
    // Session-level & post-comprehension pools
    GREETING, SIGN_OFF, BRIDGE, SCAFFOLD_QUESTION, FILLER, CLARIFICATION, DECLINE, SYNTHESIS
}

enum class OutcomeSignal { ENGAGED, EXPANDED, CORRECTED, DISENGAGED, NEUTRAL }

/** Inclusive energy/pressure range used in [PostureAffinity] (values 0.0–1.0). */
@Serializable
data class EnergyRange(val min: Double, val max: Double)

/**
 * Posture-selection constraints for first-response pool phrases.
 *
 * @property turnShapes             Set of [TurnShape] labels this phrase fits; null for post-comprehension phrases.
 * @property surfaceEnergyRange     Conversation energy window (0.0 = inert, 1.0 = high intensity); null if unconstrained.
 * @property responsePressureRange  Response-pressure window (0.0 = none, 1.0 = urgent); null if unconstrained.
 */
@Serializable
data class PostureAffinity(
    val turnShapes: Set<TurnShape>?,
    val surfaceEnergyRange: EnergyRange?,
    val responsePressureRange: EnergyRange?,
)

@Serializable
data class ResponsePhrase(
    val uid: String,
    val text: String,
    val hash: String,
    val visibility: String = "internal",
    val createdAt: Long,
    val updatedAt: Long,
    val branchAffinity: Set<String>,
    val phaseAffinity: Set<String>,
    val expressionPhase: String,
    val category: String,
    val moveType: String? = null,           // non-null for first-response posture pool only
    val postureAffinity: String? = null,    // JSON-serialized PostureAffinity; non-null for first-response pool
    val variants: List<String>? = null,
    val requiresInterpolation: Boolean = false,
    val interpolationKeys: Set<String>? = null,
)

@Serializable
data class SelectedEdge(
    val phraseUid: String,
    val sessionId: String,
    val userId: String,
    val turnIndex: Int,
    val branch: String? = null,     // null for first-response selection; non-null for post-comprehension
    val moveType: String? = null,   // null for post-comprehension selection; non-null for first-response
    val compositeScore: Double,
    val scoreBreakdown: Map<String, Double>,
    val timestamp: Long,
)

@Serializable
data class OutcomeEdge(
    val phraseUid: String,
    val sessionId: String,
    val userId: String,
    val turnIndex: Int,
    val signal: String,
    val contextSnapshot: String,
    val timestamp: Long,
)
