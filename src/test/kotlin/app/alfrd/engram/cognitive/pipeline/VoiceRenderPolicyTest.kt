package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.model.ExpressionPhase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceRenderPolicyTest {

    private val voiceModelId = "alfrd-v1"

    // ── phraseHash ────────────────────────────────────────────────────────────

    @Test
    fun `phraseHash is consistent for identical inputs`() {
        val h1 = VoiceRenderPolicy.phraseHash("Understood.", voiceModelId)
        val h2 = VoiceRenderPolicy.phraseHash("Understood.", voiceModelId)
        assertEquals(h1, h2, "Same text + model must produce the same hash")
    }

    @Test
    fun `phraseHash differs for different texts`() {
        val h1 = VoiceRenderPolicy.phraseHash("Understood.", voiceModelId)
        val h2 = VoiceRenderPolicy.phraseHash("Got it.", voiceModelId)
        assertNotEquals(h1, h2, "Different texts must produce different hashes")
    }

    @Test
    fun `phraseHash differs for different voice models`() {
        val h1 = VoiceRenderPolicy.phraseHash("Understood.", "alfrd-v1")
        val h2 = VoiceRenderPolicy.phraseHash("Understood.", "alfrd-v2")
        assertNotEquals(h1, h2, "Same text with different models must produce different hashes")
    }

    @Test
    fun `phraseHash is lowercase hex`() {
        val hash = VoiceRenderPolicy.phraseHash("Hello.", voiceModelId)
        assertTrue(hash.matches(Regex("[0-9a-f]+")), "Hash must be lowercase hex, got: $hash")
        assertEquals(64, hash.length, "SHA-256 hex must be 64 chars, got length ${hash.length}")
    }

    // ── ACKNOWLEDGE rules ─────────────────────────────────────────────────────

    @Test
    fun `ACKNOWLEDGE with cache hit returns cached strategy and phraseHash`() {
        val text = "Understood."
        val hash = VoiceRenderPolicy.phraseHash(text, voiceModelId)
        val cachedIndex = setOf(hash)

        val result = applyOne(ExpressionPhase.ACKNOWLEDGE, text, cachedIndex, reasonLatencyMs = 0L)

        assertEquals("cached", result.renderStrategy)
        assertEquals(hash, result.phraseHash)
    }

    @Test
    fun `ACKNOWLEDGE with cache miss returns live strategy and null phraseHash`() {
        val text = "Understood."
        val result = applyOne(ExpressionPhase.ACKNOWLEDGE, text, cachedIndex = emptySet(), reasonLatencyMs = 0L)

        assertEquals("live", result.renderStrategy)
        assertNull(result.phraseHash)
    }

    // ── BRIDGE rules ──────────────────────────────────────────────────────────

    @Test
    fun `BRIDGE is skip when reasonLatencyMs is below threshold`() {
        val result = applyOne(
            phase          = ExpressionPhase.BRIDGE,
            text           = "Let me think through that.",
            cachedIndex    = emptySet(),
            reasonLatencyMs = VoiceRenderPolicy.BRIDGE_THRESHOLD_MS - 1,
        )

        assertEquals("skip", result.renderStrategy)
        assertNull(result.phraseHash)
    }

    @Test
    fun `BRIDGE is skip when reasonLatencyMs equals zero`() {
        val result = applyOne(ExpressionPhase.BRIDGE, "Let me think through that.", emptySet(), reasonLatencyMs = 0L)
        assertEquals("skip", result.renderStrategy)
    }

    @Test
    fun `BRIDGE is cached when reasonLatencyMs meets threshold and phrase is cached`() {
        val text = "Let me think through that."
        val hash = VoiceRenderPolicy.phraseHash(text, voiceModelId)
        val cachedIndex = setOf(hash)

        val result = applyOne(
            phase           = ExpressionPhase.BRIDGE,
            text            = text,
            cachedIndex     = cachedIndex,
            reasonLatencyMs = VoiceRenderPolicy.BRIDGE_THRESHOLD_MS,
        )

        assertEquals("cached", result.renderStrategy)
        assertEquals(hash, result.phraseHash)
    }

    @Test
    fun `BRIDGE is live when reasonLatencyMs meets threshold and phrase is not cached`() {
        val result = applyOne(
            phase           = ExpressionPhase.BRIDGE,
            text            = "Let me think through that.",
            cachedIndex     = emptySet(),
            reasonLatencyMs = VoiceRenderPolicy.BRIDGE_THRESHOLD_MS,
        )

        assertEquals("live", result.renderStrategy)
        assertNull(result.phraseHash)
    }

    @Test
    fun `BRIDGE is cached when reasonLatencyMs greatly exceeds threshold and phrase is cached`() {
        val text = "Bear with me."
        val hash = VoiceRenderPolicy.phraseHash(text, voiceModelId)

        val result = applyOne(
            phase           = ExpressionPhase.BRIDGE,
            text            = text,
            cachedIndex     = setOf(hash),
            reasonLatencyMs = 7_000L,
        )

        assertEquals("cached", result.renderStrategy)
        assertEquals(hash, result.phraseHash)
    }

    // ── SYNTHESIS rules ───────────────────────────────────────────────────────

    @Test
    fun `SYNTHESIS is always live even when text hash is in cachedIndex`() {
        val text = "Here is the answer."
        val hash = VoiceRenderPolicy.phraseHash(text, voiceModelId)

        val result = applyOne(
            phase           = ExpressionPhase.SYNTHESIS,
            text            = text,
            cachedIndex     = setOf(hash),   // deliberately in cache — must be ignored
            reasonLatencyMs = 0L,
        )

        assertEquals("live", result.renderStrategy)
        assertNull(result.phraseHash, "Synthesis must never expose a phraseHash")
    }

    // ── PARTIAL rules ─────────────────────────────────────────────────────────

    @Test
    fun `PARTIAL is always live`() {
        val text = "Partial chunk."
        val hash = VoiceRenderPolicy.phraseHash(text, voiceModelId)

        val result = applyOne(ExpressionPhase.PARTIAL, text, cachedIndex = setOf(hash), reasonLatencyMs = 0L)

        assertEquals("live", result.renderStrategy)
        assertNull(result.phraseHash)
    }

    // ── INTERIM rules ─────────────────────────────────────────────────────────

    @Test
    fun `INTERIM is always live`() {
        val text = "Interim chunk."
        val hash = VoiceRenderPolicy.phraseHash(text, voiceModelId)

        val result = applyOne(ExpressionPhase.INTERIM, text, cachedIndex = setOf(hash), reasonLatencyMs = 0L)

        assertEquals("live", result.renderStrategy)
        assertNull(result.phraseHash)
    }

    // ── Batch applyRenderPolicy ───────────────────────────────────────────────

    @Test
    fun `applyRenderPolicy preserves input order`() {
        val ackText    = "Understood."
        val bridgeText = "Let me think through that."
        val synthText  = "Here is the answer."

        val ackHash    = VoiceRenderPolicy.phraseHash(ackText,    voiceModelId)
        val bridgeHash = VoiceRenderPolicy.phraseHash(bridgeText, voiceModelId)

        val phases = listOf(
            RawPhase(ExpressionPhase.ACKNOWLEDGE, ackText),
            RawPhase(ExpressionPhase.BRIDGE,      bridgeText),
            RawPhase(ExpressionPhase.SYNTHESIS,   synthText),
        )

        val results = VoiceRenderPolicy.applyRenderPolicy(
            phases          = phases,
            cachedIndex     = setOf(ackHash, bridgeHash),
            voiceModelId    = voiceModelId,
            reasonLatencyMs = 2_000L,
        )

        assertEquals(3, results.size)
        assertEquals(ExpressionPhase.ACKNOWLEDGE, results[0].phase)
        assertEquals(ExpressionPhase.BRIDGE,      results[1].phase)
        assertEquals(ExpressionPhase.SYNTHESIS,   results[2].phase)
        assertEquals("cached", results[0].renderStrategy)
        assertEquals("cached", results[1].renderStrategy)
        assertEquals("live",   results[2].renderStrategy)
    }

    @Test
    fun `applyRenderPolicy returns empty list for empty input`() {
        val results = VoiceRenderPolicy.applyRenderPolicy(
            phases          = emptyList(),
            cachedIndex     = emptySet(),
            voiceModelId    = voiceModelId,
            reasonLatencyMs = 0L,
        )
        assertTrue(results.isEmpty())
    }

    // ── phraseHash not present on annotated phase for live strategies ─────────

    @Test
    fun `annotated phase has null phraseHash when renderStrategy is live`() {
        val result = applyOne(ExpressionPhase.ACKNOWLEDGE, "Got it.", emptySet(), reasonLatencyMs = 0L)
        assertEquals("live", result.renderStrategy)
        assertNull(result.phraseHash, "phraseHash must be null when strategy is live")
    }

    @Test
    fun `annotated phase has null phraseHash when renderStrategy is skip`() {
        val result = applyOne(
            phase           = ExpressionPhase.BRIDGE,
            text            = "Let me think.",
            cachedIndex     = emptySet(),
            reasonLatencyMs = 0L,
        )
        assertEquals("skip", result.renderStrategy)
        assertNull(result.phraseHash, "phraseHash must be null when strategy is skip")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun applyOne(
        phase: ExpressionPhase,
        text: String,
        cachedIndex: Set<String>,
        reasonLatencyMs: Long,
    ): AnnotatedPhase = VoiceRenderPolicy.applyRenderPolicy(
        phases          = listOf(RawPhase(phase, text)),
        cachedIndex     = cachedIndex,
        voiceModelId    = voiceModelId,
        reasonLatencyMs = reasonLatencyMs,
    ).first()
}
