package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Acceptance: "10 consecutive turns run without a crash or hang." Mixes every intent/branch
 * (social, disclosure/verbal-move, question, task, correction, ambiguous/clarification, and a
 * repeat greeting) through one session, both with and without an LLM configured.
 */
class TenTurnSmokeTest {

    private val turns = listOf(
        "hi",
        "my dog's name is Newton",
        "what do you know about me?",
        "remind me to call the vet",
        "actually his name is Neutron, not Newton",
        "thanks",
        "blah blorp zam nonsense",
        "FYI I pushed the branch",
        "hi",
        "bye",
    )

    @Test
    fun `10 turns, no LLM, degraded path, no crash`() = runTest {
        val pipeline = CognitivePipeline()
        for ((i, t) in turns.withIndex()) {
            val r = pipeline.process(t, "smoke-session-nollm", "user-1")
            println("turn ${i + 1}: \"$t\" -> \"$r\"")
            assertTrue(r.isNotBlank(), "blank response on turn ${i + 1}")
        }
    }

    @Test
    fun `10 turns, echo LLM, actor path, TEXT modality, no crash and no voice leak`() = runTest {
        val echoLlm = TestLlmClient { req -> LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0, retryCount = 0) }
        val pipeline = CognitivePipeline(llmClient = echoLlm)
        for ((i, t) in turns.withIndex()) {
            val r = pipeline.process(t, "smoke-session-llm", "user-1")
            println("turn ${i + 1}: \"$t\" -> \"$r\"")
            assertTrue(r.isNotBlank(), "blank response on turn ${i + 1}")
            assertFalse(r.contains("You can hear them"), "TEXT modality leaked voice identity on turn ${i + 1}")
        }
    }
}
