package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests — full pipeline end-to-end
// ─────────────────────────────────────────────────────────────────────────────

class CognitivePipelineIntegrationTest {

    private val pipeline = CognitivePipeline()

    @Test
    fun `hey routes to SocialBranch and returns a greeting`() = runTest {
        val response = pipeline.process("Hey", "session-1", "user-1")
        assertTrue(response.isNotBlank(), "Expected a non-blank greeting")
        assertTrue(
            "Good" in response,
            "Expected a time-of-day greeting, got: $response",
        )
    }

    @Test
    fun `thanks routes to SocialBranch and returns acknowledgment`() = runTest {
        val response = pipeline.process("Thanks", "session-1", "user-1")
        assertEquals("Of course.", response)
    }

    @Test
    fun `ambiguous utterance routes to ClarificationBranch`() = runTest {
        val response = pipeline.process("Blah blorp zam", "session-1", "user-1")
        assertEquals("Could you say more about what you mean?", response)
    }

    @Test
    fun `task utterance routes to TaskBranch stub`() = runTest {
        val response = pipeline.process("Remind me to call the vet", "session-1", "user-1")
        assertTrue("task" in response.lowercase() || "noted" in response.lowercase(),
            "Expected a task stub response, got: $response")
    }

    @Test
    fun `question utterance routes to QuestionBranch stub`() = runTest {
        val response = pipeline.process("What time does school start?", "session-1", "user-1")
        assertTrue("question" in response.lowercase() || "wired" in response.lowercase(),
            "Expected a question stub response, got: $response")
    }

