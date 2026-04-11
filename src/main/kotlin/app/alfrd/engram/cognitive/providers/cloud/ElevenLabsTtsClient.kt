package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.TtsClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ElevenLabs streaming TTS client.
 *
 * HTTP/WebSocket streaming logic is a **TODO** — this stub exists to lock down
 * the [TtsClient] contract and allow downstream code to compile.
 *
 * @param apiKey ElevenLabs API key (defaults to the `ELEVENLABS_API_KEY` env var).
 * @param voiceId ElevenLabs voice identifier to use for synthesis.
 */
class ElevenLabsTtsClient(
    private val apiKey: String = System.getenv("ELEVENLABS_API_KEY") ?: "",
    private val voiceId: String = "21m00Tcm4TlvDq8ikWAM", // Rachel (default)
) : TtsClient {

    override fun streamSpeech(text: String): Flow<ByteArray> = flow {
        TODO("ElevenLabs streaming TTS not yet implemented")
    }
}
