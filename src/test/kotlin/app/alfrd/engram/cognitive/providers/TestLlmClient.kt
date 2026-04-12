package app.alfrd.engram.cognitive.providers

/**
 * Test double for [LlmClient] — delegates every call to [behavior].
 *
 * Extends [LlmClient] directly to skip [AbstractLlmClient]'s retry logic. Tests
 * receive full control over what the "LLM" returns (or throws).
 *
 * Usage:
 * ```kotlin
 * val client = TestLlmClient { req ->
 *     LlmResponse(text = "hello", latencyMs = 0L, retryCount = 0)
 * }
 * // or throw to simulate failure:
 * val failing = TestLlmClient { throw LlmTimeoutError("simulated timeout") }
 * ```
 */
class TestLlmClient(
    private val behavior: suspend (LlmRequest) -> LlmResponse = { req ->
        LlmResponse(
            text = "Test response for: ${req.prompt}",
            latencyMs = 0L,
            retryCount = 0,
        )
    },
) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse = behavior(request)
}
