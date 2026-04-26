package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import app.alfrd.engram.model.PhaseEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PhaseEventStreamerTest {

    private fun buildStreamer(
        pipeline: CognitivePipeline = CognitivePipeline(),
        phraseTracker: PhraseDeduplicationTracker = PhraseDeduplicationTracker(),
        bridgeDelayMs: Long = 1_500L,
        bridgeSecondDelayMs: Long = 5_000L,
        reasonTimeoutMs: Long = 10_000L,
    ) = PhaseEventStreamer(
        pipeline            = pipeline,
        phraseTracker       = phraseTracker,
        bridgeDelayMs       = bridgeDelayMs,
        bridgeSecondDelayMs = bridgeSecondDelayMs,
        reasonTimeoutMs     = reasonTimeoutMs,
    )

    // ── SOCIAL path: synthesis only ──────────────────────────────────────

    @Test
    fun `SOCIAL path emits only synthesis event`() = runTest {
        val streamer = buildStreamer()
        val events = streamer.stream("Hey", "s1", "u1").toList()

        assertEquals(1, events.size, "SOCIAL path must emit exactly 1 event, got: ${events.map { it.phase }}")
        assertEquals("synthesis", events[0].phase)
        assertNotNull(events[0].sequence)
        assertTrue(events[0].final == true)
    }

    // ── SIMPLE path: acknowledge → synthesis, no bridge ──────────────────

    @Test
    fun `SIMPLE path emits acknowledge then synthesis`() = runTest {
        val streamer = buildStreamer()
        val events = streamer.stream("Remind me to call the vet", "s1", "u1").toList()

        assertEquals(2, events.size, "SIMPLE path must emit 2 events, got: ${events.map { it.phase }}")
        assertEquals("acknowledge", events[0].phase)
        assertTrue(
            events[0].text in ExpressionPhrasePool.acknowledgeFor(ResponseStrategy.SIMPLE),
            "Expected acknowledge phrase from pool, got: ${events[0].text}",
        )
        assertEquals("synthesis", events[1].phase)
    }

    // ── COMPLEX path (fast pipeline): acknowledge → synthesis, no bridge ─

    @Test
    fun `COMPLEX path skips bridge when pipeline responds fast`() = runTest {
        // Fast LLM — response well within bridgeDelayMs.
        val fastLlm = TestLlmClient { LlmResponse(text = "Fast answer.", latencyMs = 0L, retryCount = 0) }
        val pipeline = CognitivePipeline(llmClient = fastLlm)
        val streamer = buildStreamer(pipeline = pipeline, bridgeDelayMs = 2_000L)

        val events = streamer.stream("Explain how photosynthesis works", "s1", "u1").toList()
        val phases = events.map { it.phase }

        assertFalse(phases.contains("bridge"), "Bridge must be skipped on fast pipeline, got: $phases")
        assertTrue(phases.contains("acknowledge"), "Acknowledge must fire on COMPLEX path")
        assertTrue(phases.contains("synthesis"), "Synthesis must fire")
    }

    // ── COMPLEX path (slow pipeline): acknowledge → bridge → synthesis ───

    @Test
    fun `COMPLEX path emits bridge when pipeline is slow`() = runTest {
        val slowLlm = TestLlmClient {
            delay(3_000L)
            LlmResponse(text = "Deep analysis result.", latencyMs = 3_000L, retryCount = 0)
        }
        val pipeline = CognitivePipeline(llmClient = slowLlm)
        val streamer = buildStreamer(
            pipeline        = pipeline,
            bridgeDelayMs   = 500L,
            reasonTimeoutMs = 10_000L,
        )

        val events = streamer.stream("Explain how photosynthesis works", "s1", "u1").toList()
        val phases = events.map { it.phase }

        assertTrue(phases.contains("acknowledge"), "Acknowledge must fire")
        assertTrue(phases.contains("bridge"),      "Bridge must fire on slow pipeline")
        assertTrue(phases.contains("synthesis"),   "Synthesis must fire after bridge")
        // Order must be correct.
        val ackIdx    = phases.indexOf("acknowledge")
        val bridgeIdx = phases.indexOf("bridge")
        val synthIdx  = phases.lastIndexOf("synthesis")
        assertTrue(ackIdx < bridgeIdx, "Acknowledge must precede bridge")
        assertTrue(bridgeIdx < synthIdx, "Bridge must precede synthesis")
    }

    // ── Second bridge at 5s ───────────────────────────────────────────────

    @Test
    fun `COMPLEX path emits second bridge at 5s when pipeline is very slow`() = runTest {
        val verySlowLlm = TestLlmClient {
            delay(7_000L)
            LlmResponse(text = "Very detailed result.", latencyMs = 7_000L, retryCount = 0)
        }
        val pipeline = CognitivePipeline(llmClient = verySlowLlm)
        val streamer = buildStreamer(
            pipeline             = pipeline,
            bridgeDelayMs        = 500L,
            bridgeSecondDelayMs  = 1_500L, // 1.5s total so second fires at 1.5s
            reasonTimeoutMs      = 30_000L,
        )

        val events = streamer.stream("Explain how photosynthesis works", "s1", "u1").toList()
        val bridgeEvents = events.filter { it.phase == "bridge" }

        assertEquals(2, bridgeEvents.size, "Expected exactly 2 bridge events, got: ${events.map { it.phase }}")
    }

    // ── Apology on timeout ────────────────────────────────────────────────

    @Test
    fun `apology event fires on reason timeout`() = runTest {
        val hangingLlm = TestLlmClient {
            delay(Long.MAX_VALUE)
            error("unreachable")
        }
        val pipeline = CognitivePipeline(llmClient = hangingLlm)
        val streamer = buildStreamer(
            pipeline        = pipeline,
            reasonTimeoutMs = 500L,
        )

        val events = streamer.stream("Explain quantum entanglement", "s1", "u1").toList()
        val phases = events.map { it.phase }

        assertTrue(phases.contains("apology"), "Apology must fire on timeout, got: $phases")
        assertFalse(phases.contains("synthesis"), "Synthesis must not fire on timeout")
        val apologyEvent = events.first { it.phase == "apology" }
        assertTrue(
            apologyEvent.text.contains("sorry") || apologyEvent.text.contains("try again"),
            "Apology text must be user-friendly, got: ${apologyEvent.text}",
        )
    }

    // ── Event shape: turnId, traceId, timestamp ───────────────────────────

    @Test
    fun `all events share the same turnId and traceId`() = runTest {
        val streamer = buildStreamer()
        val turnId  = "turn-abc"
        val traceId = "trace-xyz"

        val events = streamer.stream(
            utterance = "Remind me to call the vet",
            sessionId = "s1",
            userId    = "u1",
            turnId    = turnId,
            traceId   = traceId,
        ).toList()

        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.turnId  == turnId  }, "All events must carry the supplied turnId")
        assertTrue(events.all { it.traceId == traceId }, "All events must carry the supplied traceId")
        assertTrue(events.all { it.timestamp > 0 }, "All events must have a positive timestamp")
    }

    // ── Synthesis event has sequence + final flags ────────────────────────

    @Test
    fun `synthesis event carries sequence and final flags`() = runTest {
        val streamer = buildStreamer()
        val events = streamer.stream("Remind me to call the vet", "s1", "u1").toList()
        val synthesis = events.first { it.phase == "synthesis" }

        assertEquals(1, synthesis.sequence, "Single-chunk synthesis must have sequence=1")
        assertEquals(true, synthesis.final,  "Single-chunk synthesis must have final=true")
    }

    // ── Non-synthesis phases have no sequence / final ─────────────────────

    @Test
    fun `acknowledge event has null sequence and final`() = runTest {
        val streamer = buildStreamer()
        val events = streamer.stream("Remind me to call the vet", "s1", "u1").toList()
        val ack = events.first { it.phase == "acknowledge" }

        assertNull(ack.sequence, "Acknowledge must not carry sequence")
        assertNull(ack.final,    "Acknowledge must not carry final")
    }
}
