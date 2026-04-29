package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.TtsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.logging.Logger

/**
 * Deepgram Aura-2 streaming TTS client.
 *
 * Maintains a **single WebSocket** to `wss://api.deepgram.com/v1/speak` per conversation.
 * One [DeepgramTtsClient] instance equals one conversation. Text is sent via `Speak` messages
 * and committed for synthesis via `Flush`. The server responds with binary PCM frames and
 * a `Flushed` text frame per flush.
 *
 * Aura-2 constraints enforced here:
 *   - 1 WebSocket per instance (hard limit)
 *   - ≤ 20 flush messages per 60 s ([MAX_FLUSHES_PER_WINDOW])
 *   - ≤ 2 400 chars per 60 s ([MAX_CHARS_PER_WINDOW])
 *   - 2 000-char max per Speak message (longer text is chunked automatically)
 *   - 60-min session timeout: reconnect is attempted if idle check fails (not after explicit [close])
 *
 * @param apiKey           Deepgram API key (defaults to `DEEPGRAM_API_KEY` env var).
 * @param voiceModel       Deepgram Aura-2 model ID.
 * @param webSocketFactory Injectable factory — swap for a test double to avoid real connections.
 * @param clock            Wall-clock source for rate-limiting windows. Injectable for tests.
 * @param scope            CoroutineScope for the KeepAlive background job. Cancelled by [close].
 */
