package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/** Time is a variable here, not a clock: `sleep` advances it, so nothing waits and every wait is recorded. */
class RateLimitedNotionApiTest {

    private lateinit var fake: FakeNotionApi
    private lateinit var template: FakeTemplate
    private var clock = 0L
    private val sleeps = ArrayList<Long>()
    private val sleep: suspend (Long) -> Unit = { ms -> sleeps += ms; clock += ms }
    private val noJitter = RetryPolicy(maxAttempts = 5, baseDelayMs = 500, maxDelayMs = 30_000, jitter = { 0L })

    private val items get() = template.sources.items
    private val sessions get() = template.sources.sessions

    @BeforeEach
    fun setUp() {
        fake = FakeNotionApi()
        template = fake.installAlfrdTemplate()
        clock = 0L
        sleeps.clear()
    }

    private fun limiter(rate: Double = 3.0, burst: Int = 3) = RequestLimiter(rate, burst, nowMillis = { clock }, sleep = sleep)
    private fun api(limiter: RequestLimiter = limiter(), policy: RetryPolicy = noJitter) = RateLimitedNotionApi(fake, limiter, policy, sleep)

    private fun newTask(api: NotionApi) = runBlocking {
        api.createPage(items, mapOf("Item" to NotionValue.Title("x"), "Kind" to NotionValue.Select("Task")))
    }

    // ── Pacing ───────────────────────────────────────────────────────────────

