package app.alfrd.engram.cognitive.pipeline.hermes

import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import app.alfrd.engram.cognitive.providers.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val TOOL_NAME = "resolve_document_request"

private fun toolResponse(
    action: String,
    targetDocument: String? = null,
    candidateDocuments: List<String>? = null,
    isOwnRequest: Boolean = true,
    isNegated: Boolean = false,
): LlmResponse {
    val input = buildJsonObject {
        put("action", JsonPrimitive(action))
        targetDocument?.let { put("target_document", JsonPrimitive(it)) }
        candidateDocuments?.let { docs -> put("candidate_documents", buildJsonArray { docs.forEach { add(it) } }) }
        put("is_users_own_current_request", JsonPrimitive(isOwnRequest))
        put("is_negated", JsonPrimitive(isNegated))
    }
    return LlmResponse(text = "", toolCalls = listOf(ToolCall(TOOL_NAME, input)), latencyMs = 5, retryCount = 0)
}

/**
 * Covers HermesDocumentIntentDirector's own validation/guard logic — the "treat model output as a
 * proposed decision, validate before dispatch" boundary this class exists for. Real paraphrase
 * understanding and contextual reference resolution need a real model and are demonstrated live
 * (see the increment's own browser evidence), not unit-tested here — these tests instead pin what
 * happens to whatever a model (real or not) proposes, using TestLlmClient to return controlled
 * tool-call responses, the same pattern InterpreterTest-style suites already use elsewhere.
 */
class HermesDocumentIntentDirectorTest {
    private val approvedFilenames = HermesDelegationTrigger.APPROVED_DOCUMENTS.map { it.filename }

    @Test
    fun `a valid delegate proposal is accepted`() = runTest {
        val director = HermesDocumentIntentDirector(TestLlmClient { toolResponse("delegate", targetDocument = approvedFilenames[0]) })
        val result = director.decide("give me the rundown on that", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.Delegate(approvedFilenames[0]), result.decision)
        assertNull(result.rejectedReason)
        assertTrue(result.modelCalled)
    }

    @Test
    fun `a delegate proposal naming an unrecognized document is rejected, never substituted or laundered`() = runTest {
        val director = HermesDocumentIntentDirector(TestLlmClient { toolResponse("delegate", targetDocument = "some-other-file.md") })
        val result = director.decide("summarize some-other-file.md", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision)
        assertEquals("unrecognized_target_document", result.rejectedReason)
    }

    @Test
    fun `a valid clarify proposal with two or more known candidates is accepted`() = runTest {
        val director = HermesDocumentIntentDirector(TestLlmClient { toolResponse("clarify", candidateDocuments = approvedFilenames) })
        val result = director.decide("summarize that document", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.Clarify(approvedFilenames), result.decision)
    }

    @Test
    fun `a clarify proposal with fewer than two known candidates is rejected as insufficient`() = runTest {
        val director = HermesDocumentIntentDirector(TestLlmClient { toolResponse("clarify", candidateDocuments = listOf(approvedFilenames[0])) })
        val result = director.decide("summarize that document", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision)
        assertEquals("insufficient_clarify_candidates", result.rejectedReason)
    }

    @Test
    fun `clarify candidates naming unknown documents are filtered out before counting`() = runTest {
        val director = HermesDocumentIntentDirector(
            TestLlmClient { toolResponse("clarify", candidateDocuments = listOf(approvedFilenames[0], "unknown.md")) },
        )
        val result = director.decide("summarize that document", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision, "only one of the two candidates was actually known")
        assertEquals("insufficient_clarify_candidates", result.rejectedReason)
    }

    @Test
    fun `the model classifying this as not the user's own current request forces NoDelegation regardless of action`() = runTest {
        val director = HermesDocumentIntentDirector(
            TestLlmClient { toolResponse("delegate", targetDocument = approvedFilenames[0], isOwnRequest = false) },
        )
        val result = director.decide("he told me to summarize that", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision)
        assertEquals("model_classified_not_own_request", result.rejectedReason)
    }

    @Test
    fun `the model self-reporting negation forces NoDelegation regardless of action`() = runTest {
        val director = HermesDocumentIntentDirector(
            TestLlmClient { toolResponse("delegate", targetDocument = approvedFilenames[0], isNegated = true) },
        )
        val result = director.decide("never mind, don't bother", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision)
        assertEquals("model_classified_negated", result.rejectedReason)
    }

    @Test
    fun `a code-side negation guard overrides even a model that wrongly says this is not negated`() = runTest {
        // The model gets it wrong here (is_negated=false) — the deterministic code-side guard
        // must still catch the literal "do not summarize" case this increment's own instructions name.
        val director = HermesDocumentIntentDirector(
            TestLlmClient { toolResponse("delegate", targetDocument = approvedFilenames[0], isNegated = false) },
        )
        val result = director.decide("do not summarize that document", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision)
        assertEquals("negation_marker_detected", result.rejectedReason)
    }

