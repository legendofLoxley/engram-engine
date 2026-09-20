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
    fun `a question turn runs no keyword lookup of its own, recall is the Horizon's job`() = runTest {
        var lookups = 0
        val counting = object : app.alfrd.engram.cognitive.pipeline.memory.EngramClient by InMemoryEngramClient() {
            override suspend fun queryPhrases(userEmail: String, concept: String?, limit: Int): List<ScoredPhrase> {
                lookups++
                return emptyList()
            }
        }
        var capturedPrompt: LlmRequest? = null
        val llm = TestLlmClient { req ->
            capturedPrompt = req
            LlmResponse(text = "I don't have that noted.", latencyMs = 0, retryCount = 0)
        }
        val pipeline = CognitivePipeline(engramClient = counting, llmClient = llm)

        pipeline.process("What languages do I work with?", "s1", "user-1")

        assertEquals(0, lookups, "a question must not trigger a second retrieval path")
        assertNotNull(capturedPrompt, "the model is still asked")
        assertTrue(
            capturedPrompt!!.systemPrompt!!.contains("say plainly that you don't have it noted rather than guessing"),
            "with nothing in view the reply is told to be honest about it, not to guess about their life",
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
    fun `actor failure produces the single centralized degraded message, not a per-branch fallback`() = runTest {
        val engram = InMemoryEngramClient()
        val failingLlm = TestLlmClient { throw RuntimeException("LLM exploded") }
        val pipeline = CognitivePipeline(engramClient = engram, llmClient = failingLlm)

        val response = pipeline.process("What time does school start?", "s1", "user-1")

        assertEquals(
            Actor.DEGRADED_TEXT, response,
            "LLM failure must surface the single centralized degraded message, not a per-branch string",
        )
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
            override suspend fun getTopicConfidence(userEmail: String, topic: String): app.alfrd.engram.cognitive.pipeline.memory.TopicConfidence =
                throw RuntimeException("db down")
            override suspend fun updateTopicConfidence(
                userEmail: String, topic: String, confidence: app.alfrd.engram.cognitive.pipeline.memory.TopicConfidence,
            ): Unit = throw RuntimeException("db down")
            override suspend fun appendEpisodicTurn(
                sessionId: String, userId: String, turnIndex: Int, userUtterance: String, alfrdResponse: String,
            ): Unit = throw RuntimeException("db down")
            override suspend fun getEpisodicLog(
                userId: String, sinceMillis: Long?, untilMillis: Long?, keyword: String?, limit: Int,
            ): List<app.alfrd.engram.cognitive.pipeline.memory.EpisodicTurn> = throw RuntimeException("db down")
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

    @Test
    fun `appendEpisodicTurn then getEpisodicLog returns both sides of the turn in order`() = runTest {
        val client = InMemoryEngramClient()
        client.appendEpisodicTurn(
            sessionId = "s1", userId = "user-1", turnIndex = 0,
            userUtterance = "What do you mean?", alfrdResponse = "Sorry for the confusion.",
        )

        val log = client.getEpisodicLog(userId = "user-1")
        assertEquals(2, log.size)
        assertEquals("user", log[0].role)
        assertEquals("What do you mean?", log[0].text)
        assertEquals("alfrd", log[1].role)
        assertEquals("Sorry for the confusion.", log[1].text)
    }

    @Test
    fun `getEpisodicLog filters by keyword`() = runTest {
        val client = InMemoryEngramClient()
        client.appendEpisodicTurn(
            sessionId = "s1", userId = "user-1", turnIndex = 0,
            userUtterance = "Remind me to call the vet", alfrdResponse = "Noted!",
        )
        client.appendEpisodicTurn(
            sessionId = "s1", userId = "user-1", turnIndex = 1,
            userUtterance = "What's the weather like?", alfrdResponse = "I don't have that.",
        )

        val log = client.getEpisodicLog(userId = "user-1", keyword = "vet")
        assertEquals(1, log.size, "Expected only the vet-related turn to match")
        assertTrue(log.first().text.contains("vet"))
    }

    @Test
    fun `getEpisodicLog scopes turns to the requesting user`() = runTest {
        val client = InMemoryEngramClient()
        client.appendEpisodicTurn(
            sessionId = "s1", userId = "alice@example.com", turnIndex = 0,
            userUtterance = "Alice's turn", alfrdResponse = "Ack.",
        )
        client.appendEpisodicTurn(
            sessionId = "s2", userId = "bob@example.com", turnIndex = 0,
            userUtterance = "Bob's turn", alfrdResponse = "Ack.",
        )

        val aliceLog = client.getEpisodicLog(userId = "alice@example.com")
        assertEquals(2, aliceLog.size, "Expected both sides of alice's turn, none of bob's")
        assertEquals("Alice's turn", aliceLog.first().text)
    }
}
