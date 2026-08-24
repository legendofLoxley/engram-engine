package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests — full pipeline end-to-end
//
// With the director/actor split, exact reply wording is LLM-composed and no longer
// deterministic — these tests inject a TestLlmClient that echoes the actor's system
// prompt back verbatim, so the response text reveals which branch's directive (and
// therefore which branch) actually fired. That verifies routing/structure without
// asserting on legacy canned strings.
// ─────────────────────────────────────────────────────────────────────────────

class CognitivePipelineIntegrationTest {

    private val echoLlm = TestLlmClient { req: LlmRequest ->
        LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0)
    }
    private val pipeline = CognitivePipeline(llmClient = echoLlm)

    @Test
    fun `hey on turn 1 routes to SocialBranch's greeting directive`() = runTest {
        val response = pipeline.process("Hey", "session-1", "user-1")
        assertTrue(response.contains("first turn", ignoreCase = true), "Expected greeting directive, got: $response")
    }

    @Test
    fun `thanks routes to SocialBranch's receipt directive`() = runTest {
        val response = pipeline.process("Thanks", "session-2", "user-1")
        assertTrue(response.contains("thanked you", ignoreCase = true), "Expected thanks directive, got: $response")
    }

    @Test
    fun `ambiguous utterance routes to ClarificationBranch's directive`() = runTest {
        val response = pipeline.process("Blah blorp zam", "session-3", "user-1")
        assertTrue(response.contains("unclear", ignoreCase = true), "Expected clarification directive, got: $response")
    }

    @Test
    fun `task utterance routes to TaskBranch's directive`() = runTest {
        val response = pipeline.process("Remind me to call the vet", "session-4", "user-1")
        assertTrue(response.contains("task request", ignoreCase = true), "Expected task directive, got: $response")
    }

    @Test
    fun `question utterance routes to QuestionBranch's directive`() = runTest {
        val response = pipeline.process("What time does school start?", "session-5", "user-1")
        assertTrue(response.contains("asked a question", ignoreCase = true), "Expected question directive, got: $response")
    }

    @Test
    fun `recall question routes to QuestionBranch not MetaBranch's stub directive`() = runTest {
        val response = pipeline.process("What do you know about me?", "session-6", "user-1")
        assertFalse(response.contains("capabilities", ignoreCase = true), "Must not dead-end at MetaBranch, got: $response")
        assertTrue(response.contains("asked a question", ignoreCase = true), "got: $response")
    }

    // ── Degraded fallback: no LLM configured ──────────────────────────────────

    @Test
    fun `every branch produces the single centralized degraded message when no LLM is configured`() = runTest {
        val noLlmPipeline = CognitivePipeline()
        val utterances = listOf(
            "Hey", "Thanks", "Blah blorp zam", "Remind me to call the vet", "What time does school start?",
        )
        for ((i, utterance) in utterances.withIndex()) {
            val response = noLlmPipeline.process(utterance, "degraded-session-$i", "user-1")
            assertEquals(Actor.DEGRADED_TEXT, response, "Expected the centralized degraded text for \"$utterance\"")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — Comprehension Tier 1 classification
// ─────────────────────────────────────────────────────────────────────────────

class ComprehensionTest {

    private val comprehension = Comprehension()

    @Test
    fun `hey classified as SOCIAL with 0_90 confidence`() = runTest {
        val ctx = CognitiveContext(utterance = "Hey", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.SOCIAL, ctx.intent)
        assertEquals(0.90, ctx.intentConfidence)
    }

    @Test
    fun `thanks classified as SOCIAL`() = runTest {
        val ctx = CognitiveContext(utterance = "Thanks", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.SOCIAL, ctx.intent)
        assertEquals(0.90, ctx.intentConfidence)
    }

    @Test
    fun `blah blorp zam classified as AMBIGUOUS`() = runTest {
        val ctx = CognitiveContext(utterance = "Blah blorp zam", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.AMBIGUOUS, ctx.intent)
        assertEquals(0.30, ctx.intentConfidence)
    }

    @Test
    fun `remind me classified as TASK with 0_70 confidence`() = runTest {
        val ctx = CognitiveContext(utterance = "Remind me to call the vet", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.TASK, ctx.intent)
        assertEquals(0.70, ctx.intentConfidence)
    }

    @Test
    fun `what time question classified as QUESTION`() = runTest {
        val ctx = CognitiveContext(utterance = "What time does school start?", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.QUESTION, ctx.intent)
        assertEquals(0.70, ctx.intentConfidence)
    }

    @Test
    fun `recall question classified as QUESTION not META`() = runTest {
        val recallPhrases = listOf(
            "What do you know about me?",
            "What have I told you about my dog?",
            "What did I tell you my wife's name was?",
            "Do you remember my dog's name?",
        )
        for (utterance in recallPhrases) {
            val ctx = CognitiveContext(utterance = utterance, sessionId = "s", userId = "u")
            comprehension.evaluate(ctx)
            assertEquals(
                IntentType.QUESTION, ctx.intent,
                "Recall question '$utterance' must be QUESTION, not META",
            )
        }
    }

}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — Expression stage
// ─────────────────────────────────────────────────────────────────────────────

class ExpressionTest {

    private val expression = Expression()

    @Test
    fun `SOCIAL strategy produces only the response content`() = runTest {
        val ctx = CognitiveContext(utterance = "Hey", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.SOCIAL)
        ctx.actorResult = ActorResult(text = "Good morning.", source = "llm")
        expression.evaluate(ctx)
        assertEquals("Good morning.", ctx.responseText)
        assertEquals(listOf("Good morning."), ctx.streamingPhases)
    }

    @Test
    fun `SIMPLE strategy prepends acknowledge phrase`() = runTest {
        val ctx = CognitiveContext(utterance = "Remind me to call the vet", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.SIMPLE)
        ctx.actorResult = ActorResult(text = "I've noted that — task execution is coming soon.", source = "llm")
        expression.evaluate(ctx)
        // responseText carries synthesis only — acknowledge is a separate phase, not prepended
        assertFalse(ctx.responseText.startsWith("Understood."), "responseText must not start with ack phrase")
        assertEquals("I've noted that — task execution is coming soon.", ctx.responseText)
        // streamingPhases still has 2 elements so the streamer can emit ack + synthesis separately
        assertEquals(2, ctx.streamingPhases!!.size)
        // acknowledge phrase is still captured in streamingExpressionResult
        assertTrue(
            ctx.streamingExpressionResult?.acknowledge in ExpressionPhrasePool.acknowledgeFor(ResponseStrategy.SIMPLE),
            "Acknowledge phrase must be in the SIMPLE pool",
        )
    }

    @Test
    fun `COMPLEX strategy produces three phases`() = runTest {
        val ctx = CognitiveContext(utterance = "some complex query", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.COMPLEX)
        ctx.actorResult = ActorResult(text = "The answer.", source = "llm")
        expression.evaluate(ctx)
        assertEquals(3, ctx.streamingPhases!!.size) // acknowledge + bridge + synthesis
        assertTrue(ctx.responseText.contains("The answer."))
    }

    @Test
    fun `EMOTIONAL strategy produces three phases`() = runTest {
        val ctx = CognitiveContext(utterance = "some emotional remark", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.EMOTIONAL)
        ctx.actorResult = ActorResult(text = "That matters.", source = "llm")
        expression.evaluate(ctx)
        assertEquals(3, ctx.streamingPhases!!.size)
        assertTrue(ctx.responseText.contains("That matters."))
    }

    @Test
    fun `responseText is not set when actorResult is null`() = runTest {
        val ctx = CognitiveContext(utterance = "ignored", sessionId = "s", userId = "u")
        expression.evaluate(ctx)
        assertEquals("", ctx.responseText)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — Comprehension modality-check rule (priority 1.5)
// ─────────────────────────────────────────────────────────────────────────────

class ComprehensionModalityCheckTest {

    private val comprehension = Comprehension()

    @Test
    fun `can you hear me classified as SOCIAL via modality_check rule`() = runTest {
        val ctx = CognitiveContext(utterance = "can you hear me?", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.SOCIAL, ctx.intent)
        assertEquals(0.90, ctx.intentConfidence)
    }

    @Test
    fun `are you there classified as SOCIAL via modality_check rule`() = runTest {
        val ctx = CognitiveContext(utterance = "are you there?", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.SOCIAL, ctx.intent)
        assertEquals(0.90, ctx.intentConfidence)
    }

    @Test
    fun `is this working classified as SOCIAL via modality_check rule`() = runTest {
        val ctx = CognitiveContext(utterance = "is this working", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.SOCIAL, ctx.intent)
        assertEquals(0.90, ctx.intentConfidence)
    }

    @Test
    fun `can you understand me classified as SOCIAL via modality_check rule`() = runTest {
        val ctx = CognitiveContext(utterance = "can you understand me", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.SOCIAL, ctx.intent)
        assertEquals(0.90, ctx.intentConfidence)
    }

    @Test
    fun `hello question mark classified as SOCIAL via modality_check rule`() = runTest {
        val ctx = CognitiveContext(utterance = "hello?", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.SOCIAL, ctx.intent)
        assertEquals(0.90, ctx.intentConfidence)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — SocialBranch modality-check conditioners
// ─────────────────────────────────────────────────────────────────────────────

class SocialBranchModalityTest {

    private val branch = SocialBranch()

    @Test
    fun `can you hear me produces no retrieval and a presence-confirmation directive`() = runTest {
        val ctx = CognitiveContext(utterance = "can you hear me?", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(RetrievalIntent.None, ctx.branchResult!!.retrieval)
        assertTrue(
            ctx.branchResult!!.directive.contains("present", ignoreCase = true),
            "Expected a presence-confirmation directive, got: ${ctx.branchResult!!.directive}",
        )
    }

    @Test
    fun `are you there produces no retrieval and a presence-confirmation directive`() = runTest {
        val ctx = CognitiveContext(utterance = "are you there?", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(RetrievalIntent.None, ctx.branchResult!!.retrieval)
        assertTrue(ctx.branchResult!!.directive.contains("present", ignoreCase = true))
    }

    @Test
    fun `is this working produces no retrieval and a presence-confirmation directive`() = runTest {
        val ctx = CognitiveContext(utterance = "is this working", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(RetrievalIntent.None, ctx.branchResult!!.retrieval)
        assertTrue(ctx.branchResult!!.directive.contains("present", ignoreCase = true))
    }

    @Test
    fun `modality-check response strategy is SOCIAL`() = runTest {
        val ctx = CognitiveContext(utterance = "can you hear me?", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(ResponseStrategy.SOCIAL, ctx.branchResult!!.responseStrategy)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — VoiceContextLlmClient prompt injection
//
// Untouched by the director/actor split — VoiceContextLlmClient is no longer wired
// into CognitivePipeline (Actor sources its identity text from Conditioners.persona, via
// PersonaSource, which itself reuses identitySystemPrompt), but the class and
// VOICE_IDENTITY_SYSTEM_PROMPT stay in place, unmodified.
// ─────────────────────────────────────────────────────────────────────────────

class VoiceContextLlmClientTest {

    @Test
    fun `prepends voice identity to existing system prompt`() = runTest {
        var captured: LlmRequest? = null
        val delegate = TestLlmClient { req ->
            captured = req
            LlmResponse(text = "ok", latencyMs = 0, retryCount = 0)
        }
        val client = VoiceContextLlmClient(delegate)
        client.complete(
            LlmRequest(prompt = "hello", systemPrompt = "You are helpful.")
        )
        val sys = captured!!.systemPrompt!!
        assertTrue(sys.startsWith(VOICE_IDENTITY_SYSTEM_PROMPT), "Expected voice identity prefix, got: $sys")
        assertTrue(sys.contains("You are helpful."), "Expected branch prompt preserved, got: $sys")
    }

    @Test
    fun `injects voice identity when system prompt is null`() = runTest {
        var captured: LlmRequest? = null
        val delegate = TestLlmClient { req ->
            captured = req
            LlmResponse(text = "ok", latencyMs = 0, retryCount = 0)
        }
        val client = VoiceContextLlmClient(delegate)
        client.complete(LlmRequest(prompt = "What time is it?", systemPrompt = null))
        assertEquals(VOICE_IDENTITY_SYSTEM_PROMPT, captured!!.systemPrompt)
    }

    @Test
    fun `voice identity prompt contains key voice-only constraints`() {
        assertTrue(VOICE_IDENTITY_SYSTEM_PROMPT.contains("voice assistant"))
        assertTrue(VOICE_IDENTITY_SYSTEM_PROMPT.contains("Never say you cannot hear"))
        assertTrue(VOICE_IDENTITY_SYSTEM_PROMPT.contains("Never reference text input"))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — identity prompt selection (types.kt)
// ─────────────────────────────────────────────────────────────────────────────

class IdentitySystemPromptTest {

    @Test
    fun `voice modality resolves to the voice identity prompt`() {
        assertEquals(VOICE_IDENTITY_SYSTEM_PROMPT, identitySystemPrompt(Modality.VOICE))
    }

    @Test
    fun `text modality resolves to the text identity prompt`() {
        assertEquals(TEXT_IDENTITY_SYSTEM_PROMPT, identitySystemPrompt(Modality.TEXT))
    }

    @Test
    fun `text identity prompt never claims it can hear or speak aloud`() {
        val lower = TEXT_IDENTITY_SYSTEM_PROMPT.lowercase()
        // The prompt legitimately mentions "hear"/"speaking" as part of forbidding the claim
        // ("never say you can hear them...") — so check it never asserts the claim affirmatively,
        // rather than doing a crude substring check that would also match the negation.
        assertFalse(lower.startsWith("you can hear"), "Text identity must not open by claiming it can hear")
        assertFalse(lower.contains("responding with speech"), "Text identity must not claim it speaks aloud")
        assertTrue(lower.contains("cannot hear or speak aloud"), "Text identity should explicitly disclaim hearing/speaking")
        assertTrue(lower.contains("never say you can hear"), "Text identity should forbid claiming it can hear")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — Expression modality post-filter (both directions)
// ─────────────────────────────────────────────────────────────────────────────

class ExpressionModalityFilterTest {

    private val expression = Expression()

    @Test
    fun `VOICE response containing i can't hear is replaced with the voice fallback`() = runTest {
        val ctx = CognitiveContext(utterance = "say something", sessionId = "s", userId = "u", modality = Modality.VOICE)
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.SIMPLE)
        ctx.actorResult = ActorResult(text = "Sorry, I can't hear audio input directly.", source = "llm")
        expression.evaluate(ctx)
        assertEquals("I'm right here. What do you need?", ctx.responseText)
    }

    @Test
    fun `VOICE response containing i'm a language model is replaced with the voice fallback`() = runTest {
        val ctx = CognitiveContext(utterance = "can you speak?", sessionId = "s", userId = "u", modality = Modality.VOICE)
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.SIMPLE)
        ctx.actorResult = ActorResult(text = "I'm a language model so I cannot speak or hear.", source = "llm")
        expression.evaluate(ctx)
        assertEquals("I'm right here. What do you need?", ctx.responseText)
    }

    @Test
    fun `VOICE response containing as a text-based is replaced with the voice fallback`() = runTest {
        val ctx = CognitiveContext(utterance = "hello?", sessionId = "s", userId = "u", modality = Modality.VOICE)
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.SIMPLE)
        ctx.actorResult = ActorResult(text = "As a text-based assistant I process written input.", source = "llm")
        expression.evaluate(ctx)
        assertEquals("I'm right here. What do you need?", ctx.responseText)
    }

    @Test
    fun `clean VOICE response passes through the filter unchanged`() = runTest {
        val ctx = CognitiveContext(utterance = "what is the capital of France?", sessionId = "s", userId = "u", modality = Modality.VOICE)
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.SIMPLE)
        ctx.actorResult = ActorResult(text = "Paris is the capital of France.", source = "llm")
        expression.evaluate(ctx)
        assertTrue(ctx.responseText.contains("Paris is the capital of France."))
    }

    @Test
    fun `TEXT response falsely claiming it can hear is replaced with the text fallback`() = runTest {
        val ctx = CognitiveContext(utterance = "can you hear me?", sessionId = "s", userId = "u", modality = Modality.TEXT)
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.SIMPLE)
        ctx.actorResult = ActorResult(text = "Yes, I can hear you loud and clear!", source = "llm")
        expression.evaluate(ctx)
        assertEquals("I'm here — what do you need?", ctx.responseText)
    }

    @Test
    fun `TEXT response claiming to be listening is replaced with the text fallback`() = runTest {
        val ctx = CognitiveContext(utterance = "hello?", sessionId = "s", userId = "u", modality = Modality.TEXT)
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.SIMPLE)
        ctx.actorResult = ActorResult(text = "I'm listening — go ahead.", source = "llm")
        expression.evaluate(ctx)
        assertEquals("I'm here — what do you need?", ctx.responseText)
    }

    @Test
    fun `clean TEXT response passes through the filter unchanged`() = runTest {
        val ctx = CognitiveContext(utterance = "what is the capital of France?", sessionId = "s", userId = "u", modality = Modality.TEXT)
        ctx.branchResult = BranchResult(responseStrategy = ResponseStrategy.SIMPLE)
        ctx.actorResult = ActorResult(text = "Paris is the capital of France.", source = "llm")
        expression.evaluate(ctx)
        assertTrue(ctx.responseText.contains("Paris is the capital of France."))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests — modality-check end-to-end routing
// ─────────────────────────────────────────────────────────────────────────────

class ModalityCheckIntegrationTest {

    private val echoLlm = TestLlmClient { req: LlmRequest ->
        LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0)
    }
    private val pipeline = CognitivePipeline(llmClient = echoLlm)

    @Test
    fun `can you hear me routes to SOCIAL modality-check directive`() = runTest {
        val response = pipeline.process("can you hear me?", "session-1", "user-1")
        assertTrue(response.contains("present", ignoreCase = true), "Expected presence-confirmation directive, got: $response")
    }

    @Test
    fun `are you there routes to SOCIAL modality-check directive`() = runTest {
        val response = pipeline.process("are you there?", "session-2", "user-1")
        assertTrue(response.contains("present", ignoreCase = true), "Expected presence-confirmation directive, got: $response")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests — no greeting after turn 1
// ─────────────────────────────────────────────────────────────────────────────

class GreetingTurnGateTest {

    private val echoLlm = TestLlmClient { req: LlmRequest ->
        LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0)
    }

    @Test
    fun `hi on turn 1 gets the greeting directive but hi again later does not`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        val turn1 = pipeline.process("hi", "session-1", "user-1")
        assertTrue(turn1.contains("Greet the user warmly", ignoreCase = true), "Expected greeting directive on turn 1, got: $turn1")

        // Intervening turn so "hi" on turn 3 is unambiguously not turn 1.
        pipeline.process("what's the weather like", "session-1", "user-1")

        val turn3 = pipeline.process("hi", "session-1", "user-1")
        assertFalse(turn3.contains("Greet the user warmly", ignoreCase = true), "Must not greet again mid-session, got: $turn3")
        assertTrue(turn3.contains("NOT the first turn", ignoreCase = true), "Expected the smalltalk directive, got: $turn3")
    }
}
