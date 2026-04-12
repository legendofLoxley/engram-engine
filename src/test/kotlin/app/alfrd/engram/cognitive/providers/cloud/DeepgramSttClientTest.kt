package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.SttClient
import app.alfrd.engram.cognitive.providers.TranscriptionResult
import app.alfrd.engram.cognitive.providers.WordTiming
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

@OptIn(ExperimentalCoroutinesApi::class)
class DeepgramSttClientTest {

    // -------------------------------------------------------------------------
    // Sample JSON frames (match Deepgram v1 schema)
    // -------------------------------------------------------------------------

    private val interimResultJson = """
        {
          "type": "Results",
          "channel_index": [0, 1],
          "duration": 1.0,
          "start": 0.0,
          "is_final": false,
          "speech_final": false,
          "channel": {
            "alternatives": [
              {
                "transcript": "hello world",
                "confidence": 0.95,
                "words": [
                  {"word": "hello", "start": 0.1, "end": 0.4, "confidence": 0.97},
                  {"word": "world", "start": 0.5, "end": 0.9, "confidence": 0.93}
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private val finalResultJson = """
        {
          "type": "Results",
          "channel_index": [0, 1],
          "duration": 1.0,
          "start": 0.0,
          "is_final": true,
          "speech_final": true,
          "channel": {
            "alternatives": [
              {
                "transcript": "hello world",
                "confidence": 0.99,
                "words": [
                  {"word": "hello", "start": 0.1, "end": 0.4, "confidence": 0.98},
                  {"word": "world", "start": 0.5, "end": 0.9, "confidence": 0.99}
                ]
              }
            ]
          }
        }
    """.trimIndent()

    // Deepgram emits a blank transcript frame during silence — we emit it and let
    // the caller decide to filter. This preserves is_final / speech_final signals.
    private val silenceResultJson = """
        {
          "type": "Results",
          "channel_index": [0, 1],
          "duration": 0.5,
          "start": 1.0,
          "is_final": false,
          "speech_final": false,
          "channel": {
            "alternatives": [
              {
                "transcript": "",
                "confidence": 0.0,
                "words": []
              }
            ]
          }
        }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // JSON parsing — pure unit tests, no WebSocket involved
    // -------------------------------------------------------------------------

    @Test
    fun `parseResult returns null for non-Results frame type`() {
        val metadataJson = """{"type":"Metadata","transaction_key":"abc","request_id":"xyz"}"""
        assertNull(DeepgramSttClient.parseResult(json, metadataJson))
    }

    @Test
    fun `parseResult returns null for malformed JSON`() {
        assertNull(DeepgramSttClient.parseResult(json, "not json {{{"))
    }

    @Test
    fun `parseResult maps interim result fields correctly`() {
        val result = DeepgramSttClient.parseResult(json, interimResultJson)
        assertNotNull(result)
        assertEquals("hello world", result!!.transcript)
        assertFalse(result.isFinal)
        assertFalse(result.speechFinal)
        assertEquals(0.95f, result.confidence, 0.001f)
    }

    @Test
    fun `parseResult maps final result fields correctly`() {
        val result = DeepgramSttClient.parseResult(json, finalResultJson)
        assertNotNull(result)
        assertEquals("hello world", result!!.transcript)
        assertTrue(result.isFinal)
        assertTrue(result.speechFinal)
        assertEquals(0.99f, result.confidence, 0.001f)
    }

    @Test
    fun `parseResult propagates speechFinal true as utterance boundary signal`() {
        val result = DeepgramSttClient.parseResult(json, finalResultJson)
        assertNotNull(result)
        assertTrue(result!!.speechFinal, "speechFinal must be true to signal end of utterance")
    }

    @Test
    fun `parseResult propagates speechFinal false for interim result`() {
        val result = DeepgramSttClient.parseResult(json, interimResultJson)
        assertNotNull(result)
        assertFalse(result!!.speechFinal, "speechFinal must be false for interim result")
    }

    @Test
    fun `parseResult extracts word timings from alternatives words array`() {
        val result = DeepgramSttClient.parseResult(json, interimResultJson)!!
        val words = result.wordTimings
        assertNotNull(words)
        assertEquals(2, words!!.size)
        assertEquals(WordTiming(word = "hello", startMs = 100L, endMs = 400L, confidence = 0.97f), words[0])
        assertEquals(WordTiming(word = "world", startMs = 500L, endMs = 900L, confidence = 0.93f), words[1])
    }

    @Test
    fun `parseResult emits result with empty transcript on silence — callers may filter`() {
        // Design choice: emit silence frames so downstream can observe is_final / speech_final.
        val result = DeepgramSttClient.parseResult(json, silenceResultJson)
        assertNotNull(result)
        assertEquals("", result!!.transcript)
        assertFalse(result.isFinal)
        assertFalse(result.speechFinal)
    }

