package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Result of [Interpreter.interpret]. House style (matches [app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome]):
 * every variant explicit and distinguishable, never coerced into a generic "nothing happened".
 */
sealed interface InterpretOutcome {
    /** No tool call — the ordinary case: nothing in the utterance read as an explicit, unfinished, first-person, non-negated priority statement. */
    data object NoOperation : InterpretOutcome
    /** A validated, grounded proposal — see [Interpreter] for exactly what "validated" checked. */
    data class ProposedAssertion(val quote: String, val latencyMs: Long) : InterpretOutcome
    /**
     * A tool call was made but rejected before being trusted as evidence. [reason] is one of
     * `"ungrounded_quote"` (the quote isn't actually a substring of the utterance — the model may
     * have fabricated or paraphrased it), `"model_classified_negative"` (the model itself said
     * this wasn't a first-person, non-negated statement), `"negation_marker_detected"` /
     * `"question_detected"` (a code-side guard tripped even though the model claimed otherwise —
     * see class doc), or `"malformed_tool_input"` (missing/wrong-typed arguments).
     */
    data class ValidationRejected(val reason: String) : InterpretOutcome
    /** The LLM call itself failed (no client configured, timeout, API error) — distinct from a rejected proposal. */
    data class LlmFailure(val reason: String) : InterpretOutcome
}

/**
 * The "Director"'s interpretation stage — one Anthropic tool-call turn per utterance, deciding
 * whether to propose recording an explicit, unfinished, first-person priority statement. Never
 * produces user-facing text: any plain-text content alongside a tool call is discarded, and a
 * response with no tool call is [InterpretOutcome.NoOperation] — this stage never competes with
 * the response LLM as a second author.
 *
 * **Mechanical grounding is necessary, not sufficient — the two kinds of confirmation are kept
 * distinct, not blended.** The tool's `quote` argument is checked, in code, against the actual
 * utterance (normalized substring containment) before anything downstream trusts it — this proves
 * the text is real, not fabricated, but proves nothing about meaning: a fabrication-proof
 * containment check alone would happily accept a quote from inside a negated clause, a question,
 * or a quoted third party's statement. So the tool also requires the model to explicitly commit,
 * in a separate, inspectable field, to a classification — `is_first_person_and_not_negated` — and
 * that classification is trusted only in the negative direction (a `false` is an immediate,
 * unconditional reject); a `true` still passes through two deterministic, generic code-side guards
 * (a negation-marker check and a question check) before being trusted, because a model's own
 * "true" can itself be wrong. [Interpreter] never asserts these two kinds of confirmation are the
 * same thing — the caller ([app.alfrd.engram.cognitive.pipeline.HorizonCycleCoordinator]) records
 * both, distinctly, in the write's metadata.
 */
open class Interpreter(private val llmClient: LlmClient?) {

    private val logger = LoggerFactory.getLogger(Interpreter::class.java)

