package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient

/**
 * Resolves a user correction by locating the relevant stored fact and superseding it,
 * or ingesting the corrected fact when no existing phrase can be found.
 *
 * Flow:
 * 1. Strip correction markers ("actually", "no i meant", etc.) from the utterance.
 * 2. Extract the superseded value from a trailing "not X" clause, if present.
 * 3. Query memory for a phrase matching the superseded value; if one exists, amend it.
 * 4. If no existing phrase to amend, ingest the corrected fact directly.
 * 5. If the correction body is too short to act on, return a graceful clarification.
 */
class CorrectionBranch(
    private val engramClient: EngramClient,
) : Branch {

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

        if (newFact.length <= 4) {
            ctx.branchResult = BranchResult(
                content = "What should I update?",
                responseStrategy = ResponseStrategy.SIMPLE,
                source = "pool",
            )
            return
        }

        val queryHint = supersededValue ?: newFact.take(80)
        val existingPhrases = try {
            engramClient.queryPhrases(ctx.userEmail, queryHint, limit = 5)
        } catch (_: Exception) {
            emptyList()
        }

        val phraseToAmend = supersededValue?.let { sv ->
            existingPhrases.firstOrNull { it.text.lowercase().contains(sv.lowercase()) }
        }

        if (phraseToAmend != null) {
            try { engramClient.amendPhrase(phraseToAmend.uid, newFact) } catch (_: Exception) { }
            ctx.branchResult = BranchResult(
                content = "Got it — updated.",
                responseStrategy = ResponseStrategy.SIMPLE,
                source = "pool",
            )
        } else {
            try {
                val candidates = engramClient.decompose(newFact, ctx.priorUtterances)
                if (candidates.isNotEmpty()) engramClient.ingest(candidates, ctx.userEmail)
            } catch (_: Exception) { }
            ctx.branchResult = BranchResult(
                content = "Got it, I'll remember that.",
                responseStrategy = ResponseStrategy.SIMPLE,
                source = "pool",
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
