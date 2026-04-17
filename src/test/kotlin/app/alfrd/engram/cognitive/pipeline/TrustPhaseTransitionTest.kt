package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.pipeline.scaffold.TransitionDecision
import app.alfrd.engram.cognitive.pipeline.scaffold.TrustPhaseTransitionService
import app.alfrd.engram.cognitive.pipeline.selection.SelectionScorer
import app.alfrd.engram.model.OutcomeEdge
import app.alfrd.engram.model.ResponsePhrase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// TrustPhaseTransitionService — integration tests
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class TrustPhaseTransitionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun service(engram: InMemoryEngramClient, clock: Clock = Clock.systemUTC()) =
        TrustPhaseTransitionService(engram, clock)

    private fun outcomeEdge(signal: String = "CORRECTED") = OutcomeEdge(
        phraseUid       = "p1",
        sessionId       = "s1",
        userId          = "u1",
        turnIndex       = 1,
        signal          = signal,
        contextSnapshot = "",
        timestamp       = System.currentTimeMillis(),
    )

    private fun phrase(phaseAffinity: Set<String>): ResponsePhrase = ResponsePhrase(
        uid             = "phrase-1",
        text            = "Test phrase",
        hash            = "h1",
        visibility      = "internal",
        createdAt       = 0L,
        updatedAt       = 0L,
        branchAffinity  = setOf("SOCIAL"),
        phaseAffinity   = phaseAffinity,
        expressionPhase = "ACKNOWLEDGE",
        category        = "GREETING",
    )

    // ── 1. Ingestion drives advancement ───────────────────────────────────────

    @Test
    fun `ingestion of IDENTITY EXPERTISE PREFERENCE advances from ORIENTATION to WORKING_RHYTHM`() = runTest {
        val engram  = InMemoryEngramClient()
        val svc     = service(engram)
        val userId  = "user-advance-1"

        // Pre-load three categories including a foundational one
        val state = ScaffoldState(
            trustPhase         = 1,
            answeredCategories = setOf(PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE),
        )
        engram.updateScaffoldState(userId, state)

        val decision = svc.evaluate(engram.getScaffoldState(userId))

        assertTrue(decision is TransitionDecision.Transition, "Expected a Transition, got $decision")
        val transition = decision as TransitionDecision.Transition
        assertEquals(1, transition.from)
        assertEquals(2, transition.to)
        assertTrue(
            transition.evidence.contains("foundational identity"),
            "Evidence should mention foundational identity, got: ${transition.evidence}",
        )
    }

    @Test
    fun `apply writes updated trustPhase and records a phaseTransitions entry`() = runTest {
        val engram  = InMemoryEngramClient()
        val svc     = service(engram)
        val userId  = "user-apply-1"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 1,
            answeredCategories = setOf(PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE),
        ))

        val decision = svc.evaluate(engram.getScaffoldState(userId)) as TransitionDecision.Transition
        svc.apply(userId, decision)

        val after = engram.getScaffoldState(userId)
        assertEquals(2, after.trustPhase, "Phase should be WORKING_RHYTHM after apply")
        assertEquals(1, after.phaseTransitions.size, "Expected exactly one transition record")
        val record = after.phaseTransitions.first()
        assertEquals("ORIENTATION", record.from)
        assertEquals("WORKING_RHYTHM", record.to)
        assertTrue(record.evidence.isNotBlank())
    }

    @Test
    fun `fewer than 3 categories does not advance to WORKING_RHYTHM`() = runTest {
        val engram = InMemoryEngramClient()
        val svc    = service(engram)
        val userId = "user-no-advance-1"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 1,
            answeredCategories = setOf(PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE),
        ))

        val decision = svc.evaluate(engram.getScaffoldState(userId))
        assertSame(TransitionDecision.NoChange, decision, "Should not advance with only 2 categories")
    }

    @Test
    fun `3 categories without IDENTITY or EXPERTISE does not advance to WORKING_RHYTHM`() = runTest {
        val engram = InMemoryEngramClient()
        val svc    = service(engram)
        val userId = "user-no-advance-2"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 1,
            answeredCategories = setOf(PhraseCategory.PREFERENCE, PhraseCategory.ROUTINE, PhraseCategory.CONTEXT),
        ))

        val decision = svc.evaluate(engram.getScaffoldState(userId))
        assertSame(TransitionDecision.NoChange, decision, "Should not advance without foundational identity")
    }

    // ── 2. Session count gates CONTEXT ────────────────────────────────────────

    @Test
    fun `5 categories in one session does not advance to CONTEXT`() = runTest {
        val engram = InMemoryEngramClient()
        val svc    = service(engram)
        val userId = "user-no-context-1"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 2,
            answeredCategories = setOf(
                PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE,
                PhraseCategory.ROUTINE, PhraseCategory.RELATIONSHIP,
            ),
            sessionCount = 1, // only 1 session — gate should block
        ))

        val decision = svc.evaluate(engram.getScaffoldState(userId))
        assertSame(TransitionDecision.NoChange, decision, "Should not advance to CONTEXT with sessionCount=1")
    }

    @Test
    fun `5 categories across 3 sessions advances to CONTEXT`() = runTest {
        val engram = InMemoryEngramClient()
        val svc    = service(engram)
        val userId = "user-context-1"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 2,
            answeredCategories = setOf(
                PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE,
                PhraseCategory.ROUTINE, PhraseCategory.RELATIONSHIP,
            ),
            sessionCount = 3,
        ))

        val decision = svc.evaluate(engram.getScaffoldState(userId))
        assertTrue(decision is TransitionDecision.Transition)
        assertEquals(3, (decision as TransitionDecision.Transition).to)
    }

    // ── 3. OUTCOME gates UNDERSTANDING ────────────────────────────────────────

    @Test
    fun `6 categories and 10 sessions without CORRECTED edge stays in CONTEXT`() = runTest {
        val engram = InMemoryEngramClient()
        val svc    = service(engram)
        val userId = "user-no-understanding-1"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 3,
            answeredCategories = PhraseCategory.entries.toSet(), // all 6
            sessionCount       = 10,
        ))

        // No CORRECTED edge
        val decision = svc.evaluate(engram.getScaffoldState(userId), emptyList())
        assertSame(TransitionDecision.NoChange, decision, "Should stay in CONTEXT without CORRECTED edge")
    }

    @Test
    fun `6 categories and 10 sessions with CORRECTED edge advances to UNDERSTANDING`() = runTest {
        val engram = InMemoryEngramClient()
        val svc    = service(engram)
        val userId = "user-understanding-1"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 3,
            answeredCategories = PhraseCategory.entries.toSet(),
            sessionCount       = 10,
        ))

        val correctedEdge = outcomeEdge("CORRECTED")
        val decision = svc.evaluate(engram.getScaffoldState(userId), listOf(correctedEdge))
        assertTrue(decision is TransitionDecision.Transition)
        assertEquals(4, (decision as TransitionDecision.Transition).to)
        assertTrue(decision.evidence.contains("corrected"))
    }

    @Test
    fun `non-CORRECTED outcome signals do not advance to UNDERSTANDING`() = runTest {
        val engram = InMemoryEngramClient()
        val svc    = service(engram)
        val userId = "user-no-understanding-2"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 3,
            answeredCategories = PhraseCategory.entries.toSet(),
            sessionCount       = 10,
        ))

        val decision = svc.evaluate(
            engram.getScaffoldState(userId),
            listOf(outcomeEdge("ENGAGED"), outcomeEdge("EXPANDED")),
        )
        assertSame(TransitionDecision.NoChange, decision, "ENGAGED/EXPANDED signals should not trigger UNDERSTANDING")
    }

    // ── 4. Graceful degradation without OUTCOME data ──────────────────────────

    @Test
    fun `evaluate with empty outcomeEdges never fires UNDERSTANDING and raises no exception`() = runTest {
        val engram = InMemoryEngramClient()
        val svc    = service(engram)
        val userId = "user-graceful-1"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 3,
            answeredCategories = PhraseCategory.entries.toSet(),
            sessionCount       = 15,
        ))

        // Must not throw; must return NoChange
        val decision = svc.evaluate(engram.getScaffoldState(userId)) // default emptyList()
        assertSame(TransitionDecision.NoChange, decision)
    }

    // ── 5. Amendment does not trigger spurious transitions ────────────────────

    @Test
    fun `amending an already-answered category does not re-trigger transitions`() = runTest {
        val dispatcher = StandardTestDispatcher()
        val scope      = TestScope(dispatcher)
        val engram     = InMemoryEngramClient()
        val svc        = service(engram)
        val userId     = "user-amend-1"

        // User is already at WORKING_RHYTHM with IDENTITY answered
        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 2,
            answeredCategories = setOf(PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE),
        ))

        val writeService = MemoryWriteService(engram, scope, svc)

        // Capture with IDENTITY — category already answered → no state change, no transition eval
        writeService.captureUtterance(
            utterance        = "I'm still a software engineer.",
            userId           = userId,
            sessionId        = "s1",
            turnIndex        = 5,
            scaffoldCategory = "IDENTITY",
        )
        scope.advanceUntilIdle()

        val after = engram.getScaffoldState(userId)
        // Phase must not have changed
        assertEquals(2, after.trustPhase, "Phase should remain WORKING_RHYTHM after amendment")
        // phaseTransitions must not have grown
        assertTrue(after.phaseTransitions.isEmpty(), "No transitions expected after amendment")
    }

    // ── 6. Query respects phase ───────────────────────────────────────────────

    @Test
    fun `ORIENTATION-only phrase scores 0_3 adjacent when user is in WORKING_RHYTHM`() {
        val workingRhythmCtx = CognitiveContext(
            utterance  = "",
            sessionId  = "s1",
            userId     = "u1",
            trustPhase = "WORKING_RHYTHM",
        )

        val orientationPhrase = phrase(phaseAffinity = setOf("ORIENTATION"))
        val score = SelectionScorer.phaseAppropriateness(orientationPhrase, workingRhythmCtx)

        assertEquals(0.3, score, 0.001, "Adjacent phase should score 0.3")
    }

    @Test
    fun `WORKING_RHYTHM phrase scores 1_0 when user is in WORKING_RHYTHM`() {
        val ctx = CognitiveContext(
            utterance  = "",
            sessionId  = "s1",
            userId     = "u1",
            trustPhase = "WORKING_RHYTHM",
        )

        val matchingPhrase = phrase(phaseAffinity = setOf("WORKING_RHYTHM"))
        val score = SelectionScorer.phaseAppropriateness(matchingPhrase, ctx)

        assertEquals(1.0, score, 0.001, "Current phase should score 1.0")
    }

    @Test
    fun `CONTEXT phrase scores 0_0 when user is in ORIENTATION`() {
        val ctx = CognitiveContext(
            utterance  = "",
            sessionId  = "s1",
            userId     = "u1",
            trustPhase = "ORIENTATION",
        )
        val contextPhrase = phrase(phaseAffinity = setOf("CONTEXT"))
        val score = SelectionScorer.phaseAppropriateness(contextPhrase, ctx)

        assertEquals(0.0, score, 0.001, "Non-adjacent phase should score 0.0")
    }

    // ── 7. Dormancy regression ────────────────────────────────────────────────

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

    // ── 8. Dormancy cap ───────────────────────────────────────────────────────

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

    // ── 9. Idempotency ────────────────────────────────────────────────────────

    @Test
    fun `applying same transition decision twice records only one phaseTransitions entry`() = runTest {
        val engram  = InMemoryEngramClient()
        val svc     = service(engram)
        val userId  = "user-idempotent-1"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 1,
            answeredCategories = setOf(PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE),
        ))

        val decision = svc.evaluate(engram.getScaffoldState(userId)) as TransitionDecision.Transition

        svc.apply(userId, decision)
        svc.apply(userId, decision) // second call must be a no-op

        val after = engram.getScaffoldState(userId)
        assertEquals(2, after.trustPhase)
        assertEquals(1, after.phaseTransitions.size, "Second apply must not add a duplicate transition record")
    }

    // ── 10. Concurrent writes ─────────────────────────────────────────────────

    @Test
    fun `concurrent captureUtterance calls on same user do not double-transition`() = runTest {
        val dispatcher = StandardTestDispatcher()
        val scope      = TestScope(dispatcher)
        val engram     = InMemoryEngramClient()
        val svc        = service(engram)
        val userId     = "user-concurrent-1"

        // Seed state: 2 categories answered already; adding PREFERENCE will make 3 (IDENTITY present)
        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 1,
            answeredCategories = setOf(PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE),
        ))

        val writeService = MemoryWriteService(engram, scope, svc)

        // Two concurrent captures of the same new category
        scope.launch {
            writeService.captureUtterance(
                utterance        = "I prefer remote work.",
                userId           = userId,
                sessionId        = "s1",
                turnIndex        = 1,
                scaffoldCategory = "PREFERENCE",
            )
        }
        scope.launch {
            writeService.captureUtterance(
                utterance        = "I prefer async communication.",
                userId           = userId,
                sessionId        = "s1",
                turnIndex        = 2,
                scaffoldCategory = "PREFERENCE",
            )
        }

        scope.advanceUntilIdle()

        val after = engram.getScaffoldState(userId)
        assertEquals(2, after.trustPhase, "Phase should be WORKING_RHYTHM after concurrent advance")
        // Even if both coroutines ran, idempotency must ensure exactly one transition record
        assertEquals(
            1, after.phaseTransitions.size,
            "Concurrent transitions must result in exactly one phaseTransitions record, got: ${after.phaseTransitions.size}",
        )
    }

    // ── End-to-end: MemoryWriteService drives advancement via transition service ──

    @Test
    fun `MemoryWriteService advances phase after new category triggers criteria`() = runTest {
        val dispatcher = StandardTestDispatcher()
        val scope      = TestScope(dispatcher)
        val engram     = InMemoryEngramClient()
        val svc        = service(engram)
        val userId     = "user-e2e-1"

        // Pre-load two categories
        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 1,
            answeredCategories = setOf(PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE),
        ))

        val writeService = MemoryWriteService(engram, scope, svc)

        // Adding PREFERENCE should cross the 3-category + foundational threshold
        writeService.captureUtterance(
            utterance        = "I prefer pair programming.",
            userId           = userId,
            sessionId        = "s1",
            turnIndex        = 3,
            scaffoldCategory = "PREFERENCE",
        )

        scope.advanceUntilIdle()

        val after = engram.getScaffoldState(userId)
        assertEquals(2, after.trustPhase, "Phase should advance to WORKING_RHYTHM after threshold met")
        assertEquals(1, after.phaseTransitions.size)
    }

    @Test
    fun `MemoryWriteService does not advance phase when threshold not met`() = runTest {
        val dispatcher = StandardTestDispatcher()
        val scope      = TestScope(dispatcher)
        val engram     = InMemoryEngramClient()
        val svc        = service(engram)
        val userId     = "user-e2e-2"

        engram.updateScaffoldState(userId, ScaffoldState(
            trustPhase         = 1,
            answeredCategories = setOf(PhraseCategory.IDENTITY),
        ))

        val writeService = MemoryWriteService(engram, scope, svc)

        // Only one category — does not meet threshold of 3
        writeService.captureUtterance(
            utterance        = "I prefer Kotlin.",
            userId           = userId,
            sessionId        = "s1",
            turnIndex        = 2,
            scaffoldCategory = "EXPERTISE",
        )

        scope.advanceUntilIdle()

        val after = engram.getScaffoldState(userId)
        assertEquals(1, after.trustPhase, "Phase should remain ORIENTATION with only 2 categories")
        assertTrue(after.phaseTransitions.isEmpty())
    }
}
