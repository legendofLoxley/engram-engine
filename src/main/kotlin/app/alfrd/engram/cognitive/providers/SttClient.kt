package app.alfrd.engram.cognitive.providers

import kotlinx.coroutines.flow.Flow

/** Word-level timing annotation returned with transcription results when available. */
data class WordTiming(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
)

/**
 * A single transcription event emitted by [SttClient.streamTranscription].
 *
 * @property transcript  Partial or final transcript text for this chunk.
 * @property isFinal     True when the transcript for this utterance segment won't change.
 * @property speechFinal True when this event represents an utterance boundary (end of spoken phrase).
 * @property confidence  Model confidence in [transcript], in the range [0f, 1f].
 * @property wordTimings Optional per-word start/end offsets; null when the provider doesn't return them.
 */
data class TranscriptionResult(
    val transcript: String,
    val isFinal: Boolean,
    val speechFinal: Boolean,
    val confidence: Float,
    val wordTimings: List<WordTiming>? = null,
)

/** Streaming speech-to-text abstraction. Swap cloud for local via config. */
interface SttClient {
    /**
     * Ingest raw PCM/audio [audio] chunks and emit [TranscriptionResult] events as the provider
     * produces them. The returned [Flow] completes when the input [Flow] is exhausted and the
     * provider has flushed all pending results.
     */
    fun streamTranscription(audio: Flow<ByteArray>): Flow<TranscriptionResult>
}
