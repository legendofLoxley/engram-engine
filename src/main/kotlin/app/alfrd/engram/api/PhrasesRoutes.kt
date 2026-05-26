package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.memory.ScoredPhrase
import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire GET /phrases — perspective-scoped phrase retrieval.
 *
 * Traverses User(email) → TRUSTS → Source → ASSERTS → Phrase to return the set of
 * phrases visible to the authenticated user. Phrases reachable through multiple Sources
 * are deduplicated; scores are aggregated (max per type) across all paths.
 *
 * Query parameters:
 *   - userEmail: required — the authenticated user's email
 *   - q:         optional concept filter (case-insensitive substring match)
 *   - limit:     optional max results (default 50)
 */
fun Application.configurePhrasesRoutes(db: Database) {
    routing {
        route("/phrases") {
            get {
                val concept   = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                val userEmail = call.request.queryParameters["userEmail"]?.trim().orEmpty()
                val limit     = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

                val results = queryPhrases(db, userEmail, concept, limit)
                call.respond(HttpStatusCode.OK, results)
            }
        }
    }
}

// ── Query logic ───────────────────────────────────────────────────────────────

private val jsonParser = Json { ignoreUnknownKeys = true }

/**
 * Perspective-scoped phrase retrieval via graph traversal.
 *
 * Path: User(email=userEmail) → out(TRUSTS) → Source → out(ASSERTS) → Phrase
 *
 * Score aggregation: for each Phrase reachable through N paths, take the max value per
 * score type across all ASSERTS edges. [sourceCount] records the number of paths.
 *
 * Results are ordered: max trust ↓, max salience ↓, sourceCount ↓.
 * Returns empty list — never throws — when [userEmail] is blank or no User vertex found.
 */
internal fun queryPhrases(
    db: Database,
    userEmail: String,
    concept: String?,
    limit: Int = 50,
): List<ScoredPhrase> {
    if (userEmail.isBlank()) return emptyList()

    // ── Step 1: Find User vertex by email ─────────────────────────────────────
    val userVertex: Vertex = db.query(
        "sql",
        "SELECT FROM User WHERE email = :email",
        mapOf("email" to userEmail),
    ).use { rs ->
        if (rs.hasNext()) rs.next().toElement().asVertex() else null
    } ?: return emptyList()

    // ── Step 2: Traverse TRUSTS → Source vertices ─────────────────────────────
    val sourceVertices = userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS").toList()
    if (sourceVertices.isEmpty()) return emptyList()

    // ── Step 3: Traverse ASSERTS edges → accumulate Phrase data ──────────────
    // uid → (phraseVertex, list of score-lists from each ASSERTS path, source types)
    data class PhraseAccumulator(
        val phraseVertex: Vertex,
        val allScoreLists: MutableList<Map<String, Double>> = mutableListOf(),
        val sourceTypes: MutableList<String> = mutableListOf(),
    )

    val phraseMap = mutableMapOf<String, PhraseAccumulator>()

    for (source in sourceVertices) {
        val sourceType = source.get("type") as? String ?: "unknown"
        for (assertsEdge in source.getEdges(Vertex.DIRECTION.OUT, "ASSERTS")) {
            val phraseVertex = assertsEdge.getVertex(Vertex.DIRECTION.IN) ?: continue
            val uid = phraseVertex.get("uid") as? String ?: continue
            val scoresJson = assertsEdge.get("scores") as? String ?: "[]"
            val edgeScores = parseEdgeScores(scoresJson)

            val accum = phraseMap.getOrPut(uid) { PhraseAccumulator(phraseVertex) }
            accum.allScoreLists.add(edgeScores)
            accum.sourceTypes.add(sourceType)
        }
    }

    if (phraseMap.isEmpty()) return emptyList()

    // ── Step 4: Aggregate scores and build ScoredPhrase list ─────────────────
    val phrases = phraseMap.values.map { accum ->
        val maxScores = mutableMapOf<String, Double>()
        for (scoreMap in accum.allScoreLists) {
            for ((type, value) in scoreMap) {
                maxScores[type] = maxOf(maxScores.getOrDefault(type, 0.0), value)
            }
        }
        ScoredPhrase(
            uid         = accum.phraseVertex.get("uid") as? String ?: "",
            text        = accum.phraseVertex.get("text") as? String ?: "",
            createdAt   = (accum.phraseVertex.get("createdAt") as? Number)?.toLong() ?: 0L,
            updatedAt   = (accum.phraseVertex.get("updatedAt") as? Number)?.toLong() ?: 0L,
            scores      = maxScores,
            sourceCount = accum.allScoreLists.size,
            sourceTypes = accum.sourceTypes.distinct(),
        )
    }

    // ── Step 5: Optional concept filter ───────────────────────────────────────
    val words = concept?.lowercase()?.split(Regex("\\s+"))?.filter { it.length > 2 } ?: emptyList()
    val filtered = if (words.isEmpty()) phrases else phrases.filter { phrase ->
        words.any { phrase.text.lowercase().contains(it) }
    }

    // ── Step 6: Sort and cap ──────────────────────────────────────────────────
    return filtered.sortedWith(
        compareByDescending<ScoredPhrase> { it.scores["trust"] ?: 0.0 }
            .thenByDescending { it.scores["salience"] ?: 0.0 }
            .thenByDescending { it.sourceCount }
    ).take(limit)
}

/**
 * Parses a JSON scores array (e.g. `[{"type":"trust","value":0.5,...}]`)
 * into a map of score type → value. Returns an empty map on any parse error.
 */
private fun parseEdgeScores(json: String): Map<String, Double> {
    return try {
        val array = jsonParser.decodeFromString<JsonArray>(json)
        array.associate { element ->
            val obj = element.jsonObject
            val type  = obj["type"]?.jsonPrimitive?.content ?: return@associate "" to 0.0
            val value = obj["value"]?.jsonPrimitive?.double ?: 0.0
            type to value
        }.filterKeys { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyMap()
    }
}

