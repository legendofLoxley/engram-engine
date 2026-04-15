package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.model.ResponsePhrase
import kotlinx.serialization.Serializable

@Serializable
data class ResponseSelectionResult(
    val phrase: ResponsePhrase,
    val compositeScore: Double,
    val scoreBreakdown: Map<String, Double>,
    val interpolated: String,
)
