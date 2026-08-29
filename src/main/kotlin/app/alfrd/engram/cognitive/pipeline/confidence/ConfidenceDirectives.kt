package app.alfrd.engram.cognitive.pipeline.confidence

import app.alfrd.engram.cognitive.pipeline.memory.ConfidencePhase

/**
 * Renders [phase] as a short, natural-language directive for the actor — epistemic-confidence
 * wording only, never tone/warmth (that's [app.alfrd.engram.cognitive.pipeline.affect.Mood]'s
 * job entirely; confidence must never govern tone).
 *
 * Returns null when [phase] is null — no topic could be resolved for the current turn, so no
 * confidence note is forced onto content-free turns ("Hey").
 */
fun topicConfidenceDirective(phase: ConfidencePhase?): String? = when (phase) {
    null -> null
    ConfidencePhase.ORIENTATION ->
        "You don't yet have earned confidence on this specific topic — ask rather than assume, " +
        "and don't present inferences about it as settled fact."
    ConfidencePhase.WORKING_RHYTHM ->
        "You have some working familiarity with this specific topic — reasonable to build on it, " +
        "but confirm before assuming details you haven't actually verified."
    ConfidencePhase.CONTEXT ->
        "You have solid, tested confidence on this specific topic — feel free to make connections " +
        "the user hasn't explicitly stated."
    ConfidencePhase.UNDERSTANDING ->
        "You have deep, repeatedly-confirmed confidence on this specific topic — act on it directly."
}
