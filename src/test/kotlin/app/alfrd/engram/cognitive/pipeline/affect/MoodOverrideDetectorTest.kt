package app.alfrd.engram.cognitive.pipeline.affect

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MoodOverrideDetectorTest {

    @Test
    fun `detects a request to lighten up as PLAYFUL`() {
        assertEquals(Mood.PLAYFUL, MoodOverrideDetector.detect("Lighten up a bit, would you?"))
    }

    @Test
    fun `detects a request to drop formality as WARM`() {
        assertEquals(Mood.WARM, MoodOverrideDetector.detect("Stop being so formal"))
    }

    @Test
    fun `detects a request to be more serious as NEUTRAL`() {
        assertEquals(Mood.NEUTRAL, MoodOverrideDetector.detect("Can you be more serious please"))
    }

    @Test
    fun `detects a request for caution as CAREFUL`() {
        assertEquals(Mood.CAREFUL, MoodOverrideDetector.detect("Please be more careful here"))
    }

    @Test
    fun `returns null for ordinary utterances`() {
        assertNull(MoodOverrideDetector.detect("What time does school start?"))
        assertNull(MoodOverrideDetector.detect("Remind me to call the vet"))
    }
}
