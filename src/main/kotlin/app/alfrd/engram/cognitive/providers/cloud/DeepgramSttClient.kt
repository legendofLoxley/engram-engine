package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.SttClient
import app.alfrd.engram.cognitive.providers.TranscriptionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Deepgram streaming STT client.
 *
 * WebSocket connection logic is a **TODO** — this stub exists to lock down
 * the [SttClient] contract and allow downstream code to compile.
 *
 * @param apiKey Deepgram API key (defaults to the `DEEPGRAM_API_KEY` env var).
 */
class DeepgramSttClient(
    private val apiKey: String = System.getenv("DEEPGRAM_API_KEY") ?: "",
) : SttClient {

    override fun streamTranscription(audio: Flow<ByteArray>): Flow<TranscriptionResult> = flow {
        TODO("Deepgram WebSocket streaming not yet implemented")
    }
}
