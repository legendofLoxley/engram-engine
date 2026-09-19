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

// ─────────────────────────────────────────────────────────────────────────────
// Hermes delegation — CognitivePipeline wiring (dispatch is faked here; the real
// HermesAcpClient/ACP round trip is exercised only against live hardware, not this
// suite — see webui-bridge/README.md and the increment's own demonstration record).
// ─────────────────────────────────────────────────────────────────────────────

class CognitivePipelineHermesDelegationTest {

    private val echoLlm = TestLlmClient { req: LlmRequest ->
        LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0)
    }

    @Test
    fun `utterance naming the fixture with an inspection verb dispatches one correlated assignment`() = runTest {
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { dispatched.add(it) },
        )

        val response = pipeline.process(
            "Can you check director-hermes-fixture.txt for me?", "session-hermes-1", "user-hermes@example.com",
        )

        assertEquals(1, dispatched.size, "Expected exactly one assignment dispatched")
        val assignment = dispatched.single()
        assertEquals("user-hermes@example.com", assignment.userEmail)
        assertEquals("Can you check director-hermes-fixture.txt for me?", assignment.originalRequest)
        assertTrue(
            assignment.task.contains(app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.FIXTURE_FILENAME),
            "Assignment task must name the fixture, got: ${assignment.task}",
        )
        assertTrue(assignment.assignmentId.isNotBlank())
        assertTrue(
            response.contains("Hermes", ignoreCase = true),
            "Expected the acknowledgment directive to reach the actor's prompt, got: $response",
        )
    }

    @Test
    fun `utterance without the fixture name never dispatches`() = runTest {
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { dispatched.add(it) },
        )

        pipeline.process("What time does school start?", "session-hermes-2", "user-hermes@example.com")

        assertTrue(dispatched.isEmpty(), "Must not dispatch for an utterance that never names the fixture")
    }

    @Test
    fun `no dispatcher wired leaves a matching utterance's reply unaffected`() = runTest {
        val pipeline = CognitivePipeline(llmClient = echoLlm)

        val response = pipeline.process(
            "Please inspect director-hermes-fixture.txt", "session-hermes-3", "user-hermes@example.com",
        )

        assertFalse(response.contains("asked Hermes", ignoreCase = true), "No dispatcher wired means no delegation directive")
    }

    @Test
    fun `a dispatched assignment's id and task are surfaced on the debug trace, for a caller to correlate a later completion`() = runTest {
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { dispatched.add(it) },
        )

        val debugResult = pipeline.processForDebug(
            "Can you check director-hermes-fixture.txt for me?", "session-hermes-4", "user-hermes@example.com",
        )

        val assignment = dispatched.single()
        val traced = debugResult.trace.hermesDelegation
        assertEquals(assignment.assignmentId, traced?.assignmentId)
        assertEquals(assignment.task, traced?.task)
    }

    @Test
    fun `a non-matching utterance leaves the debug trace's hermesDelegation null`() = runTest {
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { },
        )

        val debugResult = pipeline.processForDebug("What time does school start?", "session-hermes-5", "user-hermes@example.com")

        assertNull(debugResult.trace.hermesDelegation)
    }

    @Test
    fun `utterance naming the approved document with a summarize verb dispatches a DocumentSummary assignment`() = runTest {
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val approvedFilename = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.APPROVED_DOCUMENTS.first().filename
        val fakeDirector = FakeHermesDocumentIntentDirector { _, _, _ ->
            app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.Delegate(approvedFilename),
                latencyMs = 5, modelCalled = true,
            )
        }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { dispatched.add(it) },
            documentIntentDirector = fakeDirector,
        )

        val response = pipeline.process(
            "Can you give me the rundown on that project brief — goal, deadlines, risks, and next actions?",
            "session-hermes-6", "user-hermes@example.com",
        )

        assertEquals(1, dispatched.size, "Expected exactly one assignment dispatched")
        val assignment = dispatched.single()
        val kind = assignment.kind
        assertTrue(
            kind is app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentKind.DocumentSummary,
            "Expected a DocumentSummary assignment kind, got: $kind",
        )
        assertEquals(approvedFilename, kind.targetFilename)
        assertTrue(assignment.task.contains("summarize", ignoreCase = true), "Assignment task must ask for a summary, got: ${assignment.task}")
        assertTrue(assignment.task.contains("Goal") && assignment.task.contains("Deadlines") && assignment.task.contains("Risks"), "got: ${assignment.task}")
        assertTrue(
            response.contains("Hermes", ignoreCase = true),
            "Expected the acknowledgment directive to reach the actor's prompt, got: $response",
        )
    }

    @Test
    fun `an utterance naming the fixture still dispatches MarkerCheck without ever consulting the document-intent director`() = runTest {
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val fakeDirector = FakeHermesDocumentIntentDirector { _, _, _ -> fail("must not be consulted when the marker-check regex already matched") }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { dispatched.add(it) },
            documentIntentDirector = fakeDirector,
        )

        pipeline.process("Can you check director-hermes-fixture.txt for me?", "session-hermes-7", "user-hermes@example.com")

        val kind = dispatched.single().kind
        assertTrue(kind is app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentKind.MarkerCheck, "got: $kind")
        assertEquals(0, fakeDirector.callCount)
    }

    @Test
    fun `a NoDelegation decision never dispatches`() = runTest {
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val fakeDirector = FakeHermesDocumentIntentDirector { _, _, _ ->
            app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.NoDelegation,
                latencyMs = 5, modelCalled = true,
            )
        }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { dispatched.add(it) },
            documentIntentDirector = fakeDirector,
        )

        pipeline.process("Can you summarize that project brief for me?", "session-hermes-8", "user-hermes@example.com")

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `a Clarify decision never dispatches, and the follow-up turn passes the candidates back to the director`() = runTest {
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val seenPendingCandidates = mutableListOf<List<String>?>()
        val candidates = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.APPROVED_DOCUMENTS.map { it.filename }
        var callCount = 0
        val fakeDirector = FakeHermesDocumentIntentDirector { _, _, pending ->
            callCount++
            seenPendingCandidates.add(pending)
            if (callCount == 1) {
                app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                    decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.Clarify(candidates),
                    latencyMs = 5, modelCalled = true,
                )
            } else {
                app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                    decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.Delegate(candidates[0]),
                    latencyMs = 5, modelCalled = true,
                )
            }
        }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { dispatched.add(it) },
            documentIntentDirector = fakeDirector,
        )

        val firstResponse = pipeline.process("Can you summarize that document for me?", "session-hermes-9", "user-hermes@example.com")
        assertTrue(dispatched.isEmpty(), "Clarify must never dispatch")
        assertTrue(firstResponse.contains("choose", ignoreCase = true) || firstResponse.contains("which", ignoreCase = true) || firstResponse.contains("clarify", ignoreCase = true) || candidates.any { firstResponse.contains(it) }, "got: $firstResponse")
        assertEquals(null, seenPendingCandidates[0], "no clarification was pending before the first turn")

        pipeline.process("The release checklist one", "session-hermes-9", "user-hermes@example.com")
        assertEquals(candidates, seenPendingCandidates[1], "the second call must receive the first turn's own candidates back")
        assertEquals(1, dispatched.size, "the follow-up turn's Delegate decision must dispatch")
    }

    @Test
    fun `SOCIAL turns never consult the document-intent director — the intent gate is a real cost guard`() = runTest {
        val fakeDirector = FakeHermesDocumentIntentDirector { _, _, _ -> fail("must not be consulted for an ordinary social turn") }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { },
            documentIntentDirector = fakeDirector,
        )

        pipeline.process("hey there", "session-hermes-10", "user-hermes@example.com")

        assertEquals(0, fakeDirector.callCount)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Mid-flight conversational cancellation — bounded to DocumentSummary. The fake
    // dispatcher below mimics only what HermesDelegationDispatcher's own doc requires of a
    // caller: register a HermesCancelHandle in the same registry the cancellation check
    // calls through. HermesDelegationDispatcher/HermesActiveAssignmentRegistry themselves
    // are exercised by their own test suites — not re-tested here.
    // ─────────────────────────────────────────────────────────────────────────────

    private fun negatingDirector(approvedFilename: String, negationPhrase: String = "never mind") =
        FakeHermesDocumentIntentDirector { utterance, _, _ ->
            if (utterance.contains(negationPhrase, ignoreCase = true)) {
                app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                    decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.NoDelegation,
                    latencyMs = 5, modelCalled = true, rejectedReason = "model_classified_negated",
                )
            } else {
                app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                    decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.Delegate(approvedFilename),
                    latencyMs = 5, modelCalled = true,
                )
            }
        }

    @Test
    fun `negation with exactly one outstanding assignment requests cancellation through the existing registry`() = runTest {
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val registry = app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry()
        val completionStore = app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore()
        val approvedFilename = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.APPROVED_DOCUMENTS[0].filename
        val dispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { assignment ->
            dispatched.add(assignment)
            registry.register(assignment.assignmentId, assignment.userEmail, app.alfrd.engram.cognitive.pipeline.hermes.HermesCancelHandle())
        }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = dispatcher,
            hermesActiveAssignments = registry,
            hermesAssignmentCompletionStore = completionStore,
            documentIntentDirector = negatingDirector(approvedFilename),
        )

        pipeline.process("Can you summarize that project brief for me?", "session-cancel-1", "user-cancel@example.com")
        val assignmentId = dispatched.single().assignmentId

        val debugResult = pipeline.processForDebug("Actually, never mind that", "session-cancel-1", "user-cancel@example.com")

        assertEquals("requested", debugResult.trace.hermesCancellation?.outcome)
        assertEquals(assignmentId, debugResult.trace.hermesCancellation?.assignmentId)
        assertEquals(approvedFilename, debugResult.trace.hermesCancellation?.targetFilename)
        // echoLlm echoes the actor's system prompt verbatim (see the class-level fixture doc), so
        // the assembled directive itself is what's being inspected here, not simulated model
        // output — it must say the honest "passed along, not confirmed stopped" thing and must
        // never instruct the actor to claim anything was updated/corrected/saved.
        assertTrue(debugResult.chat.responseText.contains("passed that along"), "got: ${debugResult.chat.responseText}")
        assertTrue(
            debugResult.chat.responseText.contains("do not say anything was updated, corrected, or saved"),
            "got: ${debugResult.chat.responseText}",
        )
        // Already consumed — the assignment was removed from tracking the moment cancellation was
        // requested, so a second identical negation has nothing left to act on and must not
        // re-invoke the cancellation logic at all (HermesCancelHandle.requestCancel is itself
        // idempotent and would still report "open" until the real exchange finishes, which never
        // happens in this unit test — the correct thing this test can actually observe is that
        // outstandingHermesAssignments is now empty, not the handle's own internal state).
        val secondDebugResult = pipeline.processForDebug("Actually, never mind that", "session-cancel-1", "user-cancel@example.com")
        assertNull(secondDebugResult.trace.hermesCancellation, "nothing left outstanding to cancel a second time")
    }

    @Test
    fun `an unrelated new document request while one is already outstanding leaves the first running, untouched`() = runTest {
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val registry = app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry()
        val completionStore = app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore()
        val (docA, docB) = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.APPROVED_DOCUMENTS.map { it.filename }
        val fakeDirector = FakeHermesDocumentIntentDirector { utterance, _, _ ->
            val target = if (utterance.contains("second", ignoreCase = true)) docB else docA
            app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.Delegate(target),
                latencyMs = 5, modelCalled = true,
            )
        }
        val dispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { assignment ->
            dispatched.add(assignment)
            registry.register(assignment.assignmentId, assignment.userEmail, app.alfrd.engram.cognitive.pipeline.hermes.HermesCancelHandle())
        }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = dispatcher,
            hermesActiveAssignments = registry,
            hermesAssignmentCompletionStore = completionStore,
            documentIntentDirector = fakeDirector,
        )

        pipeline.process("Can you check the first document for me?", "session-cancel-2", "user-cancel@example.com")
        val firstAssignmentId = dispatched.single().assignmentId

        pipeline.process("Can you check the second document too?", "session-cancel-2", "user-cancel@example.com")

        // A second, unrelated (non-negation) document request must dispatch its own assignment
        // and never touch the first — both remain independently cancellable/active.
        assertEquals(2, dispatched.size)
        assertEquals(
            app.alfrd.engram.cognitive.pipeline.hermes.HermesCancellationRequestOutcome.Requested,
            registry.requestCancellation(firstAssignmentId, "user-cancel@example.com"),
            "the first assignment must still be genuinely active — the second request never cancelled it",
        )
    }

    @Test
    fun `negation unrelated to any summarize request never reaches the cancellation logic`() = runTest {
        val registry = app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry()
        val completionStore = app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore()
        val approvedFilename = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.APPROVED_DOCUMENTS[0].filename
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val fakeDirector = FakeHermesDocumentIntentDirector { utterance, _, _ ->
            // A real classifier never reports a summarize-decline for grocery-list content —
            // this fixture reflects that, rather than re-deriving it from a prompt.
            if (utterance.contains("sugar", ignoreCase = true)) {
                app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                    decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.NoDelegation,
                    latencyMs = 5, modelCalled = true, rejectedReason = null,
                )
            } else {
                app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                    decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.Delegate(approvedFilename),
                    latencyMs = 5, modelCalled = true,
                )
            }
        }
        val dispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { assignment ->
            dispatched.add(assignment)
            registry.register(assignment.assignmentId, assignment.userEmail, app.alfrd.engram.cognitive.pipeline.hermes.HermesCancelHandle())
        }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = dispatcher,
            hermesActiveAssignments = registry,
            hermesAssignmentCompletionStore = completionStore,
            documentIntentDirector = fakeDirector,
        )

        pipeline.process("Can you check that document for me?", "session-cancel-3", "user-cancel@example.com")
        val assignmentId = dispatched.single().assignmentId

        val debugResult = pipeline.processForDebug("Don't add sugar to my shopping list", "session-cancel-3", "user-cancel@example.com")

        assertNull(debugResult.trace.hermesCancellation)
        assertEquals(
            app.alfrd.engram.cognitive.pipeline.hermes.HermesCancellationRequestOutcome.Requested,
            registry.requestCancellation(assignmentId, "user-cancel@example.com"),
            "an unrelated grocery-list comment must never cancel the outstanding document check",
        )
    }

    @Test
    fun `negation for an already-completed assignment is cleared by staleness cleanup, never a redundant cancel attempt`() = runTest {
        val registry = app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry()
        val completionStore = app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore()
        val approvedFilename = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.APPROVED_DOCUMENTS[0].filename
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val dispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { dispatched.add(it) }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = dispatcher,
            hermesActiveAssignments = registry,
            hermesAssignmentCompletionStore = completionStore,
            documentIntentDirector = negatingDirector(approvedFilename),
        )

        pipeline.process("Can you summarize that project brief for me?", "session-cancel-4", "user-cancel@example.com")
        val assignment = dispatched.single()
        // Simulate the dispatcher's own real completion recording (see HermesDelegationDispatcher) —
        // this assignment resolved before the user ever typed a follow-up.
        completionStore.record(
            assignmentId = assignment.assignmentId,
            userEmail = assignment.userEmail,
            outcome = app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentOutcome.Completed(
                findingsText = "done", toolName = "read", toolTargetPath = approvedFilename, toolSucceeded = true,
            ),
            decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesCompletionDecision.Accepted("done"),
            graphIngestOutcome = "Committed",
        )

        val debugResult = pipeline.processForDebug("Actually, never mind that", "session-cancel-4", "user-cancel@example.com")

        assertNull(debugResult.trace.hermesCancellation, "a resolved assignment must be cleared before the negation check ever considers it")
    }

    @Test
    fun `the never-mind path never writes a spurious fact or claims one was updated`() = runTest {
        val engramClient = app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient()
        val registry = app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry()
        val completionStore = app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore()
        val approvedFilename = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.APPROVED_DOCUMENTS[0].filename
        val dispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { assignment ->
            registry.register(assignment.assignmentId, assignment.userEmail, app.alfrd.engram.cognitive.pipeline.hermes.HermesCancelHandle())
        }
        val pipeline = CognitivePipeline(
            engramClient = engramClient,
            llmClient = echoLlm,
            hermesDelegationDispatcher = dispatcher,
            hermesActiveAssignments = registry,
            hermesAssignmentCompletionStore = completionStore,
            documentIntentDirector = negatingDirector(approvedFilename, negationPhrase = "actually, never mind that"),
        )

        pipeline.process("Can you summarize that project brief for me?", "session-cancel-5", "user-cancel@example.com")
        val response = pipeline.process("actually, never mind that", "session-cancel-5", "user-cancel@example.com")

        val storedPhrases = engramClient.queryPhrases("user-cancel@example.com", concept = null, limit = 50)
        assertTrue(
            storedPhrases.none { it.text.contains("never mind", ignoreCase = true) },
            "must not ingest the cancellation utterance itself as a fact, got: ${storedPhrases.map { it.text }}",
        )
        // echoLlm echoes the system prompt verbatim — this inspects the assembled directive
        // itself, confirming it instructs the actor honestly rather than to confirm a correction.
        assertTrue(response.contains("passed that along"), "got: $response")
        assertTrue(response.contains("do not say anything was updated, corrected, or saved"), "got: $response")
        assertTrue(
            !response.contains("confirm briefly and warmly that you've updated it", ignoreCase = true),
            "must not still carry CorrectionBranch's own directive, got: $response",
        )
    }

    @Test
    fun `the document-intent classifier is consulted at most once per turn even when both the early and late blocks would otherwise call it`() = runTest {
        val registry = app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry()
        val completionStore = app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore()
        val (docA, docB) = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.APPROVED_DOCUMENTS.map { it.filename }
        var callCount = 0
        val fakeDirector = FakeHermesDocumentIntentDirector { utterance, _, _ ->
            callCount++
            val target = if (callCount == 1) docA else docB
            app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.Delegate(target),
                latencyMs = 5, modelCalled = true,
            )
        }
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val dispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { assignment ->
            dispatched.add(assignment)
            registry.register(assignment.assignmentId, assignment.userEmail, app.alfrd.engram.cognitive.pipeline.hermes.HermesCancelHandle())
        }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = dispatcher,
            hermesActiveAssignments = registry,
            hermesAssignmentCompletionStore = completionStore,
            documentIntentDirector = fakeDirector,
        )

        pipeline.process("Can you check the first document for me?", "session-cancel-6", "user-cancel@example.com")
        assertEquals(1, callCount)

        pipeline.process("Can you check a second, different document too?", "session-cancel-6", "user-cancel@example.com")

        assertEquals(2, callCount, "exactly one decide() call for the second turn — the early check's own result must be reused by the late block, not re-requested")
        assertEquals(2, dispatched.size)
    }

    @Test
    fun `two outstanding assignments and an unnamed negation asks for clarification instead of guessing`() = runTest {
        val registry = app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry()
        val completionStore = app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore()
        val (docA, docB) = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationTrigger.APPROVED_DOCUMENTS.map { it.filename }
        var callCount = 0
        val fakeDirector = FakeHermesDocumentIntentDirector { utterance, _, _ ->
            callCount++
            when {
                callCount == 1 -> app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                    decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.Delegate(docA),
                    latencyMs = 5, modelCalled = true,
                )
                callCount == 2 -> app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                    decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.Delegate(docB),
                    latencyMs = 5, modelCalled = true,
                )
                else -> app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult(
                    decision = app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDecision.NoDelegation,
                    latencyMs = 5, modelCalled = true, rejectedReason = "model_classified_negated",
                )
            }
        }
        val dispatched = mutableListOf<app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignment>()
        val dispatcher = app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatching { assignment ->
            dispatched.add(assignment)
            registry.register(assignment.assignmentId, assignment.userEmail, app.alfrd.engram.cognitive.pipeline.hermes.HermesCancelHandle())
        }
        val pipeline = CognitivePipeline(
            llmClient = echoLlm,
            hermesDelegationDispatcher = dispatcher,
            hermesActiveAssignments = registry,
            hermesAssignmentCompletionStore = completionStore,
            documentIntentDirector = fakeDirector,
        )

        pipeline.process("Can you check the first document for me?", "session-cancel-7", "user-cancel@example.com")
        pipeline.process("Can you check the second document too?", "session-cancel-7", "user-cancel@example.com")
        val (firstId, secondId) = dispatched.map { it.assignmentId }

        val debugResult = pipeline.processForDebug("never mind", "session-cancel-7", "user-cancel@example.com")

        assertEquals("ambiguous", debugResult.trace.hermesCancellation?.outcome)
        assertEquals(setOf(docA, docB), debugResult.trace.hermesCancellation?.candidateFilenames?.toSet())
        assertTrue(!debugResult.chat.responseText.contains("updated", ignoreCase = true), "got: ${debugResult.chat.responseText}")
        // Neither was actually cancelled.
        assertEquals(app.alfrd.engram.cognitive.pipeline.hermes.HermesCancellationRequestOutcome.Requested, registry.requestCancellation(firstId, "user-cancel@example.com"))
        assertEquals(app.alfrd.engram.cognitive.pipeline.hermes.HermesCancellationRequestOutcome.Requested, registry.requestCancellation(secondId, "user-cancel@example.com"))
    }
}

private class FakeHermesDocumentIntentDirector(
    private val behavior: (utterance: String, recentTurns: List<String>, pending: List<String>?) -> app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult,
) : app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDirector(llmClient = null) {
    var callCount = 0
        private set

    override suspend fun decide(
        utterance: String,
        recentTurns: List<String>,
        pendingClarificationCandidates: List<String>?,
    ): app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentResult {
        callCount++
        return behavior(utterance, recentTurns, pendingClarificationCandidates)
    }
}