    open suspend fun interpret(utterance: String): InterpretOutcome {
        val client = llmClient ?: return InterpretOutcome.LlmFailure("no LLM client configured")
        val startMs = System.currentTimeMillis()
        val response = try {
            client.complete(
                LlmRequest(
                    prompt = utterance,
                    systemPrompt = SYSTEM_PROMPT,
                    model = LlmModel.CLAUDE_SONNET_4_5,
                    maxTokens = 256,
                    timeoutMs = 15_000,
                    tools = listOf(ASSERT_OPEN_INTENTION_TOOL),
                ),
            )
        } catch (e: Exception) {
            return InterpretOutcome.LlmFailure(e.message ?: "interpretation LLM call failed")
        }
        val latencyMs = System.currentTimeMillis() - startMs

        // Explicit tool-name/shape/call-count validation in application code — never assumed
        // from "the API only returns one tool call" or "the model only calls the tool we gave
        // it." A fixed, documented policy: only the first structurally-valid call to the exact
        // expected tool name is honored; anything else is logged and ignored, never trusted.
        val unexpected = response.toolCalls.filter { it.name != TOOL_NAME }
        if (unexpected.isNotEmpty()) {
            logger.warn("interpret: ignoring unexpected tool call(s): ${unexpected.map { it.name }}")
        }
        val matching = response.toolCalls.filter { it.name == TOOL_NAME }
        if (matching.size > 1) {
            logger.warn("interpret: response carried ${matching.size} '$TOOL_NAME' calls — honoring only the first")
        }
        val toolCall = matching.firstOrNull() ?: return InterpretOutcome.NoOperation

        val quote = (toolCall.input["quote"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val classified = (toolCall.input["is_first_person_and_not_negated"] as? JsonPrimitive)?.booleanOrNull
        if (quote.isNullOrBlank() || classified == null) {
            return InterpretOutcome.ValidationRejected("malformed_tool_input")
        }
        if (!classified) {
            return InterpretOutcome.ValidationRejected("model_classified_negative")
        }
        if (!isGrounded(utterance, quote)) {
            return InterpretOutcome.ValidationRejected("ungrounded_quote")
        }
        if (NEGATION_REGEX.containsMatchIn(utterance)) {
            return InterpretOutcome.ValidationRejected("negation_marker_detected")
        }
        if (looksLikeQuestion(utterance)) {
            return InterpretOutcome.ValidationRejected("question_detected")
        }
        return InterpretOutcome.ProposedAssertion(quote, latencyMs)
    }

    private fun isGrounded(utterance: String, quote: String): Boolean {
        val normUtterance = normalize(utterance)
        val normQuote = normalize(quote)
        return normQuote.isNotBlank() && normUtterance.contains(normQuote)
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ").trim()

    private fun looksLikeQuestion(utterance: String): Boolean {
        val trimmed = utterance.trim()
        if (trimmed.endsWith("?")) return true
        val firstWord = trimmed.lowercase().split(Regex("\\s+")).firstOrNull()?.trimEnd(',', '.', '!') ?: return false
        return firstWord in QUESTION_WORDS
    }

    companion object {
        private const val TOOL_NAME = "assert_open_intention"

        private val QUESTION_WORDS = setOf(
            "is", "are", "do", "does", "did", "should", "can", "could", "would", "anything",
            "what", "how", "when", "why", "who",
        )

        /** Word-boundary matched, never a raw substring check — "not" must not match inside "note"/"nothing"/"notice". */
        private val NEGATION_REGEX = Regex(
            """\b(not|never|no longer|isn't|wasn't|don't|doesn't|didn't|won't|can't|cannot)\b|n't\b""",
            RegexOption.IGNORE_CASE,
        )

        private const val SYSTEM_PROMPT = """You are a careful evidence-extraction assistant. Given a single user message, decide whether the user explicitly and directly states, in their own words, that something is an unfinished priority or task of theirs, right now.

Call assert_open_intention only when all of the following hold:
- The user is stating this about themselves, not asking about it, not reporting someone else's priority, and not negating it.
- The statement is a direct, explicit claim, not something you are inferring or guessing at.

Do not call it for a question, a hypothetical, a third party's statement, or a negated statement (e.g. "I'm not prioritizing X" or "I used to prioritize X"). Do not call it for anything else. If in doubt, do not call it. If you call it, quote must be the user's own words, taken verbatim from their message."""

        private val ASSERT_OPEN_INTENTION_TOOL = ToolDefinition(
            name = TOOL_NAME,
            description = "Record that the user explicitly and directly stated, in their own words, that something is an unfinished priority or task of theirs right now.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("quote") {
                        put("type", "string")
                        put("description", "The user's own words, quoted verbatim from their message, stating the unfinished priority.")
                    }
                    putJsonObject("is_first_person_and_not_negated") {
                        put("type", "boolean")
                        put(
                            "description",
                            "true only if this is the user's own statement (not a third party's, not something they're merely quoting) " +
                                "and is NOT negated (not \"I'm not going to...\", not \"I used to...\").",
                        )
                    }
                }
                putJsonArray("required") {
                    add("quote")
                    add("is_first_person_and_not_negated")
                }
            },
        )
    }
}
