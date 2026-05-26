package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.model.OutcomeSignal

/**
 * Classifies the outcome signal for a prior turn using pattern-based heuristics.
 * No LLM — pure text analysis on the current user utterance against the prior turn's context.
 *
 * Signal priority (highest to lowest):
 *   CORRECTED  → correction markers or frustration patterns detected
 *   EXPANDED   → references prior topic AND current utterance is longer than prior
 *   ENGAGED    → substantive response (> 5 words), no correction markers
 *   NEUTRAL    → default fallback (short/ambiguous response)
 *
 * DISENGAGED is never returned by [classify] — it is forced externally on session timeout.
 */
object OutcomeSignalClassifier {

    /**
     * Context captured at the end of a turn so the *next* turn can classify the outcome.
     *
     * @property utterance  The user utterance that triggered phrase selection last turn.
     * @property phraseText The response phrase text that was selected last turn.
     */
    data class PriorTurnContext(
        val utterance: String,
        val phraseText: String,
    )

    data class OutcomeClassification(val signal: OutcomeSignal, val confidence: Double)

    private val CORRECTION_MARKERS = listOf(
        "no,", "no.", "nope", "that's wrong", "thats wrong",
        "i meant", "actually,", "actually.", "not quite",
        "that's not", "thats not", "you're wrong", "youre wrong",
        "incorrect", "wrong,", "wrong.", "stop,", "stop.",
    )

    fun classify(
        currentUtterance: String,
        priorContext: PriorTurnContext,
    ): OutcomeClassification {
        val current = currentUtterance.trim().lowercase()
        val prior = priorContext.utterance.trim().lowercase()

        val currentWordCount = wordCount(currentUtterance)
        val priorWordCount = wordCount(priorContext.utterance)

        // CORRECTED — explicit correction markers or frustration patterns
        if (CORRECTION_MARKERS.any { marker ->
                current.startsWith(marker) || current.contains(" $marker")
            }) {
            return OutcomeClassification(OutcomeSignal.CORRECTED, 0.9)
        }

        // EXPANDED — references prior topic AND longer than previous input
        val priorKeywords = extractKeywords(prior)
        val referencesTopicKeywords = priorKeywords.isNotEmpty() &&
            priorKeywords.any { keyword -> current.contains(keyword) }
        if (referencesTopicKeywords && currentWordCount > priorWordCount) {
            return OutcomeClassification(OutcomeSignal.EXPANDED, 0.8)
        }

        // ENGAGED — substantive response (> 5 words), no correction markers already screened
        if (currentWordCount > 5) {
            return OutcomeClassification(OutcomeSignal.ENGAGED, 0.7)
        }

        // NEUTRAL — short or ambiguous response
        return OutcomeClassification(OutcomeSignal.NEUTRAL, 0.5)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    internal fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    internal fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "been",
            "i", "you", "he", "she", "it", "we", "they", "my", "your",
            "and", "or", "but", "in", "on", "at", "to", "for", "of", "with",
            "that", "this", "what", "how", "when", "where", "why",
            "do", "did", "have", "had", "not", "just", "so", "as", "if",
        )
        return text.split(Regex("[\\s,.!?;:\"']+"))
            .map { it.lowercase() }
            .filter { it.length > 3 && it !in stopWords }
    }
}
