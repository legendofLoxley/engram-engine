package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.PostureMoveType
import app.alfrd.engram.model.ResponseCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Query contract for the five-stage response selection pipeline.
 *
 * Two query paths share the same scoring engine:
 *
 * **First-response** (posture-governed, branch-agnostic):
 *   - Set [moveType] to the computed [PostureMoveType]; leave [branch] null.
 *   - Filter: `WHERE moveType = :moveType AND expressionPhase = FIRST_RESPONSE`.
 *   - Scoring weights use the FIRST_RESPONSE config from [SelectionWeights].
 *
 * **Post-comprehension** (branch-governed):
 *   - Set [branch] to the resolved [BranchType]; leave [moveType] null.
 *   - Filter: branchAffinity → phaseAffinity gate → expressionPhase → optional category.
 *   - Scoring weights are per-branch from [SelectionWeights].
 *
 * Exactly one of [branch] / [moveType] should be non-null per query.
 */
@Serializable
data class ResponseSelectionQuery(
    val branch: BranchType? = null,
    val moveType: PostureMoveType? = null,
    val expressionPhase: ExpressionPhase,
    val category: ResponseCategory? = null,
    @Transient val context: CognitiveContext? = null,
    val limit: Int = 1,
    val exclude: Set<String>? = null,
    // Serializable fields for API usage (context can't be serialized directly)
    val userId: String = "",
    val sessionId: String = "",
)
