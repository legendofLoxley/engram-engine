package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.PhaseEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/**
 * Extracts the phase-emission logic from the retired VoiceLoopOrchestrator into a
 * Flow-based streamer suitable for the SSE endpoint.
 *
 * A single call to [stream] processes one utterance end-to-end and emits
 * [PhaseEvent] values in the order they are ready:
 *
 *   acknowledge  → (bridge)?  → synthesis   [normal path]
 *   synthesis                               [SOCIAL path]
 *   apology                                 [Reason timeout path]
 *
 * Phase timing rules:
 * - `acknowledge` fires immediately after Comprehension tier completes (no LLM wait).
 * - `bridge` fires only when the Reason tier exceeds [bridgeDelayMs] after acknowledge.
 *   A second bridge is allowed at [bridgeSecondDelayMs] if the pipeline is still running.
 * - `synthesis` fires as soon as the pipeline result is available.
 * - `apology` fires if the pipeline exceeds [reasonTimeoutMs].
 *
 * SOCIAL branch optimization: the social path skips acknowledge and bridge, emitting
 * synthesis directly — the response is inherently fast.
 *
 * @param pipeline           The cognitive pipeline that processes utterances.
 * @param phraseTracker      Session-level deduplication for acknowledge/bridge phrases.
 * @param bridgeDelayMs      Time after acknowledge before emitting first bridge.
 * @param bridgeSecondDelayMs Time after acknowledge before emitting second bridge (if still pending).
 * @param reasonTimeoutMs    Global timeout before emitting apology.
 */
