package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.pipeline.scaffold.TrustPhaseTransitionService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// ─────────────────────────────────────────────────────────────────────────────
// MemoryWriteService - async write path tests
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryWriteServiceTest {

    // ── captureUtterance is non-suspending and returns immediately ─────────────

    @Test
    fun `captureUtterance is non-blocking - function is not suspend`() {
        val engram = InMemoryEngramClient()
        val service = MemoryWriteService(engram, TestScope())
        // Called directly without runTest - must compile and not throw
        service.captureUtterance(
            utterance = "I build Android apps.",
            userId    = "user-1",
            sessionId = "s1",
            turnIndex = 0,
        )
        // No exception, no suspend required → non-blocking ✓
    }

    // ── phrases ingested asynchronously ───────────────────────────────────────

    @Test
    fun `phrase ingestion happens asynchronously`() = runTest {
        val engram  = InMemoryEngramClient()
        val service = MemoryWriteService(engram, this)

        service.captureUtterance(
            utterance = "I love Kotlin.",
            userId    = "user-1",
            sessionId = "s1",
            turnIndex = 1,
        )

        assertTrue(engram.allPhrases().isEmpty(), "Expected no phrases before coroutine runs")

        advanceUntilIdle()

        assertTrue(engram.allPhrases().isNotEmpty(), "Expected phrase to be ingested after advanceUntilIdle")
    }

    // ── write failure does not propagate ──────────────────────────────────────

    @Test
    fun `write failure does not propagate to caller`() = runTest {
        val delegate = InMemoryEngramClient()
        val throwingEngram = object : EngramClient by delegate {
            override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> =
                throw RuntimeException("simulated decompose failure")
        }
        val service = MemoryWriteService(throwingEngram, this)

        service.captureUtterance(
            utterance = "anything",
            userId    = "user-1",
            sessionId = "s1",
            turnIndex = 0,
        )
        advanceUntilIdle()
    }

    // ── ingest failure also does not propagate ─────────────────────────────────

    @Test
    fun `ingest failure does not propagate to caller`() = runTest {
        val delegate2 = InMemoryEngramClient()
        val throwingEngram = object : EngramClient by delegate2 {
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String): Unit =
                throw RuntimeException("simulated ingest failure")
        }
        val service = MemoryWriteService(throwingEngram, this)

        service.captureUtterance(
            utterance = "I prefer async patterns.",
            userId    = "user-2",
            sessionId = "s1",
            turnIndex = 1,
        )
        advanceUntilIdle()
        // No exception thrown → pass
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MemoryWriteService — answeredCategories fold + trust-phase advance wiring
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryWriteServiceScaffoldTest {

    @Test
    fun `a newly-disclosed category is folded into answeredCategories`() = runTest {
        val engram  = InMemoryEngramClient()
        val service = MemoryWriteService(engram, this)

        service.captureUtterance("I'm a backend engineer.", "user-1", "s1", 1)
        advanceUntilIdle()

        val state = engram.getScaffoldState("user-1")
        assertTrue(
            PhraseCategory.IDENTITY in state.answeredCategories,
            "Expected IDENTITY in answeredCategories, got: ${state.answeredCategories}",
        )
    }

    @Test
    fun `a category already recorded does not trigger a redundant scaffold write`() = runTest {
        val delegate = InMemoryEngramClient()
        delegate.updateScaffoldState("user-1", ScaffoldState(answeredCategories = setOf(PhraseCategory.IDENTITY)))
        var updateCalls = 0
        val countingEngram = object : EngramClient by delegate {
            override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) {
                updateCalls++
                delegate.updateScaffoldState(userId, state)
            }
        }
        val service = MemoryWriteService(countingEngram, this)

        service.captureUtterance("I'm a backend engineer.", "user-1", "s1", 1)
        advanceUntilIdle()

        assertEquals(0, updateCalls, "Category set did not grow — expected no scaffold write")
    }

    @Test
    fun `advance rule fires end-to-end when a fold crosses the threshold`() = runTest {
        val engram = InMemoryEngramClient()
        engram.updateScaffoldState(
            "user-1",
            ScaffoldState(trustPhase = 1, answeredCategories = setOf(PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE)),
        )
        val clock = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC)
        val transitionService = TrustPhaseTransitionService(engram, clock)
        val service = MemoryWriteService(engram, this, transitionService)

        // "I'm a backend engineer." -> IDENTITY, crossing 3 answeredCategories with a
        // fundamental category present -> ORIENTATION (1) -> WORKING_RHYTHM (2).
        service.captureUtterance("I'm a backend engineer.", "user-1", "s1", 1)
        advanceUntilIdle()

        val state = engram.getScaffoldState("user-1")
        assertEquals(2, state.trustPhase, "Expected trust phase to advance to WORKING_RHYTHM")
        assertEquals(1, state.phaseTransitions.size)
    }

    @Test
    fun `advance check runs even when the turn produces no candidates, if sessionCount alone crosses the bar`() = runTest {
        val engram = InMemoryEngramClient()
        engram.updateScaffoldState(
            "user-1",
            ScaffoldState(
                trustPhase = 2,
                answeredCategories = setOf(
                    PhraseCategory.IDENTITY, PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE,
                    PhraseCategory.ROUTINE, PhraseCategory.RELATIONSHIP,
                ),
                sessionCount = 3,
            ),
        )
        val clock = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC)
        val transitionService = TrustPhaseTransitionService(engram, clock)
        val service = MemoryWriteService(engram, this, transitionService)

        // Blank input decomposes to zero candidates — the advance check must still run.
        service.captureUtterance("", "user-1", "s1", 4)
        advanceUntilIdle()

        val state = engram.getScaffoldState("user-1")
        assertEquals(3, state.trustPhase, "Expected trust phase to advance to CONTEXT")
    }

    @Test
    fun `no transitionService (default) leaves trust phase untouched`() = runTest {
        val engram = InMemoryEngramClient()
        engram.updateScaffoldState(
            "user-1",
            ScaffoldState(
                trustPhase = 1,
                answeredCategories = setOf(PhraseCategory.EXPERTISE, PhraseCategory.PREFERENCE),
            ),
        )
        val service = MemoryWriteService(engram, this) // transitionService defaults to null

        service.captureUtterance("I'm a backend engineer.", "user-1", "s1", 1)
        advanceUntilIdle()

        // answeredCategories still folds even without a transitionService...
        assertTrue(PhraseCategory.IDENTITY in engram.getScaffoldState("user-1").answeredCategories)
        // ...but trust phase never advances without one.
        assertEquals(1, engram.getScaffoldState("user-1").trustPhase)
    }

    @Test
    fun `scaffold-state write failure during the fold does not propagate to caller`() = runTest {
        val delegate = InMemoryEngramClient()
        val throwingEngram = object : EngramClient by delegate {
            override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) {
                throw RuntimeException("simulated scaffold write failure")
            }
        }
        val service = MemoryWriteService(throwingEngram, this)

        service.captureUtterance("I'm a backend engineer.", "user-1", "s1", 1)
        advanceUntilIdle()
        // No exception thrown → pass
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// InMemoryEngramClient - contrastive marker splitting tests
// ─────────────────────────────────────────────────────────────────────────────

