package app.alfrd.engram.cognitive.pipeline.confidence

import app.alfrd.engram.cognitive.pipeline.memory.ConfidenceEvidenceKind
import app.alfrd.engram.cognitive.pipeline.memory.ConfidencePhase
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.model.OutcomeSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// ─────────────────────────────────────────────────────────────────────────────
// TopicConfidenceService — per-topic, evidence-driven confidence.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class TopicConfidenceServiceTest {

    private fun service(engram: InMemoryEngramClient, scope: TestScope) =
        TopicConfidenceService(engram, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), scope)

    // ── Demonstrated competence ────────────────────────────────────────────────

    @Test
    fun `ENGAGED signal raises confidence for the topic`() = runTest {
        val engram = InMemoryEngramClient()
        val svc = service(engram, this)

        svc.recordDemonstratedCompetence("user-1", "kotlin", OutcomeSignal.ENGAGED)
        advanceUntilIdle()

        val confidence = engram.getTopicConfidence("user-1", "kotlin")
        assertEquals(TopicConfidenceService.COMPETENCE_ENGAGED_DELTA, confidence.score, 0.0001)
    }

    @Test
    fun `CORRECTED DISENGAGED and NEUTRAL signals never lower confidence`() = runTest {
        val engram = InMemoryEngramClient()
        val svc = service(engram, this)

        for (signal in listOf(OutcomeSignal.CORRECTED, OutcomeSignal.DISENGAGED, OutcomeSignal.NEUTRAL)) {
            svc.recordDemonstratedCompetence("user-1", "kotlin", signal)
        }
        advanceUntilIdle()

        val confidence = engram.getTopicConfidence("user-1", "kotlin")
        assertEquals(0.0, confidence.score, 0.0001, "Non-competence signals must be a no-op, never negative")
    }

    @Test
    fun `null topic is a no-op and never crashes`() = runTest {
        val engram = InMemoryEngramClient()
        val svc = service(engram, this)

        // Must not throw, and must not record evidence anywhere reachable by a real topic.
        svc.recordDemonstratedCompetence("user-1", null, OutcomeSignal.ENGAGED)
        svc.recordExplicitAffirmation("user-1", null)
        svc.recordContradictionDetected("user-1", null)
        svc.recordCorrectionConfirmed("user-1", null)
        advanceUntilIdle()

        assertEquals(0.0, engram.getTopicConfidence("user-1", "kotlin").score)
    }

    // ── Explicit feedback is weighted higher than inferred competence ────────────

    @Test
    fun `explicit affirmation raises confidence more than a single ENGAGED signal`() = runTest {
        val engram = InMemoryEngramClient()
        val svc = service(engram, this)

        svc.recordExplicitAffirmation("user-1", "kotlin")
        advanceUntilIdle()

        val confidence = engram.getTopicConfidence("user-1", "kotlin")
        assertTrue(
            confidence.score > TopicConfidenceService.COMPETENCE_ENGAGED_DELTA,
            "Explicit feedback must outweigh inferred competence, got score=${confidence.score}",
        )
        assertEquals(ConfidenceEvidenceKind.FEEDBACK_AFFIRMED, confidence.evidence.last().kind)
    }

    // ── Contradiction lifecycle: detected -> confirmed, resolves upward only ─────

    @Test
    fun `contradiction detected sets the flag without changing score`() = runTest {
        val engram = InMemoryEngramClient()
        val svc = service(engram, this)

        svc.recordContradictionDetected("user-1", "kotlin")
        advanceUntilIdle()

        val confidence = engram.getTopicConfidence("user-1", "kotlin")
        assertTrue(confidence.hasUnresolvedContradiction)
        assertEquals(0.0, confidence.score, 0.0001)
    }

    @Test
    fun `confirmed correction clears the contradiction flag and raises confidence`() = runTest {
        val engram = InMemoryEngramClient()
        val svc = service(engram, this)

        svc.recordContradictionDetected("user-1", "kotlin")
        advanceUntilIdle()
        svc.recordCorrectionConfirmed("user-1", "kotlin")
        advanceUntilIdle()

        val confidence = engram.getTopicConfidence("user-1", "kotlin")
        assertFalse(confidence.hasUnresolvedContradiction, "Confirmation must resolve upward, never leave it unresolved")
        assertEquals(TopicConfidenceService.CORRECTION_CONFIRMED_DELTA, confidence.score, 0.0001)
    }

    // ── Monotonic: score only ever accumulates non-negative deltas ───────────────

    @Test
    fun `repeated evidence never decreases the running score`() = runTest {
        val engram = InMemoryEngramClient()
        val svc = service(engram, this)

        var previousScore = 0.0
        val events = listOf(
            { svc.recordDemonstratedCompetence("user-1", "kotlin", OutcomeSignal.ENGAGED) },
            { svc.recordDemonstratedCompetence("user-1", "kotlin", OutcomeSignal.CORRECTED) }, // no-op
            { svc.recordExplicitAffirmation("user-1", "kotlin") },
            { svc.recordContradictionDetected("user-1", "kotlin") }, // flag only
            { svc.recordCorrectionConfirmed("user-1", "kotlin") },
        )
        for (event in events) {
            event()
            advanceUntilIdle()
            val current = engram.getTopicConfidence("user-1", "kotlin").score
            assertTrue(current >= previousScore, "Score must never decrease: was $previousScore, now $current")
            previousScore = current
        }
        assertTrue(previousScore > 0.0, "Score should have accumulated across the evidence events")
    }

    // ── Evidence cap ──────────────────────────────────────────────────────────

    @Test
    fun `evidence list is capped at 20 entries`() = runTest {
        val engram = InMemoryEngramClient()
        val svc = service(engram, this)

        repeat(25) { svc.recordExplicitAffirmation("user-1", "kotlin") }
        advanceUntilIdle()

        val confidence = engram.getTopicConfidence("user-1", "kotlin")
        assertEquals(20, confidence.evidence.size)
    }

    // ── Phase bucketing ───────────────────────────────────────────────────────

    @Test
    fun `phaseFor buckets score into the expected ConfidencePhase`() {
        assertEquals(ConfidencePhase.ORIENTATION, TopicConfidenceService.phaseFor(0.0))
        assertEquals(ConfidencePhase.WORKING_RHYTHM, TopicConfidenceService.phaseFor(TopicConfidenceService.WORKING_RHYTHM_THRESHOLD))
        assertEquals(ConfidencePhase.CONTEXT, TopicConfidenceService.phaseFor(TopicConfidenceService.CONTEXT_THRESHOLD))
        assertEquals(ConfidencePhase.UNDERSTANDING, TopicConfidenceService.phaseFor(TopicConfidenceService.UNDERSTANDING_THRESHOLD))
    }
}
