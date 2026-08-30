package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.EpisodicLogService
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Integration tests confirming the episodic conversation log is written exactly once per turn,
 * with the correct turn data, and never leaks into the Actor's prompt — the episodic log is a
 * write-only side channel, structurally separate from Conditioners (plan design decision #5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodicLogIntegrationTest {

    private data class RecordedTurn(
        val sessionId: String,
        val userId: String,
        val turnIndex: Int,
        val userUtterance: String,
        val alfrdResponse: String,
    )

    private fun trackingEngramClient(recorded: MutableList<RecordedTurn>): EngramClient {
        val delegate = InMemoryEngramClient()
        return object : EngramClient by delegate {
            override suspend fun appendEpisodicTurn(
                sessionId: String, userId: String, turnIndex: Int, userUtterance: String, alfrdResponse: String,
            ) {
                recorded += RecordedTurn(sessionId, userId, turnIndex, userUtterance, alfrdResponse)
            }
        }
    }

    @Test
    fun `processInternal records exactly one episodic turn per turn with correct data`() = runTest {
        val recorded = mutableListOf<RecordedTurn>()
        val trackingEngram = trackingEngramClient(recorded)
        val episodicLogService = EpisodicLogService(trackingEngram, this)
        val llm = TestLlmClient { LlmResponse(text = "Sure thing.", latencyMs = 0L, retryCount = 0) }
        val pipeline = CognitivePipeline(
            engramClient = trackingEngram, llmClient = llm, episodicLogService = episodicLogService,
        )

        pipeline.process("What time does school start?", "session-episodic-1", "user-episodic-1")
        advanceUntilIdle()

        assertEquals(1, recorded.size, "Expected exactly one episodic turn recorded")
        val turn = recorded.first()
        assertEquals("session-episodic-1", turn.sessionId)
        assertEquals("user-episodic-1", turn.userId)
        assertEquals("What time does school start?", turn.userUtterance)
        assertEquals("Sure thing.", turn.alfrdResponse)
    }

    @Test
    fun `two turns in the same session record two episodic turns with incrementing turnIndex`() = runTest {
        val recorded = mutableListOf<RecordedTurn>()
        val trackingEngram = trackingEngramClient(recorded)
        val episodicLogService = EpisodicLogService(trackingEngram, this)
        val llm = TestLlmClient { LlmResponse(text = "OK.", latencyMs = 0L, retryCount = 0) }
        val pipeline = CognitivePipeline(
            engramClient = trackingEngram, llmClient = llm, episodicLogService = episodicLogService,
        )

        pipeline.process("First message", "session-episodic-2", "user-episodic-2")
        pipeline.process("Second message", "session-episodic-2", "user-episodic-2")
        advanceUntilIdle()

        assertEquals(2, recorded.size)
        assertTrue(recorded[1].turnIndex > recorded[0].turnIndex, "turnIndex must increment across turns")
    }

    @Test
    fun `episodic log write failure never propagates to the caller`() = runTest {
        val delegate = InMemoryEngramClient()
        val throwingEngram = object : EngramClient by delegate {
            override suspend fun appendEpisodicTurn(
                sessionId: String, userId: String, turnIndex: Int, userUtterance: String, alfrdResponse: String,
            ): Unit = throw RuntimeException("simulated episodic write failure")
        }
        val episodicLogService = EpisodicLogService(throwingEngram, this)
        val llm = TestLlmClient { LlmResponse(text = "Still fine.", latencyMs = 0L, retryCount = 0) }
        val pipeline = CognitivePipeline(
            engramClient = throwingEngram, llmClient = llm, episodicLogService = episodicLogService,
        )

        val response = pipeline.process("Anything", "session-episodic-3", "user-episodic-3")
        advanceUntilIdle()

        assertEquals("Still fine.", response, "Episodic write failure must not affect the turn's response")
    }

    @Test
    fun `episodic log never leaks into the Actor's composed prompt`() = runTest {
        val recorded = mutableListOf<RecordedTurn>()
        val trackingEngram = trackingEngramClient(recorded)
        val episodicLogService = EpisodicLogService(trackingEngram, this)
        val echoLlm = TestLlmClient { req: LlmRequest -> LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0) }

        val withEpisodicLog = CognitivePipeline(
            engramClient = trackingEngram, llmClient = echoLlm, episodicLogService = episodicLogService,
        )
        val withoutEpisodicLog = CognitivePipeline(engramClient = InMemoryEngramClient(), llmClient = echoLlm)

        val promptWith = withEpisodicLog.process("What's the plan for today?", "session-x", "user-x")
        val promptWithout = withoutEpisodicLog.process("What's the plan for today?", "session-x", "user-x")

        assertEquals(
            promptWithout, promptWith,
            "Wiring the episodic log must not change the composed prompt — it is write-only",
        )
    }
}
