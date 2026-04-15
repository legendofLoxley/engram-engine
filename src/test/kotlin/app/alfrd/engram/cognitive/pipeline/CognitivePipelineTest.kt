package app.alfrd.engram.cognitive.pipeline

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
        assertTrue(ctx.responseText.startsWith("Understood."))
        assertTrue(ctx.responseText.contains("I've noted that"))
        assertEquals(2, ctx.streamingPhases!!.size)
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
