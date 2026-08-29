package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.confidence.TopicConfidenceService
import app.alfrd.engram.cognitive.pipeline.memory.ConfidencePhase
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// CorrectionBranch — director-only unit tests (pure parsing, no EngramClient)
// ─────────────────────────────────────────────────────────────────────────────

class CorrectionBranchTest {

    private val branch = CorrectionBranch()

    @Test
    fun `correction with not-X produces a Correction retrieval intent`() = runTest {
        val ctx = CognitiveContext(
            utterance = "Actually my dog's name is Newton, not Neutron",
            sessionId = "s", userId = "user-1", userEmail = "user-1",
        )
        branch.execute(ctx)

        val retrieval = ctx.branchResult!!.retrieval
        assertTrue(retrieval is RetrievalIntent.Correction, "Expected Correction intent, got: $retrieval")
        retrieval as RetrievalIntent.Correction
        assertEquals("neutron", retrieval.supersededValue?.lowercase())
        assertTrue(retrieval.newFact.contains("Newton"), "Expected new fact to mention Newton, got: ${retrieval.newFact}")
    }

    @Test
    fun `correction without not-X clause still produces a Correction intent with null superseded value`() = runTest {
        val ctx = CognitiveContext(
            utterance = "Actually I prefer TypeScript over JavaScript",
            sessionId = "s", userId = "user-1", userEmail = "user-1",
        )
        branch.execute(ctx)

        val retrieval = ctx.branchResult!!.retrieval
        assertTrue(retrieval is RetrievalIntent.Correction)
        retrieval as RetrievalIntent.Correction
        assertNull(retrieval.supersededValue)
        assertTrue(retrieval.newFact.isNotBlank())
    }

    @Test
    fun `unresolvable (too vague) correction produces no retrieval and a clarification directive`() = runTest {
        val ctx = CognitiveContext(utterance = "wait", sessionId = "s", userId = "user-1", userEmail = "user-1")
        branch.execute(ctx)

        assertEquals(RetrievalIntent.None, ctx.branchResult!!.retrieval)
        assertTrue(
            ctx.branchResult!!.directive.contains("update", ignoreCase = true),
            "Expected the too-vague directive to ask what to update, got: ${ctx.branchResult!!.directive}",
        )
    }

    @Test
    fun `no I meant with vague body produces no retrieval`() = runTest {
        val ctx = CognitiveContext(utterance = "no, i meant it", sessionId = "s", userId = "user-1", userEmail = "user-1")
        branch.execute(ctx)
        assertEquals(RetrievalIntent.None, ctx.branchResult!!.retrieval)
    }

    @Test
    fun `correction branch sets SIMPLE response strategy`() = runTest {
        val ctx = CognitiveContext(
            utterance = "Actually my cat's name is Shadow, not Whiskers",
            sessionId = "s", userId = "user-1", userEmail = "user-1",
        )
        branch.execute(ctx)
        assertEquals(ResponseStrategy.SIMPLE, ctx.branchResult!!.responseStrategy)
    }

