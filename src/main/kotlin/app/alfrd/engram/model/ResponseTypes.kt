package app.alfrd.engram.model

import kotlinx.serialization.Serializable

enum class BranchType { SOCIAL, ONBOARDING, QUESTION, TASK, CORRECTION, META, CLARIFICATION }

enum class TrustPhase { ORIENTATION, WORKING_RHYTHM, CONTEXT, UNDERSTANDING }

enum class ExpressionPhase { ACKNOWLEDGE, BRIDGE, PARTIAL, INTERIM, SYNTHESIS }

enum class ResponseCategory { GREETING, ACKNOWLEDGMENT, BRIDGE, SCAFFOLD_QUESTION, SIGN_OFF, FILLER, CLARIFICATION, DECLINE }

enum class OutcomeSignal { ENGAGED, EXPANDED, CORRECTED, DISENGAGED, NEUTRAL }

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
    val branch: String,
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
