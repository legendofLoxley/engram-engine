package app.alfrd.engram.cognitive.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** The LLM models supported by this pipeline. */
enum class LlmModel(val apiId: String) {
    CLAUDE_HAIKU_3_5("claude-haiku-3-5"),
    CLAUDE_SONNET_4_5("claude-sonnet-4-5"),
    CLAUDE_SONNET_4("claude-sonnet-4"),
    GEMINI_FLASH_2_0("gemini-2.0-flash"),
    GEMINI_FLASH_2_0_LITE("gemini-2.0-flash-lite"),
}

data class LlmRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val model: LlmModel = LlmModel.CLAUDE_SONNET_4_5,
    val maxTokens: Int = 1024,
    val timeoutMs: Long = 30_000,
    val structuredOutput: Boolean = false,
)

data class LlmResponse(
    val text: String,
    val parsedOutput: Map<String, Any>? = null,
    val latencyMs: Long,
    val retryCount: Int,
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