    @Test
    fun `parseResult returns null wordTimings for empty words array`() {
        val result = DeepgramSttClient.parseResult(json, silenceResultJson)
        assertNotNull(result)
        assertNull(result!!.wordTimings, "empty words array should map to null wordTimings")
    }

    // -------------------------------------------------------------------------
    // Instantiation smoke tests
    // -------------------------------------------------------------------------

    @Test
    fun `DeepgramSttClient instantiates with explicit api key`() {
        val client: SttClient = DeepgramSttClient(apiKey = "test-key")
        assertNotNull(client)
    }

    // -------------------------------------------------------------------------
    // Flow wiring tests — injected FakeWebSocket, no real network
    //
    // The channelFlow implementation bridges the WebSocket listener's callbacks
    // into a Channel<TranscriptionResult>.  In tests, we drive the flow by:
    //   1. Starting collection in a child coroutine.
    //   2. Calling advanceUntilIdle() to let the flow body start.
    //   3. Triggering listener callbacks (simulateTextMessage / simulateClose / simulateError).
    //   4. Calling advanceUntilIdle() again to let the coroutine process the events.
    //   5. Asserting side effects, then joining the collect job.
    // -------------------------------------------------------------------------

    @Test
    fun `streamTranscription emits TranscriptionResult when listener receives Results frame`() = runTest {
        val fakeWs = FakeWebSocket()
        val client = DeepgramSttClient(apiKey = "test-key", webSocketFactory = FakeWebSocketFactory(fakeWs))

        val results = mutableListOf<TranscriptionResult>()
        val collectJob = launch {
            client.streamTranscription(emptyFlow()).collect { results.add(it) }
        }

        advanceUntilIdle() // channelFlow starts; audio job completes (emptyFlow); for-loop suspends

        fakeWs.simulateTextMessage(finalResultJson)
        advanceUntilIdle()

        assertEquals(1, results.size)
        assertEquals("hello world", results[0].transcript)
        assertTrue(results[0].isFinal)
        assertTrue(results[0].speechFinal)

        fakeWs.simulateClose()
        advanceUntilIdle()
        collectJob.join()
    }

    @Test
    fun `streamTranscription filters non-Results frames and emits nothing`() = runTest {
        val fakeWs = FakeWebSocket()
        val client = DeepgramSttClient(apiKey = "test-key", webSocketFactory = FakeWebSocketFactory(fakeWs))

        val results = mutableListOf<TranscriptionResult>()
        val collectJob = launch {
            client.streamTranscription(emptyFlow()).collect { results.add(it) }
        }

        advanceUntilIdle()
        fakeWs.simulateTextMessage("""{"type":"Metadata","transaction_key":"x"}""")
        fakeWs.simulateTextMessage("""{"type":"SpeechStarted"}""")
        advanceUntilIdle()

        assertTrue(results.isEmpty(), "Non-Results frames must not emit TranscriptionResult")

        fakeWs.simulateClose()
        advanceUntilIdle()
        collectJob.join()
    }

    @Test
    fun `CloseStream is sent when audio flow completes`() = runTest {
        val fakeWs = FakeWebSocket()
        val client = DeepgramSttClient(apiKey = "test-key", webSocketFactory = FakeWebSocketFactory(fakeWs))

        val collectJob = launch {
            client.streamTranscription(emptyFlow()).collect { }
        }

        advanceUntilIdle() // audio job exhausts emptyFlow and sends CloseStream

        assertTrue(
            fakeWs.sentTextMessages.any { it.contains("CloseStream") },
            "CloseStream must be sent after the audio flow is exhausted",
        )

        fakeWs.simulateClose()
        advanceUntilIdle()
        collectJob.join()
    }

    @Test
    fun `KeepAlive is sent after 8 seconds of inactivity`() = runTest {
        val fakeWs = FakeWebSocket(rejectKeepAliveAfter = 1)
        val client = DeepgramSttClient(apiKey = "test-key", webSocketFactory = FakeWebSocketFactory(fakeWs))

        // Use a never-completing audio flow so audioJob stays alive and keepAliveJob keeps running.
        // backgroundScope is cancelled automatically at the end of runTest, avoiding any join issues.
        val neverEnding = flow<ByteArray> { awaitCancellation() }
        backgroundScope.launch {
            client.streamTranscription(neverEnding).collect { }
        }

        advanceUntilIdle()
        advanceTimeBy(8_500) // jump past the 8-second KeepAlive interval (virtual time)

        assertTrue(
            fakeWs.sentTextMessages.any { it.contains("KeepAlive") },
            "KeepAlive must be sent after 8 seconds",
        )
        // backgroundScope is cancelled at runTest exit — no need to cancel/join manually.
    }

