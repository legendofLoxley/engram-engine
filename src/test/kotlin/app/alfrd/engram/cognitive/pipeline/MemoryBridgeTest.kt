package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.Phrase
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.LlmTimeoutError
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests — memory bridge + reason branches
// ─────────────────────────────────────────────────────────────────────────────

class MemoryBridgeIntegrationTest {

    /**
     * Helper: invoke OnboardingBranch directly + Expression so the scaffold state is
     * bootstrapped (active question stored) without going through the full pipeline.
     * The pipeline only routes to OnboardingBranch via Comprehension Rule 0 when an
     * active scaffold question is already present; direct branch calls seed that state.
     */
    private suspend fun directOnboardingTurn(
        utterance: String,
        userId: String,
        engram: InMemoryEngramClient,
        llm: TestLlmClient,
    ): String {
        val ctx = CognitiveContext(utterance = utterance, sessionId = "s1", userId = userId)
        OnboardingBranch(engram, llm).execute(ctx)
        Expression().evaluate(ctx)
        return ctx.responseText
    }

    // ── Onboarding loop ───────────────────────────────────────────────────────

    @Test
    fun `first onboarding turn returns the opener question`() = runTest {
        val engram = InMemoryEngramClient()
        val llm = TestLlmClient { LlmResponse(text = "What is your role?", latencyMs = 0, retryCount = 0) }

        val response = directOnboardingTurn("hi", "user-1", engram, llm)

        assertTrue(
            OnboardingBranch.OPENER in response,
            "Expected opener in first response, got: $response",
        )
    }

    @Test
    fun `second onboarding turn decomposes and ingests, returns next scaffold question`() = runTest {
        val engram = InMemoryEngramClient()
        val llm = TestLlmClient { LlmResponse(text = "What tools do you use?", latencyMs = 0, retryCount = 0) }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = llm)

        // Turn 1 — bootstrap via direct branch call so active question is set
        directOnboardingTurn("hi", "user-1", engram, llm)

        // Turn 2 — full pipeline: Rule 0 fires because activeScaffoldQuestion is set
        val response = pipeline.process("I'm a software engineer working on mobile apps.", "s1", "user-1")

