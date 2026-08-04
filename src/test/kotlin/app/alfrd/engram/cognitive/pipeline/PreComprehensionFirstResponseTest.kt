package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Acceptance tests for the text-path pre-comprehension first-response design.
 *
 * Validates that:
 *   - DISCLOSURE/FYI/CONTINUATION inputs return a verbal acknowledgment instead of a branch stub
 *   - QUESTION/TASK_REQUEST inputs still route to their existing branches
 *   - Universal memory ingestion fires exactly once regardless of which branch handles the turn
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreComprehensionFirstResponseTest {

    // Echoes the actor's system prompt back so branch routing can be verified from the
    // directive text — with no LLM, every branch now produces the same centralized
    // degraded string, so routing tests need an LLM to distinguish which branch fired.
    private val echoLlm = TestLlmClient { req: LlmRequest ->
        LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0)
    }
    private val pipeline = CognitivePipeline(llmClient = echoLlm)

    // ── Acceptance criterion 1: Disclosure → verbal acknowledgment ────────────

    @Test
    fun `my dog's name is Newton returns a verbal acknowledgment, not a branch stub`() = runTest {
        val response = pipeline.process("My dog's name is Newton", "session-1", "user-1")
        assertFalse(
            response.contains("Memory queries aren't available yet"),
            "Disclosure must not reach MetaBranch, got: '$response'",
        )
        assertFalse(
            response.contains("task execution is coming soon"),
            "Disclosure must not reach TaskBranch, got: '$response'",
        )
        assertFalse(
            response.contains("Could you say more about what you mean"),
            "Disclosure must not reach ClarificationBranch, got: '$response'",
        )
        assertTrue(response.isNotBlank(), "Expected a non-blank acknowledgment, got: '$response'")
    }

    // ── Acceptance criterion 2: FYI → RECEIPT ────────────────────────────────

    @Test
    fun `FYI I pushed the branch returns a brief receipt, not a task stub`() = runTest {
        val response = pipeline.process("FYI I pushed the branch", "session-1", "user-1")
        assertFalse(
            response.contains("task execution is coming soon"),
            "FYI must not reach TaskBranch, got: '$response'",
        )
        assertFalse(
            response.contains("Could you say more about what you mean"),
            "FYI must not reach ClarificationBranch, got: '$response'",
        )
        assertTrue(response.isNotBlank(), "Expected a brief receipt, got: '$response'")
    }

    @Test
    fun `just so you know the deploy finished returns a verbal acknowledgment`() = runTest {
        val response = pipeline.process("Just so you know the deploy finished successfully", "session-1", "user-1")
        assertFalse(
            response.contains("task execution is coming soon"),
            "FYI must not reach TaskBranch stub, got: '$response'",
        )
        assertTrue(response.isNotBlank())
    }

    // ── Acceptance criterion 3: Disclosure about work in progress ────────────

    @Test
    fun `I'm working on alfrd returns a verbal acknowledgment, not the task stub`() = runTest {
        val response = pipeline.process("I'm working on alfrd", "session-1", "user-1")
        assertFalse(
            response.contains("task execution is coming soon"),
            "Working-on disclosure must not reach TaskBranch stub, got: '$response'",
        )
        assertTrue(response.isNotBlank(), "Expected a verbal acknowledgment, got: '$response'")
    }

    // ── Acceptance criterion 4: Questions still route to QuestionBranch ───────

    @Test
    fun `what's the weather like still routes to QuestionBranch`() = runTest {
        val response = pipeline.process("What's the weather like?", "session-1", "user-1")
        assertTrue(
            response.contains("asked a question", ignoreCase = true),
            "Expected QuestionBranch's directive, got: '$response'",
        )
    }

    // ── Acceptance criterion 5: Task requests still route to TaskBranch ───────

    @Test
    fun `can you update the task status still routes to TaskBranch`() = runTest {
        val response = pipeline.process("Can you update the task status?", "session-1", "user-1")
        assertTrue(
            response.contains("task request", ignoreCase = true),
            "Expected TaskBranch's directive, got: '$response'",
        )
    }

    @Test
    fun `remind me to call the vet still routes to TaskBranch`() = runTest {
        val response = pipeline.process("Remind me to call the vet", "session-1", "user-1")
        assertTrue(
            response.contains("task request", ignoreCase = true),
            "Expected TaskBranch's directive, got: '$response'",
        )
    }

    // ── Acceptance criterion 6: Universal memory ingestion on every PROCESS turn ──

    @Test
    fun `disclosure turn still ingests utterance into memory graph`() = runTest {
        val engram = InMemoryEngramClient()
        val mws = MemoryWriteService(engram, this)
        val p = CognitivePipeline(engramClient = engram, memoryWriteService = mws)

        val before = engram.allPhrases().size
        p.process("My dog's name is Newton", "session-1", "user-1")
        advanceUntilIdle()

        assertTrue(
            engram.allPhrases().size > before,
            "Expected disclosure utterance to be ingested into memory graph",
        )
    }

    @Test
    fun `FYI turn ingests exactly once`() = runTest {
        var ingestCallCount = 0
        val delegate = InMemoryEngramClient()
        val countingEngram = object : EngramClient by delegate {
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) {
                ingestCallCount++
                delegate.ingest(candidates, userEmail)
            }
        }
        val mws = MemoryWriteService(countingEngram, this)
        val p = CognitivePipeline(engramClient = countingEngram, memoryWriteService = mws)

        p.process("FYI I pushed the branch", "session-1", "user-1")
        advanceUntilIdle()

        assertEquals(1, ingestCallCount, "FYI turn must ingest exactly once, got $ingestCallCount")
    }

    @Test
    fun `continuation turn ingests exactly once`() = runTest {
        var ingestCallCount = 0
        val delegate = InMemoryEngramClient()
        val countingEngram = object : EngramClient by delegate {
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) {
                ingestCallCount++
                delegate.ingest(candidates, userEmail)
            }
        }
        val mws = MemoryWriteService(countingEngram, this)
        val p = CognitivePipeline(engramClient = countingEngram, memoryWriteService = mws)

        p.process("um uh I was thinking about the architecture changes we talked about", "session-1", "user-1")
        advanceUntilIdle()

        assertEquals(1, ingestCallCount, "Continuation turn must ingest exactly once, got $ingestCallCount")
    }

    // ── Guardrail: no interrogative scaffolding on returning user ─────────────

    @Test
    fun `disclosure from returning user is acknowledged, not interrogated`() = runTest {
        val engram = InMemoryEngramClient()
        engram.updateScaffoldState("user-returning",
            app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState(trustPhase = 2))
        val p = CognitivePipeline(engramClient = engram)

        val response = p.process("My dog's name is Newton", "session-1", "user-returning")

        assertFalse(
            response.contains("working on") || response.contains("get oriented"),
            "Returning user must not receive onboarding interrogation, got: '$response'",
        )
        assertTrue(response.isNotBlank())
    }
}
