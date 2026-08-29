package app.alfrd.engram.cognitive.pipeline.memory

import java.util.UUID

/**
 * Full in-memory implementation of [EngramClient] for testing and development.
 *
 * No external dependencies — the pipeline is always runnable without engram-engine running.
 */
class InMemoryEngramClient : EngramClient {

    private val phrases = mutableListOf<Phrase>()
    private val scaffoldStates = mutableMapOf<String, ScaffoldState>()
    private val topicConfidences = mutableMapOf<Pair<String, String>, TopicConfidence>()

    // ── Decompose ─────────────────────────────────────────────────────────────

    /**
     * Naive heuristic decomposition — splits on sentence boundaries and classifies
     * each segment by keyword matching. The real decomposition will use an LLM.
     */
    override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> {
        val segments = text.split(Regex("[.!?]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { sentence ->
                // Split further on contrastive markers — each clause may carry a different truth value
                sentence.split(CONTRASTIVE_MARKERS)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
        return segments.map { segment ->
            PhraseCandidate(
                content = segment,
                source = "user",
                category = classifySegment(segment.lowercase()),
            )
        }
    }

    companion object {
        /** Matches contrastive conjunctions used to split a sentence into distinct claims. */
        private val CONTRASTIVE_MARKERS = Regex(
            """\s+(?:but|however|although|yet|while|whereas|though|even\s+though)\s+""",
            RegexOption.IGNORE_CASE,
        )
    }

    private fun classifySegment(lower: String): PhraseCategory = when {
        lower.containsAny(
            "i am", "i'm", "my name", "i work as", "my job", "my role",
            "profession", "job title", "i do", "i'm a",
        ) -> PhraseCategory.IDENTITY

        lower.containsAny(
            "use ", "work with", "languages", "framework", "tool", "technology",
            "expert in", "familiar with", "code in", "develop", "programming",
        ) -> PhraseCategory.EXPERTISE

        lower.containsAny(
            "prefer", "enjoy", "love", "like to", "hate", "don't like",
            "favorite", "rather", "would rather",
        ) -> PhraseCategory.PREFERENCE

        lower.containsAny(
            "every day", "every week", "usually", "typically", "routine",
            "morning", "schedule", "habit", "regularly",
        ) -> PhraseCategory.ROUTINE

        lower.containsAny(
            "my team", "colleagues", "friend", "family", "manager",
            "reports to", "partner", "coworker",
        ) -> PhraseCategory.RELATIONSHIP

        else -> PhraseCategory.CONTEXT
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it) }

    // ── Ingest ────────────────────────────────────────────────────────────────

    override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) {
        for (c in candidates) {
            phrases.add(
                Phrase(
                    id = UUID.randomUUID().toString(),
                    content = c.content,
                    source = c.source,
                    trustPhase = 1,
                    score = 0.5,
                )
            )
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    // In-memory: no real graph traversal — concept filtering applied, email ignored.
    override suspend fun queryPhrases(userEmail: String, concept: String?, limit: Int): List<ScoredPhrase> {
        val words = concept?.lowercase()?.split(Regex("\\s+"))?.filter { it.length > 2 } ?: emptyList()
        return phrases.filter { phrase ->
            words.isEmpty() || words.any { phrase.content.lowercase().contains(it) }
        }.take(limit).map { phrase ->
            ScoredPhrase(
                uid = phrase.id,
                text = phrase.content,
                createdAt = 0L,
                updatedAt = 0L,
                scores = mapOf("trust" to phrase.score),
                sourceCount = 1,
                sourceTypes = listOf(phrase.source),
            )
        }
    }

    // ── Scaffold state ────────────────────────────────────────────────────────

    override suspend fun getScaffoldState(userId: String): ScaffoldState =
        scaffoldStates.getOrPut(userId) { ScaffoldState() }

    override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) {
        scaffoldStates[userId] = state
    }

    // ── Amend ─────────────────────────────────────────────────────────────────

    override suspend fun amendPhrase(phraseId: String, newContent: String) {
        val idx = phrases.indexOfFirst { it.id == phraseId }
        if (idx >= 0) phrases[idx] = phrases[idx].copy(content = newContent)
    }

    // ── Topic confidence ──────────────────────────────────────────────────────

    override suspend fun getTopicConfidence(userEmail: String, topic: String): TopicConfidence =
        topicConfidences.getOrDefault(userEmail to topic, TopicConfidence(topic = topic))

    override suspend fun updateTopicConfidence(userEmail: String, topic: String, confidence: TopicConfidence) {
        topicConfidences[userEmail to topic] = confidence
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    /** Expose stored phrases for assertions in tests. */
    fun allPhrases(): List<Phrase> = phrases.toList()
}
