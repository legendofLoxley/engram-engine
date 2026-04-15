package app.alfrd.engram.cognitive.pipeline

enum class AttentionAction { PROCESS, IGNORE, INTERRUPT, CONTEXT_SWITCH }

enum class AttentionPriority { HIGH, NORMAL, LOW }

enum class EnergyLevel { HIGH, MEDIUM, LOW }

enum class IntentType { ONBOARDING, TASK, QUESTION, CORRECTION, SOCIAL, META, CLARIFICATION, AMBIGUOUS }

enum class ResponseStrategy { SIMPLE, COMPLEX, EMOTIONAL, SOCIAL }

data class AffectConfig(
    val temperament: String = "composed",
    val warmth: Double = 0.7,
    val dryness: Double = 0.4,
    val energy: EnergyLevel = EnergyLevel.MEDIUM,
)

data class BranchResult(
    val content: String,
    val responseStrategy: ResponseStrategy,
    val memoryWrites: List<String>? = null,
    val phaseTransitionEvidence: String? = null,
)
