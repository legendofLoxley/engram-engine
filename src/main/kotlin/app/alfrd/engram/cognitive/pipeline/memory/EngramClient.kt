package app.alfrd.engram.cognitive.pipeline.memory

import kotlinx.serialization.Serializable

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
 * A stored phrase retrieved from the memory graph (legacy in-memory model).
 */
data class Phrase(
    val id: String,
    val content: String,
    val source: String,
    val trustPhase: Int,
    val score: Double,
)

/**
 * A phrase retrieved via perspective-scoped graph traversal (User → TRUSTS → Source → ASSERTS → Phrase).
 *
 * @param uid         Stable vertex identifier.
 * @param text        The phrase text.
 * @param createdAt   Epoch-millis when the vertex was created.
 * @param updatedAt   Epoch-millis of most recent update.
 * @param scores      Score type → max value, aggregated across all ASSERTS paths that reached this phrase.
 * @param sourceCount Number of distinct Source→ASSERTS paths that reached this phrase (corroboration signal).
 * @param sourceTypes Deduplicated list of Source.type values from all paths.
 */
@Serializable
data class ScoredPhrase(
    val uid: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val scores: Map<String, Double>,
    val sourceCount: Int,
    val sourceTypes: List<String>,
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

    /**
     * Write phrase candidates to the memory graph, attributed to [userEmail]'s personal Source.
     *
     * Implementations that write to the real graph (e.g. [DatabaseEngramClient]) use [userEmail]
     * to locate the correct User vertex and wire Source → ASSERTS → Phrase. Implementations that
     * do not track per-user state (e.g. [InMemoryEngramClient]) may ignore [userEmail].
     */
    suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String = "")

    /**
     * Retrieve phrases visible to [userEmail] via perspective-scoped graph traversal:
     *   User(email) → TRUSTS → Source → ASSERTS → Phrase
     *
     * Phrases from multiple Sources reaching the same vertex are deduplicated; scores are
     * aggregated by taking the max value per score type across all ASSERTS paths.
     *
     * [concept] optionally filters by case-insensitive substring match on Phrase.text.
     * [limit] caps the result set (default 50). Results are ordered: max trust ↓, max salience ↓, sourceCount ↓.
     *
     * Returns an empty list — never throws — when [userEmail] is blank or no User vertex is found.
     */
    suspend fun queryPhrases(userEmail: String, concept: String? = null, limit: Int = 50): List<ScoredPhrase>

    /** Get onboarding progress for [userId], initialising a fresh state if none exists. */
    suspend fun getScaffoldState(userId: String): ScaffoldState

    /** Persist updated scaffold state for [userId]. */
    suspend fun updateScaffoldState(userId: String, state: ScaffoldState)

    /** Update the content of an existing phrase by its ID. */
    suspend fun amendPhrase(phraseId: String, newContent: String)
}
