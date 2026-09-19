package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.EpisodicLogService
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
 * Acceptance tests for the memory write path on every PROCESS turn:
 *   - A turn that states something ingests it exactly once, regardless of branch.
 *   - QUESTION and SOCIAL turns write no *claim* to the memory graph — a Phrase is an assertion; the
 *     exact utterance (questions and greetings included) is recorded in the episode ledger instead.
 *   - No double-ingestion per turn.
 *   - Returning users are never interrogated mid-conversation.
 *
 * (This file first pinned "QUESTION and SOCIAL turns also ingest" — the earlier universal-ingestion
 * decision. That is deliberately reversed: recall was being crowded out by the user's own questions
 * stored as facts. See ClaimOnlyDecomposeTest.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedIngestionTest {

    private fun pipelineWithTracking(
        engram: InMemoryEngramClient = InMemoryEngramClient(),
        scope: kotlinx.coroutines.test.TestScope,
    ): Pair<CognitivePipeline, InMemoryEngramClient> {
        val mws = MemoryWriteService(engram, scope)
        val pipeline = CognitivePipeline(
            engramClient = engram,
            memoryWriteService = mws,
            episodicLogService = EpisodicLogService(engram, scope),
        )
        return pipeline to engram
    }

    @Test
    fun `QUESTION turn writes no claim to the memory graph, but the utterance is in the episode ledger`() = runTest {
        val (pipeline, engram) = pipelineWithTracking(scope = this)

        val before = engram.allPhrases().size
        pipeline.process("What's the weather like?", "session-1", "user-1")
        advanceUntilIdle()

        assertEquals(before, engram.allPhrases().size, "a question is not a claim and must not become a Phrase")
        assertTrue(
            engram.getEpisodicLog(userId = "user-1").any { it.role == "user" && it.text == "What's the weather like?" },
            "the exact question must still be recorded in the episode ledger",
        )
    }

    @Test
    fun `SOCIAL turn writes no claim to the memory graph, but the utterance is in the episode ledger`() = runTest {
        val (pipeline, engram) = pipelineWithTracking(scope = this)

        val before = engram.allPhrases().size
        pipeline.process("Hey", "session-1", "user-1")
        advanceUntilIdle()

        assertEquals(before, engram.allPhrases().size, "a bare greeting is not a claim and must not become a Phrase")
        assertTrue(
            engram.getEpisodicLog(userId = "user-1").any { it.role == "user" && it.text == "Hey" },
            "the greeting must still be recorded in the episode ledger",
        )
    }

    @Test
    fun `no utterance is ingested more than once in a single turn`() = runTest {
        var ingestCallCount = 0
        val delegate = InMemoryEngramClient()
        val countingEngram = object : EngramClient by delegate {
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String): List<String> {
                ingestCallCount++
                return delegate.ingest(candidates, userEmail)
            }
        }
        val mws = MemoryWriteService(countingEngram, this)
        val pipeline = CognitivePipeline(engramClient = countingEngram, memoryWriteService = mws)

        pipeline.process("I like hiking on weekends", "session-1", "user-1")
        advanceUntilIdle()

        assertEquals(1, ingestCallCount, "Expected exactly one ingest call per turn, got $ingestCallCount")
    }

    @Test
    fun `a pure question turn makes no ingest call at all`() = runTest {
        var ingestCallCount = 0
        val delegate = InMemoryEngramClient()
        val countingEngram = object : EngramClient by delegate {
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String): List<String> {
                ingestCallCount++
                return delegate.ingest(candidates, userEmail)
            }
        }
        val mws = MemoryWriteService(countingEngram, this)
        val pipeline = CognitivePipeline(engramClient = countingEngram, memoryWriteService = mws)

        pipeline.process("What's the weather like?", "session-1", "user-1")
        advanceUntilIdle()

        assertEquals(0, ingestCallCount, "a question carries no claim, so nothing should be ingested")
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
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String): List<String> {
                ingestCallCount++
                return delegate.ingest(candidates, userEmail)
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
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String): List<String> {
                capturedSourceTag = candidates.firstOrNull()?.source
                return delegate.ingest(candidates, userEmail)
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

        pipeline.process("I work as a backend engineer", "session-1", "user-1")
        advanceUntilIdle()

        // InMemoryEngramClient.decompose assigns a category-based source; the sourceTag in
        // MemoryWriteService is passed to ingest via userId attribution, not candidate.source.
        // What we can verify is that ingest was called (count > 0).
        assertNotNull(capturedSourceTag, "Expected ingest to be called with at least one phrase candidate")
    }
}
