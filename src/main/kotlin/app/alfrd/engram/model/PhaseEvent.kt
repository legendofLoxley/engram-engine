package app.alfrd.engram.model

import kotlinx.serialization.Serializable

/**
 * A single phase event emitted by [app.alfrd.engram.cognitive.pipeline.PhaseEventStreamer]
 * over the SSE stream.
 *
 * @property phase          One of: "acknowledge", "bridge", "synthesis", "apology".
 * @property text           The displayable/speakable text for this phase.
 * @property turnId         UUID identifying the conversational turn (stable across all events in one turn).
 * @property traceId        UUID enabling end-to-end debug correlation.
 * @property timestamp      Epoch milliseconds when the event was emitted by the server.
 * @property renderStrategy Voice Rendering Policy hint: "live" | "cached" | "skip".
 *                          Defaults to "live" until VRP is wired. Browser must default to "live"
 *                          when this field is absent (old-server backward compatibility).
 * @property phraseHash     Cache key into the browser's cachedAudioStore. Present only when
 *                          [renderStrategy] is "cached". Null otherwise.
 * @property sequence       1-based chunk index within a synthesis stream. Null for non-synthesis phases.
 * @property final          True on the last synthesis chunk. Null for non-synthesis phases.
 * @property source         "pool" for canned/selection synthesis, "llm" for LLM-generated synthesis.
 *                          Only set on synthesis frames; null for acknowledge/bridge/apology.
 */
@Serializable
data class PhaseEvent(
    val phase: String,
    val text: String,
    val turnId: String,
    val traceId: String,
    val timestamp: Long,
    val renderStrategy: String = "live",
    val phraseHash: String? = null,
    val sequence: Int? = null,
    val final: Boolean? = null,
    val source: String? = null,
)