    @Test
    fun `correction branch never touches EngramClient directly`() {
        // Compile-time guarantee, not a runtime assertion: CorrectionBranch() takes zero
        // constructor arguments, so there is no way to hand it an EngramClient.
        CorrectionBranch()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Script — correction resolution (the amend/ingest behavior moved here from the branch)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class ScriptCorrectionTest {

    @Test
    fun `not-X amends the existing phrase and subsequent recall returns the corrected value`() = runTest {
        val engram = InMemoryEngramClient()
        engram.ingest(
            listOf(PhraseCandidate("My dog's name is Neutron", "user", PhraseCategory.CONTEXT)),
            userEmail = "user-1",
        )
        val script = Script(engram)
        val ctx = CognitiveContext(
            utterance = "Actually my dog's name is Newton, not Neutron",
            sessionId = "s", userId = "user-1", userEmail = "user-1",
        )

        val result = script.run(ctx, RetrievalIntent.Correction(supersededValue = "Neutron", newFact = "my dog's name is Newton"))
        assertEquals("correction-amended", result.label)

        val recalled = engram.queryPhrases("user-1", "dog")
        val texts = recalled.map { it.text.lowercase() }
        assertTrue(texts.any { it.contains("newton") }, "Expected Newton in memory after correction, got: $recalled")
        assertFalse(
            texts.any { it.contains("neutron") && !it.contains("newton") },
            "Neutron should be superseded, got: $recalled",
        )
    }

    @Test
    fun `no matching phrase ingests the new fact fresh`() = runTest {
        val engram = InMemoryEngramClient()
        val script = Script(engram)
        val ctx = CognitiveContext(
            utterance = "Actually my name is Alex, not Sam",
            sessionId = "s", userId = "user-1", userEmail = "user-1",
        )

        val result = script.run(ctx, RetrievalIntent.Correction(supersededValue = "Sam", newFact = "my name is Alex"))
        assertEquals("correction-ingested", result.label)

        val phrases = engram.queryPhrases("user-1", "alex")
        assertTrue(phrases.isNotEmpty(), "Expected corrected fact to be stored, got: $phrases")
    }

    @Test
    fun `a successful correction confirms and raises topic confidence, never lowers it`() = runTest {
        val engram = InMemoryEngramClient()
        val confidenceService = TopicConfidenceService(engram, scope = this)
        val script = Script(engram, confidenceService = confidenceService)
        val ctx = CognitiveContext(
            utterance = "Actually my dog's name is Newton, not Neutron",
            sessionId = "s", userId = "user-1", userEmail = "user-1",
        )

        script.run(ctx, RetrievalIntent.Correction(supersededValue = "Neutron", newFact = "my dog's name is Newton"))
        advanceUntilIdle()

        // TopicResolver picks the longest keyword from "my dog's name is Newton" — "newton".
        val confidence = engram.getTopicConfidence("user-1", "newton")
        assertFalse(confidence.hasUnresolvedContradiction, "Confirmed correction must clear the contradiction flag")
        assertTrue(confidence.score > 0.0, "Confirmed correction must raise confidence above the default")
        assertEquals(ConfidencePhase.WORKING_RHYTHM, confidence.phase, "A single confirmation crosses the WORKING_RHYTHM threshold")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sweep: every correction utterance routes to CorrectionBranch's directive, not a
// stub or a wrong branch — verified by echoing the actor's system prompt back.
// ─────────────────────────────────────────────────────────────────────────────

class CorrectionRoutingSweepTest {

    private val echoLlm = TestLlmClient { req: LlmRequest ->
        LlmResponse(text = req.systemPrompt ?: "", latencyMs = 0L, retryCount = 0)
    }
    private val pipeline = CognitivePipeline(llmClient = echoLlm)

    private suspend fun assertRoutedToCorrection(utterance: String) {
        val response = pipeline.process(utterance, "s-${utterance.hashCode()}", "u")
        assertTrue(
            response.contains("corrected something", ignoreCase = true),
            "Expected CorrectionBranch's directive to reach the actor for \"$utterance\", got: $response",
        )
    }

    @Test
    fun `actually utterance routes to correction`() = runTest {
        assertRoutedToCorrection("Actually it's Newton, not Neutron")
    }

    @Test
    fun `no i meant utterance routes to correction`() = runTest {
        assertRoutedToCorrection("No I meant the staging deploy")
    }

    @Test
    fun `no comma i meant utterance routes to correction`() = runTest {
        assertRoutedToCorrection("No, I meant the other one")
    }

    @Test
    fun `thats not right utterance routes to correction`() = runTest {
        assertRoutedToCorrection("That's not right, I said Berlin not Paris")
    }

    @Test
    fun `correction intent utterance routes to correction`() = runTest {
        assertRoutedToCorrection("Actually the deadline is Friday, not Thursday")
    }
}
