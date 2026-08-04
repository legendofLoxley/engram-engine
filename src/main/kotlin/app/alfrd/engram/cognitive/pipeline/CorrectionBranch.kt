package app.alfrd.engram.cognitive.pipeline

/**
 * Resolves a user correction by locating the relevant stored fact and superseding it,
 * or ingesting the corrected fact when no existing phrase can be found.
 *
 * This is the director half only: parsing the utterance is pure string logic and stays here.
 * The actual memory lookup and write happen in [Script] via [RetrievalIntent.Correction] —
 * this branch never touches [app.alfrd.engram.cognitive.pipeline.memory.EngramClient].
 *
 * Flow:
 * 1. Strip correction markers ("actually", "no i meant", etc.) from the utterance.
 * 2. Extract the superseded value from a trailing "not X" clause, if present.
 * 3. If the correction body is too short to act on, return a graceful clarification (no retrieval).
 * 4. Otherwise, hand the superseded value and new fact to the script stage.
 */
class CorrectionBranch : Branch {

    companion object {
        private val CORRECTION_PREFIXES: List<String> = listOf(
            "actually, ", "actually,", "actually ",
            "no i meant ", "no, i meant ",
            "that's not right, ", "that's not right,", "that's not right ",
            "thats not right, ", "thats not right,", "thats not right ",
            "wait, ", "wait,", "wait ",
        ).sortedByDescending { it.length }

        // Matches "not <1–4 words>" to identify the superseded value
        private val SUPERSEDED_PATTERN = Regex(
            """\bnot\s+(\w+(?:\s+\w+){0,3})""",
            setOf(RegexOption.IGNORE_CASE),
        )
    }

    override suspend fun execute(ctx: CognitiveContext) {
        val correctionBody = stripMarker(ctx.utterance.trim())
        val lower = correctionBody.lowercase()

        val supersededMatch = SUPERSEDED_PATTERN.find(lower)
        val supersededValue = supersededMatch?.groupValues?.get(1)?.trim()

        // The new fact is everything before the "not X" clause (or the whole body when absent)
        val newFact = if (supersededMatch != null) {
            correctionBody.substring(0, supersededMatch.range.first).trim().trimEnd(',').trim()
        } else {
            correctionBody
        }

        ctx.branchResult = if (newFact.length <= 4) {
            BranchResult(
                responseStrategy = ResponseStrategy.SIMPLE,
                retrieval = RetrievalIntent.None,
                directive = "The user's correction was too vague to act on. Ask them, briefly, what they'd like you to update.",
            )
        } else {
            BranchResult(
                responseStrategy = ResponseStrategy.SIMPLE,
                retrieval = RetrievalIntent.Correction(supersededValue = supersededValue, newFact = newFact),
                directive = "The user corrected something you knew. Confirm briefly and warmly that you've updated it — don't repeat the full correction verbatim.",
            )
        }
    }

    private fun stripMarker(utterance: String): String {
        val lower = utterance.lowercase()
        for (prefix in CORRECTION_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return utterance.drop(prefix.length).trimStart().ifBlank { utterance }
            }
        }
        return utterance
    }
}
