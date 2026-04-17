package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.model.OutcomeSignal
import app.alfrd.engram.model.ResponsePhrase
import app.alfrd.engram.model.SelectedEdge
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SelectionScorerTest {

    private fun phrase(
        uid: String = "p1",
        text: String = "Good morning.",
        category: String = "GREETING",
        expressionPhase: String = "ACKNOWLEDGE",
        branchAffinity: Set<String> = setOf("SOCIAL"),
        phaseAffinity: Set<String> = setOf("ORIENTATION", "WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
    ) = ResponsePhrase(
        uid = uid, text = text, hash = "h", visibility = "internal",
        createdAt = 0L, updatedAt = 0L,
        branchAffinity = branchAffinity, phaseAffinity = phaseAffinity,
        expressionPhase = expressionPhase, category = category,
    )

    private fun selectedEdge(
        phraseUid: String = "p1",
        sessionId: String = "s1",
        userId: String = "u1",
        turnIndex: Int = 1,
        timestamp: Long = System.currentTimeMillis(),
    ) = SelectedEdge(
        phraseUid = phraseUid, sessionId = sessionId, userId = userId,
        turnIndex = turnIndex, branch = "SOCIAL", compositeScore = 0.5,
        scoreBreakdown = emptyMap(), timestamp = timestamp,
    )

    // ── Freshness ───────────────────────────────────────────────────────────

    @Test
    fun `freshness returns 1_0 for never-used phrase`() {
        val score = SelectionScorer.freshness(
            phrase(), emptyList(), "s1", 1, Instant.now(),
        )
        assertEquals(1.0, score)
    }

    @Test
    fun `freshness returns 0_0 for phrase used on current turn`() {
        val now = Instant.now()
        val edge = selectedEdge(sessionId = "s1", turnIndex = 3, timestamp = now.toEpochMilli())
        val score = SelectionScorer.freshness(phrase(), listOf(edge), "s1", 3, now)
        assertEquals(0.0, score)
    }

    @Test
    fun `freshness returns 0_05 for phrase used within 10 turns same session`() {
        val now = Instant.now()
        val edge = selectedEdge(sessionId = "s1", turnIndex = 1, timestamp = now.toEpochMilli())
        val score = SelectionScorer.freshness(phrase(), listOf(edge), "s1", 5, now)
        assertEquals(0.05, score)
    }

    @Test
    fun `freshness returns 0_3 for phrase used more than 10 turns ago same session`() {
        val now = Instant.now()
        val edge = selectedEdge(sessionId = "s1", turnIndex = 1, timestamp = now.toEpochMilli())
        val score = SelectionScorer.freshness(phrase(), listOf(edge), "s1", 15, now)
        assertEquals(0.3, score)
    }

    @Test
    fun `freshness returns 0_9 for phrase used more than 7 days ago`() {
        val now = Instant.now()
        val eightDaysAgo = now.minusSeconds(8 * 24 * 3600)
        val edge = selectedEdge(sessionId = "other", timestamp = eightDaysAgo.toEpochMilli())
        val score = SelectionScorer.freshness(phrase(), listOf(edge), "s1", 1, now)
        assertEquals(0.9, score)
    }

    @Test
    fun `freshness returns 0_6 for phrase used 3 days ago`() {
        val now = Instant.now()
        val threeDaysAgo = now.minusSeconds(3 * 24 * 3600)
        val edge = selectedEdge(sessionId = "other", timestamp = threeDaysAgo.toEpochMilli())
        val score = SelectionScorer.freshness(phrase(), listOf(edge), "s1", 1, now)
        assertEquals(0.6, score)
    }

    // ── Contextual Fit ──────────────────────────────────────────────────────

    @Test
    fun `contextual fit scores 1_0 for morning phrase in morning`() {
        val morningInstant = LocalDate.now().atTime(9, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val ctx = CognitiveContext(
            utterance = "hello", sessionId = "s", userId = "u",
            timestamp = morningInstant,
        )
        val score = SelectionScorer.contextualFit(phrase(text = "Good morning."), ctx)
        assertEquals(1.0, score)
    }

    @Test
    fun `contextual fit scores 0_0 for morning phrase in evening`() {
        val eveningInstant = LocalDate.now().atTime(20, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val ctx = CognitiveContext(
            utterance = "hello", sessionId = "s", userId = "u",
            timestamp = eveningInstant,
        )
        val score = SelectionScorer.contextualFit(phrase(text = "Good morning."), ctx)
        assertEquals(0.0, score)
    }

    @Test
    fun `contextual fit scores 1_0 for midnight oil phrase at 11 PM`() {
        val lateNight = LocalDate.now().atTime(23, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val ctx = CognitiveContext(utterance = "hello", sessionId = "s", userId = "u", timestamp = lateNight)
        val score = SelectionScorer.contextualFit(phrase(text = "Burning the midnight oil, I see."), ctx)
        assertEquals(1.0, score)
    }

    @Test
    fun `contextual fit scores 0_0 for midnight oil phrase at 2 PM`() {
        val afternoon = LocalDate.now().atTime(14, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val ctx = CognitiveContext(utterance = "hello", sessionId = "s", userId = "u", timestamp = afternoon)
        val score = SelectionScorer.contextualFit(phrase(text = "Burning the midnight oil, I see."), ctx)
        assertEquals(0.0, score)
    }

    @Test
    fun `contextual fit scores 1_0 for early start phrase before 7 AM`() {
        val earlyMorning = LocalDate.now().atTime(5, 30)
            .atZone(ZoneId.systemDefault()).toInstant()
        val ctx = CognitiveContext(utterance = "hello", sessionId = "s", userId = "u", timestamp = earlyMorning)
        val score = SelectionScorer.contextualFit(phrase(text = "Early start today."), ctx)
        assertEquals(1.0, score)
    }

    @Test
    fun `contextual fit scores 0_0 for early start phrase at 9 AM`() {
        val morning = LocalDate.now().atTime(9, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val ctx = CognitiveContext(utterance = "hello", sessionId = "s", userId = "u", timestamp = morning)
        val score = SelectionScorer.contextualFit(phrase(text = "Early start today."), ctx)
        assertEquals(0.0, score)
    }

    @Test
    fun `contextual fit scores 1_0 for been-a-while phrase with 8-day gap`() {
        val now = Instant.now()
        val eightDaysAgo = now.minusSeconds(8 * 24 * 3600).toEpochMilli()
        val ctx = CognitiveContext(
            utterance = "hello", sessionId = "s", userId = "u",
            timestamp = now,
            lastInteractionAt = eightDaysAgo,
        )
        val score = SelectionScorer.contextualFit(
            phrase(text = "It's been a while. Good to have you back."), ctx,
        )
        assertEquals(1.0, score)
    }

    @Test
    fun `contextual fit scores 0_1 for been-a-while phrase with no gap data`() {
        val ctx = CognitiveContext(utterance = "hello", sessionId = "s", userId = "u")
        val score = SelectionScorer.contextualFit(
            phrase(text = "It's been a while. Good to have you back."), ctx,
        )
        assertEquals(0.1, score)
    }

    @Test
    fun `contextual fit scores 1_0 for left-off phrase with same-day gap`() {
        val now = Instant.now()
        val twoHoursAgo = now.minusSeconds(2 * 3600).toEpochMilli()
        val ctx = CognitiveContext(
            utterance = "hello", sessionId = "s", userId = "u",
            timestamp = now,
            lastInteractionAt = twoHoursAgo,
        )
        val score = SelectionScorer.contextualFit(
            phrase(text = "Right where we left off."), ctx,
        )
        assertEquals(1.0, score)
    }

    @Test
    fun `contextual fit scores 1_0 for first-ever session meet-you phrase`() {
        val ctx = CognitiveContext(
            utterance = "hello", sessionId = "s", userId = "u",
            sessionCount = 0,
        )
        val score = SelectionScorer.contextualFit(
            phrase(text = "Good to meet you. I'd like to get oriented so I can be useful quickly."), ctx,
        )
        assertEquals(1.0, score)
    }

    @Test
    fun `contextual fit scores 0_0 for meet-you phrase on returning user`() {
        val ctx = CognitiveContext(
            utterance = "hello", sessionId = "s", userId = "u",
            sessionCount = 5,
        )
        val score = SelectionScorer.contextualFit(
            phrase(text = "Good to meet you. I'd like to get oriented so I can be useful quickly."), ctx,
        )
        assertEquals(0.0, score)
    }

    @Test
    fun `contextual fit scores 0_0 for acquainted phrase on returning user`() {
        val ctx = CognitiveContext(utterance = "hello", sessionId = "s", userId = "u", sessionCount = 3)
        val score = SelectionScorer.contextualFit(
            phrase(text = "Welcome. I'm alfrd — let's get acquainted."), ctx,
        )
        assertEquals(0.0, score)
    }

    @Test
    fun `contextual fit boosts greeting on turn 1`() {
        val ctx = CognitiveContext(
            utterance = "hello", sessionId = "s", userId = "u",
        )
        // Non time-specific greeting
        val score = SelectionScorer.contextualFit(phrase(text = "Good to see you."), ctx)
        assertTrue(score >= 0.8, "Expected high score on turn 1, got $score")
    }

    // ── Communication Fit ───────────────────────────────────────────────────

    @Test
    fun `communication fit returns 0_7 baseline`() {
        val ctx = CognitiveContext(utterance = "hey", sessionId = "s", userId = "u")
        val score = SelectionScorer.communicationFit(phrase(), ctx)
        assertEquals(0.7, score)
    }

    // ── Phase Appropriateness ───────────────────────────────────────────────

    @Test
    fun `phase appropriateness 1_0 when current phase in affinity`() {
        val ctx = CognitiveContext(
            utterance = "hey", sessionId = "s", userId = "u",
            trustPhase = "ORIENTATION",
        )
        val score = SelectionScorer.phaseAppropriateness(
            phrase(phaseAffinity = setOf("ORIENTATION", "WORKING_RHYTHM")), ctx,
        )
        assertEquals(1.0, score)
    }

    @Test
    fun `phase appropriateness 0_3 for adjacent phase`() {
        val ctx = CognitiveContext(
            utterance = "hey", sessionId = "s", userId = "u",
            trustPhase = "CONTEXT",
        )
        val score = SelectionScorer.phaseAppropriateness(
            phrase(phaseAffinity = setOf("WORKING_RHYTHM")), ctx,
        )
        assertEquals(0.3, score)
    }

    @Test
    fun `phase appropriateness 0_0 for non-adjacent phase`() {
        val ctx = CognitiveContext(
            utterance = "hey", sessionId = "s", userId = "u",
            trustPhase = "UNDERSTANDING",
        )
        val score = SelectionScorer.phaseAppropriateness(
            phrase(phaseAffinity = setOf("ORIENTATION")), ctx,
        )
        assertEquals(0.0, score)
    }

    // ── Effectiveness ───────────────────────────────────────────────────────

    @Test
    fun `effectiveness baseline 0_5 with no outcomes`() {
        assertEquals(0.5, SelectionScorer.effectiveness(emptyList()))
    }

    @Test
    fun `effectiveness increases with ENGAGED outcomes`() {
        val summaries = listOf(
            SelectionScorer.OutcomeSummary(OutcomeSignal.ENGAGED, 10),
        )
        val score = SelectionScorer.effectiveness(summaries)
        assertTrue(score > 0.5, "Expected > 0.5, got $score")
    }

    @Test
    fun `effectiveness decreases with CORRECTED outcomes`() {
        val summaries = listOf(
            SelectionScorer.OutcomeSummary(OutcomeSignal.CORRECTED, 10),
        )
        val score = SelectionScorer.effectiveness(summaries)
        assertTrue(score < 0.5, "Expected < 0.5, got $score")
    }

    @Test
    fun `effectiveness cold start dampening for low count`() {
        val summariesHigh = listOf(
            SelectionScorer.OutcomeSummary(OutcomeSignal.EXPANDED, 10),
        )
        val summariesLow = listOf(
            SelectionScorer.OutcomeSummary(OutcomeSignal.EXPANDED, 2),
        )
        val scoreHigh = SelectionScorer.effectiveness(summariesHigh)
        val scoreLow = SelectionScorer.effectiveness(summariesLow)
        // Low sample count should be closer to 0.5 baseline
        assertTrue(
            (scoreLow - 0.5) < (scoreHigh - 0.5),
            "Cold start dampening not working: low=$scoreLow high=$scoreHigh",
        )
    }
}
