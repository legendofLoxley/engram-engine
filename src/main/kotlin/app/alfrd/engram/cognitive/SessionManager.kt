package app.alfrd.engram.cognitive

import app.alfrd.engram.cognitive.pipeline.CognitivePipeline
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-session [CognitivePipeline] instances.
 *
 * - Pipelines are created lazily on first access via [factory].
 * - A session is considered expired after [ttlMs] milliseconds of inactivity.
 * - Eviction is lazy: stale entries are pruned whenever [getOrCreate] is called.
 * - Concurrent access is safe: [ConcurrentHashMap] provides the necessary atomicity.
 *   In the rare case of a race on a brand-new session, the losing pipeline is simply
 *   discarded — both are functionally equivalent [InMemoryEngramClient] instances.
 */
class SessionManager(
    private val factory: suspend () -> CognitivePipeline,
    private val ttlMs: Long = 30L * 60 * 1000,
) {

    private data class Entry(
        val pipeline: CognitivePipeline,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val sessions = ConcurrentHashMap<String, Entry>()

    suspend fun getOrCreate(sessionId: String): CognitivePipeline {
        evictExpired()

        val existing = sessions[sessionId]
        if (existing != null && !isExpired(existing)) {
            return existing.pipeline
        }

        val pipeline = factory()
        pipeline.init()
        val entry = Entry(pipeline)
        // putIfAbsent: if another thread won the race, use their pipeline
        val winner = sessions.putIfAbsent(sessionId, entry) ?: entry
        return winner.pipeline
    }

    private fun evictExpired() {
        sessions.entries.removeIf { (_, entry) -> isExpired(entry) }
    }

    private fun isExpired(entry: Entry): Boolean =
        System.currentTimeMillis() - entry.createdAt > ttlMs
}
