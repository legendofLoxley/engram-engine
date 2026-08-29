package app.alfrd.engram.cognitive.pipeline.affect

/**
 * Detects a direct user instruction to change tone ("stop being so formal", "lighten up") —
 * pure marker-phrase heuristic. An override always wins over automatic [MoodDrift] and persists
 * for the rest of the session, per the spec's "directly overridable by explicit instruction."
 */
object MoodOverrideDetector {

    private val MARKERS: List<Pair<List<String>, Mood>> = listOf(
        listOf("stop being so formal", "less formal", "be less formal", "drop the formality") to Mood.WARM,
        listOf("lighten up", "be more playful", "have some fun", "loosen up") to Mood.PLAYFUL,
        listOf("be more serious", "get serious", "less playful", "cut the jokes") to Mood.NEUTRAL,
        listOf("be more careful", "slow down", "be more cautious") to Mood.CAREFUL,
        listOf("be more guarded", "hold back a bit") to Mood.GUARDED,
    )

    fun detect(utterance: String): Mood? {
        val lower = utterance.trim().lowercase()
        for ((phrases, mood) in MARKERS) {
            if (phrases.any { lower.contains(it) }) return mood
        }
        return null
    }
}
