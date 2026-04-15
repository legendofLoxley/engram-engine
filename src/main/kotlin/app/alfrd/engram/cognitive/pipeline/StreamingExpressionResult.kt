package app.alfrd.engram.cognitive.pipeline

/**
 * Result of the Expression stage, decomposed into streaming cognition phases.
 *
 * The voice loop delivers each non-null phase to TTS in sequence, respecting
 * the non-overlap rule (no new phase starts until the previous finishes playback).
 *
 * @property acknowledge Immediate filler fired before pipeline processing.
 *                       Null for SOCIAL strategy (response is fast enough).
 * @property bridge      Conditional phrase fired if pipeline takes > 1.5 s after acknowledge.
 *                       Null for SOCIAL and SIMPLE strategies.
 * @property synthesis   The substantive pipeline response — always present.
 * @property strategy    The originating [ResponseStrategy], for logging/tracing.
 */
data class StreamingExpressionResult(
    val acknowledge: String?,
    val bridge: String?,
    val synthesis: String,
    val strategy: ResponseStrategy,
)