class DeepgramTtsClient(
    private val apiKey: String = System.getenv("DEEPGRAM_API_KEY") ?: "",
    private val voiceModel: String = "aura-2-en-default",
    internal val webSocketFactory: WebSocketFactory = DefaultTtsWebSocketFactory(),
    internal val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : TtsClient {

    // ── Inbound message types from the WS listener ─────────────────────────

    private sealed interface Inbound
    private data class AudioData(val bytes: ByteArray) : Inbound
    private object Flushed : Inbound

    // ── State ──────────────────────────────────────────────────────────────

    private val connectMutex = Mutex()
    private val speakMutex   = Mutex()

    private var ws: WebSocket? = null
    // UNLIMITED capacity so the WS listener thread never blocks on trySend.
    private var inbound: Channel<Inbound> = Channel(Channel.UNLIMITED)

    private var keepAliveJob: Job? = null
    private var reconnectAttempted = false
    private var sessionStartMs: Long = -1L
    private var closed = false

    // Rate-limiting: sliding window (one entry per flush / per Speak-text submission).
    private val flushTimestamps = ArrayDeque<Long>()        // one entry per flush
    private val charTimestamps  = ArrayDeque<Pair<Long, Int>>() // (epochMs, charCount)

    companion object {
        private val log = Logger.getLogger(DeepgramTtsClient::class.java.name)

        private const val DEEPGRAM_URL_BASE       = "wss://api.deepgram.com/v1/speak"
        internal const val KEEP_ALIVE_INTERVAL_MS = 8_000L
        private const val SESSION_TIMEOUT_MS      = 60L * 60 * 1_000  // 60 min
        internal const val MAX_FLUSHES_PER_WINDOW = 20
        internal const val MAX_CHARS_PER_WINDOW   = 2_400
        internal const val RATE_WINDOW_MS         = 60_000L
        internal const val MAX_TEXT_PER_MESSAGE   = 2_000
    }

    // ── TtsClient interface ────────────────────────────────────────────────

    /** Delegates to [speak] with a "synthesis" phase tag. */
    override fun streamSpeech(text: String): Flow<ByteArray> = speak(text, "synthesis")

    // ── speak ──────────────────────────────────────────────────────────────

    /**
     * Convert [text] to speech audio for the given [phaseType] and stream PCM chunks.
     *
     * The returned [Flow] completes when the server signals `Flushed` for this phrase.
     * Speak calls are serialised — the underlying WS is shared safely across sequential calls.
     *
     * On an unexpected WS drop, one reconnect is attempted. If that also fails, the exception
     * propagates to the caller so the edge function can apply graceful degradation.
     *
     * @param phaseType Informational tag used in logs ("acknowledge", "bridge", "synthesis").
     */
    override fun speak(text: String, phaseType: String): Flow<ByteArray> = channelFlow {
        speakMutex.withLock {
            drainStaleInbound()   // discard leftovers from any prior cancelled collection
            try {
                ensureConnected()
                sendTextAndFlush(text)
                collectAudioUntilFlushed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!reconnectAttempted) {
                    reconnectAttempted = true
                    log.info("DeepgramTTS: WS error on phase=$phaseType — reconnecting. cause=${e.message}")
                    reconnect()
                    sendTextAndFlush(text)
                    collectAudioUntilFlushed()
                } else {
                    throw e
                }
            }
        }
    }

    // ── close ──────────────────────────────────────────────────────────────

    /** Cleanly close the WebSocket and release all resources. */
    override suspend fun close() {
        closed = true
        keepAliveJob?.cancel()
        speakMutex.withLock {
            ws?.takeIf { !it.isOutputClosed }?.let { activeWs ->
                runCatching { activeWs.sendText("""{"type":"Close"}""", true).await() }
                runCatching { activeWs.abort() }
            }
            ws = null
            inbound.close()
        }
        scope.cancel()
    }

    // ── Audio collection ───────────────────────────────────────────────────

    /**
     * Drain audio chunks from [inbound] until a [Flushed] sentinel is received.
     * Throws if the channel is closed before the sentinel arrives (unexpected WS closure).
     */
    private suspend fun ProducerScope<ByteArray>.collectAudioUntilFlushed() {
        for (msg in inbound) {
            when (msg) {
                is AudioData -> this@collectAudioUntilFlushed.send(msg.bytes)
                Flushed      -> return
            }
        }
        // for-loop exits only when channel is closed (no more items and channel is done).
        throw IllegalStateException("Deepgram TTS WS closed before Flushed received")
    }

    // ── Connection management ──────────────────────────────────────────────

    private suspend fun ensureConnected() {
        val current = ws
        if (current != null && !current.isOutputClosed) {
            // Session timeout check.
            if (sessionStartMs >= 0 && clock() - sessionStartMs > SESSION_TIMEOUT_MS) {
                log.info("DeepgramTTS: 60-min session timeout — reconnecting")
                reconnect()
            }
            return
        }
        // Double-checked locking: another coroutine may have connected while we waited.
        connectMutex.withLock {
            val ws2 = ws
            if (ws2 != null && !ws2.isOutputClosed) return
            connectWs()
        }
    }

    private suspend fun reconnect() {
        ws?.let { runCatching { it.abort() } }
        ws = null
        inbound = Channel(Channel.UNLIMITED)
        connectWs()
    }

    private suspend fun connectWs() {
        val url = "$DEEPGRAM_URL_BASE?model=$voiceModel&encoding=linear16&sample_rate=16000"
        // Capture the current channel in a local val so the listener always posts to the
        // channel that was live when this connection was established (safe across reconnects).
        val currentInbound = inbound
        val msgAccumulator = StringBuilder()
        val binAccumulator = mutableListOf<ByteArray>()

        val listener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
            }

            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val bytes = ByteArray(data.remaining()).also { data.get(it) }
                if (last) {
                    val payload = if (binAccumulator.isEmpty()) {
                        bytes
                    } else {
                        binAccumulator.add(bytes)
                        ByteArray(binAccumulator.sumOf { it.size }).also { buf ->
                            var pos = 0
                            binAccumulator.forEach { chunk -> chunk.copyInto(buf, pos); pos += chunk.size }
                            binAccumulator.clear()
                        }
                    }
                    currentInbound.trySend(AudioData(payload))
                } else {
                    binAccumulator.add(bytes)
                }
                webSocket.request(1)
                return null
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                msgAccumulator.append(data)
                if (last) {
                    val text = msgAccumulator.toString()
                    msgAccumulator.clear()
                    when {
                        text.contains("\"Flushed\"") -> currentInbound.trySend(Flushed)
                        text.contains("\"Error\"")   -> log.warning("DeepgramTTS server error: $text")
                    }
                }
                webSocket.request(1)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                log.warning("DeepgramTTS WS error: ${error.message}")
                currentInbound.close(error)
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                if (statusCode == 1000) {
                    currentInbound.close()
                } else {
                    currentInbound.close(
                        IllegalStateException("Deepgram TTS WS closed unexpectedly: $statusCode $reason")
                    )
                }
                return null
            }
        }

        ws = webSocketFactory.connect(url, apiKey, listener)
        if (sessionStartMs < 0) sessionStartMs = clock()
        reconnectAttempted = false   // fresh connection resets the one-reconnect allowance
        startKeepAlive()
        log.info("DeepgramTTS: WebSocket connected (model=$voiceModel)")
    }

    // ── KeepAlive ──────────────────────────────────────────────────────────

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && !closed) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (!closed) {
                    ws?.takeIf { !it.isOutputClosed }
                        ?.sendText("""{"type":"KeepAlive"}""", true)
                }
            }
        }
    }

    // ── Rate limiting and text dispatch ────────────────────────────────────

    private suspend fun sendTextAndFlush(text: String) {
        awaitRateLimit(text.length)
        // Split long text into ≤ MAX_TEXT_PER_MESSAGE chunks; one Flush at the end.
        text.chunked(MAX_TEXT_PER_MESSAGE).forEach { chunk ->
            val msg = buildJsonObject { put("type", "Speak"); put("text", chunk) }.toString()
            ws!!.sendText(msg, true).await()
        }
        ws!!.sendText("""{"type":"Flush"}""", true).await()
        recordFlushUsage(text.length)
    }

    /**
     * Suspend until the current flush can proceed without exceeding Aura-2 rate limits.
     *
     * Uses [clock] (injectable) for window boundary computation so tests can control
     * wall time independently of virtual-time [delay].
     */
    private suspend fun awaitRateLimit(charCount: Int) {
        val windowStart = clock() - RATE_WINDOW_MS
        flushTimestamps.trimStale(windowStart)
        charTimestamps.trimStalePairs(windowStart)

        if (flushTimestamps.size >= MAX_FLUSHES_PER_WINDOW) {
            val waitMs = flushTimestamps.first() + RATE_WINDOW_MS - clock() + 1
            if (waitMs > 0) {
                log.info("DeepgramTTS: flush rate limit reached — waiting ${waitMs}ms")
                delay(waitMs)
            }
            flushTimestamps.trimStale(clock() - RATE_WINDOW_MS)
        }

        val recentChars = charTimestamps.sumOf { it.second }
        // Guard: if no prior usage is recorded, there is nothing to wait out — allow the request
        // even if charCount alone exceeds the window limit (single-phrase edge case).
        if (charTimestamps.isNotEmpty() && recentChars + charCount > MAX_CHARS_PER_WINDOW) {
            val waitMs = charTimestamps.first().first + RATE_WINDOW_MS - clock() + 1
            if (waitMs > 0) {
                log.info("DeepgramTTS: char rate limit reached — waiting ${waitMs}ms")
                delay(waitMs)
            }
            charTimestamps.trimStalePairs(clock() - RATE_WINDOW_MS)
        }
    }

    private fun recordFlushUsage(charCount: Int) {
        val now = clock()
        flushTimestamps.addLast(now)
        charTimestamps.addLast(now to charCount)
    }

    /** Discard any leftover messages from a prior cancelled collection. */
    private fun drainStaleInbound() {
        while (true) inbound.tryReceive().getOrNull() ?: break
    }

    // ── ArrayDeque extension helpers ───────────────────────────────────────

    private fun ArrayDeque<Long>.trimStale(windowStart: Long) {
        while (firstOrNull()?.let { it < windowStart } == true) removeFirst()
    }

    private fun ArrayDeque<Pair<Long, Int>>.trimStalePairs(windowStart: Long) {
        while (firstOrNull()?.first?.let { it < windowStart } == true) removeFirst()
    }
}

// ── DefaultTtsWebSocketFactory ────────────────────────────────────────────────

/** Default [WebSocketFactory] implementation for TTS — uses Java's built-in HTTP client. */
private class DefaultTtsWebSocketFactory : WebSocketFactory {
    private val http = HttpClient.newHttpClient()

    override suspend fun connect(url: String, apiKey: String, listener: WebSocket.Listener): WebSocket =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newWebSocketBuilder()
                .header("Authorization", "Token $apiKey")
                .buildAsync(URI.create(url), listener)
                .join()
        }
}
