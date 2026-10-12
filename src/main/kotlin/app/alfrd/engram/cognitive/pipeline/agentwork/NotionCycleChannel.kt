package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.pipeline.agentwork.CycleProcessor.TickResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** What the last poll did, for the debug surface. Ids, counts and reason codes only. */
@Serializable
data class ChannelSnapshot(
    val polls: Long,
    val lastPollAt: String?,
    val lastPollMs: Long?,
    /** One short line per cycle the last poll touched. Empty when it found nothing to do. */
    val lastResults: List<String>,
    val consecutiveFailures: Int,
    /** A reason code (an exception class name), never a message: provider and HTTP messages are not ours to repeat. */
    val lastError: String?,
)

/**
 * The Director's Notion input channel: it asks [CycleProcessor] for work every [pollInterval] and
 * does nothing else. It is not a scheduler. It starts no cycle and creates no row; it reacts to
 * Cycles that Jacob set to Queued, and to a Running cycle whose lease ran out.
 *
 * One poll never overlaps the next: the loop runs a poll to completion, then waits. A failure of any
 * kind (Notion down, a token not yet placed, a rate limit that outlasted its retries) is recorded
 * and the loop carries on at its normal pace; the client already backs off on 429, and the lease
 * makes a poll that was cut short safe to repeat.
 */
class NotionCycleChannel(
    private val processor: CycleProcessor,
    private val pollInterval: Duration,
    private val clock: () -> Instant = Instant::now,
) {
    init { require(!pollInterval.isNegative && !pollInterval.isZero) { "pollInterval must be positive" } }

    private val logger = LoggerFactory.getLogger(NotionCycleChannel::class.java)
    private val polls = AtomicLong()
    private val lock = Any()

    private var lastPollAt: Instant? = null
    private var lastPollMs: Long? = null
    private var lastResults: List<String> = emptyList()
    private var consecutiveFailures = 0
    private var lastError: String? = null
    /** The failure already logged at warn and not yet followed by a success, so a repeat is not logged again. */
    private var warnedFailure: String? = null

    /** Starts polling in [scope]. The first poll runs at once, so a restart reclaims an expired lease without waiting a full interval. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            pollOnce()
            delay(pollInterval.toMillis())
        }
    }

    /** One poll. Never throws except to propagate cancellation. */
    suspend fun pollOnce(): List<TickResult> {
        val startedAt = clock()
        val results = try {
            processor.runOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only. A configuration or HTTP exception message is not something to log from here.
            record(startedAt, emptyList(), failure = e::class.java.simpleName)
            return emptyList()
        }
        val error = results.filterIsInstance<TickResult.Error>().firstOrNull()
        record(startedAt, results, failure = error?.reason)
        return results
    }

    fun snapshot(): ChannelSnapshot = synchronized(lock) {
        ChannelSnapshot(polls.get(), lastPollAt?.toString(), lastPollMs, lastResults, consecutiveFailures, lastError)
    }

    private fun record(startedAt: Instant, results: List<TickResult>, failure: String?) {
        val finishedAt = clock()
        val lines = results.map(::describe)
        val sameAsBefore: Boolean
        synchronized(lock) {
            polls.incrementAndGet()
            lastPollAt = startedAt
            lastPollMs = Duration.between(startedAt, finishedAt).toMillis()
            lastResults = lines
            sameAsBefore = failure != null && failure == warnedFailure
            if (failure == null) {
                consecutiveFailures = 0
                warnedFailure = null
            } else {
                consecutiveFailures++
                lastError = failure
                warnedFailure = failure
            }
        }
        when {
            failure == null && lines.isNotEmpty() -> logger.info("notion-cycles: poll handled {}", lines.joinToString("; "))
            failure == null -> logger.debug("notion-cycles: poll found nothing to do")
            // Warn when the failure is new, then keep quiet while it repeats: a token file that has not been placed yet would otherwise log every poll.
            !sameAsBefore -> logger.warn("notion-cycles: poll failed: {}", failure)
            else -> logger.debug("notion-cycles: poll failed again: {}", failure)
        }
    }

    private fun describe(result: TickResult): String = when (result) {
        is TickResult.Finished -> "${result.cycle ?: "cycle"}: ${result.status.label.lowercase()}"
        is TickResult.Aborted -> "${result.cycle ?: "cycle"}: aborted (${result.reason})"
        is TickResult.LostClaim -> "${result.cycle ?: "cycle"}: not claimed"
        is TickResult.Error -> "error: ${result.reason}"
    }
}
