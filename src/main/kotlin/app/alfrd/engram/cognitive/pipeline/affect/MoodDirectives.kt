package app.alfrd.engram.cognitive.pipeline.affect

/**
 * Renders [mood] as a short, natural-language tone directive for the actor. This is the correct
 * home for tone modulation — replacing what the old trust-phase-driven "relationship stage" note
 * used to (wrongly) do in [app.alfrd.engram.cognitive.pipeline.Actor].
 */
fun moodDirective(mood: Mood): String = when (mood) {
    Mood.GUARDED -> "Be extra careful and measured in tone right now — keep things simple and steady."
    Mood.CAREFUL -> "Recent turns suggest some friction — be patient and steady, and avoid overclaiming."
    Mood.NEUTRAL -> "Default conversational tone — warm and professional."
    Mood.WARM -> "The conversation has been going well — feel free to be a little warmer and more relaxed."
    Mood.PLAYFUL -> "Things are going well and light — a touch of playfulness or wit is welcome here."
}
