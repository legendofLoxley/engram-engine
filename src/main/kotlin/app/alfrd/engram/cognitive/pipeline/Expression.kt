package app.alfrd.engram.cognitive.pipeline

/**
 * Expression stage — maps [ResponseStrategy] to a streaming-phase pattern and
 * writes the concatenated result to [CognitiveContext.responseText].
 *
 * Also produces a [StreamingExpressionResult] with distinct Acknowledge / Bridge / Synthesis
 * phases for the voice loop orchestrator.
 */
class Expression : CognitiveStage {

    private val modalityLeakPhrases = listOf(
        "i can see what you type",
        "as a text-based",
        "i don't have ears",
        "i can't hear",
        "i'm a language model",
    )

    override suspend fun evaluate(ctx: CognitiveContext) {
        val result = ctx.branchResult ?: return

        val filtered = applyModalityFilter(result)
        val streaming = toStreamingResult(filtered)
        ctx.streamingExpressionResult = streaming

        // Backward-compat: flatten phases into the list / concatenated text
        val phases = buildList {
            streaming.acknowledge?.let { add(it) }
            streaming.bridge?.let { add(it) }
            add(streaming.synthesis)
        }

        ctx.streamingPhases = phases
        ctx.responseText = phases.joinToString(" ")
    }

    private fun applyModalityFilter(result: BranchResult): BranchResult {
        val lower = result.content.lowercase()
        return if (modalityLeakPhrases.any { lower.contains(it) })
            result.copy(content = "I'm right here. What do you need?")
        else result
    }

    /**
     * Decompose a [BranchResult] into streaming cognition phases.
     *
     * Phrase selection is deterministic here (first element of pool).
     * The orchestrator may override acknowledge/bridge using its own
     * session-aware deduplication.
     */
    fun toStreamingResult(result: BranchResult): StreamingExpressionResult {
        val strategy = result.responseStrategy
        val ackPool = ExpressionPhrasePool.acknowledgeFor(strategy)
        val bridgePool = ExpressionPhrasePool.bridgeFor(strategy)

        return StreamingExpressionResult(
            acknowledge = ackPool.firstOrNull(),
            bridge = bridgePool.firstOrNull(),
            synthesis = result.content,
            strategy = strategy,
        )
    }
}
