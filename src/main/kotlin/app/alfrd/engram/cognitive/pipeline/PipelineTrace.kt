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
    var horizonCycle: HorizonCycleTrace? = null,
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

/** One write [app.alfrd.engram.cognitive.pipeline.HorizonCycleCoordinator] confirmed or attempted this cycle — mirrors [app.alfrd.engram.cognitive.pipeline.MutationOutcome]. */
@Serializable
data class MutationOutcomeTrace(val kind: String, val phraseUid: String?, val applied: Boolean, val textSummary: String? = null)

/** One `relevant_to` edge propagation created — mirrors [app.alfrd.engram.cognitive.pipeline.horizon.RelevanceEdgeSummary]. */
@Serializable
data class RelevanceEdgeTrace(val fromPhraseUid: String, val toPhraseUid: String, val strength: Double)

/** One propagation candidate — mirrors [app.alfrd.engram.cognitive.pipeline.horizon.PropagationCandidate]. */
@Serializable
data class PropagationCandidateTrace(val phraseUid: String, val text: String, val cycleSeq: Long)

/** One assembled Horizon item, flattened for tracing — mirrors [app.alfrd.engram.cognitive.pipeline.horizon.HorizonItem]. */
@Serializable
data class HorizonItemTrace(val category: String, val text: String, val status: String?, val surfacing: String)

/**
 * Controlled debug evidence for one Horizon cycle — populated only when [PipelineTrace] itself is
 * (i.e. `/cognitive/chat/debug` and `/debug/converse`; never the plain `/cognitive/chat` response).
 * Deliberately carries three distinct stages, not one conflated view:
 * [openCandidatesBeforePropagation] is what propagation actually had to work with (the bounded
 * open-item pool, independent of the response-prompt budget); [horizonAfterPropagation] is
 * `assemble()`'s output *before* the prompt-budget ladder touches it; [horizonAfterBudget] plus
 * [promptOmissions]/[promptBudgetOutcome] is what actually survived trimming.
 * [finalSystemPromptSent]/[finalUserPromptSent] are the literal strings sent to the Actor's LLM
 * call, captured after all budget handling — not just the Horizon fragment of it.
 */
@Serializable
data class HorizonCycleTrace(
    var cycleSeq: Long? = null,
    var allocationFailed: Boolean = false,
    var interpretOutcome: String? = null,
    var interpretLatencyMs: Long = 0,
    var mutationOutcomes: List<MutationOutcomeTrace> = emptyList(),
    var propagationOutcome: String? = null,
    var propagationEdges: List<RelevanceEdgeTrace> = emptyList(),
    var propagateLatencyMs: Long = 0,
    var assembleOutcome: String? = null,
    var assembleLatencyMs: Long = 0,
    var openCandidatesBeforePropagation: List<PropagationCandidateTrace> = emptyList(),
    var horizonAfterPropagation: List<HorizonItemTrace> = emptyList(),
    var horizonAfterBudget: List<HorizonItemTrace> = emptyList(),
    var promptOmissions: List<String> = emptyList(),
    var promptBudgetOutcome: String? = null,
    var finalSystemPromptSent: String? = null,
    var finalUserPromptSent: String? = null,
)
