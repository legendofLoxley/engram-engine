package app.alfrd.engram.cognitive.pipeline

/**
 * Tracks recently used Acknowledge and Bridge phrases within a session to avoid
 * repetition. A phrase is excluded from selection if it was used within the last
 * [windowSize] consecutive turns.
 *
 * Thread-safety: not required — each session gets its own tracker instance.
 */
class PhraseDeduplicationTracker(
    private val windowSize: Int = 3,
) {
    private val recentAcknowledge = ArrayDeque<String>()
    private val recentBridge = ArrayDeque<String>()

    /**
     * Pick an acknowledge phrase for the given [strategy], avoiding recently used ones.
     * Returns null if the strategy has no acknowledge pool.
     */
    fun pickAcknowledge(strategy: ResponseStrategy): String? {
        val pool = ExpressionPhrasePool.acknowledgeFor(strategy)
        if (pool.isEmpty()) return null
        val phrase = pickFromPool(pool, recentAcknowledge)
        recordUsage(phrase, recentAcknowledge)
        return phrase
    }

    /**
     * Pick a bridge phrase for the given [strategy], avoiding recently used ones.
     * Returns null if the strategy has no bridge pool.
     */
    fun pickBridge(strategy: ResponseStrategy): String? {
        val pool = ExpressionPhrasePool.bridgeFor(strategy)
        if (pool.isEmpty()) return null
        val phrase = pickFromPool(pool, recentBridge)
        recordUsage(phrase, recentBridge)
        return phrase
    }

    private fun pickFromPool(pool: List<String>, recent: ArrayDeque<String>): String {
        val available = pool.filter { it !in recent }
        // If all phrases exhausted, reset and pick from full pool.
        return if (available.isNotEmpty()) {
            available.first()
        } else {
            recent.clear()
            pool.first()
        }
    }

    private fun recordUsage(phrase: String, recent: ArrayDeque<String>) {
        recent.addLast(phrase)
        while (recent.size > windowSize) {
            recent.removeFirst()
        }
    }
}
