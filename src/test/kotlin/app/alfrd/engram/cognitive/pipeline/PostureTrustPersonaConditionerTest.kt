package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.ConfidencePhase
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.TopicConfidence
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Integration tests for posture, per-topic confidence, mood, and persona reaching the actor's
 * prompt as conditioners. Uses the echo-LLM pattern already established by
 * [CognitivePipelineIntegrationTest] / `GreetingTurnGateTest` (TestLlmClient echoes the composed
 * systemPrompt back as the response text), so prompt CONTENT can be asserted without a real model.
 */
class PostureTrustPersonaConditionerTest {

    private val echoLlm = TestLlmClient { req: LlmRequest ->
        LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0)
    }

    // ── Per-topic confidence reaches the prompt as an epistemic note, never tone ──

    @Test
    fun `topic confidence note appears in the composed prompt once seeded for the turn's topic`() = runTest {
        val engramClient = InMemoryEngramClient()
        // "What's the plan for today?" resolves to topic "today" via TopicResolver (longest
        // extracted keyword) — seed confidence for that exact topic.
        engramClient.updateTopicConfidence(
            "user-wr", "today",
            TopicConfidence(topic = "today", score = 0.6, phase = ConfidencePhase.WORKING_RHYTHM),
        )
        val pipeline = CognitivePipeline(engramClient = engramClient, llmClient = echoLlm)

        val response = pipeline.process("What's the plan for today?", "session-wr", "user-wr")

        assertTrue(
            response.contains("working familiarity", ignoreCase = true),
            "Expected the WORKING_RHYTHM topic-confidence note in the prompt, got: $response",
        )
    }

    @Test
    fun `fresh user defaults to the ORIENTATION topic confidence note`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        val response = pipeline.process("What's the plan for today?", "session-o1", "user-o1")

        assertTrue(
            response.contains("don't yet have earned confidence", ignoreCase = true),
            "Expected the ORIENTATION topic-confidence note in the prompt, got: $response",
        )
    }

    // ── Mood override reaches the prompt as a tone directive, never confidence ───

    @Test
    fun `explicit mood override reaches the prompt as a tone directive`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        val response = pipeline.process("Lighten up a bit, would you?", "session-mood-1", "user-mood-1")

        assertTrue(
            response.contains("playfulness", ignoreCase = true),
            "Expected the PLAYFUL mood directive in the prompt after an explicit override, got: $response",
        )
    }

    @Test
    fun `fresh session defaults to the NEUTRAL mood tone`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        val response = pipeline.process("What's the plan for today?", "session-mood-2", "user-mood-2")

        assertTrue(
            response.contains("warm and professional", ignoreCase = true),
            "Expected the NEUTRAL mood directive by default, got: $response",
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

    // ── Recent conversational continuity ──────────────────────────────────────
    // Reproduces the bug observed live in production on 2026-08-29: a warm-intro greeting sent
    // via initSession() was never recorded anywhere, so the very next turn ("what do you
    // mean?") had zero awareness alfrd had already spoken.

    @Test
    fun `warm-intro greeting reaches the next turn's prompt as recent-conversation context`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)
        val init = pipeline.initSession("session-continuity-1", "user-continuity-1")

        val response = pipeline.process("what do you mean?", "session-continuity-1", "user-continuity-1")

        assertTrue(
            response.contains(init.greeting),
            "Expected the initSession greeting to reach the next turn's prompt as recent-conversation context, got: $response",
        )
        assertTrue(
            response.contains("Recent conversation", ignoreCase = true),
            "Expected the recent-conversation block to be present, got: $response",
        )
    }

    @Test
    fun `first turn of a brand-new session has no recent-conversation block`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        val response = pipeline.process("Hey", "session-continuity-2", "user-continuity-2")

        assertFalse(
            response.contains("Recent conversation", ignoreCase = true),
            "Expected no recent-conversation block on a truly fresh turn with no prior history, got: $response",
        )
    }

    @Test
    fun `recent-turns buffer stays capped and drops the oldest entries`() = runTest {
        // A distinct LLM double from echoLlm: setup turns get a short fixed reply (keeping
        // buffer entries small and predictable), and only the final "probe" turn echoes the
        // composed system prompt back, so we can inspect the buffer's state as of that turn.
        val countingLlm = TestLlmClient { req: LlmRequest ->
            if (req.prompt == "probe") {
                LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0)
            } else {
                LlmResponse(text = "ack", latencyMs = 0L, retryCount = 0)
            }
        }
        val pipeline = CognitivePipeline(llmClient = countingLlm)
        val sessionId = "session-continuity-3"
        val userId = "user-continuity-3"

        // MAX_RECENT_TURNS caps at 6 entries (3 exchanges) — 4 exchanges here should evict the
        // first by the time we probe.
        pipeline.process("zzzfirstmarker", sessionId, userId)
        pipeline.process("second turn", sessionId, userId)
        pipeline.process("third turn", sessionId, userId)
        pipeline.process("fourth turn", sessionId, userId)

        val probe = pipeline.process("probe", sessionId, userId)

        assertFalse(
            probe.contains("zzzfirstmarker"),
            "Expected the oldest turn to have been evicted from the buffer, got: $probe",
        )
        assertTrue(
            probe.contains("fourth turn"),
            "Expected a recent turn to still be present in the buffer, got: $probe",
        )
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
            "You nailed it, thanks!",
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
