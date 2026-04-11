package app.alfrd.engram.cognitive.pipeline

/**
 * Expression stage — maps [ResponseStrategy] to a streaming-phase pattern and
 * writes the concatenated result to [CognitiveContext.responseText].
 *
 * Actual streaming / TTS integration is deferred to a later phase.
 */
class Expression : CognitiveStage {

    override suspend fun evaluate(ctx: CognitiveContext) {
        val result = ctx.branchResult ?: return

        val phases: List<String> = when (result.responseStrategy) {
            ResponseStrategy.SOCIAL -> listOf(
                result.content,
            )
            ResponseStrategy.SIMPLE -> listOf(
                "Understood.",
                result.content,
            )
            ResponseStrategy.COMPLEX -> listOf(
                "Understood.",
                "Let me think through that.",
                "Here's what I found:",
                "Additionally,",
                result.content,
            )
            ResponseStrategy.EMOTIONAL -> listOf(
                "I hear you.",
                "That sounds important.",
                result.content,
            )
        }

        ctx.streamingPhases = phases
        ctx.responseText = phases.joinToString(" ")
    }
}
