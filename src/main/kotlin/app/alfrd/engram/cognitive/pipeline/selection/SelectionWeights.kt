package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.model.BranchType

/**
 * Configurable composite scoring weights per branch.
 * Each map entry: dimension name → weight. Weights should sum to 1.0.
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

    private val weights: Map<BranchType, WeightConfig> = mapOf(
        BranchType.SOCIAL to WeightConfig(0.25, 0.20, 0.20, 0.15, 0.20),
        BranchType.ONBOARDING to WeightConfig(0.15, 0.20, 0.15, 0.30, 0.20),
        BranchType.QUESTION to WeightConfig(0.20, 0.30, 0.20, 0.10, 0.20),
        BranchType.TASK to WeightConfig(0.15, 0.20, 0.30, 0.15, 0.20),
        BranchType.CORRECTION to WeightConfig(0.15, 0.25, 0.20, 0.15, 0.25),
        BranchType.META to WeightConfig(0.20, 0.30, 0.20, 0.10, 0.20),
        BranchType.CLARIFICATION to WeightConfig(0.15, 0.20, 0.15, 0.30, 0.20),
    )

    fun forBranch(branch: BranchType): WeightConfig =
        weights[branch] ?: weights[BranchType.SOCIAL]!!
}
