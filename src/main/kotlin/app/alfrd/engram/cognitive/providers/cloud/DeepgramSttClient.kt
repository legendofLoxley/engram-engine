package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.SttClient
import app.alfrd.engram.cognitive.providers.TranscriptionResult
import app.alfrd.engram.cognitive.providers.WordTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.logging.Logger

/**
 * Deepgram Nova-3 streaming STT client.
 *
 * Opens a single WebSocket to `wss://api.deepgram.com/v1/listen` per [streamTranscription] call,
 * forwards raw PCM 16-bit LE audio chunks as binary frames, and emits [TranscriptionResult]
 * events parsed from Deepgram's JSON text frames.
 *
 * @param apiKey         Deepgram API key (defaults to the `DEEPGRAM_API_KEY` env var).
 * @param webSocketFactory Injectable factory — swap for a test double to avoid real connections.
 */
class DeepgramSttClient(
    private val apiKey: String = System.getenv("DEEPGRAM_API_KEY") ?: "",
    internal val webSocketFactory: WebSocketFactory = DefaultWebSocketFactory(),
) : SttClient {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val log: Logger = Logger.getLogger(DeepgramSttClient::class.java.name)

        private const val DEEPGRAM_URL =
            "wss://api.deepgram.com/v1/listen" +
            "?model=nova-3" +
            "&encoding=linear16" +
            "&sample_rate=16000" +
            "&channels=1" +
            "&punctuate=true" +
            "&smart_format=true" +
            "&interim_results=true" +
            "&endpointing=300" +
            "&utterance_end_ms=1000" +
            "&vad_events=true"

        private const val KEEP_ALIVE_INTERVAL_MS = 8_000L

        /**
         * Parse a single Deepgram text-frame JSON string into a [TranscriptionResult].
         *
         * Returns `null` for any frame whose `type` is not `"Results"` (e.g. Metadata,
         * UtteranceEnd, SpeechStarted) so callers can ignore non-result events cleanly.
         *
         * Empty-transcript results (silence frames) **are** returned — callers may filter them
         * if needed. This preserves the is_final / speech_final signals even on silence.
         */
        internal fun parseResult(json: Json, text: String): TranscriptionResult? {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "Results") return null

            val channel = obj["channel"]?.jsonObject ?: return null
            val alternatives = channel["alternatives"]?.jsonArray ?: return null
            val best = alternatives.firstOrNull()?.jsonObject ?: return null

            val transcript = best["transcript"]?.jsonPrimitive?.contentOrNull ?: ""
            val confidence = best["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
            val isFinal = obj["is_final"]?.jsonPrimitive?.booleanOrNull ?: false
            val speechFinal = obj["speech_final"]?.jsonPrimitive?.booleanOrNull ?: false

            val words = best["words"]?.jsonArray?.mapNotNull { wordEl ->
                val w = wordEl.jsonObject
                val word = w["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val start = w["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val end = w["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val wordConf = w["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
                WordTiming(
                    word = word,
                    startMs = (start * 1_000).toLong(),
                    endMs = (end * 1_000).toLong(),
                    confidence = wordConf,
                )
            }

            return TranscriptionResult(
                transcript = transcript,
                isFinal = isFinal,
                speechFinal = speechFinal,
                confidence = confidence,
                wordTimings = words?.ifEmpty { null },
            )
        }
    }

    // ── streamTranscription ───────────────────────────────────────────────────

    override fun streamTranscription(audio: Flow<ByteArray>): Flow<TranscriptionResult> = channelFlow {
        // Inbound results bridge: WebSocket listener posts parsed results here.
        // Capacity = UNLIMITED so the listener never blocks the WebSocket thread.
        val inbound: Channel<TranscriptionResult> = Channel(Channel.UNLIMITED)
        // A separate channel to signal WebSocket-level errors back into the coroutine world.
        val errorChannel: Channel<Throwable> = Channel(1)

        val msgAccumulator = StringBuilder()

        val listener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                msgAccumulator.append(data)
                if (last) {
                    val text = msgAccumulator.toString()
                    msgAccumulator.clear()
                    parseResult(json, text)?.let { inbound.trySend(it) }
                }
                webSocket.request(1)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                log.warning("Deepgram WebSocket error: ${error.message}")
                inbound.close(error)
                errorChannel.trySend(error)
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                inbound.close()
                return null
            }
        }

        val ws = try {
            webSocketFactory.connect(DEEPGRAM_URL, apiKey, listener)
        } catch (e: Exception) {
            log.severe("Failed to connect to Deepgram: ${e.message}")
            throw e
        }

        // Coroutine: forward audio chunks as binary frames, then signal end-of-stream.
        val audioJob = launch {
            try {
                audio.collect { chunk ->
                    ws.sendBinary(ByteBuffer.wrap(chunk), true).await()
                }
                // Input flow exhausted — tell Deepgram to flush and close.
                ws.sendText("""{"type":"CloseStream"}""", true).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warning("Deepgram audio forwarding error: ${e.message}")
            }
        }

        // Coroutine: send KeepAlive every 8 s to prevent the server from timing out on silence.
        // Stops automatically once audioJob completes (CloseStream already sent).
        val keepAliveJob = launch {
            while (isActive && !audioJob.isCompleted) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (isActive && !audioJob.isCompleted && !ws.isOutputClosed) {
                    ws.sendText("""{"type":"KeepAlive"}""", true)
                }
            }
        }

        // Forward all inbound results to the channelFlow output, propagating errors.
        try {
            for (result in inbound) {
                send(result)
            }
            // inbound closed normally (onClose fired)
        } catch (e: Exception) {
            // inbound closed with error (onError fired)
            throw e
        } finally {
            audioJob.cancel()
            keepAliveJob.cancel()
            if (!ws.isOutputClosed) {
                runCatching { ws.abort() }
            }
        }
    }
}

// ── WebSocketFactory ──────────────────────────────────────────────────────────

/**
 * Abstraction over WebSocket construction.  The default implementation uses
 * Java's built-in [HttpClient]; tests inject a [FakeWebSocket]-backed double.
 */
interface WebSocketFactory {
    /** Open a WebSocket to [url] authenticated with [apiKey] and attach [listener]. */
    suspend fun connect(url: String, apiKey: String, listener: WebSocket.Listener): WebSocket
}

private class DefaultWebSocketFactory : WebSocketFactory {
    private val http: HttpClient = HttpClient.newHttpClient()

    override suspend fun connect(url: String, apiKey: String, listener: WebSocket.Listener): WebSocket =
        withContext(Dispatchers.IO) {
            http.newWebSocketBuilder()
                .header("Authorization", "Token $apiKey")
                .buildAsync(URI.create(url), listener)
                .join()
        }
}

