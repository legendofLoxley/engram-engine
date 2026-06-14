package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.model.BranchType

/**
 * Configurable composite scoring weights per branch.
 * Each map entry: dimension name → weight. Weights should sum to 1.0.
 *
 * A null [BranchType] resolves to [FIRST_RESPONSE] — the spec's default formula weights
 * used for posture-governed first-response selection (branch-agnostic path).
 */
object SelectionWeights {

    data class WeightConfig(
        val freshness: Double,
        val contextualFit: Double,
        val communicationFit: Double,
        val phaseAppropriateness: Double,
        val effectiveness: Double,
    ) {
        fun toMap(): Map<String, Double> = mapOf(
            "freshness" to freshness,
            "contextualFit" to contextualFit,
            "communicationFit" to communicationFit,
            "phaseAppropriateness" to phaseAppropriateness,
            "effectiveness" to effectiveness,
        )
    }

    /**
     * Default weights for the first-response (posture-governed) path, matching
     * the spec's composite formula: freshness×0.20 + contextual×0.25 + comms×0.20 +
     * phase×0.15 + effectiveness×0.20.
     */
    val FIRST_RESPONSE = WeightConfig(0.20, 0.25, 0.20, 0.15, 0.20)

    private val weights: Map<BranchType, WeightConfig> = mapOf(
        BranchType.SOCIAL       to WeightConfig(0.25, 0.20, 0.20, 0.15, 0.20),
        BranchType.QUESTION     to WeightConfig(0.20, 0.30, 0.20, 0.10, 0.20),
        BranchType.TASK         to WeightConfig(0.15, 0.20, 0.30, 0.15, 0.20),
        BranchType.CORRECTION   to WeightConfig(0.15, 0.25, 0.20, 0.15, 0.25),
        BranchType.META         to WeightConfig(0.20, 0.30, 0.20, 0.10, 0.20),
        BranchType.CLARIFICATION to WeightConfig(0.15, 0.20, 0.15, 0.30, 0.20),
    )

    /** Returns weights for [branch], or [FIRST_RESPONSE] when [branch] is null. */
    fun forBranch(branch: BranchType?): WeightConfig =
        if (branch == null) FIRST_RESPONSE else weights[branch] ?: weights[BranchType.SOCIAL]!!
}

