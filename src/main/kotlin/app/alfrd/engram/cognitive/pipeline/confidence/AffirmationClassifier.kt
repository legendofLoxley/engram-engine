package app.alfrd.engram.cognitive.pipeline.confidence

/**
 * Detects explicit user affirmation ("you nailed it", "that's exactly right") in the current
 * utterance — pure marker-phrase heuristic, styled like
 * [app.alfrd.engram.cognitive.pipeline.selection.OutcomeSignalClassifier]'s correction markers.
 *
 * This is the first-class, higher-weighted explicit-feedback signal the confidence model
 * requires, deliberately distinct from the inferred [app.alfrd.engram.model.OutcomeSignal] —
 * that signal measures response-*phrase* effectiveness (a different concern: whether a selected
 * response phrase worked), not whether alfrd's underlying knowledge was affirmed as correct.
 */
object AffirmationClassifier {

    private val AFFIRMATION_MARKERS = listOf(
        "you nailed it", "you nailed that", "that's exactly right", "thats exactly right",
        "exactly right", "that's correct", "thats correct", "that's right", "thats right",
        "spot on", "nailed it", "perfect, that's right", "yes, exactly", "yes exactly",
        "you're right", "youre right", "correct!",
    )

    fun isAffirmation(utterance: String): Boolean {
        val lower = utterance.trim().lowercase()
        return AFFIRMATION_MARKERS.any { marker -> lower == marker || lower.contains(marker) }
    }
}
