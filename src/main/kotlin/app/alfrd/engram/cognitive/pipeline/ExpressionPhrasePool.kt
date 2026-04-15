package app.alfrd.engram.cognitive.pipeline

/**
 * In-memory phrase pools for Acknowledge and Bridge expression phases.
 *
 * Pools are keyed by [ResponseStrategy]. Each pool contains short, natural phrases
 * appropriate for that strategy's conversational register.
 */
object ExpressionPhrasePool {

    private val acknowledgePhrases: Map<ResponseStrategy, List<String>> = mapOf(
        ResponseStrategy.SIMPLE to listOf(
            "Understood.",
            "Got it.",
            "Of course.",
            "Right.",
            "Mm-hmm.",
        ),
        ResponseStrategy.COMPLEX to listOf(
            "Understood.",
            "Got it.",
            "Of course.",
            "Right.",
            "Mm-hmm.",
        ),
        ResponseStrategy.EMOTIONAL to listOf(
            "I hear you.",
            "I understand.",
            "Of course.",
            "Right.",
        ),
        // SOCIAL has no acknowledge — it responds directly.
    )

    private val bridgePhrases: Map<ResponseStrategy, List<String>> = mapOf(
        ResponseStrategy.COMPLEX to listOf(
            "Let me think through that.",
            "Give me a moment.",
            "There's a lot here.",
            "Bear with me.",
            "Let me work through this.",
        ),
        ResponseStrategy.EMOTIONAL to listOf(
            "That sounds important.",
            "Let me take a moment with that.",
            "I want to be thoughtful here.",
        ),
        // SIMPLE and SOCIAL have no bridge phase.
    )

    fun acknowledgeFor(strategy: ResponseStrategy): List<String> =
        acknowledgePhrases[strategy] ?: emptyList()

    fun bridgeFor(strategy: ResponseStrategy): List<String> =
        bridgePhrases[strategy] ?: emptyList()
}
