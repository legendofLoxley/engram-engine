package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * The concrete mechanism preventing a successful [ContextHorizon] from combining incompatible
 * graph states. One JVM-level [ReentrantReadWriteLock] per [Database] instance, shared by every
 * [HorizonGraphStore] writer (exclusive write lock) and [HorizonAssembler.assemble] (shared read
 * lock) — multiple `assemble()` calls run concurrently with each other, but never with a writer,
 * and a writer never runs concurrently with another writer or a reader.
 *
 * **Scope — exactly which writers this covers.** The four [HorizonGraphStore] mutators
 * (`ingestEnvironmentSignal`, `markAssertionStatus`, `markRelevant`, `markSuperseded`) — the
 * complete set of writers to Context Horizon graph state that exist in this codebase today; no
 * propagation writer is built yet (out of scope per the design doc). It does **not** cover the
 * pre-existing conversational path (`DatabaseEngramClient.ingest`/`amendPhrase`), which predates
 * this feature and is left untouched per this increment's scope boundary — that path does not
 * acquire this lock, so a concurrent conversational ingest can still race an `assemble()` call
 * unguarded. Extending coverage to that path, or to a future propagation writer, requires that
 * code to call [forDatabase] itself; this lock enforces nothing on a writer that never asks for it.
 *
 * **Why a hand-rolled lock, not an ArcadeDB-native mechanism.** Two ArcadeDB-native options were
 * tried first and rejected on concrete evidence, not preference:
 * - `Database.setTransactionIsolationLevel(REPEATABLE_READ)` — tested against the same controlled
 *   concurrent-write scenario this lock now guards; the concurrent write was still observed by the
 *   later query. It did not change the outcome.
 * - `Database.executeInReadLock`/`executeInWriteLock` — ArcadeDB's own native locks. These
 *   deadlocked: they are thread-affine (backed by a plain `ReentrantReadWriteLock` internally,
 *   confirmed by a thread dump), and this codebase's writers are `suspend fun`s that call
 *   `withContext(Dispatchers.IO)`, which can resume the continuation on a *different* OS thread
 *   than the one that entered the lock. A test that acquired `executeInWriteLock` on one thread and
 *   then called a suspend function doing `withContext(Dispatchers.IO)` inside it hung indefinitely
 *   — the inner dispatch landed on a different thread that could never re-enter the lock its own
 *   caller already held, and `Database.close()` independently needing that same internal lock hung
 *   too. Confirmed via `jstack`: one thread parked in `ReentrantReadWriteLock$WriteLock.lock` inside
 *   `LocalDatabase.executeInWriteLock` called from `LocalDatabase.close`, contending with another
 *   thread still inside `executeInWriteLock`'s callable.
 *
 * The lock here avoids that hazard by a simple discipline: it is acquired and released **entirely
 * within one synchronous `db.transaction { }` call, inside the same `withContext(Dispatchers.IO)`
 * block that runs it** — never held across a suspension point, so the thread that locks is always
 * the thread that unlocks. This is "the smallest mechanism supported by the actual database and
 * writer architecture": a plain JVM lock, scoped to exactly the writers that ask for it, used the
 * only way that composes safely with this codebase's coroutine-based write methods.
 */
internal object HorizonConsistencyLock {
    /** Default bounded wait for a lock acquisition. A single `tryLock` call, never a retry loop. */
    const val DEFAULT_TIMEOUT_MS = 3_000L

    private val locks = ConcurrentHashMap<Database, ReentrantReadWriteLock>()

    fun forDatabase(db: Database): ReentrantReadWriteLock = locks.computeIfAbsent(db) { ReentrantReadWriteLock() }
}

/**
 * Runs [block] holding [db]'s write lock, bounded by [timeoutMs] — one `tryLock` call, no retry
 * loop. Returns null if the lock could not be acquired in time; the caller must treat that as a
 * rejected write, never proceeding unguarded. Must be called from within the same
 * `withContext(Dispatchers.IO)` scope as [block] itself, with no suspension point between
 * acquiring and releasing — see [HorizonConsistencyLock] for why.
 */
internal fun <T> withHorizonWriteLock(db: Database, timeoutMs: Long = HorizonConsistencyLock.DEFAULT_TIMEOUT_MS, block: () -> T): T? {
    val lock = HorizonConsistencyLock.forDatabase(db).writeLock()
    if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return null
    try {
        return block()
    } finally {
        lock.unlock()
    }
}

/**
 * Runs [block] holding [db]'s read lock, bounded by [timeoutMs] — one `tryLock` call, no retry
 * loop. Returns null if the lock could not be acquired in time; the caller must return an explicit
 * consistency failure, never a possibly-mixed snapshot. Same no-suspension-point discipline as
 * [withHorizonWriteLock].
 */
internal fun <T> withHorizonReadLock(db: Database, timeoutMs: Long = HorizonConsistencyLock.DEFAULT_TIMEOUT_MS, block: () -> T): T? {
    val lock = HorizonConsistencyLock.forDatabase(db).readLock()
    if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return null
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
