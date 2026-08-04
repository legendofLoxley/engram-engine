package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionQuery
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService

/**
 * Script stage — the only component (besides the universal memory-capture step in
 * [CognitivePipeline]) allowed to call [EngramClient] or [ResponseSelectionService].
 *
 * Turns a director's [RetrievalIntent] into grounding material for the [Actor]. Branches
 * describe *what* to fetch; this stage does the fetching (and, for corrections, the writing).
 */
class Script(
    private val engramClient: EngramClient,
    private val selectionService: ResponseSelectionService? = null,
) {

    suspend fun run(ctx: CognitiveContext, intent: RetrievalIntent): RetrievedScript = when (intent) {
        is RetrievalIntent.None -> RetrievedScript()
        is RetrievalIntent.PhrasePool -> runPhrasePool(ctx, intent)
        is RetrievalIntent.MemoryQuery -> runMemoryQuery(ctx, intent)
        is RetrievalIntent.Correction -> runCorrection(ctx, intent)
    }

    // ── Phrase pool ──────────────────────────────────────────────────────────

    private fun runPhrasePool(ctx: CognitiveContext, intent: RetrievalIntent.PhrasePool): RetrievedScript {
        val svc = selectionService ?: return RetrievedScript()
        return try {
            val query = ResponseSelectionQuery(
                branch = intent.branch,
                moveType = intent.moveType,
                expressionPhase = intent.expressionPhase,
                category = intent.category,
                context = ctx,
                limit = 1,
            )
            val startMs = System.currentTimeMillis()
            val results = svc.select(query)
            ctx.selectionLatencyMs = System.currentTimeMillis() - startMs
            ctx.selectionResult = results.firstOrNull()
            val phrase = results.firstOrNull()?.interpolated
            if (phrase != null) RetrievedScript(lines = listOf(phrase), label = "phrase-pool") else RetrievedScript()
        } catch (_: Exception) {
            RetrievedScript()
        }
    }

    // ── Memory graph ─────────────────────────────────────────────────────────

    private suspend fun runMemoryQuery(ctx: CognitiveContext, intent: RetrievalIntent.MemoryQuery): RetrievedScript {
        val phrases = try {
            engramClient.queryPhrases(ctx.userEmail, intent.hint, limit = intent.limit)
        } catch (_: Exception) {
            emptyList()
        }
        if (phrases.isEmpty()) return RetrievedScript(label = "memory")

        val lines = phrases.take(5).map { phrase ->
            val confidence = "%.0f".format((phrase.scores["trust"] ?: 0.5) * 100)
            "${phrase.text} [source: ${phrase.sourceTypes.firstOrNull() ?: "unknown"}, confidence: $confidence%]"
        }
        return RetrievedScript(lines = lines, label = "memory")
    }

    // ── Correction ───────────────────────────────────────────────────────────

    private suspend fun runCorrection(ctx: CognitiveContext, intent: RetrievalIntent.Correction): RetrievedScript {
        val queryHint = intent.supersededValue ?: intent.newFact.take(80)
        val existingPhrases = try {
            engramClient.queryPhrases(ctx.userEmail, queryHint, limit = 5)
        } catch (_: Exception) {
            emptyList()
        }

        val phraseToAmend = intent.supersededValue?.let { sv ->
            existingPhrases.firstOrNull { it.text.lowercase().contains(sv.lowercase()) }
        }

        return if (phraseToAmend != null) {
            try { engramClient.amendPhrase(phraseToAmend.uid, intent.newFact) } catch (_: Exception) { }
            RetrievedScript(lines = listOf(intent.newFact), label = "correction-amended")
        } else {
            try {
                val candidates = engramClient.decompose(intent.newFact, ctx.priorUtterances)
                if (candidates.isNotEmpty()) engramClient.ingest(candidates, ctx.userEmail)
            } catch (_: Exception) { }
            RetrievedScript(lines = listOf(intent.newFact), label = "correction-ingested")
        }
    }
}
