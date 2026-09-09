package app.alfrd.engram.cognitive.pipeline.horizon

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes an entire per-user conversational cycle — `Script.run()`'s own writes, interpretation,
 * graph mutation, propagation, and assembly (see [app.alfrd.engram.cognitive.pipeline.CognitivePipeline]
 * for exactly what it wraps) — end to end, for one user at a time. A `ConcurrentHashMap<String,
 * Mutex>` keyed by `userEmail`, mirroring [HorizonConsistencyLock]'s per-`Database` registry
 * pattern, but a coroutine [Mutex] rather than a JVM `ReentrantReadWriteLock`: this lock must be
 * held safely across the interpretation LLM call's suspension point, which is exactly the hazard
 * [HorizonConsistencyLock]'s own doc rules out a JVM lock for.
 *
 * [HorizonConsistencyLock] alone is not this guarantee: it coordinates individual
 * [HorizonGraphStore]/[HorizonAssembler] *calls* against each other, but says nothing about the
 * *order* two full cycles for the same user complete in — a slow interpretation call on an
 * earlier-allocated cycle could otherwise finish, and write, *after* a later-allocated cycle for
 * the same user already assembled its own response. This lock closes that gap by making a whole
 * cycle for one user atomic relative to any other cycle (conversational turn or environment-signal
 * injection) for that same user; different users never contend with each other.
 *
 * Response generation/delivery (the Actor's LLM call) deliberately runs *outside* this lock — the
 * caller acquires it only for the write-and-read-for-this-turn's-own-response section, then
 * releases it before composing the reply. See [app.alfrd.engram.cognitive.pipeline.CognitivePipeline]
 * for exactly where it's acquired/released and why letting response composition overlap with the
 * *next* turn's cycle for the same user is safe: a turn's response reflects the Horizon snapshot
 * captured before that next turn began, which is the normal, expected behavior of a chat system,
 * not a consistency violation — nothing a later turn writes can be lost or reordered relative to
 * what an earlier turn already committed, which is the only invariant this lock exists to hold.
 */
internal object PerUserCycleLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(userEmail: String, block: suspend () -> T): T =
        locks.computeIfAbsent(userEmail) { Mutex() }.withLock { block() }
}