        assertTrue(engram.allPhrases().isNotEmpty(), "Expected phrases to be ingested after turn 2")
        assertTrue(response.isNotBlank(), "Expected a scaffold question in turn 2, got: $response")
    }

    @Test
    fun `third onboarding turn covers a new category and advances scaffold`() = runTest {
        val engram = InMemoryEngramClient()
        var callCount = 0
        val llm = TestLlmClient {
            callCount++
            LlmResponse(text = "Question $callCount", latencyMs = 0, retryCount = 0)
        }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = llm)

        // Bootstrap turn 1
        directOnboardingTurn("hi", "user-1", engram, llm)

        // Turns 2 & 3 via full pipeline (Rule 0 active)
        pipeline.process("I'm a software engineer.", "s1", "user-1")
        pipeline.process("I use Kotlin and Python every day.", "s1", "user-1")

        val state = engram.getScaffoldState("user-1")
        assertTrue(
            state.answeredCategories.isNotEmpty(),
            "Expected at least one answered category after multiple turns",
        )
    }

    @Test
    fun `scaffold state tracks answered categories correctly`() = runTest {
        val engram = InMemoryEngramClient()
        val llm = TestLlmClient { LlmResponse(text = "Next question", latencyMs = 0, retryCount = 0) }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = llm)

        // Bootstrap turn 1 — active question will be set
        directOnboardingTurn("hi", "user-1", engram, llm)

        val stateAfterOpener = engram.getScaffoldState("user-1")
        assertNotNull(stateAfterOpener.activeScaffoldQuestion, "Expected active question after opener turn")

        // Turn 2 full pipeline — provide identity info
        pipeline.process("I am a product designer.", "s1", "user-1")

        val stateAfterAnswer = engram.getScaffoldState("user-1")
        assertTrue(
            PhraseCategory.IDENTITY in stateAfterAnswer.answeredCategories,
            "Expected IDENTITY in answered categories, got: ${stateAfterAnswer.answeredCategories}",
        )
    }

    @Test
    fun `Comprehension Rule 0 fires on second turn due to active scaffold question`() = runTest {
        val engram = InMemoryEngramClient()
        val llm = TestLlmClient { LlmResponse(text = "Next question", latencyMs = 0, retryCount = 0) }

        // Bootstrap turn 1 to set active scaffold question
        directOnboardingTurn("hi", "user-1", engram, llm)

        val state = engram.getScaffoldState("user-1")
        assertNotNull(state.activeScaffoldQuestion, "Expected active question to be set after turn 1")

        // Even "hey" (normally SOCIAL) should route to ONBOARDING via Rule 0 when
        // scaffoldState is populated with an active question.
        val comprehension = Comprehension()
        val ctx = CognitiveContext(
            utterance = "hey",
            sessionId = "s1",
            userId = "user-1",
            scaffoldState = state,
        )
        comprehension.evaluate(ctx)
        assertEquals(IntentType.ONBOARDING, ctx.intent, "Rule 0 should override social classification")
        assertEquals(0.95, ctx.intentConfidence)
    }

    // ── Question branch with graph context ────────────────────────────────────

    @Test
    fun `question with graph context injects phrase context into LLM prompt`() = runTest {
        val engram = InMemoryEngramClient()
        engram.ingest(
            listOf(
                PhraseCandidate("I am a Kotlin developer", "user", PhraseCategory.IDENTITY),
                PhraseCandidate("I work at a fintech startup", "user", PhraseCategory.CONTEXT),
            )
        )

        var capturedPrompt: LlmRequest? = null
        val llm = TestLlmClient { req ->
            capturedPrompt = req
            LlmResponse(text = "You're a Kotlin developer.", latencyMs = 0, retryCount = 0)
        }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = llm)

        pipeline.process("What languages do I work with?", "s1", "user-1")

        assertNotNull(capturedPrompt, "LLM should have been called")
        assertTrue(
            capturedPrompt!!.systemPrompt?.contains("Kotlin") == true ||
                capturedPrompt!!.systemPrompt?.contains("fintech") == true,
            "Expected phrase context injected into system prompt, got: ${capturedPrompt!!.systemPrompt}",
        )
    }

    @Test
    fun `question without graph context receives graceful general-knowledge response`() = runTest {
        val engram = InMemoryEngramClient()
        val llm = TestLlmClient { LlmResponse(text = "Here is a general answer.", latencyMs = 0, retryCount = 0) }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = llm)

        val response = pipeline.process("What is the tallest mountain on Earth?", "s1", "user-1")

        assertTrue(response.isNotBlank(), "Expected a non-blank response for general question")
    }

    // ── Task branch memory capture ────────────────────────────────────────────

    @Test
    fun `task utterance ingests a phrase with category CONTEXT`() = runTest {
        val engram = InMemoryEngramClient()
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = null)

        pipeline.process("Remind me to review the PR tomorrow", "s1", "user-1")

        val phrases = engram.allPhrases()
        assertTrue(phrases.isNotEmpty(), "Expected a phrase to be ingested for the task")
        assertTrue(
            phrases.any { it.source == "task_stub" },
            "Expected source 'task_stub' on ingested phrase",
        )
    }

    // ── LLM failure graceful degradation ─────────────────────────────────────

    @Test
    fun `OnboardingBranch falls back to hardcoded question on LLM failure`() = runTest {
        val engram = InMemoryEngramClient()
        val failingLlm = TestLlmClient { throw LlmTimeoutError("simulated timeout") }

        // Bootstrap turn 1 — opener requires no LLM call
        val ctx1 = CognitiveContext(utterance = "hi", sessionId = "s1", userId = "user-1")
        OnboardingBranch(engram, failingLlm).execute(ctx1)

        // Turn 2 via failing LLM — should fall back to hardcoded question
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = failingLlm)
        val response = pipeline.process("I'm a data scientist.", "s1", "user-1")
        assertTrue(response.isNotBlank(), "Expected a non-blank fallback response")
    }

    @Test
    fun `QuestionBranch returns fallback message on LLM failure`() = runTest {
        val engram = InMemoryEngramClient()
        val failingLlm = TestLlmClient { throw RuntimeException("LLM exploded") }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = failingLlm)

        val response = pipeline.process("What time does school start?", "s1", "user-1")

        assertTrue(
            "question" in response.lowercase(),
            "Expected fallback message containing 'question', got: $response",
        )
    }

    @Test
    fun `branches still respond when EngramClient operations fail`() = runTest {
        val brokenEngram = object : app.alfrd.engram.cognitive.pipeline.memory.EngramClient {
            override suspend fun decompose(text: String, context: List<String>) =
                throw RuntimeException("db down")
            override suspend fun ingest(candidates: List<PhraseCandidate>) =
                throw RuntimeException("db down")
            override suspend fun queryPhrases(concept: String): List<Phrase> =
                throw RuntimeException("db down")
            override suspend fun getScaffoldState(userId: String): ScaffoldState =
                throw RuntimeException("db down")
            override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) =
                throw RuntimeException("db down")
            override suspend fun amendPhrase(phraseId: String, newContent: String) =
                throw RuntimeException("db down")
        }
        val llm = TestLlmClient { LlmResponse(text = "Still here.", latencyMs = 0, retryCount = 0) }
        val pipeline = CognitivePipeline(engramClient = brokenEngram, llmClient = llm)

        val response = pipeline.process("What is up?", "s1", "user-1")
        assertTrue(response.isNotBlank(), "Expected a non-blank response even when EngramClient is broken")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — InMemoryEngramClient
