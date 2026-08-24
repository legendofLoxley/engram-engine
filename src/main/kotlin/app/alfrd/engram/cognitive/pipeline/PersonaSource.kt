package app.alfrd.engram.cognitive.pipeline

/**
 * Persona + self-description conditioner handed to the [Actor]. Both fields come from the
 * same source deliberately — the actor's account of what it can remember and which modality
 * it's on must never drift from (or contradict) its persona framing.
 */
data class PersonaConditioner(val persona: String, val selfDescription: String)

/**
 * Supplies the actor's persona and self-description as a retrieved conditioner rather than a
 * string baked into [Actor]'s prompt-building code. [Script] calls this — persona is
 * "retrieval" in the same sense phrase-pool and memory-graph material are, it just happens to
 * be constant-per-modality today. Swapping in a data-backed implementation later (e.g. a
 * persona phrase stored in the memory graph) requires no change to [Actor] or [CognitivePipeline].
 */
interface PersonaSource {
    fun describe(modality: Modality): PersonaConditioner
}

/**
 * Default persona source: reuses the existing modality-correct identity prompts from
 * [identitySystemPrompt] for [PersonaConditioner.persona], and adds a self-description that
 * satisfies two invariants no matter what:
 *   - Never denies cross-session memory the system actually has (it persists user data across
 *     sessions via the memory graph, regardless of how new the relationship is).
 *   - Never claims a modality it isn't running on (branches strictly on [modality]).
 */
class DefaultPersonaSource : PersonaSource {

    override fun describe(modality: Modality): PersonaConditioner {
        val persona = identitySystemPrompt(modality)
        val selfDescription = buildString {
            append(
                "You have persistent memory of this user across sessions via the memory graph — " +
                "never claim to be meeting them for the first time or to have no memory of past " +
                "conversations. "
            )
            append(
                if (modality == Modality.VOICE) {
                    "You are on a live voice call with them right now: you can hear them and they can hear you."
                } else {
                    "You are exchanging text messages with them right now: you cannot hear or speak aloud."
                }
            )
        }
        return PersonaConditioner(persona = persona, selfDescription = selfDescription)
    }
}
