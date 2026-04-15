package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles for STT and TTS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fake STT that emits pre-configured final transcripts on each call.
 * Each invocation of [streamTranscription] drains the [transcripts] list in order.
 */
class FakeSttClient(
    private val transcripts: List<String>,
) : SttClient {
    override fun streamTranscription(audio: Flow<ByteArray>): Flow<TranscriptionResult> = flow {
        for (t in transcripts) {
            emit(
                TranscriptionResult(
                    transcript = t,
                    isFinal = true,
                    speechFinal = true,
                    confidence = 0.95f,
                ),
            )
        }
    }
}

/**
 * Fake TTS that records every text sent for synthesis and returns a deterministic
 * PCM byte chunk. Phrase log is inspectable for assertions.
 */
class FakeTtsClient(
    private val chunkSize: Int = 3_200, // 100 ms at 32 KB/s
) : TtsClient {
    private val _phrases = mutableListOf<String>()
    val phrases: List<String> get() = _phrases.toList()

    override fun streamSpeech(text: String): Flow<ByteArray> = flow {
        _phrases.add(text)
        emit(ByteArray(chunkSize))
    }
}

/**
 * TTS that tracks concurrent invocations to detect overlap violations.
 */
class OverlapDetectingTtsClient(
    private val chunkSize: Int = 3_200,
) : TtsClient {
    private val mutex = Mutex()
    var maxConcurrent = 0
        private set
    private var current = 0

    override fun streamSpeech(text: String): Flow<ByteArray> = flow {
        current++
        if (current > maxConcurrent) maxConcurrent = current
        emit(ByteArray(chunkSize))
        current--
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests — VoiceLoopOrchestrator
// ─────────────────────────────────────────────────────────────────────────────

class VoiceLoopOrchestratorTest {

    private fun buildOrchestrator(
        stt: SttClient,
        tts: TtsClient,
        pipeline: CognitivePipeline = CognitivePipeline(),
        phraseTracker: PhraseDeduplicationTracker = PhraseDeduplicationTracker(),
        bridgeDelayMs: Long = 1_500L,
        reasonTimeoutMs: Long = 10_000L,
    ) = VoiceLoopOrchestrator(
        sttClient = stt,
        ttsClient = tts,
        pipeline = pipeline,
        phraseTracker = phraseTracker,
        bridgeDelayMs = bridgeDelayMs,
        reasonTimeoutMs = reasonTimeoutMs,
        pcmBytesPerSec = 32_000,
        playbackBufferMs = 0L, // no buffer in tests to avoid extra delays
    )

    // ── SOCIAL path: single Synthesis, no Acknowledge/Bridge ─────────────

    @Test
    fun `SOCIAL path emits only synthesis phase`() = runTest {
        val stt = FakeSttClient(listOf("Hey"))
        val tts = FakeTtsClient()
        val audioOutput = mutableListOf<ByteArray>()
        val orchestrator = buildOrchestrator(stt, tts)

        val job = orchestrator.startLoop(
            audioInput = emptyFlow(),
            audioOutput = { audioOutput.add(it) },
            sessionId = "s1",
            userId = "u1",
            scope = this,
        )
        job.join()

        // Only synthesis — no acknowledge or bridge for SOCIAL.
        assertEquals(1, tts.phrases.size, "Expected exactly 1 TTS call for SOCIAL path")
        assertTrue(
            tts.phrases[0].contains("Good") || tts.phrases[0].contains("morning") ||
                tts.phrases[0].contains("afternoon") || tts.phrases[0].contains("evening"),
            "Expected a greeting response, got: ${tts.phrases[0]}",
        )
    }

    // ── SIMPLE path: Acknowledge + Synthesis, no Bridge ──────────────────

    @Test
    fun `SIMPLE path emits acknowledge then synthesis`() = runTest {
        val stt = FakeSttClient(listOf("Remind me to call the vet"))
        val tts = FakeTtsClient()
        val orchestrator = buildOrchestrator(stt, tts)

        val job = orchestrator.startLoop(
            audioInput = emptyFlow(),
            audioOutput = {},
            sessionId = "s1",
            userId = "u1",
            scope = this,
        )
        job.join()

        // Acknowledge + Synthesis = 2 TTS calls.
        assertEquals(2, tts.phrases.size, "Expected 2 TTS calls for SIMPLE path, got: ${tts.phrases}")
        // First is an acknowledge phrase from the pool.
        assertTrue(
            tts.phrases[0] in ExpressionPhrasePool.acknowledgeFor(ResponseStrategy.SIMPLE),
            "Expected acknowledge phrase, got: ${tts.phrases[0]}",
        )
    }

    // ── COMPLEX path: Acknowledge + Bridge (slow pipeline) + Synthesis ───

    @Test
    fun `COMPLEX path emits acknowledge and bridge when pipeline is slow`() = runTest {
        // Use a pipeline with a slow LLM to trigger the bridge phase.
        val slowLlm = TestLlmClient { req ->
            delay(3_000L) // Simulate slow reasoning
            LlmResponse(text = "Deep analysis result.", latencyMs = 3000L, retryCount = 0)
        }
        val pipeline = CognitivePipeline(llmClient = slowLlm)
        val stt = FakeSttClient(listOf("Explain how photosynthesis works"))
        val tts = FakeTtsClient()
        val orchestrator = buildOrchestrator(
            stt = stt,
            tts = tts,
            pipeline = pipeline,
            bridgeDelayMs = 500L, // Short for test
        )

        val job = orchestrator.startLoop(
            audioInput = emptyFlow(),
            audioOutput = {},
            sessionId = "s1",
            userId = "u1",
            scope = this,
        )
        job.join()

        // Acknowledge + Bridge + Synthesis = 3 TTS calls.
        assertTrue(tts.phrases.size >= 3, "Expected ≥3 TTS calls for COMPLEX+slow path, got: ${tts.phrases}")
        assertTrue(
            tts.phrases[0] in ExpressionPhrasePool.acknowledgeFor(ResponseStrategy.COMPLEX),
            "Expected acknowledge phrase, got: ${tts.phrases[0]}",
        )
        assertTrue(
            tts.phrases[1] in ExpressionPhrasePool.bridgeFor(ResponseStrategy.COMPLEX),
            "Expected bridge phrase, got: ${tts.phrases[1]}",
        )
    }

    // ── Non-overlap: verify no concurrent TTS calls ─────────────────────

    @Test
    fun `TTS phases do not overlap`() = runTest {
        val stt = FakeSttClient(listOf("Remind me to call the vet"))
        val tts = OverlapDetectingTtsClient()
        val orchestrator = buildOrchestrator(stt, tts)

        val job = orchestrator.startLoop(
            audioInput = emptyFlow(),
            audioOutput = {},
            sessionId = "s1",
            userId = "u1",
            scope = this,
        )
        job.join()

        assertEquals(1, tts.maxConcurrent, "TTS phases must not overlap — max concurrent was ${tts.maxConcurrent}")
    }

    // ── Phrase deduplication: 4 turns, no repeated Acknowledge ──────────

    @Test
    fun `acknowledge phrases are not repeated within 3 consecutive turns`() = runTest {
        val stt = FakeSttClient(listOf(
            "Remind me to call the vet",
            "Set a timer for 5 minutes",
            "Add eggs to the shopping list",
            "Schedule a meeting at 3pm",
        ))
        val tts = FakeTtsClient()
        val orchestrator = buildOrchestrator(stt, tts)

        val job = orchestrator.startLoop(
            audioInput = emptyFlow(),
            audioOutput = {},
            sessionId = "s1",
            userId = "u1",
            scope = this,
        )
        job.join()

        // Extract the acknowledge phrases (every other TTS call, starting at index 0).
        val ackPhrases = tts.phrases.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue(ackPhrases.size >= 4, "Expected at least 4 acknowledge phrases, got: $ackPhrases")

        // Check that no three consecutive acks are the same.
        for (i in 0 until ackPhrases.size - 2) {
            val window = ackPhrases.subList(i, i + 3)
            assertTrue(
                window.toSet().size > 1,
                "Acknowledge repeated within 3-turn window: $window",
            )
        }
    }

    // ── Reason timeout: apology response fires ──────────────────────────

    @Test
    fun `pipeline timeout emits apology response`() = runTest {
        val hangingLlm = TestLlmClient { req ->
            delay(Long.MAX_VALUE) // Never completes
            error("unreachable")
        }
        val pipeline = CognitivePipeline(llmClient = hangingLlm)
        val stt = FakeSttClient(listOf("Explain quantum entanglement"))
        val tts = FakeTtsClient()
        val orchestrator = buildOrchestrator(
            stt = stt,
            tts = tts,
            pipeline = pipeline,
            reasonTimeoutMs = 500L, // Short for test
        )

        val job = orchestrator.startLoop(
            audioInput = emptyFlow(),
            audioOutput = {},
            sessionId = "s1",
            userId = "u1",
            scope = this,
        )
        job.join()

        assertTrue(
            tts.phrases.any { it.contains("sorry") || it.contains("try again") },
            "Expected apology response on timeout, got: ${tts.phrases}",
        )
    }

    // ── STT empty transcript: clarification, no Acknowledge ─────────────

    @Test
    fun `empty transcript emits clarification without acknowledge`() = runTest {
        val stt = FakeSttClient(listOf(""))
        val tts = FakeTtsClient()
        val orchestrator = buildOrchestrator(stt, tts)

        val job = orchestrator.startLoop(
            audioInput = emptyFlow(),
            audioOutput = {},
            sessionId = "s1",
            userId = "u1",
            scope = this,
        )
        job.join()

        assertEquals(1, tts.phrases.size, "Expected exactly 1 TTS call for empty transcript")
        assertTrue(
            tts.phrases[0].contains("didn't catch") || tts.phrases[0].contains("say that again"),
            "Expected clarification response, got: ${tts.phrases[0]}",
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — Expression streaming result
// ─────────────────────────────────────────────────────────────────────────────

class StreamingExpressionTest {

    private val expression = Expression()

    @Test
    fun `SOCIAL strategy has null acknowledge and bridge`() = runTest {
        val result = expression.toStreamingResult(
            BranchResult(content = "Good morning.", responseStrategy = ResponseStrategy.SOCIAL),
        )
        assertNull(result.acknowledge)
        assertNull(result.bridge)
        assertEquals("Good morning.", result.synthesis)
        assertEquals(ResponseStrategy.SOCIAL, result.strategy)
    }

    @Test
    fun `SIMPLE strategy has acknowledge, null bridge`() = runTest {
        val result = expression.toStreamingResult(
            BranchResult(content = "Task noted.", responseStrategy = ResponseStrategy.SIMPLE),
        )
        assertNotNull(result.acknowledge)
        assertNull(result.bridge)
        assertEquals("Task noted.", result.synthesis)
    }

    @Test
    fun `COMPLEX strategy has acknowledge and bridge`() = runTest {
        val result = expression.toStreamingResult(
            BranchResult(content = "The answer.", responseStrategy = ResponseStrategy.COMPLEX),
        )
        assertNotNull(result.acknowledge)
        assertNotNull(result.bridge)
        assertEquals("The answer.", result.synthesis)
    }

    @Test
    fun `EMOTIONAL strategy has acknowledge and bridge`() = runTest {
        val result = expression.toStreamingResult(
            BranchResult(content = "That matters.", responseStrategy = ResponseStrategy.EMOTIONAL),
        )
        assertNotNull(result.acknowledge)
        assertNotNull(result.bridge)
        assertEquals("That matters.", result.synthesis)
    }

    @Test
    fun `streaming result backward compat - streamingPhases and responseText still set`() = runTest {
        val ctx = CognitiveContext(utterance = "test", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(content = "Done.", responseStrategy = ResponseStrategy.SIMPLE)
        expression.evaluate(ctx)

        assertNotNull(ctx.streamingExpressionResult)
        assertNotNull(ctx.streamingPhases)
        assertTrue(ctx.responseText.contains("Done."))
        assertEquals(ctx.streamingPhases!!.size, 2) // acknowledge + synthesis
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — PhraseDeduplicationTracker
// ─────────────────────────────────────────────────────────────────────────────

class PhraseDeduplicationTrackerTest {

    @Test
    fun `picks different acknowledge phrases across consecutive calls`() {
        val tracker = PhraseDeduplicationTracker(windowSize = 3)
        val phrases = (1..5).map { tracker.pickAcknowledge(ResponseStrategy.SIMPLE)!! }

        // No three consecutive should be equal.
        for (i in 0 until phrases.size - 2) {
            val window = phrases.subList(i, i + 3)
            assertTrue(
                window.toSet().size > 1,
                "Duplicate within window at position $i: $window",
            )
        }
    }

    @Test
    fun `returns null for SOCIAL acknowledge`() {
        val tracker = PhraseDeduplicationTracker()
        assertNull(tracker.pickAcknowledge(ResponseStrategy.SOCIAL))
    }

    @Test
    fun `returns null for SIMPLE bridge`() {
        val tracker = PhraseDeduplicationTracker()
        assertNull(tracker.pickBridge(ResponseStrategy.SIMPLE))
    }

    @Test
    fun `resets when all phrases exhausted`() {
        // With window = 10 (larger than pool), all phrases get exhausted.
        val tracker = PhraseDeduplicationTracker(windowSize = 10)
        val pool = ExpressionPhrasePool.acknowledgeFor(ResponseStrategy.SIMPLE)

        // Exhaust the pool
        val firstRound = (1..pool.size).map { tracker.pickAcknowledge(ResponseStrategy.SIMPLE)!! }
        assertEquals(pool.toSet(), firstRound.toSet(), "Should have used all phrases")

        // Next call should reset and return a phrase (not null).
        val afterReset = tracker.pickAcknowledge(ResponseStrategy.SIMPLE)
        assertNotNull(afterReset)
    }
}
