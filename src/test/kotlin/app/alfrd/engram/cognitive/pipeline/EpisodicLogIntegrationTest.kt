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
 * with the correct turn data — and read back for exactly one purpose: restoring the short tail of
 * the last conversation ([Conditioners.recentTurns]) when a pipeline starts, so a restart or a new
 * session continues where the user left off. Nothing else about the log reaches the Actor's prompt.
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
    fun `an empty ledger changes nothing about the composed prompt`() = runTest {
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
            "Wiring the episodic log must not change the composed prompt when there is nothing in it yet",
        )
    }

    // ── Restoring the tail of the last conversation from the ledger ───────────

    private val echoLlm = TestLlmClient { req: LlmRequest -> LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0) }

    @Test
    fun `a new pipeline for the same user restores the tail of the last conversation from the ledger`() = runTest {
        val shared = InMemoryEngramClient()
        val llm = TestLlmClient { LlmResponse(text = "Noted, the plumber it is.", latencyMs = 0L, retryCount = 0) }
        val first = CognitivePipeline(engramClient = shared, llmClient = llm, episodicLogService = EpisodicLogService(shared, this))
        first.process("Call the plumber on Tuesday", "session-before-restart", "user-restore-1")
        advanceUntilIdle()

        // A restart, or a fresh session: a new pipeline that has never heard any of it.
        val after = CognitivePipeline(engramClient = shared, llmClient = echoLlm, episodicLogService = EpisodicLogService(shared, this))
        val prompt = after.process("And what was that about?", "session-after-restart", "user-restore-1")

        assertTrue(prompt.contains("Recent conversation so far"), prompt)
        assertTrue(prompt.contains("user: Call the plumber on Tuesday"), prompt)
        assertTrue(prompt.contains("alfrd: Noted, the plumber it is."), prompt)
    }

    @Test
    fun `restored turns are the same user's, never another's`() = runTest {
        val shared = InMemoryEngramClient()
        val llm = TestLlmClient { LlmResponse(text = "Ok.", latencyMs = 0L, retryCount = 0) }
        CognitivePipeline(engramClient = shared, llmClient = llm, episodicLogService = EpisodicLogService(shared, this))
            .process("My secret is the blue door", "session-a", "user-alice")
        advanceUntilIdle()

        val bobsPrompt = CognitivePipeline(engramClient = shared, llmClient = echoLlm, episodicLogService = EpisodicLogService(shared, this))
            .process("Hello there", "session-b", "user-bob")

        assertFalse(bobsPrompt.contains("blue door"), bobsPrompt)
        assertFalse(bobsPrompt.contains("Recent conversation so far"), bobsPrompt)
    }

    @Test
    fun `the ledger is read once per pipeline, for a bounded recent window`() = runTest {
        val reads = mutableListOf<Triple<String, Long?, Int>>()
        val delegate = InMemoryEngramClient()
        val counting = object : EngramClient by delegate {
            override suspend fun getEpisodicLog(userId: String, sinceMillis: Long?, untilMillis: Long?, keyword: String?, limit: Int): List<app.alfrd.engram.cognitive.pipeline.memory.EpisodicTurn> {
                reads += Triple(userId, sinceMillis, limit)
                return emptyList()
            }
        }
        val pipeline = CognitivePipeline(engramClient = counting, llmClient = echoLlm, episodicLogService = EpisodicLogService(counting, this))
        val before = System.currentTimeMillis()

        pipeline.process("One", "s", "user-window")
        pipeline.process("Two", "s", "user-window")

        assertEquals(1, reads.size, "asked once, not every turn")
        val (user, since, limit) = reads.single()
        assertEquals("user-window", user)
        assertEquals(6, limit, "no more than the tail holds")
        assertNotNull(since)
        val window = before - since!!
        assertTrue(window in (24L * 3_600_000 - 5_000)..(24L * 3_600_000 + 5_000), "a day back, not the whole history: $window ms")
    }

    @Test
    fun `nothing is read when no ledger is wired, and a ledger that cannot be read leaves an empty tail`() = runTest {
        var reads = 0
        val delegate = InMemoryEngramClient()
        val unreadable = object : EngramClient by delegate {
            override suspend fun getEpisodicLog(userId: String, sinceMillis: Long?, untilMillis: Long?, keyword: String?, limit: Int): List<app.alfrd.engram.cognitive.pipeline.memory.EpisodicTurn> {
                reads++
                throw RuntimeException("ledger down")
            }
        }

        CognitivePipeline(engramClient = unreadable, llmClient = echoLlm).process("Hi", "s1", "u1")
        assertEquals(0, reads, "no episodicLogService, no read")

        val prompt = CognitivePipeline(engramClient = unreadable, llmClient = echoLlm, episodicLogService = EpisodicLogService(unreadable, this))
            .process("Hi again", "s2", "u2")
        assertEquals(1, reads)
        assertFalse(prompt.contains("Recent conversation so far"), "an unreadable ledger is an empty tail, and the turn still answers")
    }

    @Test
    fun `a session's greeting follows the restored turns, and the trace says how many came from the ledger`() = runTest {
        val shared = InMemoryEngramClient()
        val llm = TestLlmClient { LlmResponse(text = "Sure.", latencyMs = 0L, retryCount = 0) }
        CognitivePipeline(engramClient = shared, llmClient = llm, episodicLogService = EpisodicLogService(shared, this))
            .process("Remind me about the invoice", "s-old", "user-greet")
        advanceUntilIdle()

        val returning = CognitivePipeline(engramClient = shared, llmClient = echoLlm, episodicLogService = EpisodicLogService(shared, this))
        val greeting = returning.initSession("s-new", "user-greet").greeting
        val debug = returning.processForDebug("What was I saying?", "s-new", "user-greet")

        val prompt = debug.chat.responseText
        val restoredAt = prompt.indexOf("user: Remind me about the invoice")
        val greetingAt = prompt.indexOf("alfrd: $greeting")
        assertTrue(restoredAt in 0 until greetingAt, "the ledger's turns come first, then this session's greeting:\n$prompt")
        assertEquals(2, debug.trace.session.recentTurnsRestoredFromLedger)
        assertEquals(3, debug.trace.session.recentTurnsInPrompt, "the two restored turns and the greeting")
    }
}
