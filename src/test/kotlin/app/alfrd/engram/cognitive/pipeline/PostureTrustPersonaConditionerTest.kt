package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Integration tests for posture, trust-phase, and persona reaching the actor's prompt as
 * conditioners. Uses the echo-LLM pattern already established by
 * [CognitivePipelineIntegrationTest] / `GreetingTurnGateTest` (TestLlmClient echoes the composed
 * systemPrompt back as the response text), so prompt CONTENT can be asserted without a real model.
 */
class PostureTrustPersonaConditionerTest {

    private val echoLlm = TestLlmClient { req: LlmRequest ->
        LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0)
    }

    // ── Scaffold/trust state loaded on the main turn path ──────────────────────

    @Test
    fun `trust phase note appears in the composed prompt once scaffold state is seeded`() = runTest {
        val engramClient = InMemoryEngramClient()
        engramClient.updateScaffoldState("user-wr", ScaffoldState(trustPhase = 2))
        val pipeline = CognitivePipeline(engramClient = engramClient, llmClient = echoLlm)

        val response = pipeline.process("What's the plan for today?", "session-wr", "user-wr")

        assertTrue(
            response.contains("working rhythm", ignoreCase = true),
            "Expected the WORKING_RHYTHM relationship-stage note in the prompt, got: $response",
        )
    }

    @Test
    fun `fresh user defaults to the ORIENTATION relationship note`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        val response = pipeline.process("What's the plan for today?", "session-o1", "user-o1")

        assertTrue(
            response.contains("early days", ignoreCase = true),
            "Expected the ORIENTATION relationship-stage note in the prompt, got: $response",
        )
    }

    // ── Posture attunement reaches the prompt as a directive, not a phrase pick ──

    @Test
    fun `question-shaped turn carries the clear-answer attunement directive`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        val response = pipeline.process("What time does school start?", "session-q1", "user-q1")

        assertTrue(
            response.contains("clear, direct answer", ignoreCase = true),
            "Expected the Question attunement directive in the prompt, got: $response",
        )
    }

    @Test
    fun `task-shaped turn carries the direct-and-concrete attunement directive`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        // "Remind me..." routes to TaskBranch via Comprehension's intent keyword list, but the
        // posture module's own (narrower, imperative-verb-based) TASK_REQUEST_PATTERN doesn't
        // include "remind" — classifyTextPathTurnShape returns null for it, same as production.
        // Use an utterance that actually matches TASK_REQUEST_PATTERN's imperative prefix list.
        val response = pipeline.process("Schedule a meeting with Sarah for 3pm", "session-t1", "user-t1")

        assertTrue(
            response.contains("direct and concrete", ignoreCase = true),
            "Expected the TaskRequest attunement directive in the prompt, got: $response",
        )
    }

    // ── Persona/self-description reach the prompt, sourced from PersonaSource ──

    @Test
    fun `self-description in the composed prompt asserts persistent memory and the correct modality`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        val response = pipeline.process("Hey", "session-p1", "user-p1")

        assertTrue(response.contains("persistent memory", ignoreCase = true), "got: $response")
        assertTrue(response.contains("cannot hear or speak", ignoreCase = true), "got: $response")
        // Modality correctness itself (never affirmatively claiming voice capability on text)
        // is asserted precisely in PersonaSourceTest / IdentitySystemPromptTest — a blind
        // "you can hear" substring check here would false-positive on this same prompt's
        // negation clause ("Never say you can hear them").
    }

    // ── Misfire regression ──────────────────────────────────────────────────────

    @Test
    fun `emotionally loaded turn-1 opener carries the greeting directive AND the emotional-weight countermand`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        // Deliberately opens like a casual greeting ("Hey,") so Comprehension's Tier-1 isSocial
        // rule fires SOCIAL @ 0.90 and reaches SocialBranch's blind turn-1 greeting gate — while
        // avoiding every classifyTextPathTurnShape pattern (no first-person disclosure marker,
        // no question, no task-request phrasing, no filler density) so turnShape stays null and
        // routing genuinely falls through to SocialBranch rather than VerbalMoveBranch. Emotional
        // marker words are kept unpunctuated (computeSurfaceEnergy matches EMOTIONAL_MARKERS by
        // exact word, so a trailing comma/exclamation on the word itself would break the match)
        // and the exclamation lands on a non-marker word so its boost still applies.
        val utterance = "Hey, tonight has been really stressed and overwhelmed and honestly it was just too much!"
        val response = pipeline.process(utterance, "session-misfire", "user-misfire")

        assertTrue(
            response.contains("Greet the user warmly", ignoreCase = true),
            "Expected this to still reach SocialBranch's turn-1 gate (confirms the regression " +
            "scenario is actually exercised), got: $response",
        )
        assertTrue(
            response.contains("emotional weight", ignoreCase = true),
            "Expected the attunement countermand to accompany the greeting directive, got: $response",
        )
    }

    // ── Stability ────────────────────────────────────────────────────────────

    @Test
    fun `10 consecutive turns of mixed shapes run without crash or hang`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)
        val utterances = listOf(
            "Hey",
            "What time does school start?",
            "Remind me to call the vet",
            "Actually, no I meant the dentist",
            "Thanks",
            "I feel really overwhelmed today",
            "FYI I pushed the branch",
            "um uh I was thinking about something",
            "Can you hear me?",
            "Goodbye",
        )
        for ((i, utterance) in utterances.withIndex()) {
            val response = pipeline.process(utterance, "session-stability", "user-stability")
            assertTrue(response.isNotBlank(), "Turn ${i + 1} (\"$utterance\") produced a blank response")
        }
    }
}
