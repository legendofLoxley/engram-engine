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
    var hermesDelegation: HermesDelegationTrace? = null,
    var documentIntent: HermesDocumentIntentTrace? = null,
)

/**
 * Present only on a turn where the Director's document-intent classifier
 * (`HermesDocumentIntentDirector`) actually ran — i.e. the marker-check regex didn't already
 * match and a dispatcher was wired. Records the decision, whether the (local) model was actually
 * called, its latency, and — when the decision was rejected or downgraded — why, so a caller can
 * distinguish "the model proposed nothing," "the model proposed something code-side validation
 * rejected," and "a real delegation/clarification resulted" without re-deriving it from logs.
 */
@Serializable
data class HermesDocumentIntentTrace(
    val action: String,
    val targetDocument: String? = null,
    val candidateDocuments: List<String> = emptyList(),
    val modelCalled: Boolean = false,
    val latencyMs: Long = 0,
    val rejectedReason: String? = null,
    val usedPendingClarification: Boolean = false,
)

/**
 * Present only on a turn that just issued a real Hermes assignment (see
 * `app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatcher`). [assignmentId] is
 * the correlation key a caller (the WebUI runner adapter) polls
 * `/debug/hermes-assignment/{assignmentId}` with to learn the outcome and deliver it into the
 * originating conversation — this trace field is how that caller learns the id exists at all,
 * since the assignment is issued fire-and-forget with no other return path out of this turn.
 */
@Serializable
data class HermesDelegationTrace(
    val assignmentId: String,
    val task: String,
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
    /** How many turns of recent conversation were in the reply prompt (0 when the prompt budget dropped them). */
    var recentTurnsInPrompt: Int = 0,
    /** How many of those were read back from the durable ledger rather than made in this session — non-zero after a restart or a new session. */
    var recentTurnsRestoredFromLedger: Int = 0,
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
data class HorizonItemTrace(
    val category: String,
    val text: String,
    val status: String?,
    val surfacing: String,
    /** 0 to 1, from [why]. */
    val score: Double = 0.0,
    /** What contributed to the score, strongest first, as `kind=value (detail)` — the answer to "why is this in view". */
    val why: List<String> = emptyList(),
    val kind: String? = null,
    val container: String? = null,
)

/** One nudge set this turn: what it is on, where it came from (`touched`, `container`, `entity` or `text`), how strong, and a short label. */
@Serializable
data class NudgedItemTrace(val uid: String, val source: String, val weight: Double, val label: String? = null)

/** How many nudges one turn set, by source — see [app.alfrd.engram.cognitive.pipeline.horizon.HorizonSeeder.Seeded]. */
@Serializable
data class NudgeTrace(val touched: Int, val mentionedContainers: Int, val mentionedEntities: Int, val mentionedItems: Int, val pruned: Int)

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
    /** True when this turn opened the "what have you been up to?" question after a long silence. */
    var silenceQuestionAsked: Boolean = false,
    /** Nudges set this turn: what was touched, and what the message mentioned (projects, entities, items), and how many old ones were pruned. */
    var nudges: NudgeTrace? = null,
    /** The nudges themselves — what the message made a candidate for the Horizon, and why. Recall of something they once said shows up here. */
    var nudged: List<NudgedItemTrace> = emptyList(),
    /** The pinned focus and zoom the Horizon was assembled under; null when zoomed out. */
    var focus: String? = null,
    var zoom: String? = null,
    /** The user's containers as they appeared in the Horizon: `name (open, inView)`. */
    var containers: List<String> = emptyList(),
    var propagationOutcome: String? = null,
    var propagationEdges: List<RelevanceEdgeTrace> = emptyList(),
    var propagateLatencyMs: Long = 0,
    var assembleOutcome: String? = null,
    var assembleLatencyMs: Long = 0,
    var openCandidatesBeforePropagation: List<PropagationCandidateTrace> = emptyList(),
    var horizonAfterPropagation: List<HorizonItemTrace> = emptyList(),
    var horizonAfterBudget: List<HorizonItemTrace> = emptyList(),
    var promptOmissions: List<String> = emptyList(),
    /** How the Horizon was laid out in the reply prompt: each heading with how many items sit under it, in prompt order. */
    var promptGroups: List<String> = emptyList(),
    /** The orientation lines that head the Horizon block (focus, zoom, what else they have going); null when there was nothing to say. */
    var orientation: String? = null,
    /** Deadline items in view that the reply had already brought up recently, and so were told not to raise again unprompted. */
    var quietDeadlines: List<String> = emptyList(),
    var promptBudgetOutcome: String? = null,
    var finalSystemPromptSent: String? = null,
    var finalUserPromptSent: String? = null,
)
