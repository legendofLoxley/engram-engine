package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.ResponseCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ResponseSelectionQuery(
    val branch: BranchType,
    val expressionPhase: ExpressionPhase,
    val category: ResponseCategory? = null,
    @Transient val context: CognitiveContext? = null,
    val limit: Int = 1,
    val exclude: Set<String>? = null,
    // Serializable fields for API usage (context can't be serialized directly)
    val userId: String = "",
    val sessionId: String = "",
)
