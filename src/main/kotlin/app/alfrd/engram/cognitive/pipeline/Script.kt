package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionQuery
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionResult
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService

/**
 * Per-turn quality readout for the retrieval pass — telemetry only, never consulted by
 * routing or the actor. [coverage] is a 0..1 scalar combining three signals already
 * produced by the retrieval it describes:
 *  - [activationMass]: how strongly retrieval activated (composite score for a phrase-pool
 *    pick; mean trust score for memory-query hits; a fixed value for a correction write).
 *  - [playFired]: whether the retrieval pass produced any usable grounding material.
 *  - [conceptResolutionRatio]: fraction of the referenced concepts that resolved — template
 *    interpolation keys for a phrase-pool pick, phrases-returned/requested for a memory
 *    query, whether an existing phrase was found for a correction.
 * [gaps] lists human-readable reasons for any shortfall; empty when coverage is full.
 */
data class RetrievalCoverage(
    val coverage: Double,
    val activationMass: Double,
    val playFired: Boolean,
    val conceptResolutionRatio: Double,
    val gaps: List<String>,
) {
    companion object {
        /** No retrieval was needed this turn — trivially fully covered, nothing to report. */
        val NONE_NEEDED = RetrievalCoverage(
            coverage = 1.0,
            activationMass = 0.0,
            playFired = false,
            conceptResolutionRatio = 1.0,
            gaps = emptyList(),
        )

        /** Composite score below this is called out in [gaps] as a low-activation turn. */
        const val LOW_MASS_THRESHOLD = 0.3

        fun of(activationMass: Double, playFired: Boolean, conceptResolutionRatio: Double, gaps: List<String>) =
            RetrievalCoverage(
                coverage = ((activationMass + (if (playFired) 1.0 else 0.0) + conceptResolutionRatio) / 3.0)
                    .coerceIn(0.0, 1.0),
                activationMass = activationMass.coerceIn(0.0, 1.0),
                playFired = playFired,
                conceptResolutionRatio = conceptResolutionRatio.coerceIn(0.0, 1.0),
                gaps = gaps,
            )
    }
}

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
    private val personaSource: PersonaSource = DefaultPersonaSource(),
) {

    /**
     * Persona + self-description conditioner for [modality] — retrieved via [personaSource]
     * rather than a string baked into [Actor]'s prompt-building code. Not tied to a
     * [RetrievalIntent]: every turn needs a persona regardless of which branch fired.
     */
    fun persona(modality: Modality): PersonaConditioner = personaSource.describe(modality)

    suspend fun run(ctx: CognitiveContext, intent: RetrievalIntent): RetrievedScript = when (intent) {
        is RetrievalIntent.None -> {
            ctx.retrievalCoverage = RetrievalCoverage.NONE_NEEDED
            RetrievedScript()
        }
        is RetrievalIntent.PhrasePool -> runPhrasePool(ctx, intent)
        is RetrievalIntent.MemoryQuery -> runMemoryQuery(ctx, intent)
        is RetrievalIntent.Correction -> runCorrection(ctx, intent)
    }

    // ── Phrase pool ──────────────────────────────────────────────────────────

    private fun runPhrasePool(ctx: CognitiveContext, intent: RetrievalIntent.PhrasePool): RetrievedScript {
        val svc = selectionService ?: run {
            ctx.retrievalCoverage = RetrievalCoverage.of(
                activationMass = 0.0, playFired = false, conceptResolutionRatio = 0.0,
                gaps = listOf("selection service unavailable"),
            )
            return RetrievedScript()
        }
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
            val result = results.firstOrNull()
            ctx.selectionResult = result
            ctx.retrievalCoverage = coverageForPhrasePool(result)
            val phrase = result?.interpolated
            if (phrase != null) RetrievedScript(lines = listOf(phrase), label = "phrase-pool") else RetrievedScript()
        } catch (_: Exception) {
            ctx.retrievalCoverage = RetrievalCoverage.of(
                activationMass = 0.0, playFired = false, conceptResolutionRatio = 0.0,
                gaps = listOf("phrase-pool retrieval threw"),
            )
            RetrievedScript()
        }
    }

    private fun coverageForPhrasePool(result: ResponseSelectionResult?): RetrievalCoverage {
        if (result == null) {
            return RetrievalCoverage.of(
                activationMass = 0.0, playFired = false, conceptResolutionRatio = 0.0,
                gaps = listOf("no phrase selected from pool"),
            )
        }
        val requiredKeys = result.phrase.interpolationKeys ?: emptySet()
        val unresolvedKeys = requiredKeys.filter { result.interpolated.contains("{$it}") }
        val resolutionRatio = if (requiredKeys.isEmpty()) {
            1.0
        } else {
            (requiredKeys.size - unresolvedKeys.size).toDouble() / requiredKeys.size
        }
        val gaps = buildList {
            if (result.compositeScore < RetrievalCoverage.LOW_MASS_THRESHOLD) {
                add("low activation mass (%.2f)".format(result.compositeScore))
            }
            if (unresolvedKeys.isNotEmpty()) {
                add("unresolved interpolation keys: ${unresolvedKeys.joinToString()}")
            }
        }
        return RetrievalCoverage.of(
            activationMass = result.compositeScore, playFired = true,
            conceptResolutionRatio = resolutionRatio, gaps = gaps,
        )
    }

    // ── Memory graph ─────────────────────────────────────────────────────────

    private suspend fun runMemoryQuery(ctx: CognitiveContext, intent: RetrievalIntent.MemoryQuery): RetrievedScript {
        val phrases = try {
            engramClient.queryPhrases(ctx.userEmail, intent.hint, limit = intent.limit)
        } catch (_: Exception) {
            ctx.retrievalCoverage = RetrievalCoverage.of(
                activationMass = 0.0, playFired = false, conceptResolutionRatio = 0.0,
                gaps = listOf("memory query threw for hint '${intent.hint}'"),
            )
            emptyList()
        }
        if (phrases.isEmpty()) {
            if (ctx.retrievalCoverage == null) {
                ctx.retrievalCoverage = RetrievalCoverage.of(
                    activationMass = 0.0, playFired = false, conceptResolutionRatio = 0.0,
                    gaps = listOf("memory query returned no phrases for hint '${intent.hint}'"),
                )
            }
            return RetrievedScript(label = "memory")
        }

        val lines = phrases.take(5).map { phrase ->
            val confidence = "%.0f".format((phrase.scores["trust"] ?: 0.5) * 100)
            "${phrase.text} [source: ${phrase.sourceTypes.firstOrNull() ?: "unknown"}, confidence: $confidence%]"
        }
        val activationMass = phrases.map { it.scores["trust"] ?: 0.5 }.average()
        val resolutionRatio = phrases.size.toDouble() / intent.limit
        val gaps = if (resolutionRatio < 1.0) {
            listOf("resolved ${phrases.size}/${intent.limit} concepts for hint '${intent.hint}'")
        } else {
            emptyList()
        }
        ctx.retrievalCoverage = RetrievalCoverage.of(
            activationMass = activationMass, playFired = true,
            conceptResolutionRatio = resolutionRatio, gaps = gaps,
        )
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
            ctx.retrievalCoverage = RetrievalCoverage.of(
                activationMass = 1.0, playFired = true, conceptResolutionRatio = 1.0, gaps = emptyList(),
            )
            RetrievedScript(lines = listOf(intent.newFact), label = "correction-amended")
        } else {
            try {
                val candidates = engramClient.decompose(intent.newFact, ctx.priorUtterances)
                if (candidates.isNotEmpty()) engramClient.ingest(candidates, ctx.userEmail)
            } catch (_: Exception) { }
            ctx.retrievalCoverage = RetrievalCoverage.of(
                activationMass = 0.5, playFired = true, conceptResolutionRatio = 0.0,
                gaps = listOf("no existing phrase matched superseded value; ingested as new fact"),
            )
            RetrievedScript(lines = listOf(intent.newFact), label = "correction-ingested")
        }
    }
}
