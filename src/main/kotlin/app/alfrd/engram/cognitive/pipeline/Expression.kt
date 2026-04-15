package app.alfrd.engram.cognitive.pipeline

/**
 * Expression stage — maps [ResponseStrategy] to a streaming-phase pattern and
 * writes the concatenated result to [CognitiveContext.responseText].
 *
 * Also produces a [StreamingExpressionResult] with distinct Acknowledge / Bridge / Synthesis
 * phases for the voice loop orchestrator.
 */
class Expression : CognitiveStage {

    override suspend fun evaluate(ctx: CognitiveContext) {
        val result = ctx.branchResult ?: return

        val streaming = toStreamingResult(result)
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
