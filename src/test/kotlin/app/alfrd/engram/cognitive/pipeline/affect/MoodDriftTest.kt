package app.alfrd.engram.cognitive.pipeline.affect

import app.alfrd.engram.model.OutcomeSignal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MoodDriftTest {

    @Test
    fun `empty history leaves mood unchanged`() {
        assertEquals(Mood.NEUTRAL, MoodDrift.next(Mood.NEUTRAL, emptyList()))
    }

    @Test
    fun `a run of ENGAGED and EXPANDED drifts one step warmer`() {
        val signals = listOf(OutcomeSignal.ENGAGED, OutcomeSignal.EXPANDED, OutcomeSignal.ENGAGED)
        assertEquals(Mood.WARM, MoodDrift.next(Mood.NEUTRAL, signals))
    }

    @Test
    fun `a run of CORRECTED and DISENGAGED drifts one step more careful`() {
        val signals = listOf(OutcomeSignal.CORRECTED, OutcomeSignal.DISENGAGED, OutcomeSignal.CORRECTED)
        assertEquals(Mood.CAREFUL, MoodDrift.next(Mood.NEUTRAL, signals))
    }

    @Test
    fun `mixed signals leave mood unchanged`() {
        val signals = listOf(OutcomeSignal.ENGAGED, OutcomeSignal.CORRECTED, OutcomeSignal.NEUTRAL)
        assertEquals(Mood.NEUTRAL, MoodDrift.next(Mood.NEUTRAL, signals))
    }

    @Test
    fun `drift never overshoots past PLAYFUL at the warm end`() {
        val signals = List(5) { OutcomeSignal.EXPANDED }
        assertEquals(Mood.PLAYFUL, MoodDrift.next(Mood.PLAYFUL, signals))
    }

    @Test
    fun `drift never undershoots past GUARDED at the careful end`() {
        val signals = List(5) { OutcomeSignal.CORRECTED }
        assertEquals(Mood.GUARDED, MoodDrift.next(Mood.GUARDED, signals))
    }
}
