package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// MemoryWriteService — async write path tests
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryWriteServiceTest {

    // ── captureUtterance is non-suspending and returns immediately ─────────────

    @Test
    fun `captureUtterance is non-blocking — function is not suspend`() {
        // If this compiles and is called from a non-suspend context it proves the signature
        val engram = InMemoryEngramClient()
        val service = MemoryWriteService(engram, TestScope())
        // Called directly without runTest — must compile and not throw
        service.captureUtterance(
            utterance        = "I build Android apps.",
            userId           = "user-1",
            sessionId        = "s1",
            turnIndex        = 0,
            scaffoldCategory = null,
        )
        // No exception, no suspend required → non-blocking ✓
    }

    // ── phrases ingested asynchronously ───────────────────────────────────────

    @Test
    fun `phrase ingestion happens asynchronously`() = runTest {
        val engram  = InMemoryEngramClient()
        val service = MemoryWriteService(engram, this)

        service.captureUtterance(
            utterance        = "I love Kotlin.",
            userId           = "user-1",
            sessionId        = "s1",
            turnIndex        = 1,
            scaffoldCategory = null,
        )

        // Before driving the scheduler the phrase list is still empty
        assertTrue(engram.allPhrases().isEmpty(), "Expected no phrases before coroutine runs")

        advanceUntilIdle()

        // After driving idle the decompose+ingest coroutine has completed
        assertTrue(engram.allPhrases().isNotEmpty(), "Expected phrase to be ingested after advanceUntilIdle")
    }

    // ── write failure does not propagate ──────────────────────────────────────

    @Test
    fun `write failure does not propagate to caller`() = runTest {
        // ThrowingEngramClient always throws from decompose — simulates network failure
        val delegate = InMemoryEngramClient()
        val throwingEngram = object : EngramClient by delegate {
            override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> =
                throw RuntimeException("simulated decompose failure")
        }
        val service = MemoryWriteService(throwingEngram, this)

        // Must not throw, even after idle
        service.captureUtterance(
            utterance        = "anything",
            userId           = "user-1",
            sessionId        = "s1",
            turnIndex        = 0,
            scaffoldCategory = null,
        )
        advanceUntilIdle() // drives the launch — exception is caught internally
    }

    // ── ingest failure also does not propagate ─────────────────────────────────

    @Test
    fun `ingest failure does not propagate to caller`() = runTest {
        val delegate2 = InMemoryEngramClient()
        val throwingEngram = object : EngramClient by delegate2 {
            override suspend fun ingest(candidates: List<PhraseCandidate>): Unit =
                throw RuntimeException("simulated ingest failure")
        }
        val service = MemoryWriteService(throwingEngram, this)

        service.captureUtterance(
            utterance        = "I prefer async patterns.",
            userId           = "user-2",
            sessionId        = "s1",
            turnIndex        = 1,
            scaffoldCategory = null,
        )
        advanceUntilIdle()
        // No exception thrown → pass
    }

    // ── scaffold state updated after successful write ──────────────────────────

    @Test
    fun `scaffold state updated after successful ingestion when scaffoldCategory provided`() = runTest {
        val engram  = InMemoryEngramClient()
        val service = MemoryWriteService(engram, this)

        service.captureUtterance(
            utterance        = "I'm a backend engineer.",
            userId           = "user-3",
            sessionId        = "s1",
            turnIndex        = 1,
            scaffoldCategory = "IDENTITY",
        )

        advanceUntilIdle()

        val state = engram.getScaffoldState("user-3")
        assertTrue(
            PhraseCategory.IDENTITY in state.answeredCategories,
            "IDENTITY should be in answered categories after async write, got: ${state.answeredCategories}",
        )
    }

    @Test
    fun `scaffold state not updated when scaffoldCategory is null`() = runTest {
        val engram  = InMemoryEngramClient()
        val service = MemoryWriteService(engram, this)

        service.captureUtterance(
            utterance        = "remind me to review the PR",
            userId           = "user-4",
            sessionId        = "s1",
            turnIndex        = 2,
            scaffoldCategory = null,
        )

        advanceUntilIdle()

        val state = engram.getScaffoldState("user-4")
        assertTrue(state.answeredCategories.isEmpty(), "No scaffold category should be marked for task_intent")
    }

    @Test
    fun `unknown scaffoldCategory string is silently ignored`() = runTest {
        val engram  = InMemoryEngramClient()
        val service = MemoryWriteService(engram, this)

        service.captureUtterance(
            utterance        = "some input",
            userId           = "user-5",
            sessionId        = "s1",
            turnIndex        = 0,
            scaffoldCategory = "INVALID_CATEGORY_XYZ",
        )

        advanceUntilIdle()

        val state = engram.getScaffoldState("user-5")
        assertTrue(state.answeredCategories.isEmpty(), "Unknown category should not modify scaffold state")
    }

    @Test
    fun `scaffold state not duplicated when category already present`() = runTest {
        val engram  = InMemoryEngramClient()
        // Pre-seed the state with IDENTITY already answered
        engram.updateScaffoldState(
            "user-6",
            ScaffoldState(answeredCategories = setOf(PhraseCategory.IDENTITY))
        )
        val service = MemoryWriteService(engram, this)

        service.captureUtterance(
            utterance        = "I'm still a backend engineer.",
            userId           = "user-6",
            sessionId        = "s1",
            turnIndex        = 3,
            scaffoldCategory = "IDENTITY",
        )

        advanceUntilIdle()

        val state = engram.getScaffoldState("user-6")
        // Should still be exactly the same set — no duplicates
        assertEquals(setOf(PhraseCategory.IDENTITY), state.answeredCategories)
    }

    // ── OnboardingBranch async path ────────────────────────────────────────────

    @Test
    fun `OnboardingBranch with MemoryWriteService does not block response on ingestion`() = runTest {
        val engram  = InMemoryEngramClient()
        // Seed an active scaffold question so the branch goes to the "advance" path
        engram.updateScaffoldState(
            "user-7",
            ScaffoldState(activeScaffoldQuestion = OnboardingBranch.OPENER),
        )
        val llm     = TestLlmClient { LlmResponse(text = "What tools do you use?", latencyMs = 0, retryCount = 0) }
        val service = MemoryWriteService(engram, this)
        val branch  = OnboardingBranch(engram, llm, service)
        val ctx     = CognitiveContext(utterance = "I build mobile apps.", sessionId = "s1", userId = "user-7")

        branch.execute(ctx)

        // Response is set immediately — phrases not yet ingested (async not driven yet)
        assertTrue(ctx.branchResult != null, "Branch should set a result immediately")
        assertTrue(engram.allPhrases().isEmpty(), "Phrases should not be ingested before advanceUntilIdle")

        advanceUntilIdle()

        // After driving the scheduler, ingestion has completed
        assertTrue(engram.allPhrases().isNotEmpty(), "Phrases should be ingested after advanceUntilIdle")
    }

    // ── TaskBranch async path ─────────────────────────────────────────────────

    @Test
    fun `TaskBranch with MemoryWriteService captures intent asynchronously`() = runTest {
        val engram  = InMemoryEngramClient()
        val service = MemoryWriteService(engram, this)
        val branch  = TaskBranch(engram, service)
        val ctx     = CognitiveContext(utterance = "Set a reminder for tomorrow.", sessionId = "s1", userId = "user-8")

        branch.execute(ctx)

        // Response is set, but ingestion is still pending
        assertEquals("I've noted that — task execution is coming soon.", ctx.branchResult?.content)
        assertTrue(engram.allPhrases().isEmpty(), "Phrases should not be ingested before advanceUntilIdle")

        advanceUntilIdle()

        assertTrue(engram.allPhrases().isNotEmpty(), "Phrases should be ingested after advanceUntilIdle")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// InMemoryEngramClient — contrastive marker splitting tests
// ─────────────────────────────────────────────────────────────────────────────

class ContrastiveDecomposeTest {

    @Test
    fun `contrastive marker — but — splits into two phrases`() = runTest {
        val client     = InMemoryEngramClient()
        val candidates = client.decompose("I like React but I think Vue is better.", emptyList())
        assertEquals(2, candidates.size, "Expected 2 candidates for contrasted sentence, got: ${candidates.map { it.content }}")
    }

    @Test
    fun `contrastive marker — however — splits into two phrases`() = runTest {
        val client     = InMemoryEngramClient()
        val candidates = client.decompose("I enjoy deep work however interruptions are common.", emptyList())
        assertEquals(2, candidates.size)
    }

    @Test
    fun `contrastive marker — although — splits into two phrases`() = runTest {
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
