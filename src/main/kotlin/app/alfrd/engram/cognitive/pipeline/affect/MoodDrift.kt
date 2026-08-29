package app.alfrd.engram.cognitive.pipeline.affect

import app.alfrd.engram.model.OutcomeSignal

/**
 * Slow, session-level mood drift over a small rolling window of *already-computed*
 * [OutcomeSignal] history (the same classification [app.alfrd.engram.cognitive.pipeline.CognitivePipeline]
 * already produces each turn for response-phrase effectiveness — reused here, not recomputed).
 *
 * Deliberately takes only [OutcomeSignal] as input, never a
 * [app.alfrd.engram.cognitive.pipeline.memory.TopicConfidence]/`ConfidencePhase` — confidence
 * must never influence mood, and this signature is the enforcement point for that invariant.
 */
object MoodDrift {

    /**
     * A run of CORRECTED/DISENGAGED drifts one step toward CAREFUL/GUARDED; a run of
     * ENGAGED/EXPANDED drifts one step toward WARM/PLAYFUL; a mixed window leaves [current]
     * unchanged. "One step" keeps mood slow-moving rather than snapping between extremes.
     */
    fun next(current: Mood, recentSignals: List<OutcomeSignal>): Mood {
        if (recentSignals.isEmpty()) return current

        val negative = recentSignals.count { it == OutcomeSignal.CORRECTED || it == OutcomeSignal.DISENGAGED }
        val positive = recentSignals.count { it == OutcomeSignal.ENGAGED || it == OutcomeSignal.EXPANDED }

        return when {
            negative > positive && negative > recentSignals.size / 2 -> stepToward(current, negative = true)
            positive > negative && positive > recentSignals.size / 2 -> stepToward(current, negative = false)
            else -> current
        }
    }

    private val ORDER = listOf(Mood.GUARDED, Mood.CAREFUL, Mood.NEUTRAL, Mood.WARM, Mood.PLAYFUL)

    private fun stepToward(current: Mood, negative: Boolean): Mood {
        val index = ORDER.indexOf(current)
        val nextIndex = if (negative) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(ORDER.size - 1)
        return ORDER[nextIndex]
    }
}
