package app.alfrd.engram.cognitive.pipeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PipelineTrace(
    val comprehension: ComprehensionTrace = ComprehensionTrace(),
    val routing: RoutingTrace = RoutingTrace(),
    val session: SessionTrace = SessionTrace(),
    val latencyBreakdown: LatencyBreakdownTrace = LatencyBreakdownTrace(),
    val model: ModelTrace = ModelTrace(),
    var responseSelection: ResponseSelectionTrace? = null,
    var candidatePhrases: List<CandidatePhraseTrace> = emptyList(),
    var graphMutations: GraphMutationsTrace = GraphMutationsTrace(),
    var retrievalCoverage: RetrievalCoverageTrace? = null,
)

@Serializable
data class ComprehensionTrace(
    var tier: Int = 1,
    var tierOneRuleMatched: String? = null,
    var tierOneConfidence: Double? = null,
    var tierTwoFired: Boolean = false,
    var tierTwoResult: String? = null,
)

@Serializable
data class RoutingTrace(
    var intentType: String = "",
    var route: String = "",
    var confidence: Double = 0.0,
    var secondaryIntent: String? = null,
    var branchSelected: String = "",
)

@Serializable
data class SessionTrace(
    var scaffoldState: JsonElement? = null,
    var trustPhase: Int? = null,
    var turnCount: Int = 0,
    var sessionAgeMs: Long = 0,
)

@Serializable
data class LatencyBreakdownTrace(
    var comprehensionMs: Long = 0,
    var routingMs: Long = 0,
    var memoryMs: Long? = null,
    var reasonMs: Long = 0,
    var expressionMs: Long = 0,
    var totalPipelineMs: Long = 0,
)

@Serializable
data class ModelTrace(
    var reasonProvider: String? = null,
    var reasonModel: String? = null,
    var comprehensionModel: String? = null,
)

@Serializable
data class ResponseSelectionTrace(
    val phraseId: String,
    val phraseText: String,
    val interpolatedText: String,
    val strategy: ResponseStrategy,
    val compositeScore: Double,
    val scores: Map<String, Double>,
    val candidatesConsidered: Int,
    val selectionLatencyMs: Long,
)

@Serializable
data class CandidatePhraseTrace(
    val phraseId: String,
    val phraseText: String,
    val compositeScore: Double,
    val scores: Map<String, Double>,
    val selected: Boolean,
)

@Serializable
data class GraphMutationsTrace(
    var selectedEdge: SelectedEdgeMutationTrace? = null,
    var outcomeEdge: OutcomeEdgeMutationTrace? = null,
)

@Serializable
data class SelectedEdgeMutationTrace(
    val phraseUid: String,
    val userId: String,
    val sessionId: String,
    val turnIndex: Int,
    val branch: String?,
    val compositeScore: Double,
)

@Serializable
data class OutcomeEdgeMutationTrace(
    val phraseUid: String,
    val userId: String,
    val sessionId: String,
    val signal: String,
    val turnIndex: Int,
)

/**
 * Debug-trace mirror of [RetrievalCoverage] — the per-turn retrieval quality readout,
 * surfaced alongside the conditioners the same turn handed to the actor.
 */
@Serializable
data class RetrievalCoverageTrace(
    val coverage: Double,
    val activationMass: Double,
    val playFired: Boolean,
    val conceptResolutionRatio: Double,
    val gaps: List<String>,
)
