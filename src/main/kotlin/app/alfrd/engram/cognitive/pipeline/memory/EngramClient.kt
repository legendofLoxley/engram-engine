package app.alfrd.engram.cognitive.pipeline.memory

/** Scaffold categories in priority order for onboarding. */
enum class PhraseCategory {
    IDENTITY, EXPERTISE, PREFERENCE, ROUTINE, RELATIONSHIP, CONTEXT
}

/**
 * An atomic phrase extracted from user input, ready for ingestion into the memory graph.
 */
data class PhraseCandidate(
    val content: String,
    val source: String,
    val category: PhraseCategory,
)

/**
 * A stored phrase retrieved from the memory graph.
 */
data class Phrase(
    val id: String,
    val content: String,
    val source: String,
    val trustPhase: Int,
    val score: Double,
)

/**
 * A single phase-transition event recorded in the scaffold history.
 */
data class ScaffoldPhaseTransition(
    val from: String,
    val to: String,
    val timestamp: Long,
    val evidence: String,
)

/**
 * Snapshot of a user's onboarding progress.
 *
 * @param trustPhase             Current trust phase (1–4).
 * @param answeredCategories     Categories the user has already provided information for.
 * @param activeScaffoldQuestion The question currently being asked (drives Comprehension Rule 0).
 * @param sessionCount           Total number of sessions this user has started.
 * @param lastInteractionAt      Epoch-millis timestamp of the user's most recent interaction.
 * @param phaseTransitions       Ordered history of phase-transition events for this user.
 */
data class ScaffoldState(
    val trustPhase: Int = 1,
    val answeredCategories: Set<PhraseCategory> = emptySet(),
    val activeScaffoldQuestion: String? = null,
    val sessionCount: Int = 0,
    val lastInteractionAt: Long? = null,
    val phaseTransitions: List<ScaffoldPhaseTransition> = emptyList(),
)

/**
 * Contract for all memory operations used by the cognitive pipeline.
 *
 * Implementations must degrade gracefully — a failure here should never crash a branch.
 */
interface EngramClient {

    /** Break [text] into atomic phrase candidates. [context] is prior utterances for disambiguation. */
    suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate>

    /** Write phrase candidates to the memory graph. */
    suspend fun ingest(candidates: List<PhraseCandidate>)

    /**
     * Retrieve relevant phrases by concept or keyword.
     * [userId] filters results to phrases owned by that user; blank means no filter (dev/test only).
     */
    suspend fun queryPhrases(concept: String, userId: String = ""): List<Phrase>

    /** Get onboarding progress for [userId], initialising a fresh state if none exists. */
    suspend fun getScaffoldState(userId: String): ScaffoldState

    /** Persist updated scaffold state for [userId]. */
    suspend fun updateScaffoldState(userId: String, state: ScaffoldState)

    /** Update the content of an existing phrase by its ID. */
    suspend fun amendPhrase(phraseId: String, newContent: String)
}
