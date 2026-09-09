package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import app.alfrd.engram.cognitive.providers.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Each layer of [Interpreter]'s validation tested independently: mechanical grounding, the
 * model's own classification (trusted only in the negative direction), and the code-side
 * negation/question guards that don't trust a model-claimed "true". These specifically target
 * the counterexamples a bare substring-containment check would pass: a negated statement, a
 * quoted third party, and a question.
 */
class InterpreterTest {

    private fun toolCallFor(quote: String, isFirstPersonAndNotNegated: Boolean, name: String = "assert_open_intention"): ToolCall =
        ToolCall(
            name = name,
            input = buildJsonObject {
                put("quote", JsonPrimitive(quote))
                put("is_first_person_and_not_negated", JsonPrimitive(isFirstPersonAndNotNegated))
            },
        )

    @Test
    fun `grounded first-person non-negated statement is accepted`() = runTest {
        val utterance = "Getting Alfrd running on Arx is a priority for me — I keep meaning to get to it."
        val quote = "Getting Alfrd running on Arx is a priority for me"
        val client = TestLlmClient {
            LlmResponse(text = "", toolCalls = listOf(toolCallFor(quote, true)), latencyMs = 5, retryCount = 0)
        }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret(utterance)

        assertTrue(outcome is InterpretOutcome.ProposedAssertion, "Expected ProposedAssertion, got $outcome")
        assertEquals(quote, (outcome as InterpretOutcome.ProposedAssertion).quote)
    }

    @Test
    fun `no tool call at all is NoOperation, never a competing reply`() = runTest {
        val client = TestLlmClient { LlmResponse(text = "Sure, happy to help with that!", latencyMs = 5, retryCount = 0) }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret("What's a good way to structure a grocery list app's data model?")

        assertEquals(InterpretOutcome.NoOperation, outcome)
    }

    @Test
    fun `model classifying the statement as negative is rejected outright`() = runTest {
        val utterance = "My friend said his own priority is getting his Alfrd running on Arx."
        val client = TestLlmClient {
            LlmResponse(text = "", toolCalls = listOf(toolCallFor("getting his Alfrd running on Arx", false)), latencyMs = 5, retryCount = 0)
        }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret(utterance)

        assertEquals(InterpretOutcome.ValidationRejected("model_classified_negative"), outcome)
    }

    @Test
    fun `negation marker in the utterance is rejected even when the model claims true`() = runTest {
        val utterance = "I am not going to prioritize getting Alfrd running on Arx right now."
        val quote = "going to prioritize getting Alfrd running on Arx"
        // Model incorrectly claims true — the code-side guard must catch it anyway.
        val client = TestLlmClient {
            LlmResponse(text = "", toolCalls = listOf(toolCallFor(quote, true)), latencyMs = 5, retryCount = 0)
        }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret(utterance)

        assertEquals(InterpretOutcome.ValidationRejected("negation_marker_detected"), outcome)
    }

    @Test
    fun `a question is rejected even when the model claims true`() = runTest {
        val utterance = "Is getting Alfrd running on Arx a priority for me right now?"
        val quote = "getting Alfrd running on Arx a priority"
        val client = TestLlmClient {
            LlmResponse(text = "", toolCalls = listOf(toolCallFor(quote, true)), latencyMs = 5, retryCount = 0)
        }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret(utterance)

        assertEquals(InterpretOutcome.ValidationRejected("question_detected"), outcome)
    }

    @Test
    fun `a fabricated quote not actually in the utterance is rejected`() = runTest {
        val utterance = "Anyway, what's a good grocery list schema?"
        val client = TestLlmClient {
            LlmResponse(text = "", toolCalls = listOf(toolCallFor("this text never appeared", true)), latencyMs = 5, retryCount = 0)
        }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret(utterance)

        assertEquals(InterpretOutcome.ValidationRejected("ungrounded_quote"), outcome)
    }

    @Test
    fun `malformed tool input is rejected without crashing`() = runTest {
        val client = TestLlmClient {
            LlmResponse(
                text = "",
                toolCalls = listOf(ToolCall(name = "assert_open_intention", input = buildJsonObject { put("quote", JsonPrimitive("x")) })),
                latencyMs = 5, retryCount = 0,
            )
        }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret("some utterance")

        assertEquals(InterpretOutcome.ValidationRejected("malformed_tool_input"), outcome)
    }

    @Test
    fun `an unexpected tool name is ignored, never treated as a proposal`() = runTest {
        val client = TestLlmClient {
            LlmResponse(text = "", toolCalls = listOf(toolCallFor("x", true, name = "some_other_tool")), latencyMs = 5, retryCount = 0)
        }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret("x")

        assertEquals(InterpretOutcome.NoOperation, outcome)
    }

    @Test
    fun `only the first valid tool call is honored when multiple are present`() = runTest {
        val utterance = "Getting Alfrd running on Arx is a priority for me."
        val first = toolCallFor("Getting Alfrd running on Arx is a priority for me", true)
        val second = toolCallFor("Getting Alfrd running on Arx is a priority for me", true)
        val client = TestLlmClient {
            LlmResponse(text = "", toolCalls = listOf(first, second), latencyMs = 5, retryCount = 0)
        }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret(utterance)

        assertTrue(outcome is InterpretOutcome.ProposedAssertion, "The first valid call must still be honored despite the extra one")
    }

    @Test
    fun `an LLM call failure is distinguished from a rejected proposal`() = runTest {
        val client = TestLlmClient { throw RuntimeException("simulated LLM failure") }
        val interpreter = Interpreter(client)

        val outcome = interpreter.interpret("anything")

        assertTrue(outcome is InterpretOutcome.LlmFailure, "Expected LlmFailure, got $outcome")
    }

    @Test
    fun `no LLM client configured degrades to LlmFailure rather than throwing`() = runTest {
        val interpreter = Interpreter(null)

        val outcome = interpreter.interpret("anything")

        assertTrue(outcome is InterpretOutcome.LlmFailure)
    }
}
