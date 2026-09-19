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
    private val episodicTurns = mutableListOf<EpisodicTurn>()

    // ── Decompose ─────────────────────────────────────────────────────────────

    /**
     * Naive heuristic decomposition — splits on sentence boundaries and classifies
     * each segment by keyword matching. The real decomposition will use an LLM.
     *
     * Returns only candidate *claims*: per the semantic-graph design, a Phrase is an assertion, and
     * the exact utterance (questions and greetings included) belongs to the episode ledger, which
     * records every turn separately. So this drops questions, bare greetings/pleasantries, and bare
     * discourse lead-ins ("Unrelated," left over when a sentence is split at "but"). A sentence that
     * ends in "?" but opens with a declarative clause ("I'm worried about X, should I do Y?") keeps
     * that clause. Everything else — including splitting only on `. ! ?` and contrastive markers,
     * not "and" — is unchanged; proper claim extraction is the LLM step this placeholder stands in for.
     */
    override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> {
        val segments = SENTENCE.findAll(text)
            .flatMap { match -> claimBearingText(match.groupValues[1].trim(), match.groupValues[2]) }
            .flatMap { sentence ->
                // Split further on contrastive markers — each clause may carry a different truth value
                sentence.split(CONTRASTIVE_MARKERS)
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !isNonClaim(it) }
            }
        return segments.toList().map { segment ->
            PhraseCandidate(
                content = segment,
                source = "user",
                category = classifySegment(segment.lowercase()),
            )
        }
    }

    /** The parts of one sentence that could carry a claim, given its terminator; empty for a pure question/blank. */
    private fun claimBearingText(sentence: String, terminator: String): Sequence<String> {
        if (sentence.isBlank()) return emptySequence()
        val firstWord = firstWordOf(sentence)
        // No terminator at all: only a wh-word opener reads as a question ("who did I need to ask ...").
        // A '.' or '!' means the writer made it a statement ("What I need is a break.").
        val unpunctuatedQuestion = terminator.isEmpty() && firstWord in WH_WORDS
        if (!terminator.contains('?') && !unpunctuatedQuestion) return sequenceOf(sentence)
        // A question. Keep a leading declarative clause if there is one ("I'm worried about X, should I ...?").
        val head = sentence.substringBeforeLast(',', missingDelimiterValue = "").trim()
        val headIsDeclarative = head.isNotEmpty() &&
            head.split(Regex("\\s+")).size >= 3 &&
            firstWordOf(head) !in QUESTION_OR_CONDITIONAL_OPENERS
        return if (headIsDeclarative) sequenceOf(head) else emptySequence()
    }

    private fun firstWordOf(text: String): String =
        text.trim().lowercase().split(Regex("\\s+")).firstOrNull()?.trimEnd(',', ':', ';') ?: ""

    private fun isNonClaim(segment: String): Boolean =
        segment.lowercase().trim().trim(',', '.', '!', ';', ':', ' ') in NON_CLAIM_SEGMENTS

    companion object {
        /** Matches contrastive conjunctions used to split a sentence into distinct claims. */
        private val CONTRASTIVE_MARKERS = Regex(
            """\s+(?:but|however|although|yet|while|whereas|though|even\s+though)\s+""",
            RegexOption.IGNORE_CASE,
        )

        /** One sentence body plus its (possibly empty) terminator run — unlike a plain split, keeps the "?" so questions are recognizable. */
        private val SENTENCE = Regex("""([^.!?]+)([.!?]*)""")

        private val WH_WORDS = setOf("what", "who", "whom", "whose", "when", "where", "why", "how", "which")

        /** Words that make a clause before a trailing question a poor candidate for a stand-alone claim. */
        private val QUESTION_OR_CONDITIONAL_OPENERS = WH_WORDS + setOf(
            "is", "are", "do", "does", "did", "can", "could", "would", "should", "will", "have", "has",
            "if", "unless", "when", "since", "because", "so", "and", "but", "also", "actually",
        )

        /**
         * Whole segments that assert nothing: pleasantries, acknowledgements, and the bare discourse
         * lead-ins that are left behind when a sentence is cut at a contrastive marker ("Unrelated,"
         * from "Unrelated, but ..."). Generic English; not tied to any scenario's vocabulary.
         */
        private val NON_CLAIM_SEGMENTS = setOf(
            "hi", "hey", "hello", "hi there", "hey there", "hello there", "good morning", "good afternoon",
            "good evening", "thanks", "thank you", "thanks a lot", "ok", "okay", "sure", "yes", "no", "yeah",
            "nope", "bye", "goodbye", "cheers",
            "unrelated", "separately", "anyway", "also", "actually", "honestly", "basically", "well",
            "meanwhile", "incidentally", "by the way", "that said", "on another note",
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

    override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String): List<String> {
        val createdIds = mutableListOf<String>()
        for (c in candidates) {
            val id = UUID.randomUUID().toString()
            phrases.add(
                Phrase(
                    id = id,
                    content = c.content,
                    source = c.source,
                    trustPhase = 1,
                    score = 0.5,
                )
            )
            createdIds += id
        }
        return createdIds
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

    // ── Episodic conversation log ────────────────────────────────────────────

    override suspend fun appendEpisodicTurn(
        sessionId: String,
        userId: String,
        turnIndex: Int,
        userUtterance: String,
        alfrdResponse: String,
    ) {
        val now = System.currentTimeMillis()
        episodicTurns.add(
            EpisodicTurn(
                uid = UUID.randomUUID().toString(),
                sessionId = sessionId,
                userId = userId,
                turnIndex = turnIndex,
                role = "user",
                text = userUtterance,
                createdAt = now,
            )
        )
        episodicTurns.add(
            EpisodicTurn(
                uid = UUID.randomUUID().toString(),
                sessionId = sessionId,
                userId = userId,
                turnIndex = turnIndex,
                role = "alfrd",
                text = alfrdResponse,
                createdAt = now,
            )
        )
    }

    override suspend fun getEpisodicLog(
        userId: String,
        sinceMillis: Long?,
        untilMillis: Long?,
        keyword: String?,
        limit: Int,
    ): List<EpisodicTurn> {
        val lowerKeyword = keyword?.lowercase()
        return episodicTurns
            .filter { it.userId == userId }
            .filter { sinceMillis == null || it.createdAt >= sinceMillis }
            .filter { untilMillis == null || it.createdAt <= untilMillis }
            .filter { lowerKeyword == null || it.text.lowercase().contains(lowerKeyword) }
            .sortedBy { it.createdAt }
            .takeLast(limit)
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    /** Expose stored phrases for assertions in tests. */
    fun allPhrases(): List<Phrase> = phrases.toList()

    /** Expose stored episodic turns for assertions in tests. */
    fun allEpisodicTurns(): List<EpisodicTurn> = episodicTurns.toList()
}