class PhaseEventStreamer(
    private val pipeline: CognitivePipeline,
    private val phraseTracker: PhraseDeduplicationTracker = PhraseDeduplicationTracker(),
    private val bridgeDelayMs: Long = 1_500L,
    private val bridgeSecondDelayMs: Long = 5_000L,
    private val reasonTimeoutMs: Long = 10_000L,
    /**
     * Set of [VoiceRenderPolicy.phraseHash] values for which the browser has pre-rendered
     * audio. Populated at pipeline startup from the cached audio index. Defaults to empty
     * (all phrases → "live") until the index is loaded.
     */
    private val cachedAudioIndex: Set<String> = emptySet(),
    /**
     * TTS voice model identifier included in phrase hash computation. Must match the model
     * used to render the audio in [cachedAudioIndex].
     */
    private val voiceModelId: String = "alfrd-v1",
) {

    companion object {
        private const val APOLOGY_TEXT =
            "I'm sorry, I wasn't able to work through that in time. Could you try again?"
    }

    /**
     * Stream phase events for a single utterance.
     *
     * The returned [Flow] is cold — collection drives execution. The flow completes
     * naturally once all phases have been emitted (or the apology fires).
     *
     * Invariant: every turn terminates with exactly one `synthesis` or `apology` event.
     * A turn can never end after only an `acknowledge` or `bridge`.
     */
    fun stream(
        utterance: String,
        sessionId: String,
        userId: String,
        turnId: String = UUID.randomUUID().toString(),
        traceId: String = UUID.randomUUID().toString(),
    ): Flow<PhaseEvent> = channelFlow {
        val strategy = classifyStrategy(utterance)
        // Track whether a terminal event (synthesis or apology) has been sent so the
        // catch block can emit a fallback apology without double-sending.
        var terminalSent = false

        try {
            if (strategy == ResponseStrategy.SOCIAL) {
                // SOCIAL path: no acknowledge/bridge — synthesise directly.
                val result = awaitWithTimeout {
                    pipeline.processForStream(utterance, sessionId, userId)
                }
                if (result.text == APOLOGY_TEXT) {
                    send(phaseEvent("apology", result.text, turnId, traceId))
                } else {
                    send(synthesisEvent(result.text, result.source, turnId, traceId, sequence = 1, final = true))
                }
                terminalSent = true
                return@channelFlow
            }

            // Non-SOCIAL: emit acknowledge immediately.
            val ackPhrase = phraseTracker.pickAcknowledge(strategy)
            if (ackPhrase != null) {
                send(phaseEvent("acknowledge", ackPhrase, turnId, traceId))
            }

            val bridgeNeeded = strategy == ResponseStrategy.COMPLEX || strategy == ResponseStrategy.EMOTIONAL

            // Start the pipeline in parallel. Exceptions are caught inside the lambda so
            // they never propagate as scope-cancellation to the parent channelFlow coroutine.
            val pipelineDeferred = async {
                try {
                    pipeline.processForStream(utterance, sessionId, userId)
                } catch (e: CancellationException) {
                    throw e // external scope cancel — propagate
                } catch (_: Exception) {
                    CognitivePipeline.SynthesisResult(APOLOGY_TEXT, "pool")
                }
            }

            val pipelineResult: CognitivePipeline.SynthesisResult

            if (bridgeNeeded) {
                val fastResult = withTimeoutOrNull(bridgeDelayMs) { pipelineDeferred.await() }

                if (fastResult != null) {
                    // Pipeline was fast — bridge skipped.
                    pipelineResult = fastResult
                } else {
                    // First bridge.
                    val firstBridgePhrase = phraseTracker.pickBridge(strategy)
                    if (firstBridgePhrase != null) {
                        send(phaseEvent("bridge", firstBridgePhrase, turnId, traceId))
                    }

                    // Race to second bridge at bridgeSecondDelayMs from the start of the turn.
                    // We already waited bridgeDelayMs, so remaining = bridgeSecondDelayMs - bridgeDelayMs.
                    val secondBridgeWait = bridgeSecondDelayMs - bridgeDelayMs
                    val afterFirstBridge = withTimeoutOrNull(secondBridgeWait) { pipelineDeferred.await() }

                    if (afterFirstBridge != null) {
                        pipelineResult = afterFirstBridge
                    } else {
                        // Second bridge allowed at 5s.
                        val secondBridgePhrase = phraseTracker.pickBridge(strategy)
                        if (secondBridgePhrase != null) {
                            send(phaseEvent("bridge", secondBridgePhrase, turnId, traceId))
                        }

                        // Final wait with global timeout.
                        pipelineResult = awaitWithTimeout { pipelineDeferred.await() }
                            .also { if (it.text == APOLOGY_TEXT) pipelineDeferred.cancel() }
                    }
                }
            } else {
                // SIMPLE: no bridge — just wait for pipeline with global timeout.
                pipelineResult = awaitWithTimeout { pipelineDeferred.await() }
            }

            if (pipelineResult.text == APOLOGY_TEXT) {
                send(phaseEvent("apology", pipelineResult.text, turnId, traceId))
            } else {
                send(synthesisEvent(pipelineResult.text, pipelineResult.source, turnId, traceId, sequence = 1, final = true))
            }
            terminalSent = true

        } catch (e: CancellationException) {
            // External scope cancellation — propagate, don't emit apology.
            throw e
        } catch (e: Exception) {
            // Any unexpected failure after acknowledge was sent: guarantee apology so
            // the browser always receives a terminal event.
            if (!terminalSent) {
                try {
                    send(phaseEvent("apology", APOLOGY_TEXT, turnId, traceId))
                } catch (_: Exception) {
                    // Channel already closed — nothing to do.
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Await [block] with a global timeout. Returns a [CognitivePipeline.SynthesisResult] containing
     * [APOLOGY_TEXT] on:
     * - [reasonTimeoutMs] exceeded ([TimeoutCancellationException])
     * - Any other exception from the pipeline (LLM failure, network error, branch crash)
     *
     * External [CancellationException] (scope cancelled) is re-thrown — callers must not
     * swallow it.
     */
    private suspend fun awaitWithTimeout(
        block: suspend () -> CognitivePipeline.SynthesisResult,
    ): CognitivePipeline.SynthesisResult {
        return try {
            withTimeout(reasonTimeoutMs) { block() }
        } catch (_: TimeoutCancellationException) {
            CognitivePipeline.SynthesisResult(APOLOGY_TEXT, "pool")
        } catch (e: CancellationException) {
            throw e  // propagate external scope cancellation
        } catch (_: Exception) {
            CognitivePipeline.SynthesisResult(APOLOGY_TEXT, "pool")  // any pipeline/LLM/branch failure → apology
        }
    }

    private fun phaseEvent(phase: String, text: String, turnId: String, traceId: String): PhaseEvent {
        val (renderStrategy, phraseHash) = vrpAnnotate(phase, text)
        return PhaseEvent(
            phase          = phase,
            text           = text,
            turnId         = turnId,
            traceId        = traceId,
            timestamp      = System.currentTimeMillis(),
            renderStrategy = renderStrategy,
            phraseHash     = phraseHash,
        )
    }

    /**
     * Compute the [VoiceRenderPolicy] renderStrategy and optional phraseHash for a single
     * phase emission.
     *
     * Bridge is only passed to this function when it has already fired (Reason tier was slow),
     * so the `reasonLatencyMs >= threshold` condition is inherently satisfied — we bypass the
     * latency check and go straight to the cache lookup for bridge phrases.
     */
    private fun vrpAnnotate(phase: String, text: String): Pair<String, String?> {
        return when (phase) {
            "acknowledge" -> {
                val hash = VoiceRenderPolicy.phraseHash(text, voiceModelId)
                if (hash in cachedAudioIndex) "cached" to hash else "live" to null
            }
            "bridge" -> {
                // Bridge only reaches here when it fired (Reason was slow), so skip the
                // threshold check and apply the cache lookup directly.
                val hash = VoiceRenderPolicy.phraseHash(text, voiceModelId)
                if (hash in cachedAudioIndex) "cached" to hash else "live" to null
            }
            else -> "live" to null  // synthesis, apology — always live
        }
    }

    private fun synthesisEvent(
        text: String,
        source: String,
        turnId: String,
        traceId: String,
        sequence: Int,
        final: Boolean,
    ) = PhaseEvent(
        phase          = "synthesis",
        text           = text,
        source         = source,
        turnId         = turnId,
        traceId        = traceId,
        timestamp      = System.currentTimeMillis(),
        renderStrategy = "live",  // VRP: synthesis is always live (novel content)
        phraseHash     = null,
        sequence       = sequence,
        final          = final,
    )

    /**
     * Lightweight strategy classification based on utterance patterns.
     * Mirrors the Comprehension tier-1 rules to determine the response strategy
     * without waiting for the full pipeline.
     */
    private fun classifyStrategy(utterance: String): ResponseStrategy {
        val lower = utterance.lowercase()

        val socialPatterns = listOf(
            "hey", "hi", "hello", "good morning", "good afternoon", "good evening",
            "thanks", "thank you", "cheers",
            "bye", "goodbye", "see you", "take care",
        )
        if (socialPatterns.any { lower.contains(it) }) return ResponseStrategy.SOCIAL

        val emotionalPatterns = listOf(
            "i feel", "i'm worried", "i'm scared", "i'm sad", "i'm upset",
            "that hurts", "i'm anxious", "i'm stressed",
        )
        if (emotionalPatterns.any { lower.contains(it) }) return ResponseStrategy.EMOTIONAL

        val taskPatterns = listOf("remind", "send", "create", "set", "schedule", "add", "delete", "remove")
        if (taskPatterns.any { lower.startsWith(it) || lower.contains(" $it ") }) return ResponseStrategy.SIMPLE

        val complexSignals = listOf("explain", "how does", "what are the", "compare", "analyze", "tell me about")
        if (complexSignals.any { lower.contains(it) }) return ResponseStrategy.COMPLEX

        return ResponseStrategy.SIMPLE
    }
}
