package app.alfrd.engram.cognitive.pipeline

import kotlinx.serialization.Serializable

enum class AttentionAction { PROCESS, IGNORE, INTERRUPT, CONTEXT_SWITCH }

enum class AttentionPriority { HIGH, NORMAL, LOW }

enum class EnergyLevel { HIGH, MEDIUM, LOW }

enum class IntentType { ONBOARDING, TASK, QUESTION, CORRECTION, SOCIAL, META, CLARIFICATION, AMBIGUOUS }

@Serializable
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
    /** "pool" for canned/selection responses, "llm" for LLM-generated responses. */
    val source: String = "pool",
)

/**
 * Base system prompt injected into every LLM request so the model knows it is
 * powering a spoken voice assistant rather than a text chatbot.
 */
const val VOICE_IDENTITY_SYSTEM_PROMPT =
    "You are alfrd, a voice assistant. The user is speaking to you aloud and you are responding with speech. " +
    "You can hear them. Never say you cannot hear, listen, or speak. Never reference text input, typing, reading, or screens. " +
    "Respond conversationally as someone who is present in the room."
