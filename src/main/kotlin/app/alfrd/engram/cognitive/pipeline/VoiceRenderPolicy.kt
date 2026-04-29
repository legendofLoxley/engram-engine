package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.model.ExpressionPhase
import java.security.MessageDigest

/**
 * Voice Rendering Policy — annotates expression phases with the render strategy the
 * browser should use for audio output.
 *
 * Render rules:
 *
 * | Phase     | Condition                                    | renderStrategy |
 * |-----------|----------------------------------------------|----------------|
 * | ACKNOWLEDGE | phrase hash in cachedIndex               | cached         |
 * | ACKNOWLEDGE | phrase hash not in cachedIndex           | live           |
 * | BRIDGE    | reasonLatencyMs < BRIDGE_THRESHOLD_MS        | skip           |
 * | BRIDGE    | reasonLatencyMs >= threshold AND cached      | cached         |
 * | BRIDGE    | reasonLatencyMs >= threshold AND not cached  | live           |
 * | PARTIAL   | always                                       | live           |
 * | INTERIM   | always                                       | live           |
 * | SYNTHESIS | always                                       | live           |
 *
 * This object is pure (no state, no I/O) and designed to be called from the
 * PhaseEventStreamer immediately before SSE emission.
 */
object VoiceRenderPolicy {

    /** Time after acknowledge before bridge is considered eligible to fire. */
    const val BRIDGE_THRESHOLD_MS = 1_500L

    /**
     * Compute a stable hash for a phrase + voice model pair.
     *
     * The same [text] + [voiceModelId] always produces the same hash, both within a JVM
     * session and across restarts (SHA-256, lowercase hex). This is the key the browser
     * uses to look up pre-rendered audio in its cachedAudioStore.
     *
     * Different voice models produce different hashes for the same text, ensuring the
     * browser never serves audio rendered for the wrong voice.
     */
    fun phraseHash(text: String, voiceModelId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$text|$voiceModelId".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Annotate a list of raw phases according to VRP rules.
     *
     * @param phases          Phases to annotate — each carries the [ExpressionPhase] enum + text.
     * @param cachedIndex     Set of [phraseHash] values for which the browser has pre-rendered audio.
     * @param voiceModelId    TTS voice model identifier — included in hash computation so that
     *                        audio cached for one model is never reused for another.
     * @param reasonLatencyMs Elapsed ms from turn start to Reason tier completion. Controls bridge
     *                        gating: bridge is "skip" when this value is < [BRIDGE_THRESHOLD_MS].
     */
    fun applyRenderPolicy(
        phases: List<RawPhase>,
        cachedIndex: Set<String>,
        voiceModelId: String,
        reasonLatencyMs: Long,
    ): List<AnnotatedPhase> = phases.map { annotate(it, cachedIndex, voiceModelId, reasonLatencyMs) }

    private fun annotate(
        raw: RawPhase,
        cachedIndex: Set<String>,
        voiceModelId: String,
        reasonLatencyMs: Long,
    ): AnnotatedPhase = when (raw.phase) {
        ExpressionPhase.ACKNOWLEDGE -> {
            val hash = phraseHash(raw.text, voiceModelId)
            if (hash in cachedIndex) AnnotatedPhase(raw.phase, raw.text, "cached", hash)
            else                     AnnotatedPhase(raw.phase, raw.text, "live",   null)
        }
        ExpressionPhase.BRIDGE -> {
            if (reasonLatencyMs < BRIDGE_THRESHOLD_MS) {
                AnnotatedPhase(raw.phase, raw.text, "skip", null)
            } else {
                val hash = phraseHash(raw.text, voiceModelId)
                if (hash in cachedIndex) AnnotatedPhase(raw.phase, raw.text, "cached", hash)
                else                     AnnotatedPhase(raw.phase, raw.text, "live",   null)
            }
        }
        ExpressionPhase.PARTIAL,
        ExpressionPhase.INTERIM,
        ExpressionPhase.SYNTHESIS -> AnnotatedPhase(raw.phase, raw.text, "live", null)
    }
}
