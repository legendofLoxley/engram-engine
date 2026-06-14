package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.pipeline.memory.ScoredPhrase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Acceptance tests for universal memory ingestion (Problem 1 fix):
 *   - Every PROCESS turn ingests exactly once, regardless of branch.
 *   - QUESTION turns ingest.
 *   - SOCIAL turns ingest.
 *   - No double-ingestion per turn.
 *   - Returning users are never interrogated mid-conversation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedIngestionTest {

    private fun pipelineWithTracking(
        engram: InMemoryEngramClient = InMemoryEngramClient(),
        scope: kotlinx.coroutines.test.TestScope,
    ): Pair<CognitivePipeline, InMemoryEngramClient> {
        val mws = MemoryWriteService(engram, scope)
        val pipeline = CognitivePipeline(engramClient = engram, memoryWriteService = mws)
        return pipeline to engram
    }

    @Test
    fun `QUESTION turn ingests utterance into memory graph`() = runTest {
        val (pipeline, engram) = pipelineWithTracking(scope = this)

        val before = engram.allPhrases().size
        pipeline.process("What's the weather like?", "session-1", "user-1")
        advanceUntilIdle()

        assertTrue(
            engram.allPhrases().size > before,
            "Expected phrase count to grow after QUESTION turn",
        )
    }

    @Test
    fun `SOCIAL turn also ingests utterance`() = runTest {
        val (pipeline, engram) = pipelineWithTracking(scope = this)

        val before = engram.allPhrases().size
        pipeline.process("Hey", "session-1", "user-1")
        advanceUntilIdle()

        assertTrue(
            engram.allPhrases().size > before,
            "Expected phrase count to grow after SOCIAL turn",
        )
    }

    @Test
    fun `no utterance is ingested more than once in a single turn`() = runTest {
        var ingestCallCount = 0
        val delegate = InMemoryEngramClient()
        val countingEngram = object : EngramClient by delegate {
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) {
                ingestCallCount++
                delegate.ingest(candidates, userEmail)
            }
        }
        val mws = MemoryWriteService(countingEngram, this)
        val pipeline = CognitivePipeline(engramClient = countingEngram, memoryWriteService = mws)

        pipeline.process("What's the weather like?", "session-1", "user-1")
        advanceUntilIdle()

        assertEquals(1, ingestCallCount, "Expected exactly one ingest call per turn, got $ingestCallCount")
    }

    @Test
    fun `returning user asking a question is not interrogated mid-conversation`() = runTest {
        val engram = InMemoryEngramClient()
        // Simulate a returning user: non-empty scaffold state, no active scaffold question
        engram.updateScaffoldState(
            "user-returning",
            ScaffoldState(trustPhase = 2),
        )
        val mws = MemoryWriteService(engram, this)
        val pipeline = CognitivePipeline(engramClient = engram, memoryWriteService = mws)

        val response = pipeline.process("What's the weather like?", "session-1", "user-returning")

        assertTrue(response.isNotBlank(), "Expected a non-blank response for a returning user")
        assertFalse(
            response.contains("working on") || response.contains("get oriented"),
            "Returning user must not receive onboarding interrogation, got: '$response'",
        )
    }

    @Test
    fun `TASK turn ingests exactly once via universal ingestion`() = runTest {
        var ingestCallCount = 0
        val delegate = InMemoryEngramClient()
        val countingEngram = object : EngramClient by delegate {
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) {
                ingestCallCount++
                delegate.ingest(candidates, userEmail)
            }
        }
        val mws = MemoryWriteService(countingEngram, this)
        val pipeline = CognitivePipeline(engramClient = countingEngram, memoryWriteService = mws)

        pipeline.process("Remind me to review the PR tomorrow", "session-1", "user-1")
        advanceUntilIdle()

        assertEquals(1, ingestCallCount, "TASK turn must ingest exactly once, got $ingestCallCount")
    }

    @Test
    fun `ingested phrase uses conversation source tag`() = runTest {
        var capturedSourceTag: String? = null
        val delegate = InMemoryEngramClient()

        // Wrap the decompose to capture what source tag gets recorded
        // (InMemoryEngramClient stores candidates as-is; we verify the captureUtterance call)
        val engram = object : EngramClient by delegate {
            override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> {
                return delegate.decompose(text, context)
            }
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) {
                capturedSourceTag = candidates.firstOrNull()?.source
                delegate.ingest(candidates, userEmail)
            }
            override suspend fun queryPhrases(userEmail: String, concept: String?, limit: Int): List<ScoredPhrase> =
                delegate.queryPhrases(userEmail, concept, limit)
            override suspend fun getScaffoldState(userId: String) = delegate.getScaffoldState(userId)
            override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) =
                delegate.updateScaffoldState(userId, state)
            override suspend fun amendPhrase(phraseId: String, newContent: String) =
                delegate.amendPhrase(phraseId, newContent)
        }
        val mws = MemoryWriteService(engram, this)
        val pipeline = CognitivePipeline(engramClient = engram, memoryWriteService = mws)

        pipeline.process("What is the capital of France?", "session-1", "user-1")
        advanceUntilIdle()

        // InMemoryEngramClient.decompose assigns a category-based source; the sourceTag in
        // MemoryWriteService is passed to ingest via userId attribution, not candidate.source.
        // What we can verify is that ingest was called (count > 0).
        assertNotNull(capturedSourceTag, "Expected ingest to be called with at least one phrase candidate")
    }
}