    @Test
    fun `meta utterance routes to MetaBranch stub`() = runTest {
        val response = pipeline.process("What do you know about me?", "session-1", "user-1")
        assertTrue("memory" in response.lowercase() || "available" in response.lowercase(),
            "Expected a meta stub response, got: $response")
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
    fun `meta query classified as META with 0_85 confidence`() = runTest {
        val ctx = CognitiveContext(utterance = "What do you know about me?", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(IntentType.META, ctx.intent)
        assertEquals(0.85, ctx.intentConfidence)
    }

    @Test
    fun `Rule 0 scaffold fires before Rule 1 social when scaffold is active`() = runTest {
        // A typical social utterance should be classified as ONBOARDING when
        // scaffoldState is set, confirming Rule 0 takes priority over Rule 1.
        val ctx = CognitiveContext(
            utterance    = "Hey",
            sessionId    = "s",
            userId       = "u",
            scaffoldState = "asking_name",
        )
        comprehension.evaluate(ctx)
        assertEquals(IntentType.ONBOARDING, ctx.intent)
        assertEquals(0.95, ctx.intentConfidence)
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
        ctx.branchResult = BranchResult(content = "Good morning.", responseStrategy = ResponseStrategy.SOCIAL)
        expression.evaluate(ctx)
        assertEquals("Good morning.", ctx.responseText)
        assertEquals(listOf("Good morning."), ctx.streamingPhases)
    }

    @Test
    fun `SIMPLE strategy prepends acknowledge phrase`() = runTest {
        val ctx = CognitiveContext(utterance = "Remind me to call the vet", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(
            content = "I've noted that — task execution is coming soon.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
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
        ctx.branchResult = BranchResult(content = "The answer.", responseStrategy = ResponseStrategy.COMPLEX)
        expression.evaluate(ctx)
        assertEquals(3, ctx.streamingPhases!!.size) // acknowledge + bridge + synthesis
        assertTrue(ctx.responseText.contains("The answer."))
    }

    @Test
    fun `EMOTIONAL strategy produces three phases`() = runTest {
        val ctx = CognitiveContext(utterance = "some emotional remark", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(content = "That matters.", responseStrategy = ResponseStrategy.EMOTIONAL)
        expression.evaluate(ctx)
        assertEquals(3, ctx.streamingPhases!!.size)
        assertTrue(ctx.responseText.contains("That matters."))
    }

    @Test
    fun `responseText is not set when branchResult is null`() = runTest {
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
// Unit tests — SocialBranch modality-check responses
// ─────────────────────────────────────────────────────────────────────────────

class SocialBranchModalityTest {

    private val branch = SocialBranch()

    private val voiceConfirmations = setOf(
        "Loud and clear.",
        "I'm here.",
        "I can hear you.",
        "Right here \u2014 go ahead.",
        "Hearing you fine.",
    )

    @Test
    fun `can you hear me returns a voice-aware confirmation`() = runTest {
        val ctx = CognitiveContext(utterance = "can you hear me?", sessionId = "s", userId = "u")
        branch.execute(ctx)
        val content = ctx.branchResult!!.content
        assertTrue(content in voiceConfirmations, "Expected voice confirmation, got: $content")
    }

    @Test
    fun `are you there returns a voice-aware confirmation`() = runTest {
        val ctx = CognitiveContext(utterance = "are you there?", sessionId = "s", userId = "u")
        branch.execute(ctx)
        val content = ctx.branchResult!!.content
        assertTrue(content in voiceConfirmations, "Expected voice confirmation, got: $content")
    }

    @Test
    fun `is this working returns a voice-aware confirmation`() = runTest {
        val ctx = CognitiveContext(utterance = "is this working", sessionId = "s", userId = "u")
        branch.execute(ctx)
        val content = ctx.branchResult!!.content
        assertTrue(content in voiceConfirmations, "Expected voice confirmation, got: $content")
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
// Unit tests — Expression modality post-filter
// ─────────────────────────────────────────────────────────────────────────────

class ExpressionModalityFilterTest {

    private val expression = Expression()

    @Test
    fun `response containing i can't hear is replaced with fallback`() = runTest {
        val ctx = CognitiveContext(utterance = "say something", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(
            content = "Sorry, I can't hear audio input directly.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
        expression.evaluate(ctx)
        // synthesis-only (no ack prefix) — acknowledge is a separate phase
        assertEquals("I'm right here. What do you need?", ctx.responseText)
    }

    @Test
    fun `response containing i'm a language model is replaced with fallback`() = runTest {
        val ctx = CognitiveContext(utterance = "can you speak?", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(
            content = "I'm a language model so I cannot speak or hear.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
        expression.evaluate(ctx)
        assertEquals("I'm right here. What do you need?", ctx.responseText)
    }

    @Test
    fun `response containing as a text-based is replaced with fallback`() = runTest {
        val ctx = CognitiveContext(utterance = "hello?", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(
            content = "As a text-based assistant I process written input.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
        expression.evaluate(ctx)
        assertEquals("I'm right here. What do you need?", ctx.responseText)
    }

    @Test
    fun `clean LLM response passes through post-filter unchanged`() = runTest {
        val ctx = CognitiveContext(utterance = "what is the capital of France?", sessionId = "s", userId = "u")
        ctx.branchResult = BranchResult(
            content = "Paris is the capital of France.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
        expression.evaluate(ctx)
        assertTrue(ctx.responseText.contains("Paris is the capital of France."))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests — modality-check end-to-end routing
// ─────────────────────────────────────────────────────────────────────────────

class ModalityCheckIntegrationTest {

    private val pipeline = CognitivePipeline()

    private val voiceConfirmations = setOf(
        "Loud and clear.",
        "I'm here.",
        "I can hear you.",
        "Right here \u2014 go ahead.",
        "Hearing you fine.",
    )

    @Test
    fun `can you hear me routes to SOCIAL and returns voice confirmation`() = runTest {
        val response = pipeline.process("can you hear me?", "session-1", "user-1")
        assertTrue(response in voiceConfirmations, "Expected voice confirmation, got: $response")
    }

    @Test
    fun `are you there routes to SOCIAL and returns voice confirmation`() = runTest {
        val response = pipeline.process("are you there?", "session-1", "user-1")
        assertTrue(response in voiceConfirmations, "Expected voice confirmation, got: $response")
    }
}