class ContrastiveDecomposeTest {

    @Test
    fun `contrastive marker - but - splits into two phrases`() = runTest {
        val client     = InMemoryEngramClient()
        val candidates = client.decompose("I like React but I think Vue is better.", emptyList())
        assertEquals(2, candidates.size, "Expected 2 candidates for contrasted sentence, got: ${candidates.map { it.content }}")
    }

    @Test
    fun `contrastive marker - however - splits into two phrases`() = runTest {
        val client     = InMemoryEngramClient()
        val candidates = client.decompose("I enjoy deep work however interruptions are common.", emptyList())
        assertEquals(2, candidates.size)
    }

    @Test
    fun `contrastive marker - although - splits into two phrases`() = runTest {
        val client     = InMemoryEngramClient()
        val candidates = client.decompose("I prefer Kotlin although I also write Python.", emptyList())
        assertEquals(2, candidates.size)
    }

    @Test
    fun `single sentence without contrastive markers returns one phrase`() = runTest {
        val client     = InMemoryEngramClient()
        val candidates = client.decompose("I work remotely from Berlin.", emptyList())
        assertEquals(1, candidates.size)
    }

    @Test
    fun `multi-sentence input without markers produces one phrase per sentence`() = runTest {
        val client     = InMemoryEngramClient()
        val candidates = client.decompose(
            "I am a backend engineer. I use Kotlin and Python.",
            emptyList(),
        )
        assertEquals(2, candidates.size)
    }

    @Test
    fun `multi-sentence input with contrastive marker in one sentence splits that sentence`() = runTest {
        val client     = InMemoryEngramClient()
        val candidates = client.decompose(
            "I am a backend engineer. I like Kotlin but I also use Java.",
            emptyList(),
        )
        // Sentence 1: 1 phrase; Sentence 2 splits into 2 → total 3
        assertEquals(3, candidates.size)
    }
}
