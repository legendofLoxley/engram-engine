package app.alfrd.engram.cognitive.providers

import kotlinx.coroutines.flow.Flow

/** Streaming text-to-speech abstraction. Swap cloud for local via config. */
interface TtsClient {
    /**
     * Convert [text] to audio and stream the result as raw PCM [ByteArray] chunks.
     * The [Flow] completes when the provider has finished synthesising all audio.
     */
    fun streamSpeech(text: String): Flow<ByteArray>
}