    @Test
    fun `flow completes cleanly when WebSocket closes normally`() = runTest {
        val fakeWs = FakeWebSocket()
        val client = DeepgramSttClient(apiKey = "test-key", webSocketFactory = FakeWebSocketFactory(fakeWs))

        val collectJob = launch {
            client.streamTranscription(emptyFlow()).collect { }
        }

        advanceUntilIdle()
        fakeWs.simulateClose()
        advanceUntilIdle()
        collectJob.join()
        // If we reach here the flow completed cleanly — no assertion needed.
    }

    @Test
    fun `flow completes with error when WebSocket signals an error`() = runTest {
        val fakeWs = FakeWebSocket()
        val client = DeepgramSttClient(apiKey = "test-key", webSocketFactory = FakeWebSocketFactory(fakeWs))

        var caughtError: Throwable? = null
        val collectJob = launch {
            runCatching {
                client.streamTranscription(emptyFlow()).collect { }
            }.onFailure { caughtError = it }
        }

        advanceUntilIdle()
        fakeWs.simulateError(RuntimeException("connection reset"))
        advanceUntilIdle()
        collectJob.join()

        assertNotNull(caughtError, "Flow must propagate WebSocket errors to the collector")
        assertEquals("connection reset", caughtError!!.message)
    }

    @Test
    fun `audio chunks are forwarded as binary frames`() = runTest {
        val fakeWs = FakeWebSocket()
        val client = DeepgramSttClient(apiKey = "test-key", webSocketFactory = FakeWebSocketFactory(fakeWs))

        val chunk = ByteArray(320) { it.toByte() }
        val collectJob = launch {
            client.streamTranscription(flowOf(chunk)).collect { }
        }

        advanceUntilIdle()

        assertTrue(fakeWs.sentBinaryFrames.isNotEmpty(), "Audio chunks must be sent as binary frames")
        assertTrue(
            fakeWs.sentBinaryFrames.any { it.contentEquals(chunk) },
            "The exact audio chunk bytes must be forwarded",
        )

        fakeWs.simulateClose()
        advanceUntilIdle()
        collectJob.join()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles
// ─────────────────────────────────────────────────────────────────────────────

/** Minimal fake [WebSocket] that captures sent messages and exposes callback triggers.
 *
 * @param rejectKeepAliveAfter stop recording KeepAlive messages after this count to avoid OOM in
 *                             tests that advance virtual time past the keep-alive interval.
 */
private class FakeWebSocket(private val rejectKeepAliveAfter: Int = Int.MAX_VALUE) : WebSocket {

    val sentTextMessages = mutableListOf<String>()
    val sentBinaryFrames = mutableListOf<ByteArray>()

    private var _outputClosed = false
    var listener: WebSocket.Listener? = null

    fun simulateTextMessage(text: String) {
        listener?.onText(this, text, true)
    }

    fun simulateClose(statusCode: Int = 1000, reason: String = "") {
        _outputClosed = true
        listener?.onClose(this, statusCode, reason)
    }

    fun simulateError(error: Throwable) {
        listener?.onError(this, error)
    }

    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
        val msg = data.toString()
        val keepAliveCount = sentTextMessages.count { it.contains("KeepAlive") }
        if (msg.contains("KeepAlive") && keepAliveCount >= rejectKeepAliveAfter) {
            // Silently drop excess KeepAlive to avoid OOM accumulation in long virtual-time tests.
            return CompletableFuture.completedFuture(this)
        }
        sentTextMessages.add(msg)
        return CompletableFuture.completedFuture(this)
    }

    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> {
        val bytes = ByteArray(data.remaining()).also { data.get(it) }
        sentBinaryFrames.add(bytes)
        return CompletableFuture.completedFuture(this)
    }

    override fun request(n: Long) {}
    override fun getSubprotocol(): String = ""
    override fun isOutputClosed(): Boolean = _outputClosed
    override fun isInputClosed(): Boolean = false
    override fun abort() { _outputClosed = true }
    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
    override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> {
        _outputClosed = true
        return CompletableFuture.completedFuture(this)
    }
}

/** [WebSocketFactory] that synchronously injects [fakeWs] instead of opening a real connection. */
private class FakeWebSocketFactory(private val fakeWs: FakeWebSocket) : WebSocketFactory {
    override suspend fun connect(url: String, apiKey: String, listener: WebSocket.Listener): WebSocket {
        fakeWs.listener = listener
        listener.onOpen(fakeWs)
        return fakeWs
    }
}
