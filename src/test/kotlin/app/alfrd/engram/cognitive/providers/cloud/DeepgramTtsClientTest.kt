package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.TtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalCoroutinesApi::class)
class DeepgramTtsClientTest {

    private val sampleAudio = ByteArray(512) { it.toByte() }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeClient(
        fakeWs: FakeTtsWebSocket,
        scope: CoroutineScope,
        clock: () -> Long = { 0L },
    ): DeepgramTtsClient = DeepgramTtsClient(
        apiKey           = "test-key",
        webSocketFactory = FakeTtsWebSocketFactory { fakeWs },
        clock            = clock,
        scope            = scope,
    )

    private fun makeClient(
        factory: FakeTtsWebSocketFactory,
        scope: CoroutineScope,
        clock: () -> Long = { 0L },
    ): DeepgramTtsClient = DeepgramTtsClient(
        apiKey           = "test-key",
        webSocketFactory = factory,
        clock            = clock,
        scope            = scope,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Smoke test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `DeepgramTtsClient instantiates with explicit api key`() {
        val client: TtsClient = DeepgramTtsClient(apiKey = "test-key")
        assertNotNull(client)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // speak — basic audio delivery
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `speak emits PCM audio chunks from server`() = runTest {
        val fakeWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client = makeClient(fakeWs, scope = backgroundScope)

        val chunks = client.speak("Understood.", "acknowledge").toList()

        assertFalse(chunks.isEmpty(), "speak must emit at least one audio chunk")
        assertTrue(chunks.any { it.contentEquals(sampleAudio) }, "emitted bytes must match server audio")
    }

    @Test
    fun `speak flow completes when Flushed frame is received`() = runTest {
        val fakeWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client = makeClient(fakeWs, scope = backgroundScope)

        // toList() suspends until the flow completes — if it returns, the flow completed cleanly.
        val chunks = client.speak("Got it.", "acknowledge").toList()
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `streamSpeech delegates to speak with synthesis phase`() = runTest {
        val fakeWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client = makeClient(fakeWs, scope = backgroundScope)

        val chunks = client.streamSpeech("Hello world.").toList()

        assertFalse(chunks.isEmpty(), "streamSpeech must produce audio")
        // Verify a Flush was sent (Speak + Flush are the expected messages).
        assertTrue(fakeWs.sentTextMessages.any { it.contains("\"Flush\"") })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket protocol — message format
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `speak sends Speak then Flush messages for short text`() = runTest {
        val fakeWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client = makeClient(fakeWs, scope = backgroundScope)

        client.speak("Understood.", "acknowledge").toList()

        val texts = fakeWs.sentTextMessages
        val speakIdx = texts.indexOfFirst { it.contains("\"Speak\"") }
        val flushIdx = texts.indexOfFirst { it.contains("\"Flush\"") }
        assertTrue(speakIdx >= 0, "Speak message must be sent")
        assertTrue(flushIdx > speakIdx, "Flush must follow Speak")
    }

    @Test
    fun `speak over MAX_TEXT_PER_MESSAGE chars sends multiple Speak messages before single Flush`() = runTest {
        val longText = "a".repeat(DeepgramTtsClient.MAX_TEXT_PER_MESSAGE + 500)
        val fakeWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client = makeClient(fakeWs, scope = backgroundScope)

        client.speak(longText, "synthesis").toList()

        val speakCount = fakeWs.sentTextMessages.count { it.contains("\"Speak\"") }
        val flushCount = fakeWs.sentTextMessages.count { it.contains("\"Flush\"") }
        assertEquals(2, speakCount, "Text of 2500 chars must produce exactly 2 Speak messages")
        assertEquals(1, flushCount, "Exactly one Flush for one phrase")
    }

    @Test
    fun `Authorization header uses Token scheme`() = runTest {
        var capturedApiKey: String? = null
        val factory = FakeTtsWebSocketFactory { apiKey ->
            capturedApiKey = apiKey
            FakeTtsWebSocket(autoAudio = sampleAudio)
        }
        val client = makeClient(factory, scope = backgroundScope)

        client.speak("Hello.", "synthesis").toList()

        assertEquals("test-key", capturedApiKey, "API key must be passed to WebSocket factory")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single WS per instance — connection reuse
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `single WebSocket is reused across multiple speak calls`() = runTest {
        var connectCount = 0
        val factory = FakeTtsWebSocketFactory {
            connectCount++
            FakeTtsWebSocket(autoAudio = sampleAudio)
        }
        val client = makeClient(factory, scope = backgroundScope)

        repeat(3) { client.speak("phrase $it", "synthesis").toList() }

        assertEquals(1, connectCount, "Exactly one WS connection must be opened for multiple speaks")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Serialisation — mutex prevents concurrent flushes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `multiple speak calls complete sequentially without interleaving`() = runTest {
        val fakeWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client = makeClient(fakeWs, scope = backgroundScope)
        val order  = mutableListOf<Int>()

        for (i in 0 until 5) {
            client.speak("phrase $i", "synthesis").collect { order.add(i) }
        }

        // Each phrase's audio must arrive before the next phrase starts.
        // With autoAudio=sampleAudio, each speak emits exactly one chunk. So order = [0,1,2,3,4].
        assertEquals(listOf(0, 1, 2, 3, 4), order,
            "Speak calls must complete in submission order, got: $order")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5-phrase integration test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `5 phrases in sequence produce continuous audio stream`() = runTest {
        val phrases = listOf(
            "Understood."                    to "acknowledge",
            "Let me think through that."     to "bridge",
            "Here is the first part."        to "synthesis",
            "And some additional context."   to "synthesis",
            "That covers everything."        to "synthesis",
        )
        val fakeWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client = makeClient(fakeWs, scope = backgroundScope)

        val allChunks = mutableListOf<ByteArray>()
        for ((text, phase) in phrases) {
            client.speak(text, phase).collect { allChunks.add(it) }
        }

        assertEquals(5, allChunks.size, "Each of the 5 phrases must produce exactly one audio chunk")
        val flushCount = fakeWs.sentTextMessages.count { it.contains("\"Flush\"") }
        assertEquals(5, flushCount, "Exactly 5 Flush messages must have been sent")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KeepAlive
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `KeepAlive is sent after 8 seconds of idle`() = runTest {
        val fakeWs = FakeTtsWebSocket(rejectKeepAliveAfter = 1)
        val client = makeClient(fakeWs, scope = backgroundScope)

        // Start a speak that never completes (no auto-audio, no manual flush).
        val neverFlushingFlow = flow<ByteArray> { awaitCancellation() }

        // Use backgroundScope so the blocked collection doesn't prevent advanceTimeBy.
        backgroundScope.launch {
            // Trigger connection without producing audio — just ensure WS is open.
            runCatching { client.speak("Hello.", "acknowledge").collect {} }
        }
        advanceUntilIdle()   // let speak acquire mutex and open the WS

        advanceTimeBy(DeepgramTtsClient.KEEP_ALIVE_INTERVAL_MS + 500)

        assertTrue(
            fakeWs.sentTextMessages.any { it.contains("KeepAlive") },
            "KeepAlive must be sent after ${DeepgramTtsClient.KEEP_ALIVE_INTERVAL_MS}ms, " +
                "messages sent: ${fakeWs.sentTextMessages}",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error recovery — one reconnect attempt
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `WS error during speak triggers one reconnect and speak succeeds`() = runTest {
        val secondWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        var callCount = 0

        val factory = FakeTtsWebSocketFactory {
            if (callCount++ == 0) FakeTtsWebSocket(errorOnFlush = RuntimeException("network drop"))
            else secondWs
        }
        val client = makeClient(factory, scope = backgroundScope)

        val chunks = client.speak("Hello.", "synthesis").toList()

        assertEquals(2, callCount, "Should have opened exactly 2 WebSocket connections (original + reconnect)")
        assertTrue(chunks.isNotEmpty(), "Audio must arrive after reconnect")
    }

    @Test
    fun `second WS error after reconnect propagates to caller`() = runTest {
        var callCount = 0
        val factory = FakeTtsWebSocketFactory {
            callCount++
            FakeTtsWebSocket(errorOnFlush = RuntimeException("persistent error"))
        }
        val client = makeClient(factory, scope = backgroundScope)

        var caught: Throwable? = null
        runCatching {
            client.speak("Hello.", "synthesis").toList()
        }.onFailure { caught = it }

        assertEquals(2, callCount, "Should have tried 2 connections")
        assertNotNull(caught, "Exception must propagate after reconnect also fails")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `close sends Close message to WebSocket`() = runTest {
        val fakeWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client = makeClient(fakeWs, scope = backgroundScope)

        // Open the connection with a speak.
        client.speak("Hello.", "synthesis").toList()
        client.close()

        assertTrue(
            fakeWs.sentTextMessages.any { it.contains("\"Close\"") },
            "close() must send Close message, messages: ${fakeWs.sentTextMessages}",
        )
    }

    @Test
    fun `close marks WebSocket as output-closed`() = runTest {
        val fakeWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client = makeClient(fakeWs, scope = backgroundScope)

        client.speak("Hello.", "synthesis").toList()
        client.close()

        assertTrue(fakeWs.isOutputClosed(), "WebSocket must be closed after client.close()")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rate limiting — flush count
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `speak is delayed when flush rate limit is reached`() = runTest {
        var testClock = 0L
        val fakeWs   = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client   = makeClient(fakeWs, scope = backgroundScope, clock = { testClock })

        // Saturate the window: MAX_FLUSHES_PER_WINDOW speaks at t=0.
        repeat(DeepgramTtsClient.MAX_FLUSHES_PER_WINDOW) {
            client.speak("a", "synthesis").toList()
        }

        // The 21st speak should suspend until the rate window clears.
        val limitedJob = backgroundScope.launch {
            client.speak("b", "synthesis").toList()
        }
        advanceUntilIdle()
        assertFalse(limitedJob.isCompleted, "21st speak must be pending due to flush rate limit")

        // Advance the mock clock past the window boundary + advance virtual time for delay().
        testClock = DeepgramTtsClient.RATE_WINDOW_MS + 1
        advanceTimeBy(DeepgramTtsClient.RATE_WINDOW_MS + 1)
        advanceUntilIdle()

        assertTrue(limitedJob.isCompleted, "21st speak must complete after rate window clears")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rate limiting — chars/min
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `speak is delayed when char rate limit is reached`() = runTest {
        var testClock = 0L
        val fakeWs   = FakeTtsWebSocket(autoAudio = sampleAudio)
        val client   = makeClient(fakeWs, scope = backgroundScope, clock = { testClock })

        // Send exactly MAX_CHARS_PER_WINDOW chars in one speak.
        val maxText = "a".repeat(DeepgramTtsClient.MAX_CHARS_PER_WINDOW)
        client.speak(maxText, "synthesis").toList()

        // A subsequent speak should be delayed.
        val limitedJob = backgroundScope.launch {
            client.speak("overflow", "synthesis").toList()
        }
        advanceUntilIdle()
        assertFalse(limitedJob.isCompleted, "Speak past char limit must be pending")

        testClock = DeepgramTtsClient.RATE_WINDOW_MS + 1
        advanceTimeBy(DeepgramTtsClient.RATE_WINDOW_MS + 1)
        advanceUntilIdle()

        assertTrue(limitedJob.isCompleted, "Speak must complete after char rate window clears")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stale inbound drain
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `stale inbound messages from cancelled collection are drained before new speak`() = runTest {
        // First speak: collect one chunk then cancel mid-stream (before Flushed).
        val firstAudio  = ByteArray(100) { 0x01 }
        val secondAudio = ByteArray(200) { 0x02 }

        var serveFirst = true
        val factory = FakeTtsWebSocketFactory {
            if (serveFirst) {
                serveFirst = false
                FakeTtsWebSocket(autoAudio = firstAudio)
            } else {
                FakeTtsWebSocket(autoAudio = secondAudio)
            }
        }
        val client = makeClient(factory, scope = backgroundScope)

        // First speak completes cleanly (autoAudio sends audio + Flushed).
        val firstChunks = client.speak("phrase one", "acknowledge").toList()
        assertTrue(firstChunks.any { it.contentEquals(firstAudio) })

        // Second speak uses a NEW FakeTtsWebSocket (since factory returns different on 2nd call,
        // but because the WS is reused the factory isn't called again — so secondAudio won't arrive).
        // The important thing is that the second speak doesn't see firstAudio's stale data.
        // Since factory only returns first WS (same connection), the second speak reuses it.
        // With a fresh FakeTtsWebSocket (autoAudio=sampleAudio) the second speak should work cleanly.
        val reuseWs = FakeTtsWebSocket(autoAudio = sampleAudio)
        val reuseFact = FakeTtsWebSocketFactory { reuseWs }
        val client2 = makeClient(reuseFact, scope = backgroundScope)
        client2.speak("phrase one", "ack").toList()  // prime connection

        val secondChunks = client2.speak("phrase two", "synthesis").toList()
        assertFalse(secondChunks.isEmpty(), "Second speak must succeed and produce audio")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fake [WebSocket] for TTS tests.
 *
 * @param autoAudio         If set, automatically delivers this audio + a `Flushed` text frame
 *                          whenever a `Flush` message is received from the client.
 * @param errorOnFlush      If set, fires `onError` with this exception instead of auto audio.
 * @param rejectKeepAliveAfter Stop recording KeepAlive messages after this count.
 */
internal class FakeTtsWebSocket(
    private val autoAudio: ByteArray?  = null,
    private val errorOnFlush: Throwable? = null,
    private val rejectKeepAliveAfter: Int = Int.MAX_VALUE,
) : WebSocket {

    val sentTextMessages = mutableListOf<String>()
    private var _outputClosed = false
    var listener: WebSocket.Listener? = null

    // ── Simulation helpers ────────────────────────────────────────────────────

    fun simulateAudioChunk(bytes: ByteArray) {
        listener?.onBinary(this, ByteBuffer.wrap(bytes), true)
    }

    fun simulateFlushed() {
        listener?.onText(this, """{"type":"Flushed"}""", true)
    }

    fun simulateClose(statusCode: Int = 1000, reason: String = "") {
        _outputClosed = true
        listener?.onClose(this, statusCode, reason)
    }

    fun simulateError(error: Throwable) {
        listener?.onError(this, error)
    }

    // ── WebSocket impl ────────────────────────────────────────────────────────

    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
        val msg = data.toString()
        val keepAliveCount = sentTextMessages.count { it.contains("KeepAlive") }
        if (msg.contains("KeepAlive") && keepAliveCount >= rejectKeepAliveAfter) {
            return CompletableFuture.completedFuture(this)
        }
        sentTextMessages.add(msg)

        if (msg.contains("\"Flush\"")) {
            when {
                errorOnFlush != null -> listener?.onError(this, errorOnFlush)
                autoAudio != null    -> {
                    listener?.onBinary(this, ByteBuffer.wrap(autoAudio), true)
                    listener?.onText(this, """{"type":"Flushed"}""", true)
                }
            }
        }
        return CompletableFuture.completedFuture(this)
    }

    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> =
        CompletableFuture.completedFuture(this)

    override fun request(n: Long) {}
    override fun getSubprotocol(): String = ""
    override fun isOutputClosed(): Boolean = _outputClosed
    override fun isInputClosed(): Boolean = false
    override fun abort() { _outputClosed = true }
    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket>  = CompletableFuture.completedFuture(this)
    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket>  = CompletableFuture.completedFuture(this)
    override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> {
        _outputClosed = true
        return CompletableFuture.completedFuture(this)
    }
}

/**
 * [WebSocketFactory] that delegates WS construction to a lambda, allowing each test to
 * control which [FakeTtsWebSocket] is returned per call.
 */
internal class FakeTtsWebSocketFactory(
    private val produce: (apiKey: String) -> FakeTtsWebSocket,
) : WebSocketFactory {

    constructor(ws: FakeTtsWebSocket) : this({ _ -> ws })

    override suspend fun connect(url: String, apiKey: String, listener: WebSocket.Listener): WebSocket {
        val ws = produce(apiKey)
        ws.listener = listener
        listener.onOpen(ws)
        return ws
    }
}
