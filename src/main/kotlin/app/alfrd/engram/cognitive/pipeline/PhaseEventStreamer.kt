package app.alfrd.engram.cognitive.pipeline

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
     */
    fun stream(
        utterance: String,
        sessionId: String,
        userId: String,
        turnId: String = UUID.randomUUID().toString(),
        traceId: String = UUID.randomUUID().toString(),
    ): Flow<PhaseEvent> = channelFlow {
        val strategy = classifyStrategy(utterance)

        if (strategy == ResponseStrategy.SOCIAL) {
            // SOCIAL path: no acknowledge/bridge — synthesise directly.
            val result = awaitWithTimeout(turnId, traceId) {
                pipeline.process(utterance, sessionId, userId)
            }
            send(synthesisEvent(result, turnId, traceId, sequence = 1, final = true))
            return@channelFlow
        }

        // Non-SOCIAL: emit acknowledge immediately.
        val ackPhrase = phraseTracker.pickAcknowledge(strategy)
        if (ackPhrase != null) {
            send(phaseEvent("acknowledge", ackPhrase, turnId, traceId))
        }

        val bridgeNeeded = strategy == ResponseStrategy.COMPLEX || strategy == ResponseStrategy.EMOTIONAL

        // Start the pipeline in parallel.
        val pipelineDeferred = async { pipeline.process(utterance, sessionId, userId) }

        val pipelineResult: String

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
                    pipelineResult = awaitWithTimeout(turnId, traceId) { pipelineDeferred.await() }
                        .also { if (it == APOLOGY_TEXT) pipelineDeferred.cancel() }
                }
            }
        } else {
            // SIMPLE: no bridge — just wait for pipeline with global timeout.
            pipelineResult = awaitWithTimeout(turnId, traceId) { pipelineDeferred.await() }
        }

        if (pipelineResult == APOLOGY_TEXT) {
            send(phaseEvent("apology", pipelineResult, turnId, traceId))
        } else {
            send(synthesisEvent(pipelineResult, turnId, traceId, sequence = 1, final = true))
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun awaitWithTimeout(
        turnId: String,
        traceId: String,
        block: suspend () -> String,
    ): String {
        return try {
            withTimeout(reasonTimeoutMs) { block() }
        } catch (_: TimeoutCancellationException) {
            APOLOGY_TEXT
        }
    }

    private fun phaseEvent(phase: String, text: String, turnId: String, traceId: String) =
        PhaseEvent(
            phase     = phase,
            text      = text,
            turnId    = turnId,
            traceId   = traceId,
            timestamp = System.currentTimeMillis(),
        )

    private fun synthesisEvent(
        text: String,
        turnId: String,
        traceId: String,
        sequence: Int,
        final: Boolean,
    ) = PhaseEvent(
        phase     = "synthesis",
        text      = text,
        turnId    = turnId,
        traceId   = traceId,
        timestamp = System.currentTimeMillis(),
        sequence  = sequence,
        final     = final,
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
