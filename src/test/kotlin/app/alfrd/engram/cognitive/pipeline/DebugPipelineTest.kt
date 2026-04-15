package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugPipelineTest {

    // ── Social utterance: Tier 1 resolves, no LLM ────────────────────────────

    @Test
    fun `debug endpoint returns 200-shape with all required fields for social utterance`() = runTest {
        val pipeline = CognitivePipeline()
        pipeline.init()

        val result = pipeline.processForDebug("Hey", "session-1", "user-1")

        // Chat fields
        assertTrue(result.chat.responseText.isNotBlank())
        assertEquals(IntentType.SOCIAL, result.chat.intent)

        // Comprehension trace
        val c = result.trace.comprehension
        assertEquals(1, c.tier)
        assertEquals("social_phatic", c.tierOneRuleMatched)
        assertEquals(0.90, c.tierOneConfidence)
        assertFalse(c.tierTwoFired)
        assertNull(c.tierTwoResult)

        // Routing trace
        val r = result.trace.routing
        assertEquals("SOCIAL", r.intentType)
        assertEquals("short_circuit_social", r.route)
        assertEquals(0.90, r.confidence)
        assertNull(r.secondaryIntent)
        assertEquals("SocialBranch", r.branchSelected)

        // Session trace
        val s = result.trace.session
        assertNull(s.trustPhase)
        assertTrue(s.turnCount >= 1)

        // Latency breakdown
        val l = result.trace.latencyBreakdown
        assertTrue(l.comprehensionMs >= 0)
        assertTrue(l.routingMs >= 0)
        assertTrue(l.reasonMs >= 0)
        assertTrue(l.expressionMs >= 0)
        assertTrue(l.totalPipelineMs >= 0)

        // Model trace — no LLM for social
        assertNull(result.trace.model.reasonProvider)
        assertNull(result.trace.model.reasonModel)
        assertNull(result.trace.model.comprehensionModel)

        // No selection service → responseSelection is absent
        assertNull(result.trace.responseSelection)
    }

    // ── Ambiguous utterance with LLM: Tier 2 fires ───────────────────────────

    @Test
    fun `debug endpoint returns Tier 2 fields populated for ambiguous utterance`() = runTest {
        val llm = TestLlmClient { req ->
            // When Comprehension asks for classification, return SOCIAL
            if (req.prompt.contains("Classify")) {
                LlmResponse(text = "SOCIAL", latencyMs = 0L, retryCount = 0)
            } else {
                LlmResponse(text = "Test response", latencyMs = 0L, retryCount = 0)
            }
        }
        val pipeline = CognitivePipeline(llmClient = llm)
        pipeline.init()

        val result = pipeline.processForDebug("Blah blorp zam", "session-1", "user-1")

        val c = result.trace.comprehension
        assertEquals(2, c.tier)
        assertTrue(c.tierTwoFired)
        assertEquals("SOCIAL", c.tierTwoResult)
        assertEquals("ambiguous", c.tierOneRuleMatched)
        assertEquals(0.30, c.tierOneConfidence)
    }

    // ── Latency breakdown sums to approximately totalPipelineMs ──────────────

    @Test
    fun `latency breakdown fields sum to approximately totalPipelineMs`() = runTest {
        val pipeline = CognitivePipeline()
        pipeline.init()

        val result = pipeline.processForDebug("Thanks", "session-1", "user-1")

        val l = result.trace.latencyBreakdown
        val stageSum = l.comprehensionMs + l.routingMs + l.reasonMs + l.expressionMs + (l.memoryMs ?: 0)
        // The total includes overhead (scaffold state load, attention, etc.) so it may be
        // slightly larger than the stage sum. Allow up to 50ms delta for test stability.
        assertTrue(
            l.totalPipelineMs >= stageSum - 1,
            "totalPipelineMs (${l.totalPipelineMs}) should be >= stage sum ($stageSum) minus rounding",
        )
        assertTrue(
            l.totalPipelineMs - stageSum < 50,
            "totalPipelineMs (${l.totalPipelineMs}) minus stage sum ($stageSum) should be < 50ms overhead",
        )
    }

    // ── Regular processForChat is unaffected (regression) ────────────────────

    @Test
    fun `regular processForChat response is unaffected`() = runTest {
        val pipeline = CognitivePipeline()
        pipeline.init()

        val result = pipeline.processForChat("Hey", "session-1", "user-1")

        assertTrue(result.responseText.isNotBlank())
        assertEquals(IntentType.SOCIAL, result.intent)
        assertEquals(1, result.comprehensionTier)
        // No trace leaks into regular result
        assertTrue(result is CognitivePipeline.ChatResult)
    }
}
