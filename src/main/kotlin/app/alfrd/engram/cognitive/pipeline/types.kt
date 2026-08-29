package app.alfrd.engram.cognitive.pipeline

import kotlinx.serialization.Serializable

enum class AttentionAction { PROCESS, IGNORE, INTERRUPT, CONTEXT_SWITCH }

enum class AttentionPriority { HIGH, NORMAL, LOW }

enum class EnergyLevel { HIGH, MEDIUM, LOW }

enum class IntentType { TASK, QUESTION, CORRECTION, SOCIAL, META, CLARIFICATION, AMBIGUOUS }

@Serializable
enum class ResponseStrategy { SIMPLE, COMPLEX, EMOTIONAL, SOCIAL }

data class AffectConfig(
    val temperament: String = "composed",
    val warmth: Double = 0.7,
    val dryness: Double = 0.4,
    val energy: EnergyLevel = EnergyLevel.MEDIUM,
)

/**
 * Output of a branch (the director): conditioners for the actor, never user-facing text.
 * [retrieval] tells the [Script] stage what grounding material to fetch, if any.
 * [directive] is a free-form instruction to the [Actor] — also never shown to the user.
 */
data class BranchResult(
    val responseStrategy: ResponseStrategy,
    val retrieval: RetrievalIntent = RetrievalIntent.None,
    val directive: String = "Respond naturally and briefly.",
    val memoryWrites: List<String>? = null,
    val phaseTransitionEvidence: String? = null,
)

/**
 * Base system prompt injected into every LLM request so the model knows it is
 * powering a spoken voice assistant rather than a text chatbot.
 */
const val VOICE_IDENTITY_SYSTEM_PROMPT =
    "You are alfrd, a voice assistant. The user is speaking to you aloud and you are responding with speech. " +
    "You can hear them. Never say you cannot hear, listen, or speak. Never reference text input, typing, reading, or screens. " +
    "Respond conversationally as someone who is present in the room."

/**
 * System prompt for the text modality — the counterpart to [VOICE_IDENTITY_SYSTEM_PROMPT].
 * Kept as a distinct constant (not derived from the voice prompt) so the two identities can
 * never drift into claiming the wrong sense.
 */
const val TEXT_IDENTITY_SYSTEM_PROMPT =
    "You are alfrd, a personal assistant. The user is chatting with you over text. " +
    "You cannot hear or speak aloud — you read and write messages. Never say you can hear them, are listening, " +
    "or are speaking aloud. Never reference audio, a microphone, or being physically present in the room. " +
    "Respond naturally in writing."

/** Single source of truth for identity-prompt selection — used by [Actor]. */
fun identitySystemPrompt(modality: Modality): String =
    if (modality == Modality.VOICE) VOICE_IDENTITY_SYSTEM_PROMPT else TEXT_IDENTITY_SYSTEM_PROMPT

/**
 * One-line relationship-phase calibration appended to a branch's directive — never user-facing.
 * Tells the actor how much familiarity is earned so far. Null/unknown trust phase (including
 * scaffold-state read failure) calibrates the same as ORIENTATION: cold start stays measured,
 * never presumes closeness.
 */
fun trustPhaseCalibration(trustPhase: String?): String = when (trustPhase) {
    "WORKING_RHYTHM" -> "Relationship phase: still getting acquainted — friendly but not yet casual."
    "CONTEXT" -> "Relationship phase: established rapport — comfortable referencing shared history, casual tone is fine."
    "UNDERSTANDING" -> "Relationship phase: deep familiarity — talk like you know them well, casual and warm."
    else -> "Relationship phase: early — keep it warm but measured, don't presume familiarity yet."
}
