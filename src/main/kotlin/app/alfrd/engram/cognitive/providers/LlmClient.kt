package app.alfrd.engram.cognitive.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/** The LLM models supported by this pipeline. */
enum class LlmModel(val apiId: String) {
    CLAUDE_HAIKU_3_5("claude-haiku-4-5"),
    CLAUDE_SONNET_4_5("claude-sonnet-4-6"),
    CLAUDE_SONNET_4("claude-sonnet-4-6"),
    GEMINI_FLASH_2_0("gemini-2.0-flash"),
    GEMINI_FLASH_2_0_LITE("gemini-2.0-flash-lite"),
}

/**
 * A single tool a model may call, in Anthropic Messages API shape (`name`/`description`/JSON
 * Schema `input_schema`) — the only provider [tools] is currently wired for; see
 * [app.alfrd.engram.cognitive.providers.cloud.CloudLlmClient]. Gemini tool-calling is not built.
 */
data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

/** One `tool_use` content block the model returned — [input] is the tool's arguments, in the shape [ToolDefinition.inputSchema] declared. */
data class ToolCall(val name: String, val input: JsonObject)

data class LlmRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val model: LlmModel = LlmModel.CLAUDE_SONNET_4_5,
    val maxTokens: Int = 1024,
    val timeoutMs: Long = 30_000,
    val structuredOutput: Boolean = false,
    /** Empty (default) omits `tools` from the wire request entirely — every existing caller is unaffected. Tool choice is left at the API's default ("auto") — 0 or 1 tool call, model's discretion; this is a single-shot decision, not a multi-turn tool-result round trip. */
    val tools: List<ToolDefinition> = emptyList(),
    /**
     * When set, the model must call exactly this tool (one of [tools]) instead of choosing between a
     * tool call and plain text — provider-native `tool_choice`. Lets a caller model "nothing to do"
     * as an explicit empty argument (an empty list) rather than as the absence of a call, which a
     * small model with many tools on offer will otherwise sometimes fill with an irrelevant call.
     * Null (default) leaves tool choice at the API's default, so every existing caller is unaffected.
     */
    val forcedToolName: String? = null,
    /** Sampling temperature. Null (default) leaves the provider's own default, so every existing caller is unaffected. */
    val temperature: Double? = null,
    /**
     * Opt in to the model's own reasoning for this call. Off (default) keeps the local client's per-request
     * `enable_thinking=false`, which suits the chat path's short answers. A caller that forces a tool call
     * with a long prompt sets this: the local model's template cannot call a tool after a pre-closed think
     * block and instead writes its reasoning as visible text. Reasoning tokens count against [maxTokens], so
     * such a caller also needs a larger budget. When set with a [forcedToolName] naming the only entry of
     * [tools], the local client sends `tool_choice` as the string `"required"`, the one form its server honours.
     */
    val reasoning: Boolean = false,
)

data class LlmResponse(
    val text: String,
    val parsedOutput: Map<String, Any>? = null,
    /** `tool_use` content blocks the model returned, in response order — empty when [LlmRequest.tools] was empty or the model chose not to call one. */
    val toolCalls: List<ToolCall> = emptyList(),
    val latencyMs: Long,
    val retryCount: Int,
    /**
     * Which provider/model actually served this call — set by the [LlmClient] implementation
     * itself (never inferred elsewhere) so callers/trace metadata can report the real answering
     * client instead of assuming one. Null for implementations that don't report it (e.g. tests'
     * fakes); existing callers that don't read these fields are unaffected.
     */
    val providerName: String? = null,
    val modelName: String? = null,
    /** The provider's stop reason (`stop`, `tool_calls`, `length`), when it reports one. */
    val finishReason: String? = null,
    /** Tokens the model generated, reasoning included, when the provider reports it. */
    val completionTokens: Int? = null,
)

/** Thrown when an LLM request exceeds the configured [LlmRequest.timeoutMs]. Callers must degrade gracefully. */
class LlmTimeoutError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Primary interface for language-model completions. Swap cloud for local via config. */
interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResponse
}

/**
 * Base class that wraps any LLM backend with retry + timeout semantics.
 *
 * Retry policy:
 * - [MAX_RETRIES] = 2 (total up to 3 attempts)
 * - Exponential backoff: 100 ms, then 200 ms before the 2nd and 3rd attempt
 * - A [LlmTimeoutError] is thrown immediately on timeout — no retry.
 */
abstract class AbstractLlmClient : LlmClient {

    companion object {
        const val MAX_RETRIES = 2
        const val INITIAL_BACKOFF_MS = 100L
    }

    /**
     * Single-attempt implementation. Return a fully populated [LlmResponse];
     * [LlmResponse.retryCount] will be overwritten by [complete].
     */
    protected abstract suspend fun doComplete(request: LlmRequest): LlmResponse

    override suspend fun complete(request: LlmRequest): LlmResponse {
        var attempt = 0
        var lastError: Exception? = null

        while (attempt <= MAX_RETRIES) {
            try {
                val result = withTimeout(request.timeoutMs) {
                    doComplete(request)
                }
                return result.copy(retryCount = attempt)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw LlmTimeoutError(
                    "LLM request timed out after ${request.timeoutMs} ms (model=${request.model.apiId})",
                    e,
                )
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_RETRIES) {
                    delay(INITIAL_BACKOFF_MS shl attempt) // 100 ms, 200 ms
                }
                attempt++
            }
        }

        throw lastError!!
    }
}
