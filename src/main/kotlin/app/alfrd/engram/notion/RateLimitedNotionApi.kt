package app.alfrd.engram.notion

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

/**
 * A token bucket: at most [ratePerSecond] requests per second on average, with bursts up to
 * [burst]. Notion documents an average of three requests per second per integration.
 * [nowMillis] and [sleep] are injectable so tests run in zero real time.
 */
class RequestLimiter(
    private val ratePerSecond: Double = 3.0,
    private val burst: Int = 3,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(ratePerSecond > 0.0) { "ratePerSecond must be positive" }
        require(burst >= 1) { "burst must be at least 1" }
    }

    private val mutex = Mutex()
    private var tokens = burst.toDouble()
    private var lastRefillAt = nowMillis()

    /** Suspends until one request may be made. Waits are computed under the lock and slept outside it, so one waiter never blocks the clock. */
    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                val now = nowMillis()
                tokens = min(burst.toDouble(), tokens + (now - lastRefillAt) * ratePerSecond / 1000.0)
                lastRefillAt = now
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    ceil((1.0 - tokens) * 1000.0 / ratePerSecond).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMs == 0L) return
            sleep(waitMs)
        }
    }
}

/** How a failed request is retried. Waits double from [baseDelayMs] up to [maxDelayMs]; [jitter] adds up to a quarter on top so retries from several identities do not line up. */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 30_000,
    val jitter: (Long) -> Long = { ms -> (ms * 0.25 * Math.random()).toLong() },
) {
    init { require(maxAttempts >= 1) { "maxAttempts must be at least 1" } }

    fun backoffMs(failedAttempts: Int): Long {
        val exponential = baseDelayMs * (1L shl (failedAttempts - 1).coerceIn(0, 20))
        val capped = min(exponential, maxDelayMs)
        return capped + jitter(capped)
    }
}

/**
 * Paces every request through a [RequestLimiter] and retries what is safe to retry.
 *
 * What is safe depends on whether a repeat could do harm:
 * - **Reads, and updates** (setting properties to given values) are retried on a 429, a 409 conflict,
 *   a 5xx and any transport failure.
 * - **Creates and block appends** are not idempotent: a repeat makes a second row or a second ledger
 *   entry. They are retried only when it is certain nothing was processed (a 429, a 409, or a
 *   failure before the request left). Any other failure surfaces as [NotionAmbiguousWriteException],
 *   and the caller finds out what landed by looking the row up by its `Op key` or `Key`.
 *
 * A 429's `Retry-After` is honoured when it is longer than the computed backoff. Logging records the
 * operation, the attempt and the status, never a payload.
 */
class RateLimitedNotionApi(
    private val delegate: NotionApi,
    private val limiter: RequestLimiter = RequestLimiter(),
    private val policy: RetryPolicy = RetryPolicy(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : NotionApi {

    private val logger = LoggerFactory.getLogger(RateLimitedNotionApi::class.java)
    private val requestCount = AtomicLong()
    private val retryCount = AtomicLong()

    val requestsMade: Long get() = requestCount.get()
    val retriesMade: Long get() = retryCount.get()

    override suspend fun query(dataSourceId: String, filter: NotionFilter?, sorts: List<NotionSort>, startCursor: String?, pageSize: Int): QueryPage =
        call("query", Safety.REPEATABLE) { delegate.query(dataSourceId, filter, sorts, startCursor, pageSize) }

    override suspend fun createPage(dataSourceId: String, properties: Map<String, NotionValue>): NotionPage =
        call("createPage", Safety.WRITE_ONCE) { delegate.createPage(dataSourceId, properties) }

    override suspend fun updatePage(pageId: String, properties: Map<String, NotionValue>): NotionPage =
        call("updatePage", Safety.REPEATABLE) { delegate.updatePage(pageId, properties) }

    override suspend fun retrievePage(pageId: String): NotionPage =
        call("retrievePage", Safety.REPEATABLE) { delegate.retrievePage(pageId) }

    override suspend fun appendBlocks(parentBlockId: String, blocks: List<NotionBlock>) =
        call("appendBlocks", Safety.WRITE_ONCE) { delegate.appendBlocks(parentBlockId, blocks) }

    override suspend fun listBlocks(parentBlockId: String, startCursor: String?, pageSize: Int): BlockPage =
        call("listBlocks", Safety.REPEATABLE) { delegate.listBlocks(parentBlockId, startCursor, pageSize) }

    private enum class Safety { REPEATABLE, WRITE_ONCE }

    private suspend fun <T> call(operation: String, safety: Safety, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            limiter.acquire()
            requestCount.incrementAndGet()
            try {
                return block()
            } catch (e: NotionRateLimitedException) {
                if (attempt >= policy.maxAttempts) throw e
                pause(operation, attempt, "429", maxOf(policy.backoffMs(attempt), e.retryAfterMs ?: 0L))
            } catch (e: NotionApiException) {
                val notProcessed = e.status == 409
                val transient = e.status in TRANSIENT_STATUSES
                when {
                    notProcessed && attempt < policy.maxAttempts -> pause(operation, attempt, "${e.status}", policy.backoffMs(attempt))
                    transient && safety == Safety.REPEATABLE && attempt < policy.maxAttempts -> pause(operation, attempt, "${e.status}", policy.backoffMs(attempt))
                    transient && safety == Safety.WRITE_ONCE -> throw NotionAmbiguousWriteException(operation, e)
                    else -> throw e
                }
            } catch (e: NotionTransportException) {
                val certainlyNotSent = !e.requestMayHaveBeenProcessed
                when {
                    (certainlyNotSent || safety == Safety.REPEATABLE) && attempt < policy.maxAttempts ->
                        pause(operation, attempt, "transport", policy.backoffMs(attempt))
                    safety == Safety.WRITE_ONCE && !certainlyNotSent -> throw NotionAmbiguousWriteException(operation, e)
                    else -> throw e
                }
            }
        }
    }

    private suspend fun pause(operation: String, attempt: Int, reason: String, waitMs: Long) {
        retryCount.incrementAndGet()
        logger.warn("notion {} attempt {} failed ({}); retrying in {}ms", operation, attempt, reason, waitMs)
        sleep(waitMs)
    }

    private companion object {
        val TRANSIENT_STATUSES = setOf(500, 502, 503, 504)
    }
}
