package app.alfrd.engram.cognitive.pipeline.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.logging.Logger

/**
 * HTTP implementation of [EngramClient] targeting the engram-engine REST API at [baseUrl].
 *
 * Endpoint availability:
 * - `POST /ingest/text`              — available
 * - `GET  /phrases?q=…&userId=…`    — available
 * - `PATCH /phrases/:id`             — available
 * - `GET  /scaffold/state/{userId}`  — available
 * - `PUT  /scaffold/state/{userId}`  — available
 * - `decompose`                      — not yet available; falls back to the naive keyword heuristic
 *
 * Any network failure leads to a logged warning and a graceful degradation —
 * branches continue producing responses using LLM general knowledge.
 */
class HttpEngramClient(
    private val baseUrl: String = "http://localhost:18792",
) : EngramClient {

    private val logger = Logger.getLogger(HttpEngramClient::class.java.name)
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newHttpClient()

    // ── Decompose (local heuristic — no server endpoint yet) ──────────────────

    override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> =
        InMemoryEngramClient().decompose(text, context)

    // ── Ingest ────────────────────────────────────────────────────────────────

    override suspend fun ingest(candidates: List<PhraseCandidate>) = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(IngestRequest(texts = candidates.map { it.content }))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/ingest/text"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                logger.warning("ingest returned HTTP ${resp.statusCode()}: ${resp.body()}")
            }
        } catch (e: Exception) {
            logger.warning("engram-engine unreachable during ingest: ${e.message}")
        }
    }

    // ── Query phrases ─────────────────────────────────────────────────────────

    override suspend fun queryPhrases(concept: String, userId: String): List<Phrase> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(concept, "UTF-8")
            val userParam = if (userId.isNotBlank()) "&userId=${URLEncoder.encode(userId, "UTF-8")}" else ""
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/phrases?q=$encoded$userParam"))
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) {
                val dtos = json.decodeFromString<List<PhraseDto>>(resp.body())
                dtos.map { it.toPhrase() }
            } else {
                logger.warning("queryPhrases returned HTTP ${resp.statusCode()}")
                emptyList()
            }
        } catch (e: Exception) {
            logger.warning("engram-engine unreachable during queryPhrases: ${e.message}")
            emptyList()
        }
    }

    // ── Amend phrase ──────────────────────────────────────────────────────────

    override suspend fun amendPhrase(phraseId: String, newContent: String) = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(AmendRequest(content = newContent))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/phrases/$phraseId"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                logger.warning("amendPhrase returned HTTP ${resp.statusCode()}")
            }
        } catch (e: Exception) {
            logger.warning("engram-engine unreachable during amendPhrase: ${e.message}")
        }
    }

    // ── Scaffold state ────────────────────────────────────────────────────────
    //
    // Graceful degradation: if the endpoint is unreachable, return a default "new user"
    // state (ORIENTATION, no categories). Do NOT fall back to an in-memory map — stale
    // in-memory data after a container restart is worse than starting fresh.

    override suspend fun getScaffoldState(userId: String): ScaffoldState = withContext(Dispatchers.IO) {
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/scaffold/state/${URLEncoder.encode(userId, "UTF-8")}"))
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) {
                val dto = json.decodeFromString<ScaffoldStateDto>(resp.body())
                dto.toScaffoldState()
            } else {
                logger.warning("getScaffoldState returned HTTP ${resp.statusCode()} for userId=$userId")
                ScaffoldState()
            }
        } catch (e: Exception) {
            logger.warning("engram-engine unreachable during getScaffoldState for userId=$userId: ${e.message}")
            ScaffoldState() // default new-user state — no stale in-memory fallback
        }
    }

    override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(state.toUpdateRequest())
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/scaffold/state/${URLEncoder.encode(userId, "UTF-8")}"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                logger.warning("updateScaffoldState returned HTTP ${resp.statusCode()} for userId=$userId")
            }
        } catch (e: Exception) {
            logger.warning("engram-engine unreachable during updateScaffoldState for userId=$userId: ${e.message}")
        }
    }

    @Serializable
    private data class IngestRequest(val texts: List<String>)

    @Serializable
    private data class AmendRequest(val content: String)

    @Serializable
    private data class PhraseDto(
        val id: String,
        val content: String,
        val source: String = "unknown",
        @SerialName("trust_phase") val trustPhase: Int = 1,
        val score: Double = 0.5,
    ) {
        fun toPhrase() = Phrase(
            id = id,
            content = content,
            source = source,
            trustPhase = trustPhase,
            score = score,
        )
    }

    /**
     * DTO for GET /scaffold/state/{userId} response.
     * Maps [trustPhase] string ("ORIENTATION", "WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")
     * to the pipeline's integer representation (1–4).
     */
    @Serializable
    private data class ScaffoldStateDto(
        val userId: String = "",
        val trustPhase: String = "ORIENTATION",
        val answeredCategories: Set<String> = emptySet(),
        val activeScaffoldQuestion: String? = null,
        val sessionCount: Int = 0,
        val lastInteractionAt: Long? = null,
        val phaseTransitions: List<PhaseTransitionDto> = emptyList(),
    ) {
        fun toScaffoldState(): ScaffoldState {
            val phase = when (trustPhase) {
                "WORKING_RHYTHM" -> 2
                "CONTEXT"        -> 3
                "UNDERSTANDING"  -> 4
                else             -> 1 // ORIENTATION
            }
            val categories = answeredCategories.mapNotNull {
                try { PhraseCategory.valueOf(it) } catch (_: Exception) { null }
            }.toSet()
            val transitions = phaseTransitions.map {
                ScaffoldPhaseTransition(
                    from      = it.from,
                    to        = it.to,
                    timestamp = it.timestamp,
                    evidence  = it.evidence,
                )
            }
            return ScaffoldState(
                trustPhase             = phase,
                answeredCategories     = categories,
                activeScaffoldQuestion = activeScaffoldQuestion,
                sessionCount           = sessionCount,
                lastInteractionAt      = lastInteractionAt,
                phaseTransitions       = transitions,
            )
        }
    }

    @Serializable
    private data class PhaseTransitionDto(
        val from: String,
        val to: String,
        val timestamp: Long,
        val evidence: String,
    )

    @Serializable
    private data class UpdateScaffoldRequest(
        val trustPhase: String,
        val answeredCategories: Set<String>,
        val activeScaffoldQuestion: String? = null,
        val sessionCount: Int = 0,
        val lastInteractionAt: Long? = null,
        val phaseTransitions: List<PhaseTransitionDto> = emptyList(),
    )

    private fun ScaffoldState.toUpdateRequest() = UpdateScaffoldRequest(
        trustPhase = when (trustPhase) {
            2    -> "WORKING_RHYTHM"
            3    -> "CONTEXT"
            4    -> "UNDERSTANDING"
            else -> "ORIENTATION"
        },
        answeredCategories     = answeredCategories.map { it.name }.toSet(),
        activeScaffoldQuestion = activeScaffoldQuestion,
        sessionCount           = sessionCount,
        lastInteractionAt      = lastInteractionAt ?: System.currentTimeMillis(),
        phaseTransitions       = phaseTransitions.map {
            PhaseTransitionDto(
                from      = it.from,
                to        = it.to,
                timestamp = it.timestamp,
                evidence  = it.evidence,
            )
        },
    )
}