// ─────────────────────────────────────────────────────────────────────────────

class InMemoryEngramClientTest {

    @Test
    fun `ingest then query returns matching phrases`() = runTest {
        val client = InMemoryEngramClient()
        client.ingest(
            listOf(
                PhraseCandidate("I love Kotlin", "user", PhraseCategory.PREFERENCE),
                PhraseCandidate("I manage a team of five", "user", PhraseCategory.RELATIONSHIP),
            )
        )

        val results = client.queryPhrases("Kotlin")
        assertEquals(1, results.size)
        assertTrue(results.first().content.contains("Kotlin"))
    }

    @Test
    fun `scaffold state is initialised fresh for new user`() = runTest {
        val client = InMemoryEngramClient()
        val state = client.getScaffoldState("brand-new-user")

        assertEquals(1, state.trustPhase)
        assertTrue(state.answeredCategories.isEmpty())
        assertNull(state.activeScaffoldQuestion)
    }

    @Test
    fun `scaffold state can be updated and re-read`() = runTest {
        val client = InMemoryEngramClient()
        val updated = ScaffoldState(
            trustPhase = 1,
            answeredCategories = setOf(PhraseCategory.IDENTITY),
            activeScaffoldQuestion = "What tools do you use?",
        )
        client.updateScaffoldState("user-x", updated)

        val retrieved = client.getScaffoldState("user-x")
        assertEquals(setOf(PhraseCategory.IDENTITY), retrieved.answeredCategories)
        assertEquals("What tools do you use?", retrieved.activeScaffoldQuestion)
    }

    @Test
    fun `decompose produces candidates from multi-sentence input`() = runTest {
        val client = InMemoryEngramClient()
        val candidates = client.decompose(
            "I am a backend engineer. I use Kotlin and Python. I prefer asynchronous work.",
            emptyList(),
        )

        assertTrue(candidates.size >= 2, "Expected multiple candidates from three sentences, got ${candidates.size}")
        assertTrue(
            candidates.any { it.category == PhraseCategory.IDENTITY },
            "Expected IDENTITY category for 'I am a backend engineer'",
        )
        assertTrue(
            candidates.any { it.category == PhraseCategory.EXPERTISE },
            "Expected EXPERTISE category for 'I use Kotlin and Python'",
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — OnboardingBranch scaffold category progression
// ─────────────────────────────────────────────────────────────────────────────

class OnboardingBranchTest {

    @Test
    fun `asks about uncovered categories in priority order`() = runTest {
        val engram = InMemoryEngramClient()
        // Pre-seed IDENTITY as answered; next should be EXPERTISE or later
        engram.updateScaffoldState(
            "user-1",
            ScaffoldState(
                answeredCategories = setOf(PhraseCategory.IDENTITY),
                activeScaffoldQuestion = "dummy active question",
            )
        )

        val capturedPrompts = mutableListOf<String>()
        val llm = TestLlmClient { req ->
            capturedPrompts.add(req.prompt)
            LlmResponse(text = "Mock question about ${req.prompt}", latencyMs = 0, retryCount = 0)
        }

        val branch = OnboardingBranch(engram, llm)
        val ctx = CognitiveContext(
            utterance = "Yes I am a designer",
            sessionId = "s1",
            userId = "user-1",
            scaffoldState = engram.getScaffoldState("user-1"),
        )
        branch.execute(ctx)

        val questionText = ctx.branchResult?.content ?: ""
        assertTrue(questionText.isNotBlank(), "Expected a scaffold question")
        assertTrue(
            capturedPrompts.any {
                it.contains("expertise") || it.contains("preference") ||
                    it.contains("routine") || it.contains("relationship") || it.contains("context")
            },
            "Expected LLM prompt to ask about a category after IDENTITY, got: $capturedPrompts",
        )
    }

    @Test
    fun `hardcoded fallback questions cover all categories`() {
        val branch = OnboardingBranch(InMemoryEngramClient(), null)
        PhraseCategory.entries.forEach { cat ->
            val question = branch.javaClass
                .getDeclaredMethod("hardcodedQuestion", PhraseCategory::class.java)
                .also { it.isAccessible = true }
                .invoke(branch, cat) as String
            assertTrue(question.isNotBlank(), "Expected a hardcoded question for $cat")
        }
    }
}
