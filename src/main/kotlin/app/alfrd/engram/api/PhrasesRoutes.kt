package app.alfrd.engram.api

import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire GET /phrases — user-phrase read path.
 *
 * Queries [Phrase] vertices in ArcadeDB whose [text] field matches any word from [q].
 * Results are filtered by [userId] when provided (non-blank) so users only see their own phrases.
 * Source attribution and confidence scores are included in each result so callers (e.g. QuestionBranch)
 * can weight tentative vs confirmed knowledge in LLM prompts.
 */
fun Application.configurePhrasesRoutes(db: Database) {
    routing {
        route("/phrases") {
            get {
                val concept = call.request.queryParameters["q"]?.trim().orEmpty()
                val userId  = call.request.queryParameters["userId"]?.trim().orEmpty()

                val results = queryPhrases(db, concept, userId)
                call.respond(HttpStatusCode.OK, results)
            }
        }
    }
}

// ── Query logic ───────────────────────────────────────────────────────────────

@Serializable
data class PhraseResponse(
    val id: String,
    val content: String,
    val source: String,
    @SerialName("trust_phase") val trustPhase: Int,
    val score: Double,
)

/**
 * Fetches [Phrase] vertices matching [concept] from ArcadeDB.
 *
 * Matching is word-level: any word longer than 2 chars from [concept] must appear
 * (case-insensitive) in the phrase text. Falls back to returning all phrases when
 * [concept] is blank (up to the internal limit).
 *
 * Filtering by [userId] is enforced when non-blank — blank means "no filter" and is
 * intended only for internal/test use; production callers should always supply a userId.
 */
internal fun queryPhrases(db: Database, concept: String, userId: String): List<PhraseResponse> {
    val words = concept.lowercase().split(Regex("\\s+")).filter { it.length > 2 }

    val sql = buildString {
        append("SELECT FROM Phrase WHERE 1=1")
        if (userId.isNotBlank()) append(" AND userId = :userId")
        append(" LIMIT 100")
    }
    val params: Map<String, Any> = if (userId.isNotBlank()) mapOf("userId" to userId) else emptyMap()

    val results = mutableListOf<PhraseResponse>()
    db.query("sql", sql, params).use { rs ->
        while (rs.hasNext()) {
            val doc = rs.next().toMap()
            val text = doc["text"] as? String ?: continue
            // Word-level matching on Kotlin side — avoids SQL function concerns
            if (words.isNotEmpty() && words.none { text.lowercase().contains(it) }) continue
            results.add(
                PhraseResponse(
                    id         = doc["uid"] as? String ?: "",
                    content    = text,
                    source     = doc["source"] as? String ?: "unknown",
                    trustPhase = (doc["trustPhase"] as? Number)?.toInt() ?: 1,
                    score      = (doc["score"] as? Number)?.toDouble() ?: 0.5,
                )
            )
        }
    }
    return results.take(10)
}
