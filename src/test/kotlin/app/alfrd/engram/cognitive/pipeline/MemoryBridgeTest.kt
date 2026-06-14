package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.pipeline.memory.ScoredPhrase
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
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
    ): String {
        val ctx = CognitiveContext(utterance = utterance, sessionId = "s1", userId = userId)
        OnboardingBranch(engram).execute(ctx)
        Expression().evaluate(ctx)
        return ctx.responseText
    }

    // ── Onboarding loop ───────────────────────────────────────────────────────

    @Test
    fun `first onboarding turn returns the opener question`() = runTest {
        val engram = InMemoryEngramClient()

        val response = directOnboardingTurn("hi", "user-1", engram)

        assertTrue(
            OnboardingBranch.OPENER in response,
            "Expected opener in first response, got: $response",
        )
    }

    @Test
    fun `second onboarding turn passively ingests utterance and clears active question`() = runTest {
        val engram = InMemoryEngramClient()
        val pipeline = CognitivePipeline(engramClient = engram)

        // Turn 1 — bootstrap via direct branch call so active question is set
        directOnboardingTurn("hi", "user-1", engram)

        // Turn 2 — full pipeline: Rule 0 fires because activeScaffoldQuestion is set;
        // branch captures silently and returns no result
        val response = pipeline.process("I'm a software engineer working on mobile apps.", "s1", "user-1")

        assertTrue(engram.allPhrases().isNotEmpty(), "Expected phrases to be ingested after turn 2")
        assertTrue(response.isBlank(), "Expected no assistant response from passive onboarding turn")
        assertNull(
            engram.getScaffoldState("user-1").activeScaffoldQuestion,
            "Expected activeScaffoldQuestion cleared after passive turn",
        )
    }

    @Test
    fun `turn after passive onboarding routes normally once active question is cleared`() = runTest {
        val engram = InMemoryEngramClient()
        val pipeline = CognitivePipeline(engramClient = engram)

        // Bootstrap turn 1 — sets activeScaffoldQuestion
        directOnboardingTurn("hi", "user-1", engram)

        // Turn 2 — passive capture clears activeScaffoldQuestion
        pipeline.process("I'm a software engineer.", "s1", "user-1")

        val stateAfterTurn2 = engram.getScaffoldState("user-1")
        assertNull(stateAfterTurn2.activeScaffoldQuestion, "Active question should be cleared after passive turn")

        // Turn 3 — with no activeScaffoldQuestion, Rule 0 does NOT fire; routes normally
        // "I use Kotlin" doesn't start with an interrogative or imperative, so AMBIGUOUS
        val response = pipeline.process("I use Kotlin and Python every day.", "s1", "user-1")
        assertTrue(response.isNotBlank(), "Turn 3 should produce a response via normal routing, got blank")
    }

    @Test
    fun `scaffold state active question cleared after passive capture turn`() = runTest {
        val engram = InMemoryEngramClient()
        val pipeline = CognitivePipeline(engramClient = engram)

        // Bootstrap turn 1 — active question will be set
        directOnboardingTurn("hi", "user-1", engram)

        val stateAfterOpener = engram.getScaffoldState("user-1")
        assertNotNull(stateAfterOpener.activeScaffoldQuestion, "Expected active question after opener turn")

        // Turn 2 full pipeline — passive capture clears the active question
        pipeline.process("I am a product designer.", "s1", "user-1")

        val stateAfterAnswer = engram.getScaffoldState("user-1")
        assertNull(
            stateAfterAnswer.activeScaffoldQuestion,
            "Expected activeScaffoldQuestion cleared after passive capture turn",
        )
    }

    @Test
    fun `Comprehension Rule 0 fires on second turn due to active scaffold question`() = runTest {
        val engram = InMemoryEngramClient()

        // Bootstrap turn 1 to set active scaffold question
        directOnboardingTurn("hi", "user-1", engram)

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
        // Source attribution and confidence should be present when phrases exist
        assertTrue(
            capturedPrompt!!.systemPrompt?.contains("source:") == true,
            "Expected source attribution in system prompt, got: ${capturedPrompt!!.systemPrompt}",
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
    fun `OnboardingBranch passive turn ingests and returns blank response`() = runTest {
        val engram = InMemoryEngramClient()

        // Bootstrap turn 1 — sets activeScaffoldQuestion
        val ctx1 = CognitiveContext(utterance = "hi", sessionId = "s1", userId = "user-1")
        OnboardingBranch(engram).execute(ctx1)

        // Turn 2 via pipeline — passive capture; no response produced
        val pipeline = CognitivePipeline(engramClient = engram)
        val response = pipeline.process("I'm a data scientist.", "s1", "user-1")
        assertTrue(response.isBlank(), "Expected blank response from passive onboarding turn")
        assertTrue(engram.allPhrases().isNotEmpty(), "Expected utterance to be ingested silently")
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
    fun `pipeline propagates JWT userId as userEmail to queryPhrases`() = runTest {
        var capturedEmail: String? = null
        val trackingEngram = object : app.alfrd.engram.cognitive.pipeline.memory.EngramClient {
            override suspend fun decompose(text: String, context: List<String>) = emptyList<PhraseCandidate>()
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) = Unit
            override suspend fun queryPhrases(userEmail: String, concept: String?, limit: Int): List<ScoredPhrase> {
                capturedEmail = userEmail
                return emptyList()
            }
            override suspend fun getScaffoldState(userId: String) = ScaffoldState()
            override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) = Unit
            override suspend fun amendPhrase(phraseId: String, newContent: String) = Unit
        }
        val llm = TestLlmClient { LlmResponse(text = "Answer.", latencyMs = 0, retryCount = 0) }
        val pipeline = CognitivePipeline(engramClient = trackingEngram, llmClient = llm)

        pipeline.process("What do I work on?", "s1", "alice@example.com")

        assertEquals(
            "alice@example.com", capturedEmail,
            "Pipeline must pass the userId (JWT email) as userEmail to queryPhrases"
        )
    }

    @Test
    fun `queryPhrases receives different emails for different users`() = runTest {
        val capturedEmails = mutableListOf<String>()
        val trackingEngram = object : app.alfrd.engram.cognitive.pipeline.memory.EngramClient {
            override suspend fun decompose(text: String, context: List<String>) = emptyList<PhraseCandidate>()
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) = Unit
            override suspend fun queryPhrases(userEmail: String, concept: String?, limit: Int): List<ScoredPhrase> {
                capturedEmails.add(userEmail)
                return emptyList()
            }
            override suspend fun getScaffoldState(userId: String) = ScaffoldState()
            override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) = Unit
            override suspend fun amendPhrase(phraseId: String, newContent: String) = Unit
        }
        val llm = TestLlmClient { LlmResponse(text = "Answer.", latencyMs = 0, retryCount = 0) }

        CognitivePipeline(engramClient = trackingEngram, llmClient = llm)
            .process("What do I work on?", "s1", "alice@example.com")
        CognitivePipeline(engramClient = trackingEngram, llmClient = llm)
            .process("What do I work on?", "s2", "bob@example.com")

        assertTrue(capturedEmails.contains("alice@example.com"), "alice's email must reach queryPhrases")
        assertTrue(capturedEmails.contains("bob@example.com"), "bob's email must reach queryPhrases")
        assertNotEquals(capturedEmails[0], capturedEmails[1], "Different users must produce different emails")
    }

    @Test
    fun `branches still respond when EngramClient operations fail`() = runTest {
        val brokenEngram = object : app.alfrd.engram.cognitive.pipeline.memory.EngramClient {
            override suspend fun decompose(text: String, context: List<String>) =
                throw RuntimeException("db down")
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) =
                throw RuntimeException("db down")
            override suspend fun queryPhrases(userEmail: String, concept: String?, limit: Int): List<ScoredPhrase> =
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

        val results = client.queryPhrases(userEmail = "", concept = "Kotlin")
        assertEquals(1, results.size)
        assertTrue(results.first().text.contains("Kotlin"))
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
// Unit tests — OnboardingBranch passive listener behaviour
// ─────────────────────────────────────────────────────────────────────────────

class OnboardingBranchTest {

    @Test
    fun `passive turn captures utterance and returns null branchResult`() = runTest {
        val engram = InMemoryEngramClient()
        engram.updateScaffoldState(
            "user-1",
            ScaffoldState(activeScaffoldQuestion = "What are you working on?"),
        )

        val branch = OnboardingBranch(engram)
        val ctx = CognitiveContext(
            utterance = "I build mobile apps",
            sessionId = "s1",
            userId = "user-1",
            scaffoldState = engram.getScaffoldState("user-1"),
        )
        branch.execute(ctx)

        assertNull(ctx.branchResult, "Passive turn must not produce a branch result")
        assertTrue(engram.allPhrases().isNotEmpty(), "Utterance must be ingested via sync path")
        assertNull(
            engram.getScaffoldState("user-1").activeScaffoldQuestion,
            "Active question must be cleared after passive turn",
        )
    }

    @Test
    fun `SCAFFOLD_PRIORITY covers all PhraseCategory values`() {
        assertEquals(
            PhraseCategory.entries.toSet(),
            OnboardingBranch.SCAFFOLD_PRIORITY.toSet(),
            "SCAFFOLD_PRIORITY must include every PhraseCategory",
        )
    }
}
