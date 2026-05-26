package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.model.OutcomeSignal
import app.alfrd.engram.model.ResponsePhrase
import app.alfrd.engram.model.SelectedEdge
import com.arcadedb.database.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.time.Instant

/**
 * Five-stage response selection pipeline:
 *   1. Filter — query ArcadeDB for matching ResponsePhrase vertices
 *   2. Score — compute five dimensions per candidate
 *   3. Rank — weighted composite, return top-N
 *   4. Interpolate — resolve template variables
 *   5. Record — write SELECTED edge (fire-and-forget)
 */
class ResponseSelectionService(
    private val db: Database,
    private val fireAndForgetScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun select(query: ResponseSelectionQuery): List<ResponseSelectionResult> {
        val ctx = query.context ?: throw IllegalArgumentException("CognitiveContext is required")

        // 1. Filter
        val candidates = filterCandidates(query)
        if (candidates.isEmpty()) return emptyList()

        // Expose filter count for trace capture
        ctx.selectionCandidatesConsidered = candidates.size

        // 2. Score + 3. Rank
        val weights = SelectionWeights.forBranch(query.branch)
        val turnIndex = ctx.priorUtterances.size + 1
        val now = ctx.timestamp

        val scored = candidates.map { phrase ->
            val selectedEdges = querySelectedEdges(phrase.uid, ctx.userId)
            val outcomeSummaries = queryOutcomeSummaries(phrase.uid, ctx.userId)

            val freshnessScore = SelectionScorer.freshness(phrase, selectedEdges, ctx.sessionId, turnIndex, now)
            val contextualFitScore = SelectionScorer.contextualFit(phrase, ctx)
            val communicationFitScore = SelectionScorer.communicationFit(phrase, ctx)
            val phaseScore = SelectionScorer.phaseAppropriateness(phrase, ctx)
            val effectivenessScore = SelectionScorer.effectiveness(outcomeSummaries)

            val breakdown = mapOf(
                "freshness" to freshnessScore,
                "contextualFit" to contextualFitScore,
                "communicationFit" to communicationFitScore,
                "phaseAppropriateness" to phaseScore,
                "effectiveness" to effectivenessScore,
            )

            val weightMap = weights.toMap()
            val composite = breakdown.entries.sumOf { (dim, score) ->
                score * (weightMap[dim] ?: 0.0)
            }

            // 4. Interpolate
            val interpolated = interpolate(phrase.text, ctx)

            ResponseSelectionResult(
                phrase = phrase,
                compositeScore = composite,
                scoreBreakdown = breakdown,
                interpolated = interpolated,
            )
        }

        val candidateCount = scored.size
        val ranked = scored.sortedByDescending { it.compositeScore }.take(query.limit)
            .map { it.copy(candidatesConsidered = candidateCount) }

        // 5. Record — fire-and-forget SELECTED edge
        for (result in ranked) {
            recordSelected(result, ctx, turnIndex)
        }

        return ranked
    }

    // ── Stage 1: Filter ─────────────────────────────────────────────────────

    /**
     * Two filter paths share the same SQL plumbing:
     *
     * **First-response** (query.moveType != null, branch-agnostic):
     *   SQL gates on `moveType` and `expressionPhase`. No branchAffinity or phaseAffinity gate.
     *
     * **Post-comprehension** (query.branch != null):
     *   SQL gates on `expressionPhase` (and optional `category`), then post-filters on:
     *   1. branchAffinity contains query.branch.name
     *   2. phaseAffinity gate — exclude phrases whose phaseAffinity is entirely outside the
     *      current trust phase and its direct neighbours (would score 0.0 on phaseAppropriateness).
     */
    private fun filterCandidates(query: ResponseSelectionQuery): List<ResponsePhrase> {
        val ctx = query.context!!
        val results = mutableListOf<ResponsePhrase>()

        // Derive adjacent trust phases for the phaseAffinity gate (post-comprehension only).
        val trustPhaseAllowed: Set<String>? = if (query.moveType == null && ctx.trustPhase != null) {
            val current: String = ctx.trustPhase!!  // non-null guaranteed by outer if
            val phases = listOf("ORIENTATION", "WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")
            val idx = phases.indexOf(current)
            if (idx >= 0) buildSet {
                add(current)
                if (idx > 0) add(phases[idx - 1])
                if (idx < phases.lastIndex) add(phases[idx + 1])
            } else setOf(current)
        } else null

        val sql = if (query.moveType != null) {
            // First-response path: filter directly by moveType
            "SELECT FROM ResponsePhrase WHERE moveType = :moveType AND expressionPhase = :expressionPhase"
        } else {
            buildString {
                append("SELECT FROM ResponsePhrase WHERE 1=1")
                if (query.category != null) append(" AND category = :category")
                append(" AND expressionPhase = :expressionPhase")
            }
        }

        val params = mutableMapOf<String, Any>(
            "expressionPhase" to query.expressionPhase.name,
        )
        if (query.moveType != null) params["moveType"] = query.moveType.name
        if (query.category != null) params["category"] = query.category.name

        db.query("sql", sql, params).use { rs ->
            while (rs.hasNext()) {
                val record = rs.next()
                val doc = record.toMap()

                val uid = doc["uid"] as? String ?: continue
                if (query.exclude != null && uid in query.exclude) continue

                val branchAffinityJson = doc["branchAffinity"] as? String ?: "[]"
                val branchAffinity: Set<String> = try {
                    json.decodeFromString<List<String>>(branchAffinityJson).toSet()
                } catch (_: Exception) { emptySet() }

                // Post-comprehension branchAffinity gate
                if (query.moveType == null) {
                    val branch = query.branch ?: continue   // branch required for post-comprehension
                    if (branch.name !in branchAffinity) continue
                }

                val phaseAffinityJson = doc["phaseAffinity"] as? String ?: "[]"
                val phaseAffinity: Set<String> = try {
                    json.decodeFromString<List<String>>(phaseAffinityJson).toSet()
                } catch (_: Exception) { emptySet() }

                // Post-comprehension phaseAffinity gate: exclude phrases entirely outside
                // the current trust phase and its neighbours (phaseAppropriateness = 0.0).
                if (trustPhaseAllowed != null && phaseAffinity.isNotEmpty() &&
                    phaseAffinity.none { it in trustPhaseAllowed }
                ) continue

                val moveTypeStr = doc["moveType"] as? String
                val postureAffinityStr = doc["postureAffinity"] as? String

                val interpolationKeysJson = doc["interpolationKeys"] as? String
                val interpolationKeys: Set<String>? = interpolationKeysJson?.let {
                    try { json.decodeFromString<List<String>>(it).toSet() }
                    catch (_: Exception) { null }
                }

                val variantsJson = doc["variants"] as? String
                val variants: List<String>? = variantsJson?.let {
                    try { json.decodeFromString<List<String>>(it) }
                    catch (_: Exception) { null }
                }

                results.add(ResponsePhrase(
                    uid = uid,
                    text = doc["text"] as? String ?: "",
                    hash = doc["hash"] as? String ?: "",
                    visibility = doc["visibility"] as? String ?: "internal",
                    createdAt = doc["createdAt"] as? Long ?: 0L,
                    updatedAt = doc["updatedAt"] as? Long ?: 0L,
                    branchAffinity = branchAffinity,
                    phaseAffinity = phaseAffinity,
                    expressionPhase = doc["expressionPhase"] as? String ?: "",
                    category = doc["category"] as? String ?: "",
                    moveType = moveTypeStr,
                    postureAffinity = postureAffinityStr,
                    variants = variants,
                    requiresInterpolation = doc["requiresInterpolation"] as? Boolean ?: false,
                    interpolationKeys = interpolationKeys,
                ))
            }
        }

        return results
    }

    // ── Edge queries ────────────────────────────────────────────────────────

    private fun querySelectedEdges(phraseUid: String, userId: String): List<SelectedEdge> {
        val edges = mutableListOf<SelectedEdge>()
        try {
            db.query(
                "sql",
                "SELECT FROM SELECTED WHERE phraseUid = :phraseUid AND userId = :userId ORDER BY timestamp DESC",
                mapOf("phraseUid" to phraseUid, "userId" to userId),
            ).use { rs ->
                while (rs.hasNext()) {
                    val doc = rs.next().toMap()
                    edges.add(SelectedEdge(
                        phraseUid = doc["phraseUid"] as? String ?: "",
                        sessionId = doc["sessionId"] as? String ?: "",
                        userId = doc["userId"] as? String ?: "",
                        turnIndex = (doc["turnIndex"] as? Number)?.toInt() ?: 0,
                        branch = doc["branch"] as? String,
                        moveType = doc["moveType"] as? String,
                        compositeScore = (doc["compositeScore"] as? Number)?.toDouble() ?: 0.0,
                        scoreBreakdown = try {
                            val jsonStr = doc["scoreBreakdown"] as? String ?: "{}"
                            json.decodeFromString<Map<String, Double>>(jsonStr)
                        } catch (_: Exception) { emptyMap() },
                        timestamp = (doc["timestamp"] as? Number)?.toLong() ?: 0L,
                    ))
                }
            }
        } catch (_: Exception) {
            // Edge type may not exist yet or query fails — return empty
        }
        return edges
    }

    private fun queryOutcomeSummaries(
        phraseUid: String,
        userId: String,
    ): List<SelectionScorer.OutcomeSummary> {
        val signalCounts = mutableMapOf<OutcomeSignal, Int>()
        try {
            db.query(
                "sql",
                "SELECT signal, count(*) as cnt FROM OUTCOME WHERE phraseUid = :phraseUid AND userId = :userId GROUP BY signal",
                mapOf("phraseUid" to phraseUid, "userId" to userId),
            ).use { rs ->
                while (rs.hasNext()) {
                    val doc = rs.next().toMap()
                    val signalName = doc["signal"] as? String ?: continue
                    val count = (doc["cnt"] as? Number)?.toInt() ?: 0
                    val signal = try { OutcomeSignal.valueOf(signalName) } catch (_: Exception) { continue }
                    signalCounts[signal] = count
                }
            }
        } catch (_: Exception) {
            // Edge type may not exist yet
        }
        return signalCounts.map { (signal, count) ->
            SelectionScorer.OutcomeSummary(signal, count)
        }
    }

    // ── Stage 4: Interpolate ────────────────────────────────────────────────

    private fun interpolate(template: String, ctx: CognitiveContext): String {
        if (!template.contains("{")) return template

        val hour = java.time.LocalTime.ofInstant(ctx.timestamp, ctx.zoneId ?: java.time.ZoneId.systemDefault()).hour
        val timeOfDay = when {
            hour < 12 -> "morning"
            hour < 17 -> "afternoon"
            else -> "evening"
        }

        val replacements = mapOf(
            "{userName}" to (ctx.userId),  // userId as fallback; richer user profile is future work
            "{timeOfDay}" to timeOfDay,
            "{lastTopic}" to (ctx.priorUtterances.lastOrNull() ?: "{lastTopic}"),
            "{roomName}" to ctx.roomId,
        )

        var result = template
        for ((key, value) in replacements) {
            result = result.replace(key, value)
        }
        return result
    }

    // ── Stage 5: Record ─────────────────────────────────────────────────────

    /**
     * Writes an OUTCOME edge linking User → ResponsePhrase. Fire-and-forget — exceptions
     * are swallowed so write failures never surface to the user or caller.
     *
     * Mirrors [recordSelected] exactly: same vertex resolution, same scope, same error handling.
     */
    internal fun recordOutcome(
        phraseUid: String,
        sessionId: String,
        userId: String,
        turnIndex: Int,
        signal: OutcomeSignal,
        contextSnapshot: String,
    ) {
        fireAndForgetScope.launch {
            try {
                db.transaction {
                    val phraseVertex = db.query(
                        "sql",
                        "SELECT FROM ResponsePhrase WHERE uid = :uid",
                        mapOf("uid" to phraseUid),
                    ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
                        ?: return@transaction

                    val userVertex = db.query(
                        "sql",
                        "SELECT FROM User WHERE uid = :uid",
                        mapOf("uid" to userId),
                    ).use { rs ->
                        if (rs.hasNext()) rs.next().toElement().asVertex()
                        else null
                    } ?: db.newVertex("User").apply {
                        set("uid", userId)
                        set("username", userId)
                        set("tier", 0)
                        set("createdAt", System.currentTimeMillis())
                        save()
                    }

                    userVertex.newEdge("OUTCOME", phraseVertex, false).apply {
                        set("phraseUid", phraseUid)
                        set("sessionId", sessionId)
                        set("userId", userId)
                        set("turnIndex", turnIndex)
                        set("signal", signal.name)
                        set("contextSnapshot", contextSnapshot)
                        set("timestamp", System.currentTimeMillis())
                        save()
                    }
                }
            } catch (e: Exception) {
                // Fire-and-forget: log but don't propagate
                System.err.println("Failed to record OUTCOME edge: ${e.message}")
            }
        }
    }

    private fun recordSelected(result: ResponseSelectionResult, ctx: CognitiveContext, turnIndex: Int) {
        // Determine path: first-response phrases have a non-null moveType stored on the phrase.
        // - First-response: moveType = phrase.moveType, branch = null
        // - Post-comprehension: branch = branchResult.responseStrategy or derived label, moveType = null
        val isFirstResponse = result.phrase.moveType != null
        val edgeMoveType = if (isFirstResponse) result.phrase.moveType else null
        val edgeBranch = if (!isFirstResponse)
            ctx.branchResult?.responseStrategy?.name ?: "SOCIAL"
        else null

        fireAndForgetScope.launch {
            try {
                db.transaction {
                    val phraseVertex = db.query(
                        "sql",
                        "SELECT FROM ResponsePhrase WHERE uid = :uid",
                        mapOf("uid" to result.phrase.uid),
                    ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
                        ?: return@transaction

                    val userVertex = db.query(
                        "sql",
                        "SELECT FROM User WHERE uid = :uid",
                        mapOf("uid" to ctx.userId),
                    ).use { rs ->
                        if (rs.hasNext()) rs.next().toElement().asVertex()
                        else null
                    } ?: db.newVertex("User").apply {
                        set("uid", ctx.userId)
                        set("username", ctx.userId)
                        set("tier", 0)
                        set("createdAt", System.currentTimeMillis())
                        save()
                    }

                    userVertex.newEdge("SELECTED", phraseVertex, false).apply {
                        set("phraseUid", result.phrase.uid)
                        set("sessionId", ctx.sessionId)
                        set("userId", ctx.userId)
                        set("turnIndex", turnIndex)
                        set("branch", edgeBranch as String?)
                        set("moveType", edgeMoveType as String?)
                        set("compositeScore", result.compositeScore)
                        set("scoreBreakdown", json.encodeToString(result.scoreBreakdown))
                        set("timestamp", System.currentTimeMillis())
                        save()
                    }
                }
            } catch (e: Exception) {
                System.err.println("Failed to record SELECTED edge: ${e.message}")
            }
        }
    }
}
