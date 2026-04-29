package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.model.ExpressionPhase

/**
 * Input to [VoiceRenderPolicy.applyRenderPolicy] — pairs a phase enum value with
 * the actual spoken text so the policy can compute hashes and apply rules.
 */
data class RawPhase(
    val phase: ExpressionPhase,
    val text: String,
)

/**
 * Output of [VoiceRenderPolicy.applyRenderPolicy] — the input phase enriched with
 * a render strategy and optional cache key.
 *
 * @property renderStrategy  "cached" | "live" | "skip"
 * @property phraseHash      Non-null only when [renderStrategy] == "cached" — the key into
 *                           the browser's cachedAudioStore.
 */
data class AnnotatedPhase(
    val phase: ExpressionPhase,
    val text: String,
    val renderStrategy: String,
    val phraseHash: String?,
)
