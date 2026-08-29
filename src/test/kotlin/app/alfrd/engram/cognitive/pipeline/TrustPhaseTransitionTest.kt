package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.pipeline.scaffold.TransitionDecision
import app.alfrd.engram.cognitive.pipeline.scaffold.TrustPhaseTransitionService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// TrustPhaseTransitionService — dormancy-regression tests.
//
// The session/category-counting advancement model (evaluate()/computeNextPhase()) has been
// removed — it was never invoked on the live turn path and is superseded entirely by per-topic
// confidence (see TopicConfidenceServiceTest). SelectionScorer.contextualFit/phaseAppropriateness
// coverage that used to live here was a duplicate of SelectionScorerTest and has been dropped.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class TrustPhaseTransitionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun service(engram: InMemoryEngramClient, clock: Clock = Clock.systemUTC()) =
        TrustPhaseTransitionService(engram, clock)

    // ── Dormancy regression ────────────────────────────────────────────────────

    @Test
    fun `dormancy of 100 days regresses CONTEXT to WORKING_RHYTHM`() {
        val engram = InMemoryEngramClient()
        val now    = Instant.now()
        val clock  = Clock.fixed(now, ZoneOffset.UTC)
        val svc    = service(engram, clock)

        val daysAgo100 = now.minusMillis(TimeUnit.DAYS.toMillis(100))
        val state = ScaffoldState(trustPhase = 3, lastInteractionAt = daysAgo100.toEpochMilli())

        val decision = svc.evaluateDormancyRegression(state)

        assertTrue(decision is TransitionDecision.Transition)
        val transition = decision as TransitionDecision.Transition
        assertEquals(3, transition.from)
        assertEquals(2, transition.to)
        assertTrue(
            transition.evidence.contains("100 days"),
            "Evidence should mention '100 days', got: ${transition.evidence}",
        )
    }

    @Test
    fun `dormancy of 89 days does not regress`() {
        val engram = InMemoryEngramClient()
        val now    = Instant.now()
        val clock  = Clock.fixed(now, ZoneOffset.UTC)
        val svc    = service(engram, clock)

        val daysAgo89 = now.minusMillis(TimeUnit.DAYS.toMillis(89))
        val state = ScaffoldState(trustPhase = 3, lastInteractionAt = daysAgo89.toEpochMilli())

        val decision = svc.evaluateDormancyRegression(state)
        assertSame(TransitionDecision.NoChange, decision, "89 days is below the 90-day threshold")
    }

    @Test
    fun `dormancy regression of UNDERSTANDING regresses to CONTEXT`() {
        val engram = InMemoryEngramClient()
        val now    = Instant.now()
        val clock  = Clock.fixed(now, ZoneOffset.UTC)
        val svc    = service(engram, clock)

        val daysAgo120 = now.minusMillis(TimeUnit.DAYS.toMillis(120))
        val state = ScaffoldState(trustPhase = 4, lastInteractionAt = daysAgo120.toEpochMilli())

        val decision = svc.evaluateDormancyRegression(state)

        assertTrue(decision is TransitionDecision.Transition)
        assertEquals(4, (decision as TransitionDecision.Transition).from)
        assertEquals(3, decision.to)
    }

    // ── Dormancy cap ───────────────────────────────────────────────────────────

    @Test
    fun `dormancy cap prevents regression below WORKING_RHYTHM for WORKING_RHYTHM user`() {
        val engram = InMemoryEngramClient()
        val now    = Instant.now()
        val clock  = Clock.fixed(now, ZoneOffset.UTC)
        val svc    = service(engram, clock)

        val daysAgo120 = now.minusMillis(TimeUnit.DAYS.toMillis(120))
        val state = ScaffoldState(trustPhase = 2, lastInteractionAt = daysAgo120.toEpochMilli())

        val decision = svc.evaluateDormancyRegression(state)
        assertSame(
            TransitionDecision.NoChange, decision,
            "WORKING_RHYTHM (2) must not regress further from dormancy",
        )
    }

    @Test
    fun `dormancy does not regress ORIENTATION`() {
        val engram = InMemoryEngramClient()
        val now    = Instant.now()
        val clock  = Clock.fixed(now, ZoneOffset.UTC)
        val svc    = service(engram, clock)

        val daysAgo200 = now.minusMillis(TimeUnit.DAYS.toMillis(200))
        val state = ScaffoldState(trustPhase = 1, lastInteractionAt = daysAgo200.toEpochMilli())

        val decision = svc.evaluateDormancyRegression(state)
        assertSame(TransitionDecision.NoChange, decision, "ORIENTATION must not regress from dormancy")
    }

    @Test
    fun `no lastInteractionAt produces no regression`() {
        val engram = InMemoryEngramClient()
        val svc    = service(engram)

        val state = ScaffoldState(trustPhase = 3, lastInteractionAt = null)

        val decision = svc.evaluateDormancyRegression(state)
        assertSame(TransitionDecision.NoChange, decision)
    }

    // ── Idempotency ────────────────────────────────────────────────────────────

    @Test
    fun `applying same dormancy transition twice records only one phaseTransitions entry`() = runTest {
        val engram  = InMemoryEngramClient()
        val now     = Instant.now()
        val clock   = Clock.fixed(now, ZoneOffset.UTC)
        val svc     = service(engram, clock)
        val userId  = "user-idempotent-1"

        val daysAgo100 = now.minusMillis(TimeUnit.DAYS.toMillis(100))
        engram.updateScaffoldState(userId, ScaffoldState(trustPhase = 3, lastInteractionAt = daysAgo100.toEpochMilli()))

        val decision = svc.evaluateDormancyRegression(engram.getScaffoldState(userId)) as TransitionDecision.Transition

        svc.apply(userId, decision)
        svc.apply(userId, decision) // second call must be a no-op

        val after = engram.getScaffoldState(userId)
        assertEquals(2, after.trustPhase)
        assertEquals(1, after.phaseTransitions.size, "Second apply must not add a duplicate transition record")
    }
}
