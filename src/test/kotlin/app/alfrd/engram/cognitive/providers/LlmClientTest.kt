package app.alfrd.engram.cognitive.providers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [AbstractLlmClient] retry and timeout behaviour.
 *
 * All timing assertions use virtual time provided by [runTest] so that the test
 * suite runs in milliseconds, not real wall-clock time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmClientTest {

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private val defaultRequest = LlmRequest(
        prompt = "Hello",
        model = LlmModel.CLAUDE_SONNET_4_5,
        timeoutMs = 5_000,
    )

    private fun successResponse(text: String = "ok", latencyMs: Long = 10L) =
        LlmResponse(text = text, latencyMs = latencyMs, retryCount = 0)

    /**
     * Creates an [AbstractLlmClient] whose [doComplete] delegates to [behavior].
     * [behavior] receives the attempt number (0-indexed) so tests can program per-attempt results.
     */
    private fun fakeLlmClient(behavior: suspend (attempt: Int) -> LlmResponse): AbstractLlmClient {
        var attempt = 0
        return object : AbstractLlmClient() {
            override suspend fun doComplete(request: LlmRequest): LlmResponse =
                behavior(attempt++)
        }
    }

    // -------------------------------------------------------------------------
    // Success path
    // -------------------------------------------------------------------------

    @Test
    fun `succeeds on first attempt without retrying`() = runTest {
        val client = fakeLlmClient { successResponse() }

        val result = client.complete(defaultRequest)

        assertEquals("ok", result.text)
        assertEquals(0, result.retryCount)
        assertEquals(0L, currentTime) // no delay used
    }

    @Test
    fun `retryCount reflects number of prior failures`() = runTest {
        val client = fakeLlmClient { attempt ->
            if (attempt < 2) throw RuntimeException("transient error")
            successResponse()
        }

        val result = client.complete(defaultRequest)

        assertEquals("ok", result.text)
        assertEquals(2, result.retryCount)
    }

    // -------------------------------------------------------------------------
    // Retry backoff timing
    // -------------------------------------------------------------------------

    @Test
    fun `applies 100ms backoff before first retry`() = runTest {
        val client = fakeLlmClient { attempt ->
            if (attempt == 0) throw RuntimeException("first failure")
            successResponse()
        }

        client.complete(defaultRequest)

        assertEquals(AbstractLlmClient.INITIAL_BACKOFF_MS, currentTime)
    }

    @Test
    fun `applies 200ms backoff before second retry`() = runTest {
        val client = fakeLlmClient { attempt ->
            if (attempt < 2) throw RuntimeException("failure $attempt")
            successResponse()
        }

        client.complete(defaultRequest)

        // 100ms after attempt 0 + 200ms after attempt 1 = 300ms total virtual time
        assertEquals(300L, currentTime)
    }

    @Test
    fun `no extra delay after final failed attempt`() = runTest {
        val client = fakeLlmClient { throw RuntimeException("always fails") }

        assertThrows<RuntimeException> { client.complete(defaultRequest) }

        // 100ms + 200ms = 300ms; no delay after the 3rd attempt since it's the last
        assertEquals(300L, currentTime)
    }

    // -------------------------------------------------------------------------
    // Exhausting retries
    // -------------------------------------------------------------------------

    @Test
    fun `propagates original exception after all retries exhausted`() = runTest {
        val cause = IllegalStateException("persistent error")
        val client = fakeLlmClient { throw cause }

        // Use try/catch so the suspend call stays inside the coroutine context.
        // Note: Kotlin coroutines may produce a recovery copy with an enhanced stack trace,
        // so we verify type and message rather than object identity.
        var caught: IllegalStateException? = null
        try {
            client.complete(defaultRequest)
        } catch (e: IllegalStateException) {
            caught = e
        }

        assertNotNull(caught, "Expected IllegalStateException to be thrown")
        assertEquals(cause.message, caught!!.message)
    }

    @Test
    fun `attempts exactly MAX_RETRIES + 1 times before giving up`() = runTest {
        var callCount = 0
        val client = fakeLlmClient { at ->
            callCount = at + 1
            throw RuntimeException("always fails")
        }

        assertThrows<RuntimeException> { client.complete(defaultRequest) }

        assertEquals(AbstractLlmClient.MAX_RETRIES + 1, callCount)
    }

    // -------------------------------------------------------------------------
    // Timeout behaviour
    // -------------------------------------------------------------------------

    @Test
    fun `throws LlmTimeoutError when doComplete exceeds timeoutMs`() = runTest {
        val client = fakeLlmClient { delay(Long.MAX_VALUE); successResponse() }
        val request = defaultRequest.copy(timeoutMs = 500)

        assertThrows<LlmTimeoutError> { client.complete(request) }
    }

    @Test
    fun `LlmTimeoutError contains the configured timeout duration`() = runTest {
        val client = fakeLlmClient { delay(Long.MAX_VALUE); successResponse() }
        val request = defaultRequest.copy(timeoutMs = 1_234)

        val error = assertThrows<LlmTimeoutError> { client.complete(request) }

        assertTrue(error.message!!.contains("1234"), "Message should contain timeout value")
    }

    @Test
    fun `timeout does not trigger retries`() = runTest {
        var callCount = 0
        val client = fakeLlmClient {
            callCount++
            delay(Long.MAX_VALUE)
            successResponse()
        }
        val request = defaultRequest.copy(timeoutMs = 100)

        assertThrows<LlmTimeoutError> { client.complete(request) }

        assertEquals(1, callCount, "Should not retry on timeout")
    }

    // -------------------------------------------------------------------------
    // parsedOutput propagation
    // -------------------------------------------------------------------------

    @Test
    fun `parsedOutput is preserved in successful response`() = runTest {
        val parsed = mapOf("key" to "value")
        val client = fakeLlmClient {
            LlmResponse(text = "{}", parsedOutput = parsed, latencyMs = 5, retryCount = 0)
        }

        val result = client.complete(defaultRequest.copy(structuredOutput = true))

        assertEquals(parsed, result.parsedOutput)
    }
}
