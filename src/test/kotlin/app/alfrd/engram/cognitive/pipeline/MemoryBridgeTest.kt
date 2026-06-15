package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.pipeline.memory.ScoredPhrase
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests — memory bridge + reason branches
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryBridgeIntegrationTest {

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

    // ── Universal ingestion ───────────────────────────────────────────────────

    @Test
    fun `task utterance is ingested via universal ingestion`() = runTest {
        val engram  = InMemoryEngramClient()
        val mws     = MemoryWriteService(engram, this)
        val pipeline = CognitivePipeline(engramClient = engram, memoryWriteService = mws)

        pipeline.process("Remind me to review the PR tomorrow", "s1", "user-1")

        advanceUntilIdle()

        assertTrue(engram.allPhrases().isNotEmpty(), "Expected phrase to be ingested for the task turn")
    }

    // ── LLM failure graceful degradation ─────────────────────────────────────

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

    // ── Recall-question routing ───────────────────────────────────────────────

    @Test
    fun `recall question with fact in graph returns that fact not the meta stub`() = runTest {
        val engram = InMemoryEngramClient()
        engram.ingest(
            listOf(PhraseCandidate("My dog's name is Newton", "user", PhraseCategory.IDENTITY))
        )
        val llm = TestLlmClient { LlmResponse(text = "Your dog's name is Newton.", latencyMs = 0, retryCount = 0) }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = llm)

        val response = pipeline.process("What did I tell you my dog's name was?", "s1", "user-1")

        assertFalse(
            response == "Memory queries aren't available yet.",
            "Recall question must not dead-end at the MetaBranch stub",
        )
        assertTrue(
            "Newton" in response,
            "Expected the graph fact in the response, got: $response",
        )
    }

    @Test
    fun `recall question against empty graph returns graceful no-data response not stub`() = runTest {
        val engram = InMemoryEngramClient()
        val llm = TestLlmClient {
            LlmResponse(text = "I don't have that in my memory yet.", latencyMs = 0, retryCount = 0)
        }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = llm)

        val response = pipeline.process("What did I tell you my dog's name was?", "s1", "user-1")

        assertFalse(
            response == "Memory queries aren't available yet.",
            "Empty-graph recall must not produce the MetaBranch stub",
        )
        assertTrue(response.isNotBlank(), "Expected a graceful non-blank response for empty-graph recall")
    }

    @Test
    fun `no normal conversational turn produces the meta stub string`() = runTest {
        val engram = InMemoryEngramClient()
        val llm = TestLlmClient { LlmResponse(text = "Sure thing.", latencyMs = 0, retryCount = 0) }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = llm)

        val utterances = listOf(
            "What did I tell you about my dog?",
            "What do you know about me?",
            "What have I told you?",
            "Do you remember my wife's name?",
            "What is the weather like?",
            "Hey",
            "Remind me to call the vet",
        )
        for (utterance in utterances) {
            val response = pipeline.process(utterance, "s1", "user-1")
            assertFalse(
                response == "Memory queries aren't available yet.",
                "Utterance '$utterance' must never produce the MetaBranch stub, got: $response",
            )
        }
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
