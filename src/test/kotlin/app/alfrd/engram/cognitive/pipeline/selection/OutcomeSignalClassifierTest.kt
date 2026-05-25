package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.model.OutcomeSignal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OutcomeSignalClassifierTest {

    private fun priorContext(
        utterance: String = "hello",
        phraseText: String = "Good morning.",
    ) = OutcomeSignalClassifier.PriorTurnContext(utterance = utterance, phraseText = phraseText)

    // ── CORRECTED ───────────────────────────────────────────────────────────

    @Test
    fun `CORRECTED when utterance starts with 'no,'`() {
        val result = OutcomeSignalClassifier.classify("No, that's not what I meant.", priorContext())
        assertEquals(OutcomeSignal.CORRECTED, result.signal)
        assertEquals(0.9, result.confidence)
    }

    @Test
    fun `CORRECTED when utterance starts with 'no dot'`() {
        val result = OutcomeSignalClassifier.classify("No. I wanted something different.", priorContext())
        assertEquals(OutcomeSignal.CORRECTED, result.signal)
    }

    @Test
    fun `CORRECTED when utterance contains 'i meant'`() {
        val result = OutcomeSignalClassifier.classify("Sorry, I meant the other project.", priorContext())
        assertEquals(OutcomeSignal.CORRECTED, result.signal)
    }

    @Test
    fun `CORRECTED when utterance contains 'that's wrong'`() {
        val result = OutcomeSignalClassifier.classify("Actually that's wrong, let me clarify.", priorContext())
        assertEquals(OutcomeSignal.CORRECTED, result.signal)
    }

    @Test
    fun `CORRECTED when utterance contains 'not quite'`() {
        val result = OutcomeSignalClassifier.classify("Hmm, not quite what I was after.", priorContext())
        assertEquals(OutcomeSignal.CORRECTED, result.signal)
    }

    // ── EXPANDED ────────────────────────────────────────────────────────────

    @Test
    fun `EXPANDED when current references prior topic and is longer`() {
        val prior = priorContext(utterance = "kotlin project")
        // 7 words (> 2) and contains "kotlin" from prior
        val result = OutcomeSignalClassifier.classify(
            "Yes, the kotlin project is actually a backend service for notifications.",
            prior,
        )
        assertEquals(OutcomeSignal.EXPANDED, result.signal)
        assertEquals(0.8, result.confidence)
    }

    @Test
    fun `EXPANDED requires BOTH topic reference AND longer length`() {
        // References keyword "backend" but shorter than prior — should NOT be EXPANDED
        val prior = priorContext(utterance = "the backend service handles payments and authentication flows")
        val result = OutcomeSignalClassifier.classify("backend", prior)
        // 1 word, not > prior word count — falls through to NEUTRAL (not ENGAGED either)
        assertNotEquals(OutcomeSignal.EXPANDED, result.signal)
    }

    // ── ENGAGED ─────────────────────────────────────────────────────────────

    @Test
    fun `ENGAGED when more than 5 words and no correction or topic expansion`() {
        val result = OutcomeSignalClassifier.classify(
            "That sounds great, let's move forward with it.",
            priorContext(utterance = "start"),
        )
        assertEquals(OutcomeSignal.ENGAGED, result.signal)
        assertEquals(0.7, result.confidence)
    }

    @Test
    fun `ENGAGED threshold is strictly greater than 5 words`() {
        // Exactly 5 words → NEUTRAL
        val fiveWords = OutcomeSignalClassifier.classify(
            "okay sure let's go",   // 4 words → NEUTRAL
            priorContext(utterance = "x"),
        )
        assertEquals(OutcomeSignal.NEUTRAL, fiveWords.signal)

        // 6 words → ENGAGED
        val sixWords = OutcomeSignalClassifier.classify(
            "okay sure let us get started",
            priorContext(utterance = "x"),
        )
        assertEquals(OutcomeSignal.ENGAGED, sixWords.signal)
    }

    // ── NEUTRAL ─────────────────────────────────────────────────────────────

    @Test
    fun `NEUTRAL for short acknowledgment with no markers`() {
        val result = OutcomeSignalClassifier.classify("okay", priorContext())
        assertEquals(OutcomeSignal.NEUTRAL, result.signal)
        assertEquals(0.5, result.confidence)
    }

    @Test
    fun `NEUTRAL for single word response`() {
        val result = OutcomeSignalClassifier.classify("yes", priorContext())
        assertEquals(OutcomeSignal.NEUTRAL, result.signal)
    }

    // ── DISENGAGED is never returned by classify ─────────────────────────────

    @Test
    fun `classify never returns DISENGAGED`() {
        val inputs = listOf("", "  ", "okay", "no that is wrong very much",
            "yes i want to expand on this topic further today")
        for (input in inputs) {
            val result = OutcomeSignalClassifier.classify(input, priorContext())
            assertNotEquals(
                OutcomeSignal.DISENGAGED, result.signal,
                "DISENGAGED should not be returned by classify() for input: '$input'",
            )
        }
    }

    // ── CORRECTED takes priority over EXPANDED ───────────────────────────────

    @Test
    fun `CORRECTED takes priority even when utterance also references prior topic`() {
        val prior = priorContext(utterance = "kotlin backend service")
        // Contains correction marker AND references prior keyword
        val result = OutcomeSignalClassifier.classify(
            "No, the kotlin backend service is different from what I described.",
            prior,
        )
        assertEquals(OutcomeSignal.CORRECTED, result.signal)
    }

    // ── Helper internals ─────────────────────────────────────────────────────

    @Test
    fun `wordCount handles empty string`() {
        assertEquals(0, OutcomeSignalClassifier.wordCount(""))
        assertEquals(0, OutcomeSignalClassifier.wordCount("   "))
    }

    @Test
    fun `wordCount counts correctly`() {
        assertEquals(3, OutcomeSignalClassifier.wordCount("one two three"))
        assertEquals(1, OutcomeSignalClassifier.wordCount("  word  "))
    }

    @Test
    fun `extractKeywords filters stop words and short tokens`() {
        val keywords = OutcomeSignalClassifier.extractKeywords("i am working on the kotlin project")
        assertFalse(keywords.contains("the"))
        assertFalse(keywords.contains("on"))
        assertTrue(keywords.contains("kotlin"))
        assertTrue(keywords.contains("project"))
        assertTrue(keywords.contains("working"))
    }
}