    @Test
    fun `a burst up to the bucket size goes through without waiting`() = runBlocking {
        val api = api()
        repeat(3) { api.query(items) }
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun `beyond the burst, requests are spaced to the sustained rate`() = runBlocking {
        val api = api()
        repeat(9) { api.query(items) }
        // 3 free, then 6 more at 3 per second is about two seconds of waiting (each wait is rounded up to a whole millisecond).
        assertTrue(sleeps.sum() in 1_995L..2_010L, "total wait was ${sleeps.sum()}ms: $sleeps")
        assertEquals(sleeps.sum(), clock)
    }

    @Test
    fun `over a long run the sustained rate never exceeds the configured one`() = runBlocking {
        val api = api()
        repeat(30) { api.query(items) }
        // After the initial burst of 3, the other 27 requests are spread at 3 per second: nine seconds.
        assertTrue(clock in 8_990L..9_100L, "30 requests finished at ${clock}ms")
    }

    @Test
    fun `an idle gap refills the bucket, but never past its size`() = runBlocking {
        val api = api()
        repeat(3) { api.query(items) }
        clock += 60_000
        repeat(3) { api.query(items) }
        assertEquals(emptyList<Long>(), sleeps)
        api.query(items)
        assertTrue(sleeps.isNotEmpty(), "a fourth request in the same instant had to wait")
    }

    // ── 429 ──────────────────────────────────────────────────────────────────

    @Test
    fun `a 429 is retried after the longer of Retry-After and the backoff`() = runBlocking {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        fake.failNext("query", NotionRateLimitedException(retryAfterMs = 4_000))
        api.query(items)
        assertEquals(listOf(4_000L), sleeps)
        assertEquals(2, fake.requestCount("query"))
        assertEquals(1L, api.retriesMade)
    }

    @Test
    fun `backoff doubles from the base, and is capped`() = runBlocking {
        val api = api(limiter(rate = 1000.0, burst = 1000), RetryPolicy(maxAttempts = 8, baseDelayMs = 500, maxDelayMs = 3_000, jitter = { 0L }))
        fake.failNext("query", NotionApiException(503, "service_unavailable", "down"), times = 5)
        api.query(items)
        assertEquals(listOf(500L, 1_000L, 2_000L, 3_000L, 3_000L), sleeps)
    }

    @Test
    fun `jitter is added on top of the backoff, never subtracted`() = runBlocking {
        val api = api(limiter(rate = 1000.0, burst = 1000), RetryPolicy(maxAttempts = 3, baseDelayMs = 400, maxDelayMs = 30_000, jitter = { it / 4 }))
        fake.failNext("query", NotionApiException(500, "internal_server_error", "oops"))
        api.query(items)
        assertEquals(listOf(500L), sleeps)
    }

    @Test
    fun `retries give up after the last attempt and rethrow the failure`() {
        val api = api(limiter(rate = 1000.0, burst = 1000), RetryPolicy(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 100, jitter = { 0L }))
        fake.failNext("query", NotionRateLimitedException(null), times = 10)
        assertThrows(NotionRateLimitedException::class.java) { runBlocking { api.query(items) } }
        assertEquals(3, fake.requestCount("query"))
    }

    // ── Reads and updates are repeatable ────────────────────────────────────

    @Test
    fun `a read is retried on a 5xx and on a dropped connection`() = runBlocking {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        fake.failNext("query", NotionApiException(502, "bad_gateway", "x"))
        fake.failNext("listBlocks", NotionTransportException(requestMayHaveBeenProcessed = true, cause = IOException("reset")))
        api.query(items)
        val session = api.createPage(sessions, mapOf("Session" to NotionValue.Title("today")))
        api.listBlocks(session.id)
        assertEquals(2, fake.requestCount("query"))
        assertEquals(2, fake.requestCount("listBlocks"))
    }

    @Test
    fun `an update is repeatable, so a 503 is retried`() = runBlocking {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        val task = newTask(api)
        fake.failNext("updatePage", NotionApiException(503, "service_unavailable", "x"))
        api.updatePage(task.id, mapOf("Tentative" to NotionValue.Checkbox(true)))
        assertTrue(fake.page(task.id).checkbox("Tentative"))
        assertEquals(2, fake.requestCount("updatePage"))
    }

    @Test
    fun `an error that retrying cannot fix is not retried`() {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        assertThrows(NotionApiException::class.java) { runBlocking { api.query("00000000-0000-4000-8000-000000000000") } }
        assertEquals(1, fake.requestCount("query"))
        assertEquals(emptyList<Long>(), sleeps)
    }

    // ── Creates and appends are not ─────────────────────────────────────────

    @Test
    fun `a create that was rate limited was not processed, so it is retried and makes exactly one row`() = runBlocking {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        fake.failNext("createPage", NotionRateLimitedException(1_000))
        val created = newTask(api)
        assertEquals(2, fake.requestCount("createPage"))
        assertEquals(listOf(created.id), fake.rows(items).map { it.id })
    }

    @Test
    fun `a create that hit a 409 conflict was not saved, so it is retried`() = runBlocking {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        fake.failNext("createPage", NotionApiException(409, "conflict_error", "try again"))
        newTask(api)
        assertEquals(2, fake.requestCount("createPage"))
    }

    @Test
    fun `a create that fails with a 5xx may have landed, so it is never blindly retried`() {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        fake.failNext("createPage", NotionApiException(503, "service_unavailable", "x"))
        val e = assertThrows(NotionAmbiguousWriteException::class.java) { newTask(api) }
        assertEquals("createPage", e.operation)
        assertEquals(1, fake.requestCount("createPage"))
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun `a create that timed out may have landed, so it surfaces as ambiguous`() {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        fake.failNext("createPage", NotionTransportException(requestMayHaveBeenProcessed = true, cause = IOException("timeout")))
        assertThrows(NotionAmbiguousWriteException::class.java) { newTask(api) }
        assertEquals(1, fake.requestCount("createPage"))
    }

    @Test
    fun `a create that failed before the request left is safe to retry`() = runBlocking {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        fake.failNext("createPage", NotionTransportException(requestMayHaveBeenProcessed = false, cause = IOException("connection refused")))
        newTask(api)
        assertEquals(2, fake.requestCount("createPage"))
        assertEquals(1, fake.rows(items).size)
    }

    @Test
    fun `a ledger append that may have landed is ambiguous, never repeated into a duplicate entry`() {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        val session = runBlocking { api.createPage(sessions, mapOf("Session" to NotionValue.Title("today"))) }
        fake.failNext("appendBlocks", NotionApiException(500, "internal_server_error", "x"))
        assertThrows(NotionAmbiguousWriteException::class.java) {
            runBlocking { api.appendBlocks(session.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("hello"))))) }
        }
        assertEquals(1, fake.requestCount("appendBlocks"))
    }

    @Test
    fun `a rate-limited append is retried once and lands once`() = runBlocking {
        val api = api(limiter(rate = 1000.0, burst = 1000))
        val session = api.createPage(sessions, mapOf("Session" to NotionValue.Title("today")))
        fake.failNext("appendBlocks", NotionRateLimitedException(500))
        api.appendBlocks(session.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("hello")))))
        assertEquals(listOf("hello"), fake.blockTexts(session.id))
    }

    @Test
    fun `every attempt, including a retry, takes a token from the limiter`() = runBlocking {
        val api = api(limiter(rate = 1.0, burst = 1))
        fake.failNext("query", NotionApiException(503, "service_unavailable", "x"))
        api.query(items)
        assertEquals(2, api.requestsMade)
        assertTrue(sleeps.sum() >= 1_000L, "the retry did not wait for a token: $sleeps")
    }
}