    @Test
    fun `no tool call at all is NoDelegation`() = runTest {
        val director = HermesDocumentIntentDirector(TestLlmClient { LlmResponse(text = "just chatting", latencyMs = 5, retryCount = 0) })
        val result = director.decide("hello", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision)
        assertEquals("no_tool_call", result.rejectedReason)
    }

    @Test
    fun `malformed tool input missing a required field is NoDelegation`() = runTest {
        val badInput = buildJsonObject { put("action", JsonPrimitive("delegate")) } // missing is_users_own_current_request / is_negated
        val director = HermesDocumentIntentDirector(
            TestLlmClient { LlmResponse(text = "", toolCalls = listOf(ToolCall(TOOL_NAME, badInput)), latencyMs = 5, retryCount = 0) },
        )
        val result = director.decide("summarize it", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision)
        assertEquals("malformed_tool_input", result.rejectedReason)
    }

    @Test
    fun `a null llmClient never calls anything and reports modelCalled false`() = runTest {
        val director = HermesDocumentIntentDirector(null)
        val result = director.decide("summarize it", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision)
        assertFalse(result.modelCalled)
        assertEquals("no_llm_client", result.rejectedReason)
    }

    @Test
    fun `an LLM call that throws is reported honestly, not silently swallowed as NoDelegation with no reason`() = runTest {
        val director = HermesDocumentIntentDirector(TestLlmClient { throw RuntimeException("boom") })
        val result = director.decide("summarize it", emptyList(), null)
        assertEquals(HermesDocumentIntentDecision.NoDelegation, result.decision)
        assertTrue(result.modelCalled)
        assertTrue(result.rejectedReason!!.contains("llm_call_failed"))
    }

    @Test
    fun `pending clarification candidates and recent turns are both included in the prompt sent to the model`() = runTest {
        var capturedPrompt: String? = null
        val director = HermesDocumentIntentDirector(
            TestLlmClient { req: LlmRequest ->
                capturedPrompt = req.prompt
                toolResponse("delegate", targetDocument = approvedFilenames[0])
            },
        )
        director.decide(
            utterance = "the release checklist one",
            recentTurns = listOf("user: can you summarize a document for me?", "alfrd: which one did you mean?"),
            pendingClarificationCandidates = approvedFilenames,
        )
        val prompt = capturedPrompt!!
        assertTrue(prompt.contains("which document they meant"), "got: $prompt")
        approvedFilenames.forEach { assertTrue(prompt.contains(it), "expected $it in prompt, got: $prompt") }
        assertTrue(prompt.contains("can you summarize a document for me?"), "recent turns must reach the prompt, got: $prompt")
        assertTrue(prompt.contains("the release checklist one"), "the current utterance must reach the prompt, got: $prompt")
    }

    @Test
    fun `tools declared to the LLM request name the expected tool`() = runTest {
        var capturedToolNames: List<String>? = null
        val director = HermesDocumentIntentDirector(
            TestLlmClient { req: LlmRequest ->
                capturedToolNames = req.tools.map { it.name }
                toolResponse("delegate", targetDocument = approvedFilenames[0])
            },
        )
        director.decide("summarize it", emptyList(), null)
        assertEquals(listOf(TOOL_NAME), capturedToolNames)
    }
}

class HermesDocumentIntentDirectorNegationGuardTest {
    @Test
    fun `catches don't summarize immediately before the word`() {
        assertTrue(callIsExplicitlyNegatedRequest("don't summarize that document"))
    }

    @Test
    fun `catches do not summarize`() {
        assertTrue(callIsExplicitlyNegatedRequest("please do not summarize that one"))
    }

    @Test
    fun `does not false-positive on an unrelated negation far from the summarize word`() {
        assertFalse(callIsExplicitlyNegatedRequest("I don't have much time today, but can you summarize the project brief for me?"))
    }

    @Test
    fun `does not match when there is no summarize word at all`() {
        assertFalse(callIsExplicitlyNegatedRequest("don't worry about it"))
    }

    @Test
    fun `does not match a legitimate request mentioning a past inability with didn't, which is not a recognized negation word`() {
        assertFalse(callIsExplicitlyNegatedRequest("I didn't get a chance to read that, can you summarize it?"))
    }

    // isExplicitlyNegatedRequest is `internal` on the companion — directly callable from test
    // code in the same Gradle module, no reflection needed.
    private fun callIsExplicitlyNegatedRequest(utterance: String): Boolean =
        HermesDocumentIntentDirector.isExplicitlyNegatedRequest(utterance)
}
