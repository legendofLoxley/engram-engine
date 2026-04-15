package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.SttClient
import app.alfrd.engram.cognitive.providers.TranscriptionResult
import app.alfrd.engram.cognitive.providers.TtsClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wires STT → CognitivePipeline → TTS into a single voice loop with streaming
 * cognition phases (Acknowledge → Bridge → Synthesis).
 *
 * Non-overlap rule: no new phase is sent to TTS while a previous phase's audio
 * is still playing. Duration is estimated from PCM byte count at 16 kHz / 16-bit
 * (32 KB/s) plus a 50 ms buffer.
 *
 * @param sttClient      Streaming speech-to-text provider.
 * @param ttsClient      Streaming text-to-speech provider.
 * @param pipeline       The cognitive pipeline that processes utterances.
 * @param phraseTracker  Session-level deduplication for acknowledge/bridge phrases.
 * @param bridgeDelayMs  How long to wait after acknowledge before emitting the bridge phase.
 * @param reasonTimeoutMs Maximum time to wait for pipeline to complete before emitting apology.
 * @param pcmBytesPerSec PCM byte rate for playback duration estimation (16 kHz, 16-bit = 32000).
 * @param playbackBufferMs Extra buffer added to estimated playback duration.
 */
class VoiceLoopOrchestrator(
    private val sttClient: SttClient,
    private val ttsClient: TtsClient,
    private val pipeline: CognitivePipeline,
    private val phraseTracker: PhraseDeduplicationTracker = PhraseDeduplicationTracker(),
    private val bridgeDelayMs: Long = 1_500L,
    private val reasonTimeoutMs: Long = 10_000L,
    private val pcmBytesPerSec: Int = 32_000,
    private val playbackBufferMs: Long = 50L,
) {
    private var loopJob: Job? = null

    /** Mutex enforcing the non‐overlap rule: only one TTS phase plays at a time. */
    private val ttsGate = Mutex()

    companion object {
        private const val APOLOGY_RESPONSE =
            "I'm sorry, I wasn't able to work through that in time. Could you try again?"
        private const val CLARIFICATION_RESPONSE =
            "I didn't catch that. Could you say that again?"
    }

    /**
     * Start the voice loop. Collects final transcripts from STT, processes them
     * through the cognitive pipeline, and streams phased TTS audio to [audioOutput].
     *
     * The loop runs until [stopLoop] is called or [audioInput] completes.
     */
    fun startLoop(
        audioInput: Flow<ByteArray>,
        audioOutput: suspend (ByteArray) -> Unit,
        sessionId: String,
        userId: String,
        scope: CoroutineScope,
    ): Job {
        val job = scope.launch {
            val transcripts: Flow<TranscriptionResult> = sttClient.streamTranscription(audioInput)

            transcripts
                .filter { it.isFinal && it.speechFinal }
                .collect { result ->
                    val transcript = result.transcript.trim()
                    if (transcript.isEmpty()) {
                        handleEmptyTranscript(audioOutput)
                        return@collect
                    }
                    handleTurn(transcript, sessionId, userId, audioOutput)
                }
        }
        loopJob = job
        return job
    }

    /** Cancel the running voice loop. */
    fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * Process a single conversational turn through the streaming cognition phases.
     */
    private suspend fun handleTurn(
        transcript: String,
        sessionId: String,
        userId: String,
        audioOutput: suspend (ByteArray) -> Unit,
    ) = coroutineScope {
        // Kick off the pipeline immediately (runs in parallel with acknowledge).
        val pipelineDeferred = async {
            pipeline.process(transcript, sessionId, userId)
        }

        // Build expression result to determine phase pattern.
        // We need the strategy before the pipeline finishes, so we run comprehension
        // + routing to figure out the strategy, then use Expression to build phases.
        // However, the full pipeline already does this — the simplest approach is to
        // determine strategy from a lightweight pre-classification.
        val strategy = classifyStrategy(transcript)
        val expression = Expression()

        if (strategy == ResponseStrategy.SOCIAL) {
            // SOCIAL: no acknowledge/bridge — just wait for pipeline and synthesise.
            val response = awaitPipelineWithTimeout(pipelineDeferred)
            playPhase(response, audioOutput)
            return@coroutineScope
        }

        // Non-SOCIAL: fire acknowledge immediately.
        val ackPhrase = phraseTracker.pickAcknowledge(strategy)
        if (ackPhrase != null) {
            playPhase(ackPhrase, audioOutput)
        }

        // Race: does the pipeline finish within bridgeDelayMs?
        val bridgeNeeded = strategy == ResponseStrategy.COMPLEX || strategy == ResponseStrategy.EMOTIONAL
        val pipelineResult: String

        if (bridgeNeeded) {
            val fastResult = withTimeoutOrNull(bridgeDelayMs) {
                pipelineDeferred.await()
            }
            if (fastResult != null) {
                // Pipeline was fast — skip bridge, go straight to synthesis.
                pipelineResult = fastResult
            } else {
                // Pipeline is slow — fire bridge phrase.
                val bridgePhrase = phraseTracker.pickBridge(strategy)
                if (bridgePhrase != null) {
                    playPhase(bridgePhrase, audioOutput)
                }
                pipelineResult = awaitPipelineWithTimeout(pipelineDeferred)
            }
        } else {
            // SIMPLE: no bridge, just wait for pipeline.
            pipelineResult = awaitPipelineWithTimeout(pipelineDeferred)
        }

        // Synthesis phase — the actual response.
        playPhase(pipelineResult, audioOutput)
    }

    /**
     * Await the pipeline deferred with a global timeout. If the pipeline exceeds
     * [reasonTimeoutMs], cancel it and return an apology response.
     */
    private suspend fun awaitPipelineWithTimeout(deferred: Deferred<String>): String {
        return try {
            withTimeout(reasonTimeoutMs) {
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            deferred.cancel()
            APOLOGY_RESPONSE
        }
    }

    /**
     * Send a text phrase through TTS and stream all audio chunks to the output,
     * respecting the non-overlap rule. After all chunks are sent, wait for the
     * estimated playback duration before releasing the gate.
     */
    private suspend fun playPhase(text: String, audioOutput: suspend (ByteArray) -> Unit) {
        ttsGate.withLock {
            var totalBytes = 0L
            ttsClient.streamSpeech(text).collect { chunk ->
                audioOutput(chunk)
                totalBytes += chunk.size
            }
            // Estimate playback duration and wait for it to finish.
            val durationMs = (totalBytes * 1_000L) / pcmBytesPerSec + playbackBufferMs
            if (durationMs > 0) {
                delay(durationMs)
            }
        }
    }

    /**
     * Handle an empty transcript — emit a clarification prompt without
     * an acknowledge phase.
     */
    private suspend fun handleEmptyTranscript(audioOutput: suspend (ByteArray) -> Unit) {
        playPhase(CLARIFICATION_RESPONSE, audioOutput)
    }

    /**
     * Lightweight strategy classification based on utterance patterns.
     * Mirrors the Comprehension tier-1 rules to determine the response strategy
     * without waiting for the full pipeline.
     */
    private fun classifyStrategy(utterance: String): ResponseStrategy {
        val lower = utterance.lowercase()
        // Social patterns — greetings, thanks, goodbyes
        val socialPatterns = listOf(
            "hey", "hi", "hello", "good morning", "good afternoon", "good evening",
            "thanks", "thank you", "cheers",
            "bye", "goodbye", "see you", "take care",
        )
        if (socialPatterns.any { lower.contains(it) }) return ResponseStrategy.SOCIAL

        // Emotional patterns
        val emotionalPatterns = listOf(
            "i feel", "i'm worried", "i'm scared", "i'm sad", "i'm upset",
            "that hurts", "i'm anxious", "i'm stressed",
        )
        if (emotionalPatterns.any { lower.contains(it) }) return ResponseStrategy.EMOTIONAL

        // Task / imperative → SIMPLE
        val taskPatterns = listOf("remind", "send", "create", "set", "schedule", "add", "delete", "remove")
        if (taskPatterns.any { lower.startsWith(it) || lower.contains(" $it ") }) return ResponseStrategy.SIMPLE

        // Question with complexity signals → COMPLEX
        val complexSignals = listOf("explain", "how does", "what are the", "compare", "analyze", "tell me about")
        if (complexSignals.any { lower.contains(it) }) return ResponseStrategy.COMPLEX

        // Default: questions and other → SIMPLE
        return if (lower.contains("?")) ResponseStrategy.SIMPLE else ResponseStrategy.SIMPLE
    }
}
