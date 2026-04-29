package app.alfrd.engram.cognitive.providers

import kotlinx.coroutines.flow.Flow

/** Streaming text-to-speech abstraction. Swap cloud for local via config. */
interface TtsClient {
    /**
     * Convert [text] to audio and stream the result as raw PCM [ByteArray] chunks.
     * The [Flow] completes when the provider has finished synthesising all audio.
     */
    fun streamSpeech(text: String): Flow<ByteArray>

    /**
     * Convert [text] to audio for the given [phaseType] tag ("acknowledge", "bridge", "synthesis")
     * and stream the result as raw PCM [ByteArray] chunks.
     *
     * Default implementation delegates to [streamSpeech] and ignores [phaseType].
     * Persistent-session providers (e.g. [DeepgramTtsClient]) override this to route
     * the flush correctly over their shared WebSocket.
     */
    fun speak(text: String, phaseType: String): Flow<ByteArray> = streamSpeech(text)

    /**
     * Release all resources held by this client (WebSocket, background jobs, etc.).
     * No-op by default for stateless HTTP-based providers.
     */
    suspend fun close() {}
}
