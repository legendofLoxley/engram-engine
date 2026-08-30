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
 * Per-topic confidence phases — regions of accumulated confidence on a given topic, per the
 * Onboarding Scaffold Specification §4 (amended). Deliberately a separate enum from
 * [app.alfrd.engram.model.TrustPhase]: that enum still governs the older, relationship-wide
 * dormancy-regression/phrase-pool-phase-gating mechanism ([app.alfrd.engram.cognitive.pipeline.scaffold.TrustPhaseTransitionService]),
 * which this task does not touch. Reusing it here would re-couple the two mechanisms this
 * redesign is meant to separate.
 */
enum class ConfidencePhase { ORIENTATION, WORKING_RHYTHM, CONTEXT, UNDERSTANDING }

/** What kind of evidence moved a [TopicConfidence] score. */
@Serializable
enum class ConfidenceEvidenceKind { COMPETENCE, FEEDBACK_AFFIRMED, CORRECTION_CONFIRMED }

/**
 * A single evidence event that moved a [TopicConfidence] score. [delta] is always >= 0 —
 * confidence never decreases from evidence; see [TopicConfidence.hasUnresolvedContradiction]
 * for the only non-monotonic state.
 */
@Serializable
data class ConfidenceEvidenceEntry(
    val kind: ConfidenceEvidenceKind,
    val delta: Double,
    val timestamp: Long,
    val note: String = "",
)

/**
 * alfrd's earned confidence in its own memory/knowledge on a specific topic — per-topic,
 * monotonic (score only ever increases), evidence-driven. Attached to a [Concept] vertex on the
 * graph via a `CONFIDENT_IN` edge from the User vertex (see [DatabaseEngramClient]), not a flat
 * per-user field — per Onboarding Scaffold Specification §4: trust/confidence lives "attached to
 * the same Concepts the knowledge itself is organized around, not bolted on as a separate global
 * score."
 *
 * @param hasUnresolvedContradiction The only non-monotonic state: true while a correction has
 *   been detected but not yet confirmed written. Resolves upward (cleared + score raised) via
 *   [ConfidenceEvidenceKind.CORRECTION_CONFIRMED] — never downward.
 */
data class TopicConfidence(
    val topic: String,
    val score: Double = 0.0,
    val phase: ConfidencePhase = ConfidencePhase.ORIENTATION,
    val hasUnresolvedContradiction: Boolean = false,
    val evidence: List<ConfidenceEvidenceEntry> = emptyList(),
    val updatedAt: Long = 0L,
)

/**
 * A single recorded turn in the durable, searchable episodic conversation log — structurally
 * separate from the Phrase/Concept fact graph and its confidence/trust scoring (see
 * [DatabaseEngramClient] for how `Utterance` vertices are chained via `FOLLOWS`). Unlike
 * [Phrase], nothing here is deduplicated by content hash — every literal occurrence of a turn,
 * including exact repeats across sessions, is preserved in order.
 *
 * @param role "user" or "alfrd".
 */
@Serializable
data class EpisodicTurn(
    val uid: String,
    val sessionId: String,
    val userId: String,
    val turnIndex: Int,
    val role: String,
    val text: String,
    val createdAt: Long,
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

    /** Get per-topic confidence for [userEmail] on [topic], initialising a fresh value if none exists. */
    suspend fun getTopicConfidence(userEmail: String, topic: String): TopicConfidence

    /** Persist updated per-topic confidence for [userEmail] on [topic]. */
    suspend fun updateTopicConfidence(userEmail: String, topic: String, confidence: TopicConfidence)

    /**
     * Append one conversational turn (both the user's utterance and alfrd's response) to the
     * durable episodic log, chained onto the session's existing turns via `FOLLOWS`.
     *
     * Structurally separate from [ingest]/[queryPhrases] — never routes through the Phrase/Concept
     * fact graph. Implementations must degrade gracefully: a failure here must never propagate to
     * the caller (mirrors the fire-and-forget contract of [app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService]).
     */
    suspend fun appendEpisodicTurn(
        sessionId: String,
        userId: String,
        turnIndex: Int,
        userUtterance: String,
        alfrdResponse: String,
    )

    /**
     * Retrieve episodic turns for [userId], most-recent-last, optionally bounded by
     * [sinceMillis]/[untilMillis] (epoch-millis, inclusive) and filtered by a case-insensitive
     * substring match on turn text via [keyword]. Returns an empty list — never throws — when
     * [userId] is blank or no turns are found.
     */
    suspend fun getEpisodicLog(
        userId: String,
        sinceMillis: Long? = null,
        untilMillis: Long? = null,
        keyword: String? = null,
        limit: Int = 200,
    ): List<EpisodicTurn>
}
