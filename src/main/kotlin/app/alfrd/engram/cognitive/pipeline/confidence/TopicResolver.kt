package app.alfrd.engram.cognitive.pipeline.confidence

import app.alfrd.engram.cognitive.pipeline.selection.OutcomeSignalClassifier

/**
 * Extracts a single representative topic keyword from an utterance or fact string — pure
 * heuristic, no LLM (CPU-only, matches this codebase's existing keyword-heuristic style in
 * [OutcomeSignalClassifier]). Used both to resolve *which* [app.alfrd.engram.cognitive.pipeline.memory.TopicConfidence]
 * a turn's evidence applies to, and to look up the current turn's confidence for conditioning.
 *
 * Deliberately does not implement any concept-linking or cross-topic resolution — topics are
 * independent string keys; spreading/generalization between them is explicitly out of scope.
 */
object TopicResolver {

    /**
     * Returns the longest keyword extracted from [text], or null when [text] is trivial/empty
     * (e.g. "Hey") so content-free turns get no forced evidence or conditioning.
     */
    fun resolve(text: String): String? =
        OutcomeSignalClassifier.extractKeywords(text).maxByOrNull { it.length }
}
