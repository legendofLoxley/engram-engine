package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Allocates the durable, per-user monotonic cycle identity [HorizonGraphStore]'s mutators require
 * as a caller-supplied `cycleSeq` — the shipped foundation deliberately left this to "the caller,"
 * and nothing in this codebase owned it before this task.
 *
 * Pure atomic allocation only — no retry detection, no request identity. That is a deliberately
 * separate concern, owned by [RequestLedger] together with [PerUserCycleLock]. An earlier design
 * here tried to fold "was this a retry" into allocation itself via a caller-supplied idempotency
 * key compared against the previous call; it was rejected because a stable per-request identity
 * and "the next cycle number" are different things; conflating them made retry detection both
 * miss genuine retries (a retried interpretation call can legitimately extract different text)
 * and suppress genuine repeats (two different, legitimate turns can share identical text).
 *
 * A cycle number allocated here and never used by a corresponding write (a crash between
 * allocation and the write landing) is a harmless gap: nothing here or in [HorizonAssembler]
 * requires cycle numbers to be dense, only monotonic and unique per user.
 */
interface CycleSequencer {
    /** Atomically allocates and returns the next cycle number for [userEmail]. Null on failure (no User vertex, lock timeout). */
    suspend fun allocateCycle(userEmail: String): Long?
}

class ArcadeCycleSequencer(
    private val db: Database,
    private val lockTimeoutMs: Long = HorizonConsistencyLock.DEFAULT_TIMEOUT_MS,
) : CycleSequencer {

    private val logger = LoggerFactory.getLogger(ArcadeCycleSequencer::class.java)

    override suspend fun allocateCycle(userEmail: String): Long? = withContext(Dispatchers.IO) {
        try {
            withHorizonWriteLock(db, lockTimeoutMs) {
                var allocated: Long? = null
                db.transaction {
                    val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: run {
                        logger.warn("allocateCycle: no User vertex for email=$userEmail — skipping")
                        return@transaction
                    }
                    val current = (userVertex.get("lastCycleSeq") as? Number)?.toLong() ?: 0L
                    val next = current + 1
                    userVertex.modify().apply {
                        set("lastCycleSeq", next)
                        save()
                    }
                    allocated = next
                }
                allocated
            } ?: run {
                logger.warn("allocateCycle: could not acquire the Horizon write lock within ${lockTimeoutMs}ms for userEmail=$userEmail — rejecting")
                null
            }
        } catch (e: Exception) {
            logger.warn("allocateCycle failed for userEmail=$userEmail: ${e.message}")
            null
        }
    }
}
