package app.alfrd.engram.cognitive.pipeline

/**
 * Expression stage — maps [ResponseStrategy] to a streaming-phase pattern and
 * writes the actor's composed text to [CognitiveContext.responseText].
 *
 * Also runs a modality-aware safety-net filter: even with a correct per-modality identity
 * prompt at the [Actor] call site, an LLM can occasionally ignore its system prompt, so this
 * catches the two ways that can leak — a voice reply admitting it can't hear, or a text reply
 * falsely claiming it can hear/listen. Both directions read [CognitiveContext.modality] — the
 * same field the [Actor] reads via [Conditioners.modality] — so there is exactly one source of
 * truth for "which identity applies to this turn."
 */
class Expression : CognitiveStage {

    private val voiceLeakPhrases = listOf(
        "i can see what you type",
        "as a text-based",
        "i don't have ears",
        "i can't hear",
        "i'm a language model",
    )

    private val textLeakPhrases = listOf(
        "i can hear you",
        "i'm listening",
        "loud and clear",
        "hearing you fine",
        "as a voice assistant",
        "speaking with you",
    )

    override suspend fun evaluate(ctx: CognitiveContext) {
        val actorResult = ctx.actorResult ?: return
        val strategy = ctx.branchResult?.responseStrategy ?: ResponseStrategy.SIMPLE

        val filteredText = applyModalityFilter(actorResult.text, ctx.modality)
        val streaming = toStreamingResult(filteredText, strategy)
        ctx.streamingExpressionResult = streaming

        // Backward-compat: flatten phases into the list / concatenated text
        val phases = buildList {
            streaming.acknowledge?.let { add(it) }
            streaming.bridge?.let { add(it) }
            add(streaming.synthesis)
        }

        ctx.streamingPhases = phases
        // responseText carries only the synthesis content so that pipeline.process() and
        // the SSE synthesis frame never contain an acknowledge/bridge prefix.
        ctx.responseText = streaming.synthesis
    }

    private fun applyModalityFilter(text: String, modality: Modality): String {
        val lower = text.lowercase()
        val leaks = if (modality == Modality.VOICE) voiceLeakPhrases else textLeakPhrases
        val fallback = if (modality == Modality.VOICE)
            "I'm right here. What do you need?"
        else
            "I'm here — what do you need?"
        return if (leaks.any { lower.contains(it) }) fallback else text
    }

    /**
     * Decompose composed text into streaming cognition phases.
     *
     * Phrase selection is deterministic here (first element of pool).
     * The orchestrator may override acknowledge/bridge using its own
     * session-aware deduplication.
     */
    fun toStreamingResult(text: String, strategy: ResponseStrategy): StreamingExpressionResult {
        val ackPool = ExpressionPhrasePool.acknowledgeFor(strategy)
        val bridgePool = ExpressionPhrasePool.bridgeFor(strategy)

        return StreamingExpressionResult(
            acknowledge = ackPool.firstOrNull(),
            bridge = bridgePool.firstOrNull(),
            synthesis = text,
            strategy = strategy,
        )
    }
}
